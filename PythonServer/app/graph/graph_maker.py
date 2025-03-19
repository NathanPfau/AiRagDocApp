# Defines the graph structure that defines the steps and conditions of agent
from langgraph.graph import StateGraph, END, START
from langgraph.prebuilt import ToolNode, tools_condition
from app.graph.agent_state import AgentState
import app.graph.nodes as nodes 
from app.services.pinecone_service import get_retriever_tool
from app.config import Config
from psycopg import AsyncConnection
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver 
from langchain.schema.runnable import RunnableLambda
import asyncio

_graph=None
_db_connection = None
_lock = asyncio.Lock()  #Prevents race conditions when initializing the graph

async def get_db_connection():
    """Ensure there is a persistent database connection."""
    global _db_connection
    if _db_connection is None or _db_connection.closed: 
        _db_connection = await AsyncConnection.connect(f"postgresql://{Config.RDS_USER}:{Config.RDS_PASSWORD}@{Config.RDS_HOST}:{Config.RDS_PORT}/{Config.RDS_DB}", **Config.connection_kwargs)
    return _db_connection

async def retrieve_tool_wrapper(state):
    """Retrieves a dynamically generated retriever tool based on search criteria."""
    search_kwargs = state.get("search_kwargs", {}) 
    return await get_retriever_tool(search_kwargs)

async def agent_wrapper(state):
    return await nodes.agent(state)

async def make_graph():
    global _graph

    async with _lock:  #Ensures only one graph initialization runs at a time
        if _graph is not None:
            return  #Prevents duplicate initialization

        try:
            conn = await get_db_connection()  #Use persistent connection
            checkpointer = AsyncPostgresSaver(conn)  #Store checkpointing connection

            # Ensure the checkpointing system is ready
            await checkpointer.setup()

            # Define a new graph
            workflow = StateGraph(AgentState)
            
            retriever_tool = await retrieve_tool_wrapper({})  
            retrieve = ToolNode([retriever_tool])

            workflow.add_node("agent", RunnableLambda(agent_wrapper))  #Async wrapper
            workflow.add_node("retrieve", retrieve) 

            # Other workflow nodes
            workflow.add_node("rewrite", nodes.rewrite)
            workflow.add_node("generate", nodes.generate)

            # Graph edges
            workflow.add_edge(START, "agent")
            workflow.add_conditional_edges(
                "agent",
                tools_condition,
                {
                    "tools": "retrieve",
                    END: END,
                },
            )
            workflow.add_conditional_edges(
                "retrieve",
                nodes.grade_documents,
            )
            workflow.add_edge("generate", END)
            workflow.add_edge("rewrite", "agent")

            # Compile the graph with checkpointing
            _graph = workflow.compile(checkpointer)

        except Exception as e:
            raise Exception(f"Failed to create graph: {str(e)}")

async def get_graph():
    if _graph is None:
        await make_graph()
    return _graph

async def retrieve_tool(state):
    return await [get_retriever_tool(state.search_kwargs)]
      
