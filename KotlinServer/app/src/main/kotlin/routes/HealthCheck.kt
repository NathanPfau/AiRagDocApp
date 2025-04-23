package routes

import io.ktor.server.routing.*
import io.ktor.server.response.*


fun Route.healthRoutes() {
    get("/health"){
        call.respondText("OK")
    }
}
