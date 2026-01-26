package org.example.app.services

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object UserDocuments : Table("user_documents") {
    val userId = text("user_id")
    val documentName = text("document_name")
    val uploadTime = timestampWithTimeZone("upload_time").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(userId, documentName, name = "PK_UserDocuments")
}

object UserChats : Table("user_chats") {
    val userId = text("user_id")
    val threadId = text("thread_id")
    val chatName = text("chat_name")
    val timeUpdated = timestampWithTimeZone("creation_time").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(userId, threadId, chatName, name = "PK_UserChats")
    init {
        uniqueIndex("ux_user_chats_thread_id", threadId)
    }
}

object UserChatDocuments : Table("user_chat_documents") {
    val threadId = text("thread_id").references(UserChats.threadId, onDelete = ReferenceOption.CASCADE)
    val userId = text("user_id")
    val documentName = text("document_name")
    override val primaryKey = PrimaryKey(threadId, userId, documentName, name = "PK_UserChatDocuments")
}

object ChatMessages : Table("chat_messages") {
    val id = integer("id").autoIncrement()
    val threadId = text("thread_id").references(UserChats.threadId, onDelete = ReferenceOption.CASCADE)
    val sender = text("sender")
    val message = text("message")
    val timeSent = timestampWithTimeZone("time_sent").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id, name = "PK_ChatMessages")
}