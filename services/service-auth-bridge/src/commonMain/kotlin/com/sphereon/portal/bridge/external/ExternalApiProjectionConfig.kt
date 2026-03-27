package com.sphereon.portal.bridge.external

/**
 * Per-client projection configuration for the external REST API.
 *
 * Controls which canonical claims and which auxiliary categories/fields
 * a given client is allowed to see. Matches the plan's config model:
 * ```yaml
 * external-api:
 *   clients:
 *     krs-module:
 *       scopes: ["reconciliation:read"]
 *       projection:
 *         claims: ["given_name", "family_name", "email"]
 *         auxiliary:
 *           enrollment: ["enrollment_status", "programme_code", "cohort"]
 * ```
 */
data class ClientProjection(
    val clientId: String,
    val requiredScopes: Set<String> = setOf("reconciliation:read"),
    /** Which canonical claim fields to include. null = all, empty = none. */
    val allowedClaims: Set<String>? = null,
    /** Per-category auxiliary field projection. null = all categories/fields. */
    val allowedAuxiliary: Map<String, Set<String>?>? = null,
    val canWrite: Boolean = false,
)

/**
 * Top-level external API projection configuration.
 * Maps client IDs (from JWT `client_id`/`azp`) to their projection rules.
 */
data class ExternalApiProjectionConfig(
    val clients: Map<String, ClientProjection>,
    val jwtIssuer: String? = null,
)

/**
 * Provider interface for [ExternalApiProjectionConfig].
 * Implemented by [com.sphereon.portal.bridge.external.ExternalApiProjectionConfigBinder] in jvmMain.
 */
interface ExternalApiProjectionConfigProvider {
    fun getConfig(): ExternalApiProjectionConfig
}
