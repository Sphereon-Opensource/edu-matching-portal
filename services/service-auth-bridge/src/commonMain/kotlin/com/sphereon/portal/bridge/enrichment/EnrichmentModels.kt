package com.sphereon.portal.bridge.enrichment

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class EnrichClaimsRequest(
    val tenantId: String,
    val internalIdentityId: String,
    val baseClaims: Map<String, JsonElement>,
    val config: EnrichmentConfig? = null,
)

@Serializable
data class EnrichClaimsResponse(
    val claims: Map<String, JsonElement>,
)

/**
 * Configuration for token enrichment.
 */
@Serializable
data class EnrichmentConfig(
    /** Which canonical claim fields to include. Empty set = all canonical claims. */
    val canonicalClaims: Set<String> = emptySet(),
    /** Which auxiliary categories + fields to include. Key = category, value = field names (empty = all). */
    val auxiliaryCategories: Map<String, Set<String>> = emptyMap(),
    /** Claim namespace prefix for auxiliary data (e.g., "aux:" -> "aux:enrollment_status"). */
    val auxiliaryPrefix: String = "",
)
