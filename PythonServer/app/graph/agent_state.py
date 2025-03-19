from typing import Annotated, Sequence
from typing_extensions import TypedDict
from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages
from typing import Dict, Optional

class AgentState(TypedDict):
    # The add_messages function defines how an update should be processed
    # Default is to replace. add_messages says "append"
    messages: Annotated[Sequence[BaseMessage], add_messages]
    user_id: str  # Store user ID for filtering results
    thread_id: str  # Store thread ID for multi-threaded conversation tracking
    rewrite_count: int  # Tracks how many times a query has been rewritten    
    search_kwargs: dict # Filters for retrieval tool