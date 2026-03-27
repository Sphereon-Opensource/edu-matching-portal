package com.sphereon.portal.sts

import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.di.session.SessionScope
import com.sphereon.oauth2.client.client.OAuth2Client
import com.sphereon.oauth2.common.token.OidcTokenClaimExtractor
import com.sphereon.oauth2.server.authorization.config.FederationProviderConfig
import com.sphereon.oauth2.server.authorization.impl.config.FederationProviderConfigBinder
import com.sphereon.oauth2.server.authorization.impl.provider.CompositeUserAuthenticationProvider
import com.sphereon.oauth2.server.authorization.impl.provider.FederatedUserAuthenticationProvider
import com.sphereon.oauth2.server.authorization.impl.provider.NoOpUserAuthenticationProviderModule
import com.sphereon.oauth2.server.authorization.impl.provider.ReconciliationCallbackHandler
import com.sphereon.oauth2.server.authorization.provider.UserAuthenticationProvider
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * STS-specific DI module that wires up both federated and wallet authentication.
 *
 * Provides a [CompositeUserAuthenticationProvider] that routes:
 * - Federation (OIDC) requests to [FederatedUserAuthenticationProvider]
 * - Wallet (OID4VP) requests to [StsWalletAuthProvider] (HTTP-based, calls auth bridge)
 *   when login_hint starts with "oid4vp:"
 *
 * The federation provider supports multiple upstream OIDC providers (e.g., SURFconext,
 * Keycloak) configured via `{namespace}.federation.providers.*`. Both federation login
 * and reconciliation (IDV) flows are handled by the same provider — the flow type is
 * determined by the [FlowContext] stored in the pending federation state.
 *
 * Federation attributes are:
 * 1. Extracted from upstream IdP using provider-specific extraction mappings
 * 2. Mapped to canonical names
 * 3. Projected using shared canonical attribute rules (project=true attributes only)
 *
 * Replaces the default no-op user-auth provider module.
 */
@ContributesTo(SessionScope::class, replaces = [NoOpUserAuthenticationProviderModule::class])
interface StsAuthProvidersModule {

    @Provides
    @SingleIn(SessionScope::class)
    fun federationProviderConfigs(
        execution: SessionExecution
    ): Map<String, FederationProviderConfig> {
        // Try config service first (APP level), fall back to environment variables
        val configService = execution.conf.conf(ConfigLevel.APP)
        val namespace = (configService as? com.sphereon.core.api.conf.ConfigService)?.getNamespace() ?: "unknown"
        val testProp = configService.getPropertyAsString("$namespace.federation.providers.names", null)
        println("[STS-DEBUG] ConfigService class=${configService::class.simpleName}, namespace=$namespace, federation.providers.names=$testProp")
        val configs = FederationProviderConfigBinder(configService).bind()
        if (configs.isNotEmpty()) {
            return configs.associateBy { it.id }
        }

        // Fallback: construct from environment variables directly
        val providers = mutableListOf<FederationProviderConfig>()

        val surfIssuer = System.getenv("FEDERATION_ISSUER_URL")
        val surfClientId = System.getenv("FEDERATION_CLIENT_ID")
        if (surfIssuer != null && surfClientId != null) {
            providers += FederationProviderConfig(
                id = "surf",
                name = "eduID (SURFconext)",
                issuerUrl = surfIssuer,
                clientId = surfClientId,
                clientSecret = System.getenv("FEDERATION_CLIENT_SECRET"),
                scopes = listOf("openid", "profile", "email"),
                identifierClaimName = "sub",
                enabled = true,
                authorizationEndpointOverride = System.getenv("FEDERATION_AUTHORIZATION_ENDPOINT_OVERRIDE")?.ifBlank { null },
                callbackPath = "/federation/callback"
            )
        }

        val keycloakIssuer = System.getenv("KEYCLOAK_ISSUER_URL")
        val keycloakEnabled = System.getenv("KEYCLOAK_ENABLED")?.toBoolean() ?: false
        if (keycloakIssuer != null && keycloakEnabled) {
            providers += FederationProviderConfig(
                id = "keycloak",
                name = "Keycloak (local)",
                issuerUrl = keycloakIssuer,
                clientId = "portal",
                clientSecret = System.getenv("KEYCLOAK_CLIENT_SECRET"),
                scopes = listOf("openid", "profile", "email"),
                identifierClaimName = "email",
                enabled = true,
                authorizationEndpointOverride = System.getenv("KEYCLOAK_AUTHORIZATION_ENDPOINT_OVERRIDE")?.ifBlank { null },
                tokenEndpointOverride = System.getenv("KEYCLOAK_TOKEN_ENDPOINT_OVERRIDE")?.ifBlank { null },
                userinfoEndpointOverride = System.getenv("KEYCLOAK_USERINFO_ENDPOINT_OVERRIDE")?.ifBlank { null },
                callbackPath = "/federation/callback"
            )
        }

        if (providers.isEmpty()) {
            error("No federation providers configured. Set FEDERATION_ISSUER_URL/FEDERATION_CLIENT_ID or configure sphereon.app.federation.providers.* properties.")
        }
        return providers.associateBy { it.id }
    }

    @Provides
    @SingleIn(SessionScope::class)
    fun federatedUserAuthenticationProvider(
        oauth2Client: OAuth2Client,
        providerConfigs: Map<String, FederationProviderConfig>,
        tokenClaimExtractor: OidcTokenClaimExtractor,
        execution: SessionExecution
    ): FederatedUserAuthenticationProvider {
        val configService = execution.conf.conf(ConfigLevel.APP)

        // Determine default provider (first enabled)
        val defaultProviderId = providerConfigs.values.firstOrNull { it.enabled }?.id
            ?: error("No enabled federation provider found")

        // Load canonical attribute rules from config and derive per-provider extraction mappings
        val knownProviderIds = providerConfigs.keys
        val canonicalRules = StsCanonicalAttributeRulesConfigBinder.bind(configService, knownProviderIds)
        val extractionMappings = StsCanonicalAttributeRulesConfigBinder.deriveExtractionMappings(
            canonicalRules, defaultProviderId
        )

        // Read reconciliation rule version from config
        val ruleVersion = configService.getPropertyAsString(
            "identity.reconciliation.rule-version", null
        ) ?: "unknown"

        // Build attribute mapper: extract -> canonicalize -> validate -> project + STS metadata
        val claimMapper: ((Map<String, Any>) -> Map<String, Any>)? =
            if (extractionMappings.isNotEmpty()) {
                { rawClaims ->
                    val projected = applyCanonicalProjection(rawClaims, extractionMappings, canonicalRules)
                    // Inject STS reconciliation metadata into projected claims
                    projected + mapOf(
                        "reconcile_rule_version" to ruleVersion,
                        "reconcile_time" to kotlinx.datetime.Clock.System.now().toString()
                    )
                }
            } else null

        // Build reconciliation handler (STS → auth-bridge claims POST)
        val authBridgeUrl = System.getenv("AUTH_BRIDGE_BASE_URL")
            ?: configService.getPropertyAsString(
                "oid4vp.auth-bridge.base-url",
                "http://localhost:8090"
            ) ?: "http://localhost:8090"
        val frontendUrl = System.getenv("FRONTEND_URL")
            ?: configService.getPropertyAsString(
                "reconciliation.frontend-url",
                "http://localhost:3000"
            ) ?: "http://localhost:3000"

        val reconciliationHandler = StsReconciliationHandler(authBridgeUrl, frontendUrl)

        return FederatedUserAuthenticationProvider(
            oauth2Client = oauth2Client,
            providers = providerConfigs,
            defaultProviderId = defaultProviderId,
            tokenClaimExtractor = tokenClaimExtractor,
            claimMapper = claimMapper,
            reconciliationHandler = reconciliationHandler,
        )
    }

    @Provides
    @SingleIn(SessionScope::class)
    fun provideUserAuthenticationProvider(
        federatedProvider: FederatedUserAuthenticationProvider,
        execution: SessionExecution
    ): UserAuthenticationProvider {
        val configService = execution.conf.conf(ConfigLevel.APP)
        val authBridgeUrl = System.getenv("AUTH_BRIDGE_BASE_URL")
            ?: configService.getPropertyAsString(
                "oid4vp.auth-bridge.base-url",
                "http://localhost:8090"
            ) ?: "http://localhost:8090"
        println("[STS] Auth bridge URL: $authBridgeUrl")

        val walletSubjectClaimName = configService.getPropertyAsString(
            "oid4vp.auth-bridge.user-identifier-claim-path", "sub"
        ) ?: "sub"
        val walletProvider = StsWalletAuthProvider(authBridgeUrl, walletSubjectClaimName = walletSubjectClaimName)
        return CompositeUserAuthenticationProvider(
            federationProvider = federatedProvider,
            walletProvider = walletProvider
        )
    }

    companion object {
        /**
         * Apply the canonical attribute projection pipeline:
         *
         * 1. Raw upstream claims -> extraction mappings -> canonical attributes
         * 2. Validate required attributes and fail closed on missing evidence
         * 3. Project only `project=true` attributes
         */
        internal fun applyCanonicalProjection(
            rawClaims: Map<String, Any>,
            extractionMappings: List<FederationExtractionMapping>,
            canonicalRules: List<StsCanonicalAttributeRule>,
        ): Map<String, Any> {
            // Step 1: Extract upstream claims into canonical names
            val canonicalAttributes = mutableMapOf<String, Any>()
            for (mapping in extractionMappings) {
                val value = rawClaims[mapping.source]
                if (value != null) {
                    canonicalAttributes[mapping.target] = value
                }
            }

            // Step 2: Validate required canonical attributes -- fail closed on missing
            val missingRequired = canonicalRules
                .filter { it.required && !canonicalAttributes.containsKey(it.canonicalName) }
                .map { it.canonicalName }
            if (missingRequired.isNotEmpty()) {
                error("Required canonical attributes missing after federation extraction: ${missingRequired.joinToString(", ")}")
            }

            // Step 3: Project only project=true attributes
            val projectableNames = canonicalRules.filter { it.project }.map { it.canonicalName }.toSet()
            return canonicalAttributes.filterKeys { it in projectableNames }
        }
    }
}
