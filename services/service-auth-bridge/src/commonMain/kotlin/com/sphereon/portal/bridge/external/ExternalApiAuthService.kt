package com.sphereon.portal.bridge.external

import com.sphereon.core.api.auth.AuthHeaders
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.util.RequestUtils
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.model.IdentityLinkBinding
import com.sphereon.oauth2.jwt.validation.JwtValidationService
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataService
import com.sphereon.portal.bridge.auxiliary.DecryptedAuxiliaryData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Authentication result from [ExternalApiAuthService.authenticate].
 */
data class ExternalApiAuthResult(
    val clientId: String,
    val projection: ClientProjection,
    val tenantId: String,
)

/**
 * Shared authentication and projection service for the external API.
 *
 * Validates OAuth2 bearer tokens, resolves per-client projection configuration,
 * and provides helpers for projecting claims and auxiliary data.
 *
 * Injected into each endpoint command to avoid duplicating auth logic.
 */
class ExternalApiAuthService(
    private val jwtValidationService: JwtValidationService,
    private val projectionConfigProvider: ExternalApiProjectionConfigProvider,
    private val cryptoServiceProvider: () -> ReconciliationCryptoService,
    private val auxiliaryDataService: AuxiliaryDataService,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Validates the bearer token, checks per-client scopes, and resolves the projection.
     *
     * Returns [ExternalApiAuthResult] on success, or an error pair (statusCode, errorBody) on failure.
     */
    suspend fun authenticate(request: GenericHttpRequest): Result<ExternalApiAuthResult> {
        val authHeader = RequestUtils.extractHeaderValue(request.headers, AuthHeaders.AUTHORIZATION)
            ?: return Result.failure(AuthError(401, "unauthorized", "Bearer token required"))

        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
            return Result.failure(AuthError(401, "unauthorized", "Bearer token required"))
        }

        val token = authHeader.substringAfter(" ").trim()
        val config = projectionConfigProvider.getConfig()

        val validated = jwtValidationService.validateAccessToken(token).getOrElse { error ->
            return Result.failure(AuthError(401, "unauthorized", error.message ?: "Token validation failed"))
        }

        val clientId = validated.clientId ?: validated.subject
            ?: return Result.failure(AuthError(401, "unauthorized", "No client_id in token"))

        val projection = config.clients[clientId]
            ?: return Result.failure(AuthError(403, "forbidden", "Client $clientId not authorized"))

        val tokenScopes = validated.scopes
        val missingScopes = projection.requiredScopes - tokenScopes
        if (missingScopes.isNotEmpty()) {
            return Result.failure(AuthError(403, "insufficient_scope",
                "Missing required scopes: ${missingScopes.joinToString(", ")}"))
        }

        // External API clients operate in the default tenant unless a tenant_id claim is explicitly present
        val tenantId = "default"
        return Result.success(ExternalApiAuthResult(clientId, projection, tenantId))
    }

    /**
     * Decrypt canonical claims from a binding and apply per-client claim projection.
     */
    suspend fun projectClaims(
        binding: IdentityLinkBinding,
        projection: ClientProjection,
    ): Map<String, JsonElement> {
        val allClaims = try {
            val plaintext = cryptoServiceProvider().decrypt(binding.persistedAttributesEnvelope.encrypted)
            json.decodeFromString<Map<String, JsonElement>>(plaintext)
        } catch (_: Exception) {
            return emptyMap()
        }
        return if (projection.allowedClaims != null) {
            allClaims.filterKeys { it in projection.allowedClaims }
        } else allClaims
    }

    /**
     * Decrypt and project auxiliary data per the client's auxiliary projection rules.
     */
    suspend fun projectAuxiliary(
        tenantId: String,
        internalIdentityId: String,
        projection: ClientProjection,
    ): Map<String, Map<String, JsonElement>> {
        val allDecrypted = auxiliaryDataService.getDecrypted(tenantId, internalIdentityId)
        val allowedAux = projection.allowedAuxiliary

        val filtered: List<DecryptedAuxiliaryData> = if (allowedAux != null) {
            allDecrypted.filter { it.category in allowedAux }
        } else allDecrypted

        return filtered.associate { dec ->
            dec.category to projectAuxiliaryFields(dec.category, dec.data, projection)
        }
    }

    /**
     * Apply per-category field projection to auxiliary data.
     */
    fun projectAuxiliaryFields(
        category: String,
        fields: Map<String, JsonElement>,
        projection: ClientProjection,
    ): Map<String, JsonElement> {
        val allowedFields = projection.allowedAuxiliary?.get(category)
        return if (allowedFields != null) {
            fields.filterKeys { it in allowedFields }
        } else fields
    }
}

/**
 * Authentication/authorization error with HTTP status code.
 */
class AuthError(
    val statusCode: Int,
    val error: String,
    val description: String,
) : Exception("$error: $description")
