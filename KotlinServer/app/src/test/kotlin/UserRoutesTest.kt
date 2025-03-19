import io.ktor.server.application.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import routes.userRoutes
import org.example.app.services.UserDocuments
import org.example.app.services.UserChats
import org.example.app.services.UserChatDocuments
import org.example.app.services.ChatMessages
import io.ktor.client.request.forms.formData
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import org.example.app.services.GuestSession
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*


// Set up an in-memory database for Exposed.
// This function initializes the H2 database for testing and creates the required tables.
fun initTestDatabase() {
    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    transaction {
        SchemaUtils.create(UserDocuments, UserChats, UserChatDocuments, ChatMessages)
    }
}

// Create a dummy HTTP client for testing.
// The client uses a MockEngine to simulate responses based on the request URL.
private fun createTestHttpClient(): HttpClient {
    val engine = MockEngine { request ->
        when {
            // Simulate a successful PDF upload response.
            request.url.encodedPath.contains("/upload-pdf/") -> {
                respond("Upload success", HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
            }
            // Simulate a successful document deletion response.
            request.url.encodedPath.contains("/delete-doc/") -> {
                respond("Delete doc success", HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
            }
            // Simulate a successful chat state deletion response.
            request.url.encodedPath.contains("/delete-state/") -> {
                respond("Delete state success", HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
            }
            // Simulate a successful AI answer response.
            request.url.encodedPath.contains("/ask/") -> {
                respond("""{"response":"AI answer"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            // Default response for other URLs.
            else -> {
                respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
    }
    return HttpClient(engine)
}

// Configure a test module that installs routes and required plugins.
// This function sets up ContentNegotiation, Sessions, the test database, and routes.
fun Application.testModule() {
    install(ContentNegotiation) {
        json()
    }
    install(Sessions) {
        cookie<GuestSession>("GUEST_SESSION"){}
    }
    initTestDatabase()
    routing {
        userRoutes(createTestHttpClient())
    }
}

class UserRoutesTest {

    @Test
    fun testSessionInitUnauthorized() = testApplication {
        application { testModule() }
        // Send a GET request to the /session-init endpoint.
        val response = client.get("/chat-page/session-init") {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        print(response)
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun testDocumentsPostMissingFormData() = testApplication {
        application { testModule() }
        val boundary = "WebAppBoundary"

        // Create multipart form data with empty content.
        val content = MultiPartFormDataContent(
        formData { /* empty form data */ },
        boundary = boundary
        )

        // Send a POST request to the /documents endpoint.
        val response = client.post("/chat-page/documents") {
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            setBody(content)
        }
        // Expect a BadRequest status because form data is missing.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing form data"))
    }

    @Test
    fun testDocumentsPostEmptyFile() = testApplication {
        application { testModule() }
        val boundary = "WebAppBoundary"
        // Create multipart form data with required fields but an empty file.
        val content = MultiPartFormDataContent(
            formData {
                append("user_id", "testUser")
                append("source", "testSource")
                // Append an empty file part.
                append("file", ByteArray(0), Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ContentDisposition, "filename=\"test.pdf\"")
                })
            },
            boundary = boundary
        )

        // Send a POST request to the /documents endpoint.
        val response = client.post("/chat-page/documents") {
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            setBody(content)
        }
        // Expect a BadRequest status due to empty file content.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("fileBytes empty"))
    }

    @Test
    fun testDocumentsPostSuccess() = testApplication {
        application { testModule() }
        val boundary = "WebAppBoundary"
        val fileBytes = "dummy pdf content".toByteArray()
        // Create multipart form data with valid user_id, source, and file.
        val content = MultiPartFormDataContent(
            formData {
                append("user_id", "testUser")
                append("source", "testSource")
                append("file", fileBytes, Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ContentDisposition, "filename=\"test.pdf\"")
                })
            },
            boundary = boundary
        )
        // Send a POST request to the /documents endpoint.
        val response = client.post("/chat-page/documents") {
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            setBody(content)
        }
        // Expect a Created status for a successful upload.
        assertEquals(HttpStatusCode.Created, response.status)
        assert(response.bodyAsText().contains("Document added and upload successful"))
    }

    @Test
    fun testDocumentsDeleteMissingParams() = testApplication {
        application { testModule() }
        // Test deletion with missing user_id.
        var response = client.delete("/chat-page/documents?doc_name=testSource")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing user_id"))

        // Test deletion with missing doc_name.
        response = client.delete("/chat-page/documents?user_id=testUser")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing doc_name"))
    }

    @Test
    fun testDocumentsDeleteSuccess() = testApplication {
        application { testModule() }
        // Send a DELETE request with proper query parameters.
        val response = client.delete("/chat-page/documents?user_id=testUser&doc_name=testSource")
        // Expect an OK status if deletion is successful.
        assertEquals(HttpStatusCode.OK, response.status)
        assert(response.bodyAsText().contains("Delete doc success"))
    }

    @Test
    fun testChatsPostMissingThreadId() = testApplication {
        application { testModule() }
        // Send a POST request to /chats without the thread_id parameter.
        val response = client.post("/chat-page/chats?user_id=testUser&chat_name=DefaultChat&document_names=doc1&document_names=doc2")
        // Send a POST request to /chats without the thread_id parameter.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing thread_id"))
    }

    @Test
    fun testChatsPostSuccess() = testApplication {
        application { testModule() }
        // Send a POST request to create a new chat with all required parameters.
        val response = client.post("/chat-page/chats?user_id=testUser&thread_id=thread1&chat_name=DefaultChat&document_names=doc1&document_names=doc2")
        // Expect a Created status for a successful chat creation.
        assertEquals(HttpStatusCode.Created, response.status)
        assert(response.bodyAsText().contains("Chat documents added"))
    }

    @Test
    fun testChatsDeleteMissingParams() = testApplication {
        application { testModule() }
        // Test deletion with missing userId.
        var response = client.delete("/chat-page/chats?thread_id=thread1&chatName=testChat")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing userId"))

        // Test deletion with missing thread_id.
        response = client.delete("/chat-page/chats?userId=testUser&chatName=testChat")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing thread_id"))

        // Test deletion with missing chatName.
        response = client.delete("/chat-page/chats?userId=testUser&thread_id=thread1")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing chatName"))
    }

    @Test
    fun testChatsDeleteSuccess() = testApplication {
        application { testModule() }
        // Send a DELETE request with all required parameters.
        val response = client.delete("/chat-page/chats?userId=testUser&thread_id=thread1&chatName=testChat")
        // Expect an OK status if the deletion is successful.
        assertEquals(HttpStatusCode.OK, response.status)
        assert(response.bodyAsText().contains("Chat deleted"))
    }

    @Test
    fun testMessagesGetMissingThreadId() = testApplication {
        application { testModule() }
        // Send a GET request to /messages without providing thread_id.
        val response = client.get("/chat-page/messages")
        // Expect a BadRequest status.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing thread_id"))
    }

    @Test
    fun testMessagesGetSuccess() = testApplication {
        application { testModule() }
        // Send a GET request to retrieve messages for a given thread.
        val response = client.get("/chat-page/messages?thread_id=thread1")
        // Expect an OK status.
        assertEquals(HttpStatusCode.OK, response.status)
        assert(response.bodyAsText().contains("[]"))
    }

    @Test
    fun testAskGetMissingParams() = testApplication {
        application { testModule() }
        // Send a GET request to /ask missing the "query" parameter.
        val response = client.get("/chat-page/ask?thread_id=thread1&user_id=testUser")
        // Expect a BadRequest status because "query" is required.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing query"))
    }

    @Test
    fun testAskGetSuccess() = testApplication {
        application { testModule() }

        // Create a chat record for thread1.
        val chatResponse = client.post("/chat-page/chats?user_id=testUser&thread_id=thread1&chat_name=DefaultChat&document_names=doc1")
        assertEquals(HttpStatusCode.Created, chatResponse.status)

        // Send GET request to /ask.
        val response = client.get("/chat-page/ask?query=Hello&thread_id=thread1&user_id=testUser&document_names=doc1")
        // Expect 201 Created
        assertEquals(HttpStatusCode.Created, response.status)
        assert(response.bodyAsText().contains("AI answer"))
    }

}