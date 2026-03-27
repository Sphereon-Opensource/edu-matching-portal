package com.sphereon.portal.bridge.external

import com.sphereon.core.api.conf.AppConfigService

/**
 * Reads external API projection configuration from AppConfigService.
 *
 * Config format (YAML):
 * ```yaml
 * external-api:
 *   jwt:
 *     issuer: http://keycloak:8080/realms/portal
 *   clients:
 *     names: krs,svs
 *     krs:
 *       client-id: krs-system
 *       scopes: reconciliation:read
 *       projection:
 *         claims: given_name,family_name,email
 *         auxiliary:
 *           names: enrollment,grades
 *           enrollment: enrollment_status,programme_code,cohort
 *           grades: gpa,credits
 *       can-write: true
 * ```
 */
class ExternalApiProjectionConfigBinder(
    private val appConfigService: AppConfigService,
) : ExternalApiProjectionConfigProvider {

    companion object {
        private const val PREFIX = "external-api"
    }

    override fun getConfig(): ExternalApiProjectionConfig {
        val jwtIssuer = appConfigService.getPropertyAsString("$PREFIX.jwt.issuer")
        val clientNames = parseCommaSeparated(appConfigService.getPropertyAsString("$PREFIX.clients.names"))

        val clients = clientNames.associate { name ->
            val clientPrefix = "$PREFIX.clients.$name"
            val clientId = appConfigService.getPropertyAsString("$clientPrefix.client-id") ?: name

            val scopes = parseCommaSeparated(appConfigService.getPropertyAsString("$clientPrefix.scopes"))
                .ifEmpty { setOf("reconciliation:read") }

            val allowedClaims = appConfigService.getPropertyAsString("$clientPrefix.projection.claims")
                ?.let { parseCommaSeparated(it) }

            val auxiliaryNames = parseCommaSeparated(
                appConfigService.getPropertyAsString("$clientPrefix.projection.auxiliary.names")
            )
            val allowedAuxiliary = if (auxiliaryNames.isNotEmpty()) {
                auxiliaryNames.associateWith { catName ->
                    val fields = appConfigService.getPropertyAsString("$clientPrefix.projection.auxiliary.$catName")
                    fields?.let { parseCommaSeparated(it) }
                }
            } else null

            val canWrite = appConfigService.getPropertyAsString("$clientPrefix.can-write")
                ?.toBooleanStrictOrNull() ?: false

            clientId to ClientProjection(
                clientId = clientId,
                requiredScopes = scopes,
                allowedClaims = allowedClaims,
                allowedAuxiliary = allowedAuxiliary,
                canWrite = canWrite,
            )
        }

        return ExternalApiProjectionConfig(
            clients = clients,
            jwtIssuer = jwtIssuer,
        )
    }

    private fun parseCommaSeparated(value: String?): Set<String> {
        return value?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }
}
