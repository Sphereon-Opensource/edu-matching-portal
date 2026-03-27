package com.sphereon.portal.sts

import com.sphereon.oauth2.server.authorization.impl.provider.ReconciliationCallbackHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * STS-side reconciliation handler that forwards extracted OIDC claims
 * to the auth-bridge for identity binding creation.
 *
 * Called by [FederatedUserAuthenticationProvider] when a federation callback
 * is for a reconciliation flow (flow=reconciliation in the pending state).
 *
 * Flow:
 * 1. STS exchanges authorization code for tokens, extracts claims
 * 2. This handler POSTs raw claims to auth-bridge `/auth/oid4vp/sessions/{sessionId}/reconciliation/complete`
 * 3. Auth-bridge creates identity match + encrypted binding
 * 4. Handler returns redirect URL for the browser → frontend login page
 *
 * @param authBridgeUrl Base URL of the auth bridge service (e.g., http://localhost:8090)
 * @param frontendUrl Base URL of the portal frontend (e.g., http://localhost:3000)
 */
class StsReconciliationHandler(
    private val authBridgeUrl: String,
    private val frontendUrl: String,
) : ReconciliationCallbackHandler {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    override suspend fun onReconciliationComplete(
        claims: Map<String, Any>,
        providerId: String,
        issuer: String,
        oid4vpSessionId: String,
    ): String {
        // Build the claims payload as JSON
        val claimsJson = JsonObject(claims.mapValues { (_, v) -> JsonPrimitive(v.toString()) })
        val body = JsonObject(mapOf(
            "claims" to claimsJson,
            "issuer" to JsonPrimitive(issuer),
            "providerId" to JsonPrimitive(providerId),
        ))
        val bodyString = json.encodeToString(JsonObject.serializer(), body)

        // POST to auth-bridge
        val url = "${authBridgeUrl.trimEnd('/')}/auth/oid4vp/sessions/$oid4vpSessionId/reconciliation/complete"
        val uri = URI(url)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            connection.outputStream.use { os ->
                os.write(bodyString.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no error body"
                } catch (_: Exception) { "could not read error" }
                throw RuntimeException("Reconciliation completion failed: HTTP $responseCode - $errorBody")
            }
        } finally {
            connection.disconnect()
        }

        // Return the frontend redirect URL
        return "${frontendUrl.trimEnd('/')}/login?wallet_session=$oid4vpSessionId&idv=complete"
    }
}
