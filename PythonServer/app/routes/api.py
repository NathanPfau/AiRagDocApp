from fastapi import APIRouter, Query, UploadFile, File, Form
from fastapi.responses import JSONResponse
from app.graph.graph_maker import get_graph
from app.services.pdf_processing import upload_text 
from psycopg import AsyncConnection
from app.config import Config
from app.services.pinecone_service import get_vector_store
import traceback
import asyncpg

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
        return JSONResponse(content={"message": str(e), "traceback": error_details}, status_code=500)

    return JSONResponse(content={"filename": file.filename, "message": "PDF processed and graph stored successfully."})

@router.get("/ask/")
async def ask_question(query: str, thread_id: str, user_id: str, document_names: list[str] = Query([])):
    """Handles user questions by invoking the LangGraph pipeline with filtering."""
    try:
        graph = await get_graph()

        # Calculate k dynamically: (5 * number of documents) but cap at 20
        num_docs = len(document_names)
        k = min(5 * num_docs, 20)

        # Construct search filter, filters on user_id and the source names (pdf names)
        search_kwargs = {
            "k": k,
            "filter": {
                "user_id": user_id,
                "source": {"$in": document_names} 
            }
        }

        inputs = {
            "messages": [
                ("user", query),
            ],
            "search_kwargs": search_kwargs
        }

        # Pass thread_id to graph so it uses the correct State
        config = {"configurable": {"thread_id": thread_id}}

        # Pass search_kwargs into graph execution
        result = await graph.ainvoke(inputs, config)
        #xtract just the actual message field from the last message
        last_message = result["messages"][-1]
        last_message_content = getattr(last_message, "content", "No content found.")

    except Exception as e:
        error_details = traceback.format_exc()  
        return JSONResponse(content={"message": str(e), "traceback": error_details}, status_code=500)

    return {"response": last_message_content}

@router.delete("/delete-state/")
async def delete_state(thread_id: str):
    """Deletes all records related to a given thread ID from relevant tables."""
    try:
        conn = await asyncpg.connect(
            database=Config.RDS_DB,
            user=Config.RDS_USER,
            password=Config.RDS_PASSWORD,
            host=Config.RDS_HOST
        )
        
        async with conn.transaction():
            # Delete from related tables first
            await conn.execute("DELETE FROM checkpoint_blobs WHERE thread_id = $1", thread_id)
            await conn.execute("DELETE FROM checkpoint_writes WHERE thread_id = $1", thread_id)
            # Then delete from checkpoints
            await conn.execute("DELETE FROM checkpoints WHERE thread_id = $1", thread_id)
        
        await conn.close()
        return {"response": f"State for thread_id {thread_id} deleted successfully."}

    except Exception as e:
        error_details = traceback.format_exc()
        return JSONResponse(content={"message": str(e), "traceback": error_details}, status_code=500)

@router.delete("/delete-doc/")
async def delete_document(user_id: str, doc_name: str):
    """Deletes a document from the vector store using the UUIDs stored in the database."""
    try:
        # Retrieve the list of UUIDs from the database for the given user_id and doc_name
        uuids = await get_document_uuids(user_id, doc_name)
        if not uuids:
            return JSONResponse(content={"message": "Document not found. Nothing to delete."}, status_code=404)

        # Get the vector store instance
        vector_store = await get_vector_store()
        if vector_store is None:
            raise RuntimeError("Vector store is not initialized.")

        # Attempt to delete the document vectors by their UUIDs
        deletion_success = await vector_store.adelete(ids=uuids)

        # Check deletion result, at time of writing code Pinecone does not send a bool if the delete was successful
        if deletion_success is None or deletion_success:
            await delete_document_uuids_from_db(user_id, doc_name)
            return {"response": f"Document '{doc_name}' for user '{user_id}' deleted successfully."}
        else:
            return JSONResponse(content={"message": "Failed to delete document from vector store."}, status_code=500)

    except Exception as e:
        error_details = traceback.format_exc()
        return JSONResponse(content={"message": str(e), "traceback": error_details}, status_code=500)
    
async def get_document_uuids(user_id: str, doc_source: str) -> list:
    """Query the PostgreSQL database for UUIDs matching the given user_id and doc_source."""
    conn = await asyncpg.connect(
        database=Config.RDS_DB,
        user=Config.RDS_USER,
        password=Config.RDS_PASSWORD,
        host=Config.RDS_HOST
    )
    rows = await conn.fetch(
        "SELECT uuid FROM vector_ids WHERE user_id = $1 AND doc_source = $2",
        user_id, doc_source
    )
    await conn.close()
    return [row["uuid"] for row in rows]

async def delete_document_uuids_from_db(user_id: str, doc_source: str):
    """Delete UUID entries for the document from the PostgreSQL database."""
    conn = await asyncpg.connect(
        database=Config.RDS_DB,
        user=Config.RDS_USER,
        password=Config.RDS_PASSWORD,
        host=Config.RDS_HOST
    )
    await conn.execute(
        "DELETE FROM vector_ids WHERE user_id = $1 AND doc_source = $2",
        user_id, doc_source
    )
    await conn.close()