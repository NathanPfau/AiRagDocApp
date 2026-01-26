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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.Database
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
fun initTestDatabase() {
    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    transaction {
        // Create tables used in your routes.
        SchemaUtils.create(UserDocuments, UserChats, UserChatDocuments, ChatMessages)
    }
}

// A dummy external client that returns responses based on the request URL.
private fun createTestHttpClient(): HttpClient {
    val engine = MockEngine { request ->
        when {
            request.url.encodedPath.contains("/upload-pdf/") -> {
                respond("Upload success", HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
            }
            request.url.encodedPath.contains("/delete-doc/") -> {
                respond("Delete doc success", HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
            }
            request.url.encodedPath.contains("/delete-state/") -> {
                respond("Delete state success", HttpStatusCode.OK, headersOf("Content-Type", "text/plain"))
            }
            request.url.encodedPath.contains("/ask/") -> {
                respond("""{\"response\":\"AI answer\"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            else -> {
                respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
    }
    return HttpClient(engine)
}

// Configure a test module that installs our routes.
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
    val content = MultiPartFormDataContent(
        formData { /* empty form data */ },
        boundary = boundary
    )

    val response = client.post("/chat-page/documents") {
        header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
        setBody(content)
    }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing form data"))
    }

    @Test
    fun testDocumentsPostEmptyFile() = testApplication {
        application { testModule() }
        val boundary = "WebAppBoundary"
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
        val response = client.post("/chat-page/documents") {
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            setBody(content)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("fileBytes empty"))
    }

    @Test
    fun testDocumentsPostSuccess() = testApplication {
        application { testModule() }
        val boundary = "WebAppBoundary"
        val fileBytes = "dummy pdf content".toByteArray()
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
        val response = client.post("/chat-page/documents") {
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
            setBody(content)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assert(response.bodyAsText().contains("Document added and upload successful"))
    }

    @Test
    fun testDocumentsDeleteMissingParams() = testApplication {
        application { testModule() }
        // Missing user_id.
        var response = client.delete("/chat-page/documents?doc_name=testSource")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing user_id"))

        // Missing doc_name.
        response = client.delete("/chat-page/documents?user_id=testUser")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing doc_name"))
    }

    @Test
    fun testDocumentsDeleteSuccess() = testApplication {
        application { testModule() }
        val response = client.delete("/chat-page/documents?user_id=testUser&doc_name=testSource")
        assertEquals(HttpStatusCode.OK, response.status)
        assert(response.bodyAsText().contains("Delete doc success"))
    }

    @Test
    fun testChatsPostMissingThreadId() = testApplication {
        application { testModule() }
        val response = client.post("/chat-page/chats?user_id=testUser&chat_name=DefaultChat&document_names=doc1&document_names=doc2")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing thread_id"))
    }

    @Test
    fun testChatsPostSuccess() = testApplication {
        application { testModule() }
        val response = client.post("/chat-page/chats?user_id=testUser&thread_id=thread1&chat_name=DefaultChat&document_names=doc1&document_names=doc2")
        assertEquals(HttpStatusCode.Created, response.status)
        assert(response.bodyAsText().contains("Chat documents added"))
    }

    @Test
    fun testChatsDeleteMissingParams() = testApplication {
        application { testModule() }
        // Missing userId.
        var response = client.delete("/chat-page/chats?thread_id=thread1&chatName=testChat")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing userId"))

        // Missing thread_id.
        response = client.delete("/chat-page/chats?userId=testUser&chatName=testChat")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing thread_id"))

        // Missing chatName.
        response = client.delete("/chat-page/chats?userId=testUser&thread_id=thread1")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing chatName"))
    }

    @Test
    fun testChatsDeleteSuccess() = testApplication {
        application { testModule() }
        val response = client.delete("/chat-page/chats?userId=testUser&thread_id=thread1&chatName=testChat")
        assertEquals(HttpStatusCode.OK, response.status)
        assert(response.bodyAsText().contains("Chat deleted"))
    }

    @Test
    fun testMessagesGetMissingThreadId() = testApplication {
        application { testModule() }
        val response = client.get("/chat-page/messages")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing thread_id"))
    }

    @Test
    fun testMessagesGetSuccess() = testApplication {
        application { testModule() }
        val response = client.get("/chat-page/messages?thread_id=thread1")
        assertEquals(HttpStatusCode.OK, response.status)
        assert(response.bodyAsText().contains("[]"))
    }

    @Test
    fun testAskGetMissingParams() = testApplication {
        application { testModule() }
        val response = client.get("/chat-page/ask?thread_id=thread1&user_id=testUser")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("Missing query"))
    }

//    @Test
//    fun testAskGetSuccess() = testApplication {
//        application { testModule() }
//        val response = client.get("/chat-page/ask?query=Hello&thread_id=thread1&user_id=testUser&document_names=doc1")
//        assertEquals(HttpStatusCode.OK, response.status)
//        assert(response.bodyAsText().contains("response"))
//    }

//    @Test
//    fun testLogoutRedirect() = testApplication {
//        application { testModule() }
//        val response = client.get("/chat-page/logout")
//        assertEquals(HttpStatusCode.Found, response.status)
//        val location = response.headers[HttpHeaders.Location] ?: ""
//        assert(location.contains("logout"))
//        assert(location.contains("client_id"))
//        assert(location.contains("logout_uri"))
//    }
}