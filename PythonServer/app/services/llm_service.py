from langchain_aws import ChatBedrock
from app.config import Config


def get_llm():
    return ChatBedrock(
        model_id=Config.LLM_MODEL,
        temperature=0,
        max_tokens=1024,
        )
