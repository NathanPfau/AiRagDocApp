from langchain_aws import BedrockEmbeddings

def get_embeddings():
    return BedrockEmbeddings(model_id="amazon.titan-embed-text-v1")