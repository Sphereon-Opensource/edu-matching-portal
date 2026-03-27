package com.sphereon.portal.bridge.external

import com.sphereon.identity.matching.model.AssuranceSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Response from POST /lookup and GET /{internalIdentityId}.
 * Contains only projected fields — never raw encrypted blobs.
 */
@Serializable
data class ExternalIdentityResponse(
    val found: Boolean,
    val internalIdentityId: String? = null,
    val claims: Map<String, JsonElement> = emptyMap(),
    val auxiliary: Map<String, Map<String, JsonElement>> = emptyMap(),
    val assurance: AssuranceSummary? = null,
    val lastVerifiedAt: String? = null,
)

/**
 * Request body for POST /lookup.
 * Caller provides an already-hashed identifier (HMAC) and its type.
 */
@Serializable
data class ExternalLookupRequest(
    val identifierHash: String? = null,
    val identifier: String? = null,
    val identifierType: String,
)

@Serializable
data class ExternalAuxiliaryWriteRequest(
    val fields: Map<String, JsonElement>,
)

@Serializable
data class ExternalErrorResponse(
    val error: String,
    val errorDescription: String? = null,
)
