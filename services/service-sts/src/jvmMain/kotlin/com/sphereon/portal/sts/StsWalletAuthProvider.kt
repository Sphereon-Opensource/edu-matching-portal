package com.sphereon.portal.sts

import com.sphereon.core.api.Err
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.oauth2.server.authorization.provider.AuthenticatedUser
import com.sphereon.oauth2.server.authorization.provider.AuthenticationError
import com.sphereon.oauth2.server.authorization.provider.AuthenticationHint
import com.sphereon.oauth2.server.authorization.provider.AuthenticationMethod
import com.sphereon.oauth2.server.authorization.provider.UserAuthenticationProvider
import com.sphereon.oauth2.server.authorization.provider.UserCredentials
import com.sphereon.oauth2.server.authorization.provider.UserInfo
import kotlin.time.Clock
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Lightweight wallet authentication provider for the STS.
 *
 * Calls the auth bridge service via HTTP to check if an OID4VP session is completed.
 * This avoids pulling in the full auth bridge implementation (with all its transitive deps)
 * into the STS.
 *
 * In the hybrid flow:
 * 1. Frontend creates OID4VP session on auth bridge (direct)
 * 2. Wallet scans QR, VP verified, optional reconciliation completes
 * 3. Frontend calls signIn('sts-wallet') which triggers STS /authorize?login_hint=oid4vp:{sessionId}
 * 4. STS calls this provider's getAuthenticatedUser() which calls auth bridge /complete
 * 5. Auth bridge returns userId, claims, acr, amr, claimSource -> STS issues authorization code
 *
 * @param authBridgeBaseUrl Base URL of the auth bridge service (e.g., http://localhost:8090)
 * @param connectionFactory Testable hook for opening the auth bridge HTTP connection
 */
class StsWalletAuthProvider(
    private val authBridgeBaseUrl: String,
    /** Canonical claim name to use as the effective userId/sub for wallet tokens (from config). */
    private val walletSubjectClaimName: String = "sub",
    private val connectionFactory: (URI) -> HttpURLConnection = { uri ->
        uri.toURL().openConnection() as HttpURLConnection
    }
) : UserAuthenticationProvider {

    private val json = Json { ignoreUnknownKeys = true }

    // Cache user info from completed sessions
    private val userInfoCache = mutableMapOf<String, UserInfo>()

    override suspend fun getAuthenticatedUser(
        sessionId: String
    ): IdkResult<AuthenticatedUser?, AuthenticationError> {
        try {
            // Call auth bridge to complete authentication for this session
            val url = URI("$authBridgeBaseUrl/auth/oid4vp/sessions/$sessionId/complete")
            println("[STS-Wallet] Calling auth bridge: $url")
            val conn = connectionFactory(url)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            val responseCode = conn.responseCode
            println("[STS-Wallet] Auth bridge response: $responseCode")
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                println("[STS-Wallet] Auth bridge error body: $errorBody")
                conn.disconnect()
                // 409 = session not verified yet or already completed — not an error, just not ready
                if (responseCode == 409) return Ok(null)
                return Ok(null)
            }
            conn.disconnect()

            println("[STS-Wallet] Auth bridge response body: $responseBody")
            val jsonObj = json.decodeFromString<JsonObject>(responseBody)
            val userId = jsonObj["userId"]?.jsonPrimitive?.content
                ?: jsonObj["user_id"]?.jsonPrimitive?.content
            if (userId == null) {
                println("[STS-Wallet] No userId found in response")
                return Ok(null)
            }
            println("[STS-Wallet] Authenticated user: $userId")
            val authenticatedAtMs = jsonObj["authenticatedAt"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: jsonObj["authenticated_at"]?.jsonPrimitive?.content?.toLongOrNull()
            val authenticatedAt = if (authenticatedAtMs != null) {
                kotlin.time.Instant.fromEpochMilliseconds(authenticatedAtMs)
            } else {
                Clock.System.now()
            }

            // Read ACR and AMR from the auth bridge response.
            // The auth bridge sets these based on the binding's AssuranceSummary (which is
            // derived from the reconciliation provider's configured assurance level).
            val acr = jsonObj["acr"]?.jsonPrimitive?.content
            val amr = (jsonObj["amr"] as? JsonArray)?.map { it.jsonPrimitive.content }

            // Enrich claims with canonical + auxiliary data from the auth bridge.
            // The /internal/enrichment/claims endpoint decrypts persisted canonical claims
            // and auxiliary data, merging them into the base claims from /complete.
            val baseClaims = jsonObj["claims"]?.jsonObject ?: JsonObject(emptyMap())
            val enrichedClaims = enrichClaims(userId, baseClaims)

            // Use the configured claim as the effective userId/sub (consistent with federated login).
            // The auth-bridge persists and returns the eduid claim; the STS config specifies which
            // claim to use via oid4vp.auth-bridge.user-identifier-claim-path.
            // Falls back to the auth-bridge userId (federated_subject) if not available.
            val effectiveUserId = enrichedClaims?.get(walletSubjectClaimName)?.jsonPrimitive?.contentOrNull
                ?: enrichedClaims?.get("eduid")?.jsonPrimitive?.contentOrNull
                ?: userId
            userInfoCache[effectiveUserId] = buildUserInfoFromClaims(effectiveUserId, enrichedClaims)

            return Ok(
                AuthenticatedUser(
                    userId = effectiveUserId,
                    authenticatedAt = authenticatedAt,
                    authenticationMethod = AuthenticationMethod.CUSTOM,
                    acr = acr,
                    amr = amr
                )
            )
        } catch (e: Exception) {
            return Err(AuthenticationError.Generic(
                exception = e,
                description = "Failed to check wallet auth session: ${e.message}"
            ))
        }
    }

    override suspend fun initiateAuthentication(
        sessionId: String,
        returnUrl: String,
        hint: AuthenticationHint?
    ): IdkResult<String, AuthenticationError> {
        // Wallet auth is already complete before reaching the STS
        return Err(AuthenticationError.MethodUnavailable(
            method = AuthenticationMethod.CUSTOM,
            description = "Wallet authentication should be completed before calling STS /authorize"
        ))
    }

    override suspend fun authenticateWithCredentials(
        credentials: UserCredentials
    ): IdkResult<String?, AuthenticationError> {
        return Err(AuthenticationError.MethodUnavailable(
            method = AuthenticationMethod.CUSTOM,
            description = "Direct credential auth not supported for wallet"
        ))
    }

    override suspend fun logout(userId: String): IdkResult<Unit, AuthenticationError> {
        userInfoCache.remove(userId)
        return Ok(Unit)
    }

    override suspend fun getUserInfo(userId: String): IdkResult<UserInfo, AuthenticationError> {
        val cached = userInfoCache[userId]
        return if (cached != null) Ok(cached) else Ok(UserInfo(userId = userId, displayName = "OID4VP User"))
    }

    override suspend fun isAuthenticationMethodAvailable(
        method: AuthenticationMethod
    ): IdkResult<Boolean, AuthenticationError> {
        return Ok(method == AuthenticationMethod.CUSTOM)
    }

    /**
     * Call the auth bridge's internal enrichment endpoint to merge canonical claims
     * and auxiliary data into the base claims from /complete.
     */
    private fun enrichClaims(userId: String, baseClaims: JsonObject): JsonObject {
        return try {
            val url = URI("$authBridgeBaseUrl/internal/enrichment/claims")
            val conn = connectionFactory(url)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            val requestBody = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                "tenantId" to JsonPrimitive("default"),
                "internalIdentityId" to JsonPrimitive(userId),
                "baseClaims" to baseClaims,
            )))
            conn.outputStream.bufferedWriter().use { it.write(requestBody) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val responseBody = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val responseObj = json.decodeFromString<JsonObject>(responseBody)
                responseObj["claims"]?.jsonObject ?: baseClaims
            } else {
                println("[STS-Wallet] Enrichment call failed ($responseCode), using base claims")
                conn.disconnect()
                baseClaims
            }
        } catch (e: Exception) {
            println("[STS-Wallet] Enrichment call error: ${e.message}, using base claims")
            baseClaims
        }
    }

    /**
     * Build a full [UserInfo] from the claims returned by the auth bridge.
     */
    private fun buildUserInfoFromClaims(userId: String, claims: JsonObject?): UserInfo {
        if (claims == null) return UserInfo(userId = userId, displayName = "OID4VP User")

        return UserInfo(
            userId = userId,
            username = claims["preferred_username"]?.jsonPrimitive?.content
                ?: claims["email"]?.jsonPrimitive?.content,
            displayName = claims["name"]?.jsonPrimitive?.content
                ?: buildDisplayName(claims),
            email = claims["email"]?.jsonPrimitive?.content,
            emailVerified = claims["email_verified"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            phoneNumber = claims["phone_number"]?.jsonPrimitive?.content,
            phoneNumberVerified = claims["phone_number_verified"]?.jsonPrimitive?.content?.toBooleanStrictOrNull(),
            attributes = claims.filterKeys { it !in STANDARD_CLAIM_KEYS }
                .mapValues { (_, v) -> jsonElementToAttributeValue(v) }
        )
    }

    private fun buildDisplayName(claims: JsonObject): String {
        val given = claims["given_name"]?.jsonPrimitive?.content
        val family = claims["family_name"]?.jsonPrimitive?.content
        return listOfNotNull(given, family).takeIf { it.isNotEmpty() }?.joinToString(" ")
            ?: "OID4VP User"
    }

    private fun jsonElementToAttributeValue(element: JsonElement): Any = when (element) {
        is JsonPrimitive -> {
            val content = element.contentOrNull
            content?.toBooleanStrictOrNull()
                ?: content?.toLongOrNull()
                ?: content?.toDoubleOrNull()
                ?: content.orEmpty()
        }
        is JsonArray -> element.map { jsonElementToAttributeValue(it) }
        is JsonObject -> element.mapValues { (_, value) -> jsonElementToAttributeValue(value) }
    }

    private companion object {
        // Claims handled by dedicated UserInfo fields — these are NOT filtered out
        // of attributes so they also appear as custom claims in the STS token.
        // Only "sub" is excluded since it's the user ID, not a display claim.
        val STANDARD_CLAIM_KEYS = setOf("sub")
    }
}
