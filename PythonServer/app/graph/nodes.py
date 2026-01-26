from typing import Literal
from langchain_core.output_parsers import StrOutputParser
from pydantic import BaseModel, Field
from langsmith import traceable
from langchain_core.messages import AIMessage, HumanMessage
from app.graph.agent_state import AgentState
import app.services.prompts as PromptTemplate
from app.services.llm_service import get_llm
from app.services.pgvector_service import get_retriever_tool


def get_last_human_message(messages):
    """Finds the most recent HumanMessage in the messages list."""
    for message in reversed(messages):  # Iterate in reverse order
        if isinstance(message, HumanMessage):
            return message.content  # Return the content of the last human message
    return None  # Return None if no human messages are found

def filter_messages(messages: list):
    # This is very simple helper function which only ever uses the last 5 messages
    return messages[-5:]

def increment_count(state: AgentState):
        return {"rewrite_count": state["rewrite_count"] + 1}

### Edges

@traceable
async def grade_documents(state) -> Literal["generate", "rewrite"]:
    """
    Determines whether the retrieved documents are relevant to the question.

    Args:
        state (messages): The current state

    Returns:
        str: A decision for whether the documents are relevant or not
    """

    # Define LLM response schema
    class Grade(BaseModel):
        binary_score: str = Field(description="Relevance score 'yes' or 'no'")

    llm = get_llm()
    # Use async LLM call
    llm_with_tool = llm.with_structured_output(Grade)
    prompt = PromptTemplate.get_grader_prompt()
    chain = prompt | llm_with_tool

    messages = state["messages"]
    last_message = messages[-1]

    question = get_last_human_message(messages)
    docs = last_message.content

    scored_result = await chain.ainvoke({"question": question, "context": docs})
    score = scored_result.binary_score

    rewrite_count = state.get("rewrite_count", 0)

    if score == "yes" or rewrite_count >= 1:
        return "generate"
    else:
        return "rewrite"


### Nodes

@traceable
async def agent(state):
    """
    Invokes the agent model to generate a response based on the current state.
    Dynamically decides whether to retrieve data using search_kwargs.

    Args:
        state (dict): The current state containing messages and retrieval settings.

    Returns:
        dict: The updated state with the agent response.
    """

    messages = state["messages"]
    search_kwargs = state.get("search_kwargs")  # Extract user-specific retrieval settings

    llm = get_llm()

    #Dynamically create retriever tool for this request
    retriever_tool = await get_retriever_tool(search_kwargs)

    if "search_kwargs" in state:
        del state["search_kwargs"]

    #Bind tools dynamically (retriever tool per request)
    model = llm.bind_tools([retriever_tool])

    # Select the appropriate prompt
    if isinstance(messages[-1], AIMessage):
        prompt = PromptTemplate.get_agent_from_ai_prompt()
    else:
        prompt = PromptTemplate.get_agent_from_human_prompt()

    chain = prompt | model
    messages = filter_messages(state["messages"])

    #Use async invoke for LLM call
    response = await chain.ainvoke({"messages": messages})

    return {
        "messages": [response], 
        "rewrite_count": state.get("rewrite_count", 0)
    }

@traceable
async def rewrite(state):
    """
    Transform the query to produce a better question.

    Args:
        state (messages): The current state

    Returns:
        dict: The updated state with re-phrased question
    """
    messages = state["messages"]
    question = get_last_human_message(messages)

    msg = [
        HumanMessage(
            content=f""" \n 
            Look at the input and try to reason about the underlying semantic intent / meaning. \n 
            Here is the initial question:
            \n ------- \n
            {question} 
            \n ------- \n
            Formulate an improved question: """,
        )
    ]

    llm = get_llm()

    # Use async invoke for LLM call
    response = await llm.ainvoke(msg)

    return {"messages": [response], "rewrite_count": state.get("rewrite_count") + 1}

@traceable
async def generate(state):
    """
    Generate answer

    Args:
        state (messages): The current state

    Returns:
         dict: The updated state with re-phrased question
    """
    messages = state["messages"]
    question = get_last_human_message(messages)
    last_message = messages[-1]
    docs = last_message.content

    prompt = PromptTemplate.get_generate_prompt()

    llm = get_llm()

    # Use async invoke for LLM call
    rag_chain = prompt | llm | StrOutputParser()
    response = await rag_chain.ainvoke({"context": docs, "question": question})

    return {"messages": [response], "rewrite_count": 0}