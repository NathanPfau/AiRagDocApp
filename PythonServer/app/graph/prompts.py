# Prompts that are passed to each of the nodes in the graph
from langchain_core.prompts import PromptTemplate

def get_grader_prompt():
    return PromptTemplate(
        template="""You are a grader assessing relevance of a retrieved document to a user question. \n 
        Here is the retrieved document: \n\n {context} \n\n
        Here is the user question: {question} \n
        If the document contains keyword(s) or semantic meaning related to the user question, grade it as relevant. \n
        Give a binary score 'yes' or 'no' score to indicate whether the document is relevant to the question.""",
        input_variables=["context", "question"],)

def get_agent_from_ai_prompt():
    return PromptTemplate(
        template="""
            You are an AI assistant answering questions based on stored documents.

            You are rerecieving suggestions for rewriting the query from an AI assistant.
            Rewrite the query to produce a better question but the new query still has to
            be in context of the orginal query. Then call the retriever tool to retrieve relevant documents.

            **Rewrite qurey help:** {messages}

            **Your Response:**
            """,
        input_variables=["messages"],)

def get_agent_from_human_prompt():
    return PromptTemplate(
        template="""
            You are an AI assistant answering questions based on stored documents.

            - If **retrieved documents exist**, always include relevant excerpts in your response.
            - If the query is **broad** (e.g., "Tell me about the document"), summarize the key themes and topics.
            - If the query is **specific**, provide a direct answer with supporting excerpts.
            - If **no matching content is found in the retrieved documents**, inform the user instead of assuming nothing exists.
            - If the user query is **ambiguous**, ask for clarification.
            - If the user query is a greeting, respond with a greeting.
            - If the user query is not relevant to the documents, respond accordingly and do not change topics.

            **User Query:** {messages}

            **Your Response:**
            """,
        input_variables=["messages"],)

def get_generate_prompt():
    return PromptTemplate(
        template="""
            "You are an assistant for question-answering tasks. 
            -Return the answer in markdown format for citing key points.
            -Do not start the answer with "Based on the context" or things silimar to that. 
            -Use the following pieces of retrieved context to answer the question. 
            -If you don't know the answer, just say that you don't know. 
            -Try to keep the answer concise. If more detail is required to anwser the
            question the answer, your repsonse can be longer can be longer.
            -Provide direct sources from the documents if appropriate. 
            -If the question is broad, provide a general summary of the document and if
            the context seems fragmented, try to provide a coherent answer.
            Question: {question} 
            Context: {context} 
            Answer:
            """,
        input_variables=["messages"],)

def get_retriever_prompt():
    return """
    Retrieve the most relevant text segments from the provided documents based on the user's query.
    
    - If the query is specific, return the most relevant excerpts that directly answer the question.
    - If the query is broad (e.g., "Tell me about the document"), retrieve key sections summarizing the main topics and themes.
    - If the query say something like tell me about the document or mention they upoaded a document, return the most relevant excerpts that directly answer the question.
    - If documents **exist in the database**, ensure they are used in the response.
    - If no relevant documents match the query, inform the user clearly instead of assuming there are none.
    """