import pymupdf
import asyncio
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.services.pgvector_service import get_vector_store
import traceback
import io
from langchain_core.documents import Document

async def upload_text(pdf_path, doc_metadata: dict):
    try:
        vector_store = await get_vector_store()
        if vector_store is None:
            raise RuntimeError("Vector store is not initialized. Ensure pgvector is set up correctly.")

        user_id = doc_metadata["user_id"]
        source = doc_metadata["source"]

        # Check if document already exists by searching for any vector with matching metadata
        existing_docs = await vector_store.asimilarity_search(
            query="check_duplicate",
            k=1,
            filter={"user_id": user_id, "source": source}
        )
        if existing_docs:
            return {"message": f"Document from source '{source}' already exists. Upload skipped."}

        # Extract text chunks from the PDF
        text_splits = await extract_text_from_pdf(pdf_path)
        if not text_splits:
            raise ValueError("Extracted text is empty. Ensure the PDF is not blank or encrypted.")

        # Create Document objects with metadata for each chunk
        documents = [
            Document(page_content=text, metadata=doc_metadata.copy())
            for text in text_splits
        ]

        # Upload to vector store - pgvector handles ID generation internally
        await vector_store.aadd_documents(documents)

        return {"message": "Text uploaded successfully."}

    except Exception as e:
        error_details = traceback.format_exc()
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