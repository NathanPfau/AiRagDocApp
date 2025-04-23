import pymupdf
import asyncio
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.services.pinecone_service import get_vector_store
import traceback
import io 
from uuid import uuid4
import asyncpg
from app.config import Config

async def insert_vector_ids(conn, user_id, doc_source, uuids):
    # Insert each UUID into the table
    for uid in uuids:
        await conn.execute(
            """
            INSERT INTO vector_ids (user_id, doc_source, uuid)
            VALUES ($1, $2, $3)
            ON CONFLICT DO NOTHING
            """,
            user_id, doc_source, uid
        )

async def upload_text(pdf_path, doc_metadata: dict):
    try:
        vector_store = await get_vector_store()
        if vector_store is None:
            raise RuntimeError("Vector store is not initialized. Ensure Pinecone is set up correctly.")

        user_id = doc_metadata["user_id"]
        source = doc_metadata["source"]

        # Query vector store to see if a document already exists, etc...
        existing_docs = await vector_store.asearch(
            query="check_duplicate", 
            search_type="similarity",
            filter={"user_id": user_id, "source": source}
        )
        if existing_docs:
            # print(f"Document from source '{source}' already exists. Skipping upload.")
            return {"message": f"Document from source '{source}' already exists. Upload skipped."}

        # Extract text chunks from the PDF
        text_splits = await extract_text_from_pdf(pdf_path)
        if not text_splits:
            raise ValueError("Extracted text is empty. Ensure the PDF is not blank or encrypted.")

        # Generate unique UUIDs for each text chunk
        uuids = [str(uuid4()) for _ in range(len(text_splits))]

        metadatas = [doc_metadata.copy() for _ in range(len(text_splits))]

        # Debug info
        # print("Text chunks:", text_splits)
        # print("UUIDs:", uuids)

        # Upload to vector store
        await vector_store.aadd_texts(texts=text_splits, metadatas=metadatas, ids=uuids)

        # Now store the UUIDs in your PostgreSQL database for tracking
        conn = await asyncpg.connect(
            database=Config.RDS_DB,
            user=Config.RDS_USER,
            password=Config.RDS_PASSWORD,
            host=Config.RDS_HOST
        )
        await insert_vector_ids(conn, user_id, source, uuids)
        await conn.close()

        # print("Text uploaded successfully and vector IDs stored in the database.")
        return {"message": "Text uploaded successfully."}

    except Exception as e:
        error_details = traceback.format_exc()
        # print(f"Failed to upload text: {error_details}")
        raise RuntimeError(f"Upload failed: {error_details}") from e
    
async def extract_text_from_pdf(pdf_path):
    """
    Extracts text from a PDF file asynchronously.
    """
    try:
        # Run the blocking PDF operation in a separate thread
        text_splits = await asyncio.to_thread(_sync_extract_text_from_pdf, pdf_path)
        return text_splits

    except Exception as e:
        # print(pdf_path)
        error_details = traceback.format_exc()
        # print(f"Error extracting text from PDF {pdf_path}: {error_details}")
        raise RuntimeError(f"Text extraction failed for {pdf_path}: {error_details}") from e

def _sync_extract_text_from_pdf(pdf_input):
    try:
        # Reset pointer and read all bytes
        pdf_input.seek(0)
        file_bytes = pdf_input.read()
        
        # Wrap the bytes in a BytesIO stream
        pdf_stream = io.BytesIO(file_bytes)
        
        # Open the PDF using PyMuPDF
        doc = pymupdf.open(stream=pdf_stream, filetype="pdf")
        
        # Extract text from all pages
        text = ""
        for page in doc:
            text += page.get_text() + "\n"
        
        # Check if text was extracted
        if not text.strip():
            raise ValueError("No text found in PDF. The document may be scanned or contain images instead of text.")
        
        # Use your text splitter to break text into chunks
        text_splitter = RecursiveCharacterTextSplitter.from_tiktoken_encoder(
            chunk_size=512, chunk_overlap=20
        )
        return text_splitter.split_text(text)
    
    except Exception as e:
        error_details = traceback.format_exc()
        # print(f"Error extracting text with PyMuPDF from PDF {pdf_input}: {error_details}")
        raise RuntimeError(f"Text extraction failed for {pdf_input}: {error_details}") from e