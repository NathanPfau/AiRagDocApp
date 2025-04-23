package org.example.app
import java.io.File

import io.github.cdimascio.dotenv.Dotenv
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.calllogging.*
import org.slf4j.event.Level
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.http.content.staticFiles
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.app.services.GuestSession
import org.example.app.services.cleanupGuestSession
import org.example.app.services.guestSessionList
import routes.healthRoutes
import routes.userRoutes
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.example.app.services.ChatMessages
import org.example.app.services.UserChatDocuments
import org.example.app.services.UserChats
import org.example.app.services.UserDocuments
import io.ktor.http.encodeURLPath

val dotenv: Dotenv = Dotenv.configure()
    .directory("./")
    .load()
val pythonServerUrl = dotenv["PYTHON_URL"] ?: throw IllegalStateException("PYTHON_URL is not set")
val rdsHost = dotenv["RDS_HOST"] ?: throw IllegalStateException("RDS_URL is not set")
val rdsUsername = dotenv["RDS_USERNAME"] ?: throw IllegalStateException("RDS_USERNAME is not set")
val rdsPassword = dotenv["RDS_PASSWORD"] ?: throw IllegalStateException("RDS_PASSWORD is not set")
val clientId = dotenv["COGNITO_CLIENT_ID"] ?: throw IllegalStateException("COGNITO_CLIENT_ID is not set")
val cognitoDomain = dotenv["COGNITO_DOMAIN"] ?: throw IllegalStateException("COGNITO_DOMAIN is not set")
val logoutRedirectUri = dotenv["LOGOUT_REDIRECT_URI"] ?: throw IllegalStateException("LOGOUT_REDIRECT_URI is not set")

fun main(args: Array<String>) {
    embeddedServer(Netty, configure = {
        connectors.add(EngineConnectorBuilder().apply {
            host = "0.0.0.0"
            port = 80
        })
    }) {

        install(ContentNegotiation) {
            json()
        }
        install(CallLogging) {
            level = Level.INFO
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, "Internal Server Error: ${cause.localizedMessage}")
            }
        }
        install(Sessions) {
            cookie<GuestSession>("GUEST_SESSION") {
                cookie.httpOnly = true
                cookie.maxAgeInSeconds = 18000
            }
        }
        Database.connect(
            url = rdsHost,
            driver = "org.postgresql.Driver",
            user = rdsUsername,
            password = rdsPassword
        )
        transaction {
            SchemaUtils.create(UserDocuments, UserChats, UserChatDocuments, ChatMessages)
        }



        val client = HttpClient(){
            install(HttpTimeout){
                requestTimeoutMillis = 360000
            }
        }

        val cleanupIntervalMillis = 60 * 60 * 1000L
        // Launch background cleanup job
        launch {
            while (true) {
                delay(cleanupIntervalMillis)
                try {
                    val now = System.currentTimeMillis()

                    for (session in guestSessionList.toList()){
                        if ((now - session.createdAt) >= 18000 * 1000L){
                            cleanupGuestSession(client, session)
                            guestSessionList.remove(session)
                        }
                    }

                    environment.log.info("Guest session cleanup completed successfully.")
                } catch (ex: Exception) {
                    environment.log.error("Error during guest session cleanup", ex)
                }
            }
        }

        routing {
            userRoutes(client)
            healthRoutes()
            staticFiles("/static", File("/app/static"))
            // Serve index.html on the root URL
            get("/") {
                call.respondFile(File("/app/static/index.html"))
            }
            get("/login") {
                call.respondRedirect("/chat-page")
            }
            get("/signup") {
                call.respondRedirect("/chat-page")
            }
            get("/chat-page") {
                call.respondFile(File("/app/static/index.html"))
            }
            routing {
                get("/logout") {
                    // Invalidate the AWSELBAuthSessionCookie by setting it to an empty value and an immediate expiration
                    call.response.cookies.append(
                        Cookie(
                            name = "AWSELBAuthSessionCookie-0",
                            value = "",
                            maxAge = 0, // Expires immediately
                            path = "/", // Make sure this matches the path of the original cookie
                            secure = true, // Should match the original cookie's secure attribute
                            httpOnly = true // Should match the original cookie's httpOnly attribute
                        )
                    )
                    call.response.cookies.append(
                        Cookie(
                            name = "AWSELBAuthSessionCookie-1",
                            value = "",
                            maxAge = 0, // Expires immediately
                            path = "/", // Make sure this matches the path of the original cookie
                            secure = true, // Should match the original cookie's secure attribute
                            httpOnly = true // Should match the original cookie's httpOnly attribute
                        )
                    )
                    call.respondRedirect("$cognitoDomain/logout?client_id=$clientId&logout_uri=" +
                            logoutRedirectUri.encodeURLPath(encodeSlash = true))
                }
            }
            get("{...}") {
            call.respond(HttpStatusCode.NotFound, "Not Found")
            }
        }

    }.start(wait = true)
}
