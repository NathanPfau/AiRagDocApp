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
import org.example.app.services.SessionManager.guestSessionList
import org.example.app.services.SessionManager.removeGuestSession

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
    // Create an embedded Ktor server using Netty.
    embeddedServer(Netty, configure = {
        connectors.add(EngineConnectorBuilder().apply {
            host = "0.0.0.0"
            port = 80
        })
    }) {

        // Install ContentNegotiation plugin with JSON serialization support.
        install(ContentNegotiation) {
            json()
        }

        // Install CallLogging plugin for logging incoming requests.
        install(CallLogging) {
            level = Level.INFO
        }

        // Install StatusPages to handle exceptions and return proper HTTP responses.
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, "Internal Server Error: ${cause.localizedMessage}")
            }
        }

        // Install Sessions support with a cookie-based session for GuestSession.
        install(Sessions) {
            cookie<GuestSession>("GUEST_SESSION") {
                cookie.httpOnly = true
                cookie.maxAgeInSeconds = 18000
            }
        }

        // Connect to the PostgreSQL database using Exposed.
        Database.connect(
            url = rdsHost,
            driver = "org.postgresql.Driver",
            user = rdsUsername,
            password = rdsPassword
        )

        // Create the necessary database tables if they don't already exist.
        transaction {
            SchemaUtils.create(UserDocuments, UserChats, UserChatDocuments, ChatMessages)
        }

        // Create an HttpClient with an increased timeout to wait for responses from the Python server.
        val client = HttpClient(){
            install(HttpTimeout){
                // Increase timeout to 6 minutes to give enough time for response from python server
                requestTimeoutMillis = 360000
            }
        }

        val cleanupIntervalMillis = 60 * 60 * 1000L
        // Launch background cleanup job for expired guest sessions
        launch {
            while (true) {
                delay(cleanupIntervalMillis)
                try {
                    val now = System.currentTimeMillis()

                    // Iterate over a snapshot of the guest session list.
                    for (session in guestSessionList.toList()){
                        if ((now - session.createdAt) >= 18000 * 1000L){
                            cleanupGuestSession(client, session)
                            removeGuestSession(session)
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
            // Serve static files from the /app/static directory.
            staticFiles("/static", File("/app/static"))
            // Serve index.html on the root URL
            get("/") {
                call.respondFile(File("/app/static/index.html"))
            }

            // Redirect /login and /signup to /chat-page.
            get("/login") {
                call.respondRedirect("/chat-page")
            }
            get("/signup") {
                call.respondRedirect("/chat-page")
            }

            // Serve index.html for the /chat-page route.
            get("/chat-page") {
                call.respondFile(File("/app/static/index.html"))
            }

            // Nested routing block for logout.
            routing {
                get("/logout") {
                    // Invalidate the AWSELBAuthSessionCookie by setting it to an empty value and an immediate expiration
                    call.response.cookies.append(
                        Cookie(
                            name = "AWSELBAuthSessionCookie-0",
                            value = "",
                            maxAge = 0, // Expires immediately
                            path = "/",
                            secure = true,
                            httpOnly = true
                        )
                    )
                    call.response.cookies.append(
                        Cookie(
                            name = "AWSELBAuthSessionCookie-1",
                            value = "",
                            maxAge = 0, // Expires immediately
                            path = "/",
                            secure = true,
                            httpOnly = true
                        )
                    )
                    // Redirect to the Cognito logout URL with the specified clientId and logoutRedirectUri.
                    call.respondRedirect("$cognitoDomain/logout?client_id=$clientId&logout_uri=" +
                            logoutRedirectUri.encodeURLPath(encodeSlash = true))
                }
            }

            // Fallback route for unmatched requests: respond with 404 Not Found.
            get("{...}") {
                call.respond(HttpStatusCode.NotFound, "Not Found")
            }
        }

    }.start(wait = true)
}
