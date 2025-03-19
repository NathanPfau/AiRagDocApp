import os
from dotenv import load_dotenv

# Load .env file
load_dotenv()

class Config:
    PINECONE_API_KEY = os.getenv("PINECONE_API_KEY")
    REGION = os.getenv("REGION")
    EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL")
    LLM_MODEL = os.getenv("LLM_MODEL")
    LANGCHAIN_TRACING_V2 = os.getenv("LANGCHAIN_TRACING_V2")
    LANGSMITH_PROJECT = os.getenv("LANGSMITH_PROJECT")
    LANGSMITH_API_KEY = os.getenv("LANGSMITH_API_KEY")
    LANGSMITH_ENDPOINT = os.getenv("LANGSMITH_ENDPOINT")
    LANGSMITH_TRACING = os.getenv("LANGSMITH_TRACING")
    INDEX_NAME = os.getenv("INDEX_NAME")
    RDS_HOST = os.getenv("RDS_HOST")
    RDS_PORT = os.getenv("RDS_PORT")
    RDS_USER = os.getenv("RDS_USER")
    RDS_PASSWORD = os.getenv("RDS_PASSWORD")
    RDS_DB = os.getenv("RDS_DB")  
    connection_kwargs = {
            "autocommit": True,
            "prepare_threshold": 0,
        }