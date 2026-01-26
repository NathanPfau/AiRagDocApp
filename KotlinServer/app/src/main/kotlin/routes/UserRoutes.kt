package routes

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.pythonServerUrl
import org.example.app.services.AlbAuthPlugin
import org.example.app.services.ChatMessages
import org.example.app.services.UserChatDocuments
import org.example.app.services.UserChats
import org.example.app.services.UserDocuments
import org.example.app.services.getEffectiveUserId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString

private val activeStreams = AtomicInteger(0)
private const val maxConcurrentStreams = 50
private val userStreams = ConcurrentHashMap<String, AtomicInteger>()
private const val maxStreamsPerUser = 3

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
data class StreamRequest(
    val thread_id: String,
    val query: String,
    val user_id: String,
    val document_names: List<String>
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
                    return@get call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                }

                // User is authenticated, proceed with session initialization
                run {
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
                    val authenticatedUserId = call.getEffectiveUserId()
                    if (authenticatedUserId == null) {
                        return@post call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                    }

                    val multipart = call.receiveMultipart()
                    var source: String? = null
                    var filePart: PartData.FileItem? = null
                    var fileBytes: ByteArray? = null

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                when (part.name) {
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

                    if (source == null || filePart == null) {
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
                            append("user_id", authenticatedUserId)
                            append("source", source)
                        }
                    )

                    if (response.status.isSuccess()) {
                        transaction {
                            UserDocuments.insert {
                                it[UserDocuments.userId] = authenticatedUserId
                                it[UserDocuments.documentName] = source
                            }
                        }
                        call.respond(HttpStatusCode.Created, "Document added and upload successful")
                    } else {
                        call.respondText("Upload failed", status = response.status)
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error in POST /documents")
                }
            }
            // DELETE /documents?doc_name=...
            delete {
                try {
                    val authenticatedUserId = call.getEffectiveUserId()
                    if (authenticatedUserId == null) {
                        return@delete call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                    }

                    val docName = call.request.queryParameters["doc_name"]
                        ?: return@delete call.respondText("Missing doc_name", status = HttpStatusCode.BadRequest)

                    val ownsDocument = transaction {
                        UserDocuments.selectAll().where {
                            (UserDocuments.userId eq authenticatedUserId) and (UserDocuments.documentName eq docName)
                        }.count() > 0
                    }
                    if (!ownsDocument) {
                        return@delete call.respond(HttpStatusCode.Forbidden, "Access denied")
                    }

                    val response: HttpResponse = client.delete("$pythonServerUrl/delete-doc/") {
                        parameter("user_id", authenticatedUserId)
                        parameter("doc_name", docName)
                    }

                    if (response.status.isSuccess()) {
                        transaction {
                            UserChatDocuments.deleteWhere {
                                (UserChatDocuments.userId eq authenticatedUserId) and (UserChatDocuments.documentName eq docName)
                            }
                        }
                        transaction {
                            UserDocuments.deleteWhere {
                                (UserDocuments.userId eq authenticatedUserId) and (UserDocuments.documentName eq docName)
                            }
                        }
                        call.respond(HttpStatusCode.OK, "Document deleted successfully")
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
                    val authenticatedUserId = call.getEffectiveUserId()
                    if (authenticatedUserId == null) {
                        return@post call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                    }

                    val threadId = call.request.queryParameters["thread_id"]
                        ?: return@post call.respondText("Missing thread_id", status = HttpStatusCode.BadRequest)
                    val chatName = call.request.queryParameters["chat_name"]
                        ?: return@post call.respondText("Missing chat_name", status = HttpStatusCode.BadRequest)
                    val chatDocs = call.request.queryParameters.getAll("document_names") ?: listOf()

                    if (chatDocs.isNotEmpty()) {
                        val ownedDocs = transaction {
                            UserDocuments.selectAll().where {
                                (UserDocuments.userId eq authenticatedUserId) and
                                        (UserDocuments.documentName inList chatDocs)
                            }.map { it[UserDocuments.documentName] }
                        }
                        val unauthorizedDocs = chatDocs.filterNot { it in ownedDocs }
                        if (unauthorizedDocs.isNotEmpty()) {
                            return@post call.respond(HttpStatusCode.Forbidden, "Access denied to specified documents")
                        }
                    }

                    transaction {
                        UserChats.insert {
                            it[UserChats.userId] = authenticatedUserId
                            it[UserChats.threadId] = threadId
                            it[UserChats.chatName] = chatName
                        }
                    }
                    transaction {
                        for (docName in chatDocs) {
                            UserChatDocuments.insert {
                                it[UserChatDocuments.threadId] = threadId
                                it[UserChatDocuments.userId] = authenticatedUserId
                                it[UserChatDocuments.documentName] = docName
                            }
                        }
                    }
                    call.respond(HttpStatusCode.Created, "Chat added")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error in POST /chats")
                }
            }
            // DELETE /chats?thread_id=...&chatName=...
            delete {
                try {
                    val authenticatedUserId = call.getEffectiveUserId()
                    if (authenticatedUserId == null) {
                        return@delete call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                    }

                    val threadId = call.request.queryParameters["thread_id"]
                        ?: return@delete call.respondText("Missing thread_id", status = HttpStatusCode.BadRequest)
                    val chatName = call.request.queryParameters["chatName"]
                        ?: return@delete call.respondText("Missing chatName", status = HttpStatusCode.BadRequest)

                    val ownsChat = transaction {
                        UserChats.selectAll().where {
                            (UserChats.userId eq authenticatedUserId) and (UserChats.threadId eq threadId)
                        }.count() > 0
                    }
                    if (!ownsChat) {
                        return@delete call.respond(HttpStatusCode.Forbidden, "Access denied")
                    }

                    val response: HttpResponse = client.delete("$pythonServerUrl/delete-state/") {
                        parameter("thread_id", threadId)
                    }
                    if (response.status.isSuccess()) {
                        transaction {
                            // Delete associated chat documents first
                            UserChatDocuments.deleteWhere {
                                (UserChatDocuments.userId eq authenticatedUserId) and
                                        (UserChatDocuments.threadId eq threadId)
                            }
                            // Delete chat messages
                            ChatMessages.deleteWhere {
                                ChatMessages.threadId eq threadId
                            }
                            // Finally delete the chat itself
                            UserChats.deleteWhere {
                                (UserChats.userId eq authenticatedUserId) and
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
                    val authenticatedUserId = call.getEffectiveUserId()
                    if (authenticatedUserId == null) {
                        return@get call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                    }

                    val threadId = call.request.queryParameters["thread_id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing thread_id")

                    val chatOwnership = transaction {
                        val chat = UserChats.selectAll().where {
                            UserChats.threadId eq threadId
                        }.firstOrNull()

                        when {
                            chat == null -> "not_found"  // Chat doesn't exist yet (new chat)
                            chat[UserChats.userId] == authenticatedUserId -> "owned"  // User owns this chat
                            else -> "forbidden"  // Chat exists but belongs to someone else
                        }
                    }

                    when (chatOwnership) {
                        "forbidden" -> {
                            return@get call.respond(HttpStatusCode.Forbidden, "Access denied")
                        }
                        "not_found" -> {
                            // New chat that hasn't been saved to DB yet - return empty messages
                            return@get call.respond(HttpStatusCode.OK, MessagesResponse(messages = emptyList(), chatDocs = emptyList()))
                        }
                        // "owned" -> continue to fetch messages below
                    }

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

        // Streaming endpoint for real-time AI responses
        post("/ask-stream") {
            var threadId = ""
            var query = ""
            var userId = ""
            var documentNames: List<String> = emptyList()
            var userMessageId: Int? = null
            var placeholderAiMessageId: Int? = null
            val accumulatedResponse = StringBuilder()

            try {
                val authenticatedUserId = call.getEffectiveUserId()
                if (authenticatedUserId == null) {
                    call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                    return@post
                }
                userId = authenticatedUserId

                // Parse JSON request body
                val requestBody = call.receiveText()
                val requestJson = Json.parseToJsonElement(requestBody).jsonObject
                threadId = requestJson["thread_id"]?.jsonPrimitive?.content ?: ""
                query = requestJson["query"]?.jsonPrimitive?.content ?: ""
                documentNames = requestJson["document_names"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                // Validate required parameters
                if (threadId.isEmpty() || query.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, "Missing thread_id or query")
                    return@post
                }

                if (documentNames.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, "No documents provided")
                    return@post
                }

                val ownsThread = transaction {
                    UserChats.selectAll().where {
                        (UserChats.threadId eq threadId) and (UserChats.userId eq userId)
                    }.count() > 0
                }
                if (!ownsThread) {
                    call.respond(HttpStatusCode.Forbidden, "Access denied")
                    return@post
                }

                // Validate input lengths
                if (query.length > 10000) {
                    call.respond(HttpStatusCode.BadRequest, "Query too long. Maximum 10000 characters.")
                    return@post
                }
                if (threadId.length > 100 || userId.length > 100) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid parameter length")
                    return@post
                }
                if (documentNames.size > 100) {
                    call.respond(HttpStatusCode.BadRequest, "Too many documents. Maximum 100.")
                    return@post
                }

                // Check global stream limit
                if (activeStreams.get() >= maxConcurrentStreams) {
                    call.respond(HttpStatusCode.ServiceUnavailable, "Server is at capacity. Please try again later.")
                    return@post
                }

                // Check per-user stream limit
                val userStreamCount = userStreams.computeIfAbsent(userId) { AtomicInteger(0) }
                if (userStreamCount.get() >= maxStreamsPerUser) {
                    call.respond(HttpStatusCode.TooManyRequests, "Too many concurrent streams. Please wait for existing streams to complete.")
                    return@post
                }

                // Increment stream counters
                activeStreams.incrementAndGet()
                userStreamCount.incrementAndGet()

                call.application.environment.log.info("[STREAM] Starting streaming request for thread: $threadId, user: $userId")

                // Optimistic database insert: Save user message and AI placeholder BEFORE streaming
                val messageTimestamp = OffsetDateTime.now(ZoneOffset.UTC)

                transaction(transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                    // Insert user message
                    userMessageId = ChatMessages.insert {
                        it[ChatMessages.threadId] = threadId
                        it[ChatMessages.sender] = "user"
                        it[ChatMessages.message] = query
                        it[ChatMessages.timeSent] = messageTimestamp
                    } get ChatMessages.id

                    // Insert AI placeholder message (empty - will be updated after streaming)
                    placeholderAiMessageId = ChatMessages.insert {
                        it[ChatMessages.threadId] = threadId
                        it[ChatMessages.sender] = "ai"
                        it[ChatMessages.message] = "" // Empty placeholder
                        it[ChatMessages.timeSent] = messageTimestamp
                    } get ChatMessages.id

                    // Update chat timestamp to show last activity
                    UserChats.update({ UserChats.threadId eq threadId }) {
                        it[timeUpdated] = messageTimestamp
                    }
                }

                call.application.environment.log.info("[STREAM] Saved user message and AI placeholder (ID: $placeholderAiMessageId)")

                // Set SSE-friendly headers
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.response.headers.append("X-Accel-Buffering", "no") // For nginx proxies

                // Build request body for Python server using proper serialization
                val pythonRequestBody = Json.encodeToString(StreamRequest(
                    thread_id = threadId,
                    query = query,
                    user_id = userId,
                    document_names = documentNames
                ))

                call.application.environment.log.info("[STREAM] Proxying request to Python server: $pythonServerUrl/ask-stream/")

                // Use Ktor's SSE for proper streaming
                try {
                    call.respondBytesWriter(ContentType.Text.EventStream) {
                        coroutineScope {
                            // Immediate prelude to commit response and keep connection alive
                            writeStringUtf8(": sse prelude\n\n") // comment line; valid SSE
                            writeStringUtf8("event: ping\ndata: 0\n\n")
                            flush()

                            var firstDataSeen = false
                            var messageCount = 0

                            // Run the upstream SSE reader in a child coroutine
                            val readerJob = launch {
                                try {
                                    client.sseSession(urlString = "$pythonServerUrl/ask-stream/") {
                                        method = HttpMethod.Post
                                        contentType(ContentType.Application.Json)
                                        setBody(pythonRequestBody)
                                    }.incoming.collect { event ->
                                        val sseMessage = buildString {
                                            event.id?.let { append("id: $it\n") }
                                            event.event?.let { append("event: $it\n") }
                                            event.data?.let { append("data: $it\n") }
                                            append("\n")
                                        }

                                        // Accumulate tokens from response
                                        event.data?.let { data ->
                                            runCatching {
                                                val json = Json.parseToJsonElement(data).jsonObject
                                                json["token"]?.jsonPrimitive?.content?.let { accumulatedResponse.append(it) }
                                            }
                                        }

                                        messageCount++
                                        if (!firstDataSeen) firstDataSeen = true
                                        if (messageCount <= 5 || messageCount % 50 == 0) {
                                            call.application.environment.log.info("[STREAM] Forwarding SSE message #$messageCount to client")
                                        }

                                        writeStringUtf8(sseMessage)
                                        flush()
                                    }
                                } catch (e: Exception) {
                                    call.application.environment.log.error("[STREAM] Upstream connection error: ${e.message}", e)
                                    // Send error event to client
                                    val errorEvent = """event: error
data: {"message": "Connection to AI service failed", "type": "upstream_error"}

"""
                                    writeStringUtf8(errorEvent)
                                    flush()
                                }
                            }

                            // Heartbeats until first real data arrives (prevents proxy timeouts)
                            try {
                                while (readerJob.isActive && !firstDataSeen) {
                                    delay(3000)
                                    if (!firstDataSeen && readerJob.isActive) {
                                        writeStringUtf8("event: ping\ndata: 1\n\n")
                                        flush()
                                    }
                                }
                                // Wait for the reader to finish relaying all upstream events
                                readerJob.join()
                                call.application.environment.log.info("[STREAM] Forwarded total $messageCount SSE messages")
                            } catch (_: kotlinx.coroutines.CancellationException) {
                                call.application.environment.log.info("[STREAM] Client disconnected after $messageCount messages")
                            } catch (e: Throwable) {
                                call.application.environment.log.warn("[STREAM] SSE forward error: ${e.message}")
                                try {
                                    val errorEvent = """event: error
data: {"message": "Streaming error occurred", "type": "stream_error"}

"""
                                    writeStringUtf8(errorEvent)
                                    flush()
                                } catch (_: Exception) {
                                    // Ignore if client disconnected
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    call.application.environment.log.warn("[STREAM] Writer error (outer): ${e.message}")
                }

                // Update AI placeholder with accumulated response OR delete if streaming failed
                placeholderAiMessageId?.let { aiMsgId ->
                    if (accumulatedResponse.isNotEmpty()) {
                        try {
                            val aiResponseTimestamp = OffsetDateTime.now(ZoneOffset.UTC)
                            transaction(transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                                ChatMessages.update({ ChatMessages.id eq aiMsgId }) {
                                    it[ChatMessages.message] = accumulatedResponse.toString()
                                    it[timeSent] = aiResponseTimestamp
                                }

                                // Update chat timestamp with AI response time
                                UserChats.update({ UserChats.threadId eq threadId }) {
                                    it[timeUpdated] = aiResponseTimestamp
                                }
                            }
                            call.application.environment.log.info("[STREAM] Updated AI placeholder with response (${accumulatedResponse.length} chars)")
                        } catch (e: Exception) {
                            call.application.environment.log.error("[STREAM] Failed to update AI response: ${e.message}", e)
                            // Delete placeholder on update failure
                            try {
                                transaction(transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                                    ChatMessages.deleteWhere { id eq aiMsgId }
                                }
                                call.application.environment.log.warn("[STREAM] Deleted AI placeholder due to update failure")
                            } catch (deleteError: Exception) {
                                call.application.environment.log.error("[STREAM] Failed to delete placeholder: ${deleteError.message}")
                            }
                        }
                    } else {
                        // No response accumulated - streaming failed, delete both messages
                        try {
                            transaction(transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
                                ChatMessages.deleteWhere { id eq aiMsgId }
                                userMessageId?.let { userMsgId ->
                                    ChatMessages.deleteWhere { id eq userMsgId }
                                }
                            }
                            call.application.environment.log.warn("[STREAM] Deleted user message and AI placeholder - no response accumulated")
                        } catch (e: Exception) {
                            call.application.environment.log.error("[STREAM] Failed to delete messages: ${e.message}", e)
                        }
                    }
                }

                call.application.environment.log.info("[STREAM] Streaming completed successfully")

            } catch (e: Exception) {
                // Cannot call respond() here if response already started during streaming
                call.application.environment.log.error("[STREAM] Outer error: ${e.message}", e)
            } finally {
                // Decrement stream counters
                if (userId.isNotEmpty()) {
                    activeStreams.decrementAndGet()
                    userStreams[userId]?.decrementAndGet()
                }
            }
        }
    }
}