package org.example.app.services

import io.ktor.client.HttpClient
import org.example.app.pythonServerUrl
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

// SessionManager manages the list of guest sessions in memory.
object SessionManager {
    private val _guestSessionList = mutableListOf<GuestSession>()

    val guestSessionList: List<GuestSession>
        get() = _guestSessionList

    fun addGuestSession(session: GuestSession) {
        _guestSessionList.add(session)
    }

    fun removeGuestSession(session: GuestSession) {
        _guestSessionList.remove(session)
    }
}

// Data class representing a guest session.
// A unique sessionId is generated for each guest and a createdAt timestamp is recorded.
@Serializable
data class GuestSession(val sessionId: String = "guest_" + UUID.randomUUID().toString(), val createdAt: Long = System.currentTimeMillis())

// Cleans up all data associated with a guest session.
// This function deletes guest uploads, chat data from the database,
// and also removes associated files and threads from the Python server.
suspend fun cleanupGuestSession(client: HttpClient, guestSession: GuestSession) {
    val sessionId = guestSession.sessionId
    val allDocs: List<String> = transaction {
        UserDocuments.selectAll()
            .where { UserDocuments.userId eq sessionId }
            .map { it[UserDocuments.documentName] }
    }
    val allThreadIds: List<String> = transaction {
        UserChats.selectAll()
            .where { UserChats.userId eq sessionId }
            .map { it[UserChats.threadId] }
    }
    // Delete guest uploads/documents and chat data from the database.
    transaction {
        UserDocuments.deleteWhere { UserDocuments.userId eq sessionId }
        UserChats.deleteWhere { UserChats.userId eq sessionId }
    }
    // Delete associated files on the Python server.
    for (doc in allDocs) {
        client.delete("$pythonServerUrl/delete-doc/") {
            parameter("user_id", sessionId)
            parameter("doc_name", doc)
        }
    }
    // Delete associated threads on the Python server.
    for (threadId in allThreadIds) {
        client.delete("$pythonServerUrl/delete-state/") {
            parameter("thread_id", threadId)
        }
    }
}