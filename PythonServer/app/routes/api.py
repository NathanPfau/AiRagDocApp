from fastapi import APIRouter, Query, UploadFile, File, Form, Request
from fastapi.responses import JSONResponse, StreamingResponse
from app.graph.graph_maker import get_graph
from app.services.pdf_processing import upload_text
from psycopg import AsyncConnection
from app.config import Config
from app.services.pgvector_service import get_vector_store
import traceback
import asyncpg
import json
import time
import asyncio
import logging

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

router = APIRouter()

@router.post("/upload-pdf/")
async def upload_pdf(
    file: UploadFile = File(...),
    user_id: str = Form(...),
    source: str = Form(...)
):
    file.file.seek(0)
    """Processes a PDF file and uploads text to the vector database."""
    if file.content_type != "application/pdf":
        return JSONResponse(content={"message": "Invalid file type. Please upload a PDF."}, status_code=400)

    try:
        doc_metadata = {"user_id": user_id, "source": source}
        await upload_text(file.file, doc_metadata)
    except Exception as e:
        error_details = traceback.format_exc()
        # SECURITY: Log full error details server-side, but send generic message to client
        logger.error(f"[UPLOAD_PDF] Error processing PDF for user {user_id[:8] if user_id else 'None'}...: {str(e)}")
        logger.error(f"[UPLOAD_PDF] FULL TRACEBACK: {error_details}")
        return JSONResponse(content={"message": "Failed to process PDF. Please try again."}, status_code=500)

    return JSONResponse(content={"filename": file.filename, "message": "PDF processed and graph stored successfully."})

@router.post("/ask-stream/")
async def ask_question_stream(request: Request):
    """
    Streams AI responses using Server-Sent Events (SSE).

    Provides real-time, progressive rendering of AI responses for better UX.
    Uses LangGraph's astream_events for token-level streaming.

    Args:
        request (Request): FastAPI request containing JSON body with:
            - query (str): The user's question
            - thread_id (str): Conversation thread identifier
            - user_id (str): User identifier for filtering documents
            - document_names (list[str]): List of document names to search within

    Returns:
        StreamingResponse: SSE formatted stream of AI tokens
    """
    start_time = time.time()

    try:
        body = await request.json()
        query = body.get("query")
        thread_id = body.get("thread_id")
        user_id = body.get("user_id")
        document_names = body.get("document_names", [])

        # Validate document_names
        if not isinstance(document_names, list):
            return JSONResponse(
                content={"error": "document_names must be a list"},
                status_code=400
            )
        if len(document_names) > 100:
            return JSONResponse(
                content={"error": "Too many documents specified. Maximum is 100."},
                status_code=400
            )
        # Filter out any non-string or empty values
        document_names = [d for d in document_names if isinstance(d, str) and d.strip()]

        logger.info(f"[ASK_STREAM] Starting request - User: {user_id[:8] if user_id else 'None'}..., Thread: {thread_id}, Query length: {len(query) if query else 0}, Document count: {len(document_names)}")

        # Validate required parameters
        if not query or not thread_id or not user_id:
            logger.error(f"[ASK_STREAM] Missing required parameters - Query: {bool(query)}, Thread: {bool(thread_id)}, User: {bool(user_id)}")
            return JSONResponse(
                content={"error": "query, thread_id, and user_id are required"},
                status_code=400
            )

        # Validate message length
        MAX_MESSAGE_LENGTH = 4000
        if len(query) > MAX_MESSAGE_LENGTH:
            logger.error(f"[ASK_STREAM] Query too long - Thread: {thread_id}, Length: {len(query)}")
            return JSONResponse(
                content={
                    "error": f"Query too long. Maximum length is {MAX_MESSAGE_LENGTH} characters.",
                    "max_length": MAX_MESSAGE_LENGTH,
                    "your_length": len(query)
                },
                status_code=400
            )

        # Initialize graph
        logger.info(f"[ASK_STREAM] Initializing AI graph for thread {thread_id}")
        graph = await get_graph()

        # Calculate k dynamically: (5 * number of documents) but cap at 20
        num_docs = len(document_names)
        k = min(5 * num_docs, 20) if num_docs > 0 else 5  # Default to 5 if no docs are specified

        # Construct search filters
        search_kwargs = {
            "k": k,
            "filter": {
                "user_id": user_id,
                "source": {"$in": document_names}  # Filter by document names
            }
        }

        logger.info(f"[ASK_STREAM] Prepared search kwargs: k={k}, documents count={len(document_names)}")

        inputs = {
            "messages": [
                ("user", query),
            ],
            "search_kwargs": search_kwargs
        }

        config = {"configurable": {"thread_id": thread_id}}

        async def event_generator():
            """Generate SSE events from LangGraph stream"""
            try:
                logger.info(f"[ASK_STREAM] Starting event stream for thread {thread_id}")
                token_count = 0

                # Stream events from LangGraph with 5-minute timeout
                STREAM_TIMEOUT_SECONDS = 300  # 5 minutes

                try:
                    # Stream all LLM tokens from the graph
                    async with asyncio.timeout(STREAM_TIMEOUT_SECONDS):
                        event_count = 0

                        async for event in graph.astream_events(inputs, config, version="v2"):
                            # Check if client disconnected
                            if await request.is_disconnected():
                                logger.info(f"[ASK_STREAM] Client disconnected for thread {thread_id}")
                                return

                            event_count += 1
                            event_type = event.get("event")

                            # Log first few events for debugging
                            if event_count <= 10:
                                logger.info(f"[ASK_STREAM] Event #{event_count}: type={event_type}, keys={list(event.keys())}")

                            # Only process streaming events from LLM calls
                            if event_type == "on_chat_model_stream":
                                # Only stream tokens from the generate and agent nodes.
                                # Exclude rewrite (query reformulation) and grade_documents
                                # (relevance scoring) since those are internal graph steps.
                                node_name = event.get("metadata", {}).get("langgraph_node")
                                if node_name in ("rewrite", "grade_documents"):
                                    continue

                                chunk = event.get("data", {}).get("chunk", {})

                                # Extract content using hasattr for proper attribute access
                                if hasattr(chunk, "content"):
                                    content = chunk.content

                                    # Only stream string content (skip tool calls)
                                    if isinstance(content, str) and content:
                                        token_count += 1
                                        if token_count <= 5:
                                            logger.debug(f"[ASK_STREAM] Yielding token #{token_count}")
                                        yield f"data: {json.dumps({'token': content})}\n\n"

                except asyncio.TimeoutError:
                    logger.error(f"[ASK_STREAM] Timeout after {STREAM_TIMEOUT_SECONDS}s for thread {thread_id} - Tokens generated: {token_count}")
                    yield f"data: {json.dumps({'error': 'Stream timeout. The response took too long to generate. Please try a shorter question.'})}\n\n"
                    return

                elapsed_time = time.time() - start_time
                logger.info(f"[ASK_STREAM] Stream completed for thread {thread_id} - Tokens: {token_count}, Duration: {elapsed_time:.2f}s")

                # Send completion signal
                yield f"data: {json.dumps({'done': True})}\n\n"

            except Exception as e:
                elapsed_time = time.time() - start_time
                error_details = traceback.format_exc()
                # SECURITY: Log full error details server-side, but send generic message to client
                logger.error(f"[ASK_STREAM] ERROR in event generator - Thread: {thread_id}, Duration: {elapsed_time:.2f}s, Error: {str(e)}")
                logger.error(f"[ASK_STREAM] FULL TRACEBACK: {error_details}")
                yield f"data: {json.dumps({'error': 'An error occurred processing your request. Please try again.'})}\n\n"

        return StreamingResponse(
            event_generator(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no"  # Disable nginx buffering
            }
        )

    except Exception as e:
        elapsed_time = time.time() - start_time
        error_details = traceback.format_exc()
        # SECURITY: Log full error details server-side, but send generic message to client
        logger.error(f"[ASK_STREAM] ERROR - Duration: {elapsed_time:.2f}s, Error: {str(e)}")
        logger.error(f"[ASK_STREAM] FULL TRACEBACK: {error_details}")

        return JSONResponse(
            content={
                "error": "An error occurred. Please try again."
            },
            status_code=500
        )


@router.delete("/delete-state/")
async def delete_state(thread_id: str):
    """Deletes all records related to a given thread ID from relevant tables."""
    conn = None
    try:
        conn = await asyncpg.connect(
            database=Config.RDS_DB,
            user=Config.RDS_USER,
            password=Config.RDS_PASSWORD,
            host=Config.RDS_HOST,
            port=int(Config.RDS_PORT)
        )

        async with conn.transaction():
            # Delete from related tables first
            await conn.execute("DELETE FROM checkpoint_blobs WHERE thread_id = $1", thread_id)
            await conn.execute("DELETE FROM checkpoint_writes WHERE thread_id = $1", thread_id)
            # Finally, delete from checkpoints
            await conn.execute("DELETE FROM checkpoints WHERE thread_id = $1", thread_id)

        return {"response": f"State for thread_id {thread_id} deleted successfully."}

    except Exception as e:
        error_details = traceback.format_exc()
        # SECURITY: Log full error details server-side, but send generic message to client
        logger.error(f"[DELETE_STATE] Error deleting state for thread {thread_id}: {str(e)}")
        logger.error(f"[DELETE_STATE] FULL TRACEBACK: {error_details}")
        return JSONResponse(content={"message": "Failed to delete state. Please try again."}, status_code=500)
    finally:
        if conn:
            await conn.close()

@router.delete("/delete-doc/")
async def delete_document(user_id: str, doc_name: str):
    """Deletes a document from the vector store by filtering on metadata."""
    try:
        # Get the vector store instance
        vector_store = await get_vector_store()
        if vector_store is None:
            raise RuntimeError("Vector store is not initialized.")

        # First check if the document exists (use async method)
        existing_docs = await vector_store.asimilarity_search(
            query="check_exists",
            k=1,
            filter={"user_id": user_id, "source": doc_name}
        )
        if not existing_docs:
            return JSONResponse(content={"message": "Document not found. Nothing to delete."}, status_code=404)

        # Delete vectors by metadata filter using raw SQL via the connection
        # pgvector's PGVector class uses a collection-based approach
        # We need to delete directly from the langchain_pg_embedding table
        conn = await asyncpg.connect(
            database=Config.RDS_DB,
            user=Config.RDS_USER,
            password=Config.RDS_PASSWORD,
            host=Config.RDS_HOST,
            port=int(Config.RDS_PORT)
        )
        try:
            # Delete from langchain's embedding table where metadata matches
            await conn.execute(
                """
                DELETE FROM langchain_pg_embedding
                WHERE cmetadata->>'user_id' = $1 AND cmetadata->>'source' = $2
                """,
                user_id, doc_name
            )
        finally:
            await conn.close()

        return {"response": f"Document '{doc_name}' for user '{user_id}' deleted successfully."}

    except Exception as e:
        error_details = traceback.format_exc()
        # SECURITY: Log full error details server-side, but send generic message to client
        logger.error(f"[DELETE_DOC] Error deleting document '{doc_name}' for user {user_id[:8] if user_id else 'None'}...: {str(e)}")
        logger.error(f"[DELETE_DOC] FULL TRACEBACK: {error_details}")
        return JSONResponse(content={"message": "Failed to delete document. Please try again."}, status_code=500)