package routes

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.pythonServerUrl
import org.example.app.services.AlbAuthPlugin
import org.example.app.services.ChatMessages
import org.example.app.services.UserChatDocuments
import org.example.app.services.UserChats
import org.example.app.services.UserDocuments
import org.example.app.services.getEffectiveUserId
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

const val region_ : String = "us-west-2"

@Serializable
data class SessionResponse(
    val userId: String,
    val documents: List<String>,
    val chats: List<Chat>,
    val isGuest: Boolean
)

@Serializable
data class Chat(
    val threadId: String,
    val chatName: String,
    val chatDocs: List<String> = emptyList()
)

@Serializable
data class ChatDoc(
    val threadId: String,
    val documentName: String
)

@Serializable
data class ChatMessageDTO(
    val sender: String,
    val message: String
)

@Serializable
data class MessagesResponse(
    val messages: List<ChatMessageDTO>,
    val chatDocs: List<String>
)

fun Route.userRoutes(client: HttpClient) {

    route("/chat-page") {
        install(AlbAuthPlugin) {
            this.client = client
            this.region = region_
        }

        get("/session-init") {
            try {
                val effectiveUserId: String? = call.getEffectiveUserId()
                if (effectiveUserId == null) {
                } else {
                    val documents: List<String> = transaction {
                        val docs: List<String> = UserDocuments.selectAll().where { UserDocuments.userId eq effectiveUserId }
                            .map { it[UserDocuments.documentName] }
                        docs
                    }
                    val chats: List<Map<String, String>> = transaction {
                        val ch: List<Map<String, String>> = UserChats.selectAll().where { UserChats.userId eq effectiveUserId }
                            .map { mapOf("thread_id" to it[UserChats.threadId], "chat_name" to it[UserChats.chatName]) }
                        ch
                    }
                    val threadIds: List<String> = chats.map { it["thread_id"] as String }
                    val chatDocs: List<Map<String, String>> = transaction {
                        val cds: List<Map<String, String>> = UserChatDocuments.selectAll()
                            .where { UserChatDocuments.threadId inList threadIds }
                            .map { mapOf("thread_id" to it[UserChatDocuments.threadId], "document_name" to it[UserChatDocuments.documentName]) }
                        cds
                    }

                    val chatList: List<Chat> = chats.map { chatMap ->
                        val threadId = chatMap["thread_id"]!!
                        val chatName = chatMap["chat_name"]!!
                        // Filter the chatDocs for those that belong to this chat/thread.
                        val chatDocsForChat = chatDocs.filter { it["thread_id"] == threadId }
                            .map { it["document_name"]!! }
                        Chat(threadId, chatName, chatDocsForChat)
                    }
                    val chatDocsList: List<ChatDoc> = chatDocs.map { ChatDoc(it["thread_id"]!!, it["document_name"]!!) }

                    val sessionResponse = SessionResponse(
                        userId = effectiveUserId,
                        documents = documents,
                        chats = chatList,
                        isGuest = effectiveUserId.startsWith("guest_"),
                    )

                    call.respond(HttpStatusCode.OK, sessionResponse)
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error in /session-init")
            }
        }

        route("/documents") {

            // POST /documents
            post {
                try {
                    val multipart = call.receiveMultipart()
                    var userId: String? = null
                    var source: String? = null
                    var filePart: PartData.FileItem? = null
                    var fileBytes: ByteArray? = null

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                when (part.name) {
                                    "user_id" -> userId = part.value
                                    "source" -> source = part.value
                                }
                            }
                            is PartData.FileItem -> {
                                if (part.name == "file") {
                                    filePart = part
                                    fileBytes = filePart.provider().toByteArray()
                                }
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    if (userId == null || source == null || filePart == null) {
                        call.respondText("Missing form data", status = HttpStatusCode.BadRequest)
                        return@post
                    }

                    if (fileBytes?.isEmpty() == true) {
                        call.respondText("fileBytes empty", status = HttpStatusCode.BadRequest)
                        return@post
                    }

                    val response: HttpResponse = client.submitFormWithBinaryData(
                        url = "$pythonServerUrl/upload-pdf/",
                        formData = formData {
                            append("file", fileBytes!!, Headers.build {
                                append(HttpHeaders.ContentType, "application/pdf")
                                append(HttpHeaders.ContentDisposition, "filename=\"${filePart.originalFileName}\"")
                            })
                            append("user_id", userId)
                            append("source", source)
                        }
                    )

                    if (response.status.isSuccess()) {
                        transaction {
                            UserDocuments.insert {
                                it[UserDocuments.userId] = userId
                                it[UserDocuments.documentName] = source
                            }
                        }
                        call.respond(HttpStatusCode.Created, "Document added and upload successful")
                    } else {
                        call.respondText("Upload failed: ${response.bodyAsText()}", status = response.status)
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error in POST /documents")
                }
            }
            // DELETE /documents?user_id=...&document_name=...
            delete {
                try {
                    val userId = call.request.queryParameters["user_id"]
                        ?: return@delete call.respondText("Missing user_id", status = HttpStatusCode.BadRequest)
                    val docName = call.request.queryParameters["doc_name"]
                        ?: return@delete call.respondText("Missing doc_name", status = HttpStatusCode.BadRequest)
                    val response: HttpResponse = client.delete("$pythonServerUrl/delete-doc/") {
                        parameter("user_id", userId)
                        parameter("doc_name", docName)
                    }
                    call.respondText(response.bodyAsText(), status = response.status)

                    if (response.status.isSuccess()) {
                        transaction {
                            UserChatDocuments.deleteWhere {
                                (UserChatDocuments.userId eq userId) and (UserChatDocuments.documentName eq docName)
                            }
                        }
                        transaction {
                            UserDocuments.deleteWhere {
                                (UserDocuments.userId eq userId) and (UserDocuments.documentName eq docName)
                            }
                        }
                    } else {
                        call.respond(HttpStatusCode.BadRequest, "Document not deleted")
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error in DELETE /documents")
                }
            }
        }


        route("/chats") {

            post {
                try {
                    val userId = call.request.queryParameters["user_id"]
                        ?: return@post call.respondText("Missing user_id", status = HttpStatusCode.BadRequest)
                    val threadId = call.request.queryParameters["thread_id"]
                        ?: return@post call.respondText("Missing thread_id", status = HttpStatusCode.BadRequest)
                    val chatName = call.request.queryParameters["chat_name"]
                        ?: return@post call.respondText("Missing user_id", status = HttpStatusCode.BadRequest)
                    val chatDocs = call.request.queryParameters.getAll("document_names") ?: listOf()

                    transaction {
                        UserChats.insert {
                            it[UserChats.userId] = userId
                            it[UserChats.threadId] = threadId
                            it[UserChats.chatName] = chatName
                        }
                    }
                    transaction {
                        for (docName in chatDocs) {
                            UserChatDocuments.insert {
                                it[UserChatDocuments.threadId] = threadId
                                it[UserChatDocuments.userId] = userId
                                it[UserChatDocuments.documentName] = docName
                            }
                        }
                    }
                    call.respond(HttpStatusCode.Created, "Chat added")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error in POST /chats")
                }
            }
            // DELETE /chats?user_id=...&thread_id=...&chat_name=...
            delete {
                try {
                    val userId = call.request.queryParameters["userId"]
                        ?: return@delete call.respondText("Missing userId", status = HttpStatusCode.BadRequest)
                    val threadId = call.request.queryParameters["thread_id"]
                        ?: return@delete call.respondText("Missing thread_id", status = HttpStatusCode.BadRequest)
                    val chatName = call.request.queryParameters["chatName"]
                        ?: return@delete call.respondText("Missing chatName", status = HttpStatusCode.BadRequest)
                    val response: HttpResponse = client.delete("$pythonServerUrl/delete-state/") {
                        parameter("thread_id", threadId)
                    }
                    if (response.status.isSuccess()) {
                        transaction {
                            UserChats.deleteWhere {
                                (UserChats.userId eq userId) and
                                        (UserChats.threadId eq threadId) and
                                        (UserChats.chatName eq chatName)
                            }
                        }
                        call.respond(HttpStatusCode.OK, "Chat deleted")
                    } else {
                        call.respond(HttpStatusCode.BadRequest, "Chat deletion failed")
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error in DELETE /chats")
                }
            }
        }

        route("/messages") {
            get {
                try {
                    val threadId = call.request.queryParameters["thread_id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing thread_id")
                    val messageChain: List<ChatMessageDTO> = transaction {
                        ChatMessages
                            .selectAll()
                            .where { ChatMessages.threadId eq threadId }
                            .orderBy(ChatMessages.timeSent to SortOrder.ASC)
                            .map { row ->
                                ChatMessageDTO(
                                    sender  = row[ChatMessages.sender],
                                    message = row[ChatMessages.message]
                                )
                            }
                    }
                    val chatDocs: List<String> = transaction {
                        UserChatDocuments.selectAll().where { UserChatDocuments.threadId eq threadId }
                            .map { it[UserChatDocuments.documentName] }
                    }
                    call.respond(HttpStatusCode.OK, MessagesResponse(messages = messageChain, chatDocs = chatDocs))
                } catch (e: Exception) {
                    call.application.environment.log.error("Error sending chat messages and docs", e)
                    call.respond(HttpStatusCode.InternalServerError, "Error in GET /messages")
                }
            }
        }

        route("/ask") {
            get {
                try {
                    val query = call.request.queryParameters["query"]
                        ?: return@get call.respondText("Missing query", status = HttpStatusCode.BadRequest)
                    val threadId = call.request.queryParameters["thread_id"]
                        ?: return@get call.respondText("Missing thread_id", status = HttpStatusCode.BadRequest)
                    val userId = call.request.queryParameters["user_id"]
                        ?: return@get call.respondText("Missing user_id", status = HttpStatusCode.BadRequest)
                    val documentNames = call.request.queryParameters.getAll("document_names") ?: listOf()

                    if (documentNames.isEmpty()) {
                        return@get call.respond(HttpStatusCode.BadRequest, "No documents provided")
                    }

                    val response: HttpResponse = client.get("$pythonServerUrl/ask/") {
                        parameter("query", query)
                        parameter("thread_id", threadId)
                        parameter("user_id", userId)
                        documentNames.forEach { docName ->
                            parameter("document_names", docName)
                        }
                    }
                    if (response.status.isSuccess()) {
                        val responseBody = response.bodyAsText()
                        val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
                        val aiMessage = jsonResponse["response"]?.jsonPrimitive?.content ?: "No content found."
                        transaction {
                            ChatMessages.insert {
                                it[ChatMessages.threadId] = threadId
                                it[ChatMessages.sender] = "user"
                                it[ChatMessages.message] = query
                            }
                            ChatMessages.insert {
                                it[ChatMessages.threadId] = threadId
                                it[ChatMessages.sender] = "ai"
                                it[ChatMessages.message] = aiMessage
                            }
                        }
                        call.respond(HttpStatusCode.Created, aiMessage)
                    } else {
                        call.respond(HttpStatusCode.BadRequest, "Failed to ask")
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error in GET /ask")
                }
            }
        }
    }
}