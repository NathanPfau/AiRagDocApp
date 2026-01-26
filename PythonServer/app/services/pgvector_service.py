import asyncio
from urllib.parse import quote_plus
from langchain_postgres import PGVector
from app.config import Config
import app.services.embedding_service as embed
from langchain_core.tools.retriever import create_retriever_tool
import app.services.prompts as prompt_template

_vector_store = None
_lock = asyncio.Lock()

def get_connection_string():
    """Build PostgreSQL connection string for pgvector with URL-encoded password."""
    # URL-encode password to handle special characters like @, /, #, %
    encoded_password = quote_plus(Config.RDS_PASSWORD) if Config.RDS_PASSWORD else ""
    return f"postgresql+psycopg://{Config.RDS_USER}:{encoded_password}@{Config.RDS_HOST}:{Config.RDS_PORT}/{Config.RDS_DB}"

async def initialize_pgvector():
    global _vector_store

    async with _lock:
        if _vector_store:
            return  # Already initialized

        try:
            connection_string = get_connection_string()

            # Initialize PGVector store with async_mode=True for async operations
            _vector_store = PGVector(
                embeddings=embed.get_embeddings(),
                collection_name="document_vectors",
                connection=connection_string,
                use_jsonb=True,  # Store metadata as JSONB for efficient filtering
                async_mode=True,  # Enable async operations
            )

            # Initialize the vector extension and tables asynchronously
            await _vector_store.acreate_vector_extension()
            await _vector_store.acreate_tables_if_not_exists()
            await _vector_store.acreate_collection()

        except Exception as e:
            _vector_store = None  # Reset on failure
            raise RuntimeError(f"pgvector failed to initialize: {e}") from e

async def get_retriever_tool(search_kwargs):
    """Returns a retriever tool with dynamically assigned search_kwargs."""
    if _vector_store is None:
        await initialize_pgvector()

    # Convert Pinecone-style filters to pgvector filters
    filter_dict = search_kwargs.get("filter", {})
    pgvector_filter = {}

    if "user_id" in filter_dict:
        pgvector_filter["user_id"] = filter_dict["user_id"]

    if "source" in filter_dict:
        source_filter = filter_dict["source"]
        if isinstance(source_filter, dict) and "$in" in source_filter:
            pgvector_filter["source"] = {"$in": source_filter["$in"]}
        else:
            pgvector_filter["source"] = source_filter

    # Build new search_kwargs for pgvector
    pgvector_search_kwargs = {
        "k": search_kwargs.get("k", 5),
    }
    if pgvector_filter:
        pgvector_search_kwargs["filter"] = pgvector_filter

    # Create a new retriever instance for each request
    retriever = _vector_store.as_retriever(search_kwargs=pgvector_search_kwargs)

    # Create and return a new retriever tool instance
    return create_retriever_tool(
        retriever,
        "retrieve_text",
        prompt_template.get_retriever_prompt(),
    )

async def get_vector_store():
    if _vector_store is None:
        await initialize_pgvector()
    return _vector_store
