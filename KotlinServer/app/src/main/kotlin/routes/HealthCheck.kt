package routes

import io.ktor.server.routing.*
import io.ktor.server.response.*

// Health check for the AWS ALB

fun Route.healthRoutes() {
    get("/health"){
        call.respondText("OK")
    }
}
