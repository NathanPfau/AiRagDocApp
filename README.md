# AI Agent Using RAG for Document Query 
## Overview 

This project consists of three main parts: 

- **[LangChain AI Agent](#langchain-ai-agent)**:
	Located in the /PythonServer directory, this component houses the logic for the AI agent. It uses an API to interface with the agent, which was built using the LangChain Python framework and LangGraph to set up its workflow.
- **[Kotlin Ktor Server](#kotlin-ktor-server)**:
	Located in the /KotlinServer directory, this server sits between the client and the PythonServer. Its purpose is to manage user sessions and store/retrieve data related to past chat history and the names of uploaded PDFs.
- **[React Client Side](#react-client-side)**:
	Located in the /synap-docs-app directory, this is a single-page application built using React. It features two main views: a landing page that allows users to log in, sign up, or try the app as a guest, and a chat page where users can upload PDFs and interact with the AI agent.

The project is deployed on AWS using an EC2 instance behind an Application Load Balancer (ALB). Registered users have their credentials managed through AWS Cognito, and the ALB authenticates all requests. The ALB also handles TLS offloading before forwarding requests to the EC2 instance, which is connected to an Amazon RDS PostgreSQL instance with the pgvector extension for vector storage, agent states, user message history, and document metadata.

**Live demo:** [synapdocs.com](https://synapdocs.com)

---

## LangChain AI Agent

This LangChain application is designed to integrate advanced natural language processing with data ingestion and state management. The codebase is organized into several key modules, each handling a distinct part of the overall workflow:

### PDF Processing:

The pdf_processing.py module extracts and processes text from PDF documents, making it possible to incorporate external knowledge sources.

### Vector Database Integration:

Through pgvector_service.py, the app connects with PostgreSQL using the pgvector extension to index and search document embeddings, enabling efficient similarity searches and retrieval.

### API Endpoints:

The api.py file exposes endpoints for interacting with the LangChain agent and managing document ingestion. It uses Server-Sent Events (SSE) to stream AI responses token-by-token using LangGraph's `astream_events()`, enabling real-time response delivery to the client.

### Agent State Management:

Using agent_state.py, the application maintains and tracks the state of its conversational agents, ensuring context-aware interactions.

### Agent Workflow:

In graph_maker.py, the AI agent’s workflow is structured using LangGraph, which leverages the nodes defined in nodes.py to build the graph.

### Node & Prompt Handling:

The nodes.py and prompts.py modules define the building blocks of the LangChain's chain-of-thought framework, managing both the individual nodes (representing different processing steps) and the prompt templates that guide the agent's behavior.

### LLM & Embedding Services:

The llm_service.py and embedding_service.py modules handle interactions with AWS Bedrock for language model inference and text embeddings respectively, abstracting the AI model integrations.

---
## Kotlin Ktor Server

This part of the project is a Kotlin-based backend application that uses the Ktor framework to deliver scalable and maintainable services. The codebase is organized into modular components, each addressing a specific area of functionality. This separation of concerns enhances readability, ease of testing, and future extensibility.
### App.kt

This file serves as the main entry point of the application. It initializes the server and sets up the application environment by configuring essential plugins, middleware, and routes—effectively bootstrapping the entire system.
### UserRoutes.kt

This component manages endpoints for connecting to the agent API in /PythonServer. It proxies SSE streams from the Python server to the client while handling database operations for message persistence. It also manages user authentication and profile-related API endpoints.
### HealthCheck.kt

This file provides a mechanism to monitor the health of the application. It exposes a dedicated endpoint that typically returns an “OK” or HTTP 200 response, facilitating external monitoring.
### AlbAuthPlugin.kt

This module integrates authentication with AWS Application Load Balancer. It implements authentication middleware to intercept and validate incoming requests by checking for proper authentication tokens or headers, thereby enhancing the overall security of the application.
### DatabaseTables.kt

This file defines the application’s database schema. It specifies the structure of database tables, columns, and relationships, serving as the central blueprint for the persistent storage layer and ensuring consistency across data access layers.
### GuestSessions.kt

This module manages sessions for guest users. It handles the creation, tracking, and expiration of sessions for users who interact with the service without full registration, ensuring that unauthenticated sessions are managed securely and efficiently.

Overall, this Kotlin backend application is designed with a clear separation of concerns and modularity in mind. Its structure not only simplifies development and testing but also facilitates seamless integration with other services and future scalability.

---
## React Client Side

The synap-docs-app is a React-based single-page application that serves as the client interface for interacting with the AI agent. It offers an intuitive and modern user experience with two main views: a landing page for authentication (login, sign-up, or guest access) and a chat page for uploading PDF documents and querying the AI agent.
### App.tsx

This is the root component of the application. It sets up overall routing and initializes the application state by establishing the core layout and global context, and integrating routing between different views.
### main.tsx

Serving as the entry point of the application, this file bootstraps the React application and connects the root component to the HTML container in the DOM.
### NewChatDialog.tsx

This component provides the interface for initiating new chat sessions with the AI agent. It opens a dialog/modal for starting a new conversation and captures the initial input necessary to set up the chat context.
### Sidebar.tsx

The Sidebar offers navigational controls and additional options for the user. It displays links and controls for navigating between different sections of the app, enhancing usability by organizing chat and session options.
### UploadDoc.tsx

This component handles the uploading of PDF documents. It offers a user interface for selecting and uploading files, and integrates with backend services to process the uploaded documents efficiently.
### ChatPage.tsx

Acting as the primary interactive view, ChatPage displays the conversation history and facilitates real-time querying and responses from the AI agent. It consumes SSE streams and uses a token queue system to render AI responses with a smooth typing animation effect. It also supports document uploads within the chat interface, making it a central hub for user interactions.
### LandingPage.tsx

Serving as the initial entry point for users, the LandingPage provides options for user login, sign-up, or guest access. It also offers an overview and introduction to the application, setting the stage for a smooth user experience.

Overall, the synap-docs-app delivers a seamless and engaging user interface that bridges document management with interactive AI chat functionality, ensuring that users can easily upload, manage, and query documents through a well-designed React application.

---