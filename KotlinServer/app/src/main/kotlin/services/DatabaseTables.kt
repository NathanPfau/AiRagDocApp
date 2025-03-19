package org.example.app.services

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

// Table for storing document names uploaded by users.
object UserDocuments : Table("user_documents") {
    val userId = text("user_id")
    val documentName = text("document_name")
    val uploadTime = datetime("upload_time").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(userId, documentName, name = "PK_UserDocuments")
}

// Table for storing chat session names and the threadId.
object UserChats : Table("user_chats") {
    val userId = text("user_id")
    val threadId = text("thread_id")
    val chatName = text("chat_name")
    val timeUpdated = datetime("creation_time").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(userId, threadId, chatName, name = "PK_UserChats")
    init {
        uniqueIndex("ux_user_chats_thread_id", threadId)
    }
}

// Table for associating documents with chats.
object UserChatDocuments : Table("user_chat_documents") {
    val threadId = text("thread_id").references(UserChats.threadId, onDelete = ReferenceOption.CASCADE)
    val userId = text("user_id")
    val documentName = text("document_name")
    override val primaryKey = PrimaryKey(threadId, userId, documentName, name = "PK_UserChatDocuments")
}

// Table for storing individual chat messages.
object ChatMessages : Table("chat_messages") {
    val id = integer("id").autoIncrement()
    val threadId = text("thread_id").references(UserChats.threadId, onDelete = ReferenceOption.CASCADE)
    val sender = text("sender")
    val message = text("message")
    val timeSent = datetime("time_sent").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id, name = "PK_ChatMessages")
}