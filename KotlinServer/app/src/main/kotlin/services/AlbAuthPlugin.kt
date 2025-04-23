package org.example.app.services

import io.ktor.client.*
import io.ktor.client.request.*
import java.util.Base64
import org.json.JSONObject
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.sessions.sessions
import io.ktor.util.AttributeKey
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

val dotenv: Dotenv = Dotenv.configure()
    .directory("./")
    .load()
val albArn = dotenv["ALB_ARN"] ?: throw IllegalStateException("ALB_ARN is not set")

val AuthStatusKey = AttributeKey<Boolean>("AuthStatus")

fun ApplicationCall.getEffectiveUserId(): String? {
    val isAuthenticated = attributes.getOrNull(AuthStatusKey) == true
    if (isAuthenticated) {
        val token = request.headers["x-amzn-oidc-data"]
        // Log the token for debugging
        if (token != null) {
            val decodedJwt = JWT.decode(token)
            val userId = decodedJwt.getClaim("sub").asString()
            return userId
        } else {
            return null
        }
    } else {
        // Create or retrieve guest session
        val guestSession = (sessions.get("GUEST_SESSION") as? GuestSession) ?: run {
            val newSession = GuestSession()
            sessions.set("GUEST_SESSION", newSession)
            newSession
        }
        guestSessionList.add(guestSession)
        return guestSession.sessionId
    }
}

suspend fun ApplicationCall.verifyAlbToken(client: HttpClient, region: String): Boolean {
    request.headers.forEach { key, values ->
    }

    val expectedAlbArn = albArn
    val token = request.headers["x-amzn-oidc-data"]
    if (token == null) {
        return false
    }
    // Decode the JWT header (first part)
    val jwtParts = token.split(".")
    if (jwtParts.isEmpty()) {
        return false
    }
    val headerEncoded = jwtParts[0]
    val decodedHeaderBytes = Base64.getDecoder().decode(headerEncoded)
    val decodedHeaderString = String(decodedHeaderBytes)
    val headerJson = JSONObject(decodedHeaderString)

    // Verify the signer
    val receivedAlbArn = headerJson.getString("signer")
    if (receivedAlbArn != expectedAlbArn) {
        return false
    }

    // Get the key id from the header
    val kid = headerJson.getString("kid")
    val url = "https://public-keys.auth.elb.$region.amazonaws.com/$kid"

    // Retrieve the public key (PEM format) from AWS
    val pubKeyPem: String = client.get(url).bodyAsText()
    val publicKey = getPublicKeyFromPEM(pubKeyPem) as ECPublicKey

    // Verify the JWT using the public key
    val algorithm = Algorithm.ECDSA256(publicKey, null)
    return try {
        JWT.require(algorithm).build().verify(token)
        true
    } catch (e: Exception) {
        false
    }
}

fun getPublicKeyFromPEM(pem: String): PublicKey {
    val cleanPem = pem
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\\s".toRegex(), "")
    val decoded = Base64.getDecoder().decode(cleanPem)
    val keySpec = X509EncodedKeySpec(decoded)
    val keyFactory = KeyFactory.getInstance("EC")
    return keyFactory.generatePublic(keySpec)
}

// Define the route-scoped plugin.
class AlbAuthPluginConfig {
    lateinit var client: HttpClient
    lateinit var region: String
}

val AlbAuthPlugin = createRouteScopedPlugin("AlbAuthPlugin", ::AlbAuthPluginConfig) {
    val client = pluginConfig.client
    val region = pluginConfig.region

    onCall { call ->
        val isAuthenticated = call.verifyAlbToken(client, region)
        call.attributes.put(AuthStatusKey, isAuthenticated)
    }
}