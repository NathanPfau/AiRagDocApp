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

/**
 * AlbAuthPlugin is used to verify if a user has an account or if they are a guest.
 * ALB has Cognito integrated in and the ALB handles the authentication flow with
 * cognito and after a successful authentication it sends a JWT with the request to
 * verify that the request is from an authenticated user and to verify that
 * the ALB is the one sending the request.
 */

val dotenv: Dotenv = Dotenv.configure()
    .directory("./")
    .load()
val albArn = dotenv["ALB_ARN"] ?: throw IllegalStateException("ALB_ARN is not set")

val AuthStatusKey = AttributeKey<Boolean>("AuthStatus")

/**
 * Retrieves the effective user ID from the current ApplicationCall.
 *
 * If the user is authenticated, it decodes the JWT token and extracts the "sub" claim.
 * Otherwise, it creates or retrieves a guest session and returns the guest session ID.
 */
fun ApplicationCall.getEffectiveUserId(): String? {
    val isAuthenticated = attributes.getOrNull(AuthStatusKey) == true
    if (isAuthenticated) {
        // Retrieve the JWT token from the request headers.
        val token = request.headers["x-amzn-oidc-data"]

        if (token != null) {
            // Decode the JWT and extract the "sub" claim, which represents the user ID.
            val decodedJwt = JWT.decode(token)
            val userId = decodedJwt.getClaim("sub").asString()
            return userId
        } else {
            return null
        }
    } else {
        // If not authenticated, either retrieve an existing guest session or create a new one.
        val guestSession = (sessions.get("GUEST_SESSION") as? GuestSession) ?: run {
            val newSession = GuestSession()
            sessions.set("GUEST_SESSION", newSession)
            newSession
        }
        // Add the guest session to the session manager.
        SessionManager.addGuestSession(guestSession)
        return guestSession.sessionId
    }
}

/**
 * Verifies the ALB token from the request headers.
 *
 * It checks that:
 * - The token exists.
 * - The token's header contains the expected ALB ARN as its signer.
 * - The JWT signature is valid using the public key fetched from AWS.
 *
 * @param client The HttpClient used to retrieve the public key.
 * @param region The AWS region where the ALB public keys are hosted.
 * @return True if the token is verified; otherwise, false.
 */
suspend fun ApplicationCall.verifyAlbToken(client: HttpClient, region: String): Boolean {

    val expectedAlbArn = albArn
    val token = request.headers["x-amzn-oidc-data"]
    if (token == null) {
        return false
    }
    // Split the token to extract the JWT header.
    val jwtParts = token.split(".")
    if (jwtParts.isEmpty()) {
        return false
    }
    val headerEncoded = jwtParts[0]
    val decodedHeaderBytes = Base64.getDecoder().decode(headerEncoded)
    val decodedHeaderString = String(decodedHeaderBytes)
    val headerJson = JSONObject(decodedHeaderString)

    // Verify that the signer in the JWT header matches the expected ALB ARN.
    val receivedAlbArn = headerJson.getString("signer")
    if (receivedAlbArn != expectedAlbArn) {
        return false
    }

    // Extract the key ID (kid) from the JWT header.
    val kid = headerJson.getString("kid")
    // Build the URL to fetch the public key in PEM format from AWS.
    val url = "https://public-keys.auth.elb.$region.amazonaws.com/$kid"

    // Retrieve the public key PEM string.
    val pubKeyPem: String = client.get(url).bodyAsText()
    // Convert the PEM string into an ECPublicKey object.
    val publicKey = getPublicKeyFromPEM(pubKeyPem) as ECPublicKey

    // Verify the JWT signature using the public key.
    val algorithm = Algorithm.ECDSA256(publicKey, null)
    return try {
        JWT.require(algorithm).build().verify(token)
        true
    } catch (e: Exception) {
        application.environment.log.debug("verifyAlbToken: JWT verification failed with exception: ${e.message}")
        false
    }
}

/**
 * Converts a PEM formatted public key string into a PublicKey object.
 *
 * @param pem The PEM string containing the public key.
 * @return The PublicKey derived from the PEM string.
 */
fun getPublicKeyFromPEM(pem: String): PublicKey {
    // Clean the PEM string by removing header, footer, and any whitespace.
    val cleanPem = pem
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\\s".toRegex(), "")
    val decoded = Base64.getDecoder().decode(cleanPem)
    val keySpec = X509EncodedKeySpec(decoded)
    val keyFactory = KeyFactory.getInstance("EC")
    return keyFactory.generatePublic(keySpec)
}

// Configuration class for the AlbAuthPlugin.
class AlbAuthPluginConfig {
    // HttpClient instance used to make requests (e.g., for retrieving public keys).
    lateinit var client: HttpClient
    // AWS region where the ALB public keys are hosted.
    lateinit var region: String
}

/**
 * A route-scoped plugin that verifies ALB tokens for incoming calls.
 *
 * It uses the provided HttpClient and AWS region from the configuration to verify
 * the token in the "x-amzn-oidc-data" header and then stores the authentication status
 * in the call's attributes.
 */
val AlbAuthPlugin = createRouteScopedPlugin("AlbAuthPlugin", ::AlbAuthPluginConfig) {
    val client = pluginConfig.client
    val region = pluginConfig.region

    onCall { call ->
        // Verify the ALB token and store the result (true if verified, false otherwise).
        val isAuthenticated = call.verifyAlbToken(client, region)
        call.attributes.put(AuthStatusKey, isAuthenticated)
    }
}