# Code for setting up connection with Pincone vector db and sets global vector store
import asyncio
from pinecone import Pinecone, ServerlessSpec, PineconeException
from langchain_pinecone import PineconeVectorStore
from app.config import Config
import app.services.embedding_service as embed
from langchain.tools.retriever import create_retriever_tool
import PythonServer.app.graph.prompts as prompt_template

_vector_store = None

async def initialize_pinecone():
    global _vector_store

    if _vector_store:
        return  # Already initialized

    try:
        pc = Pinecone(api_key=Config.PINECONE_API_KEY)  # Initialize Pinecone client
        existing_indexes = [index_info["name"] for index_info in pc.list_indexes()]

        # Create index if it doesn't exist
        if Config.INDEX_NAME not in existing_indexes:
            pc.create_index(
                name=Config.INDEX_NAME,
                dimension=1536, 
                metric="cosine",
                spec=ServerlessSpec(cloud="aws", region="us-east-1"),
            )
            while not pc.describe_index(Config.INDEX_NAME).status["ready"]:
                await asyncio.sleep(1)

        index = pc.Index(Config.INDEX_NAME)

        # Set private vector store
        _vector_store = PineconeVectorStore(index=index, embedding=embed.get_embeddings())

    except PineconeException as e:
        raise RuntimeError("Pinecone failed to initialize. Check API key or network connection.") from e
    
async def get_retriever_tool(search_kwargs):
    """Returns a retriever tool with dynamically assigned search_kwargs."""
    if _vector_store is None:
        await initialize_pinecone()  # Ensure Pinecone is initialized

    # Create a new retriever instance for each request
    retriever = _vector_store.as_retriever(search_kwargs=search_kwargs)

    # Create and return a new retriever tool instance
    return create_retriever_tool(
        retriever,
        "retrieve_text",
        prompt_template.get_retriever_prompt(),
    )

async def get_vector_store():
    if _vector_store is None:
        await initialize_pinecone()
    return _vector_store
