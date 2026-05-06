package com.sphereon.portal.sts

import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.conf.PropertyResolver
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.di.session.SessionScope
import com.sphereon.oauth2.server.authorization.config.FederationProviderConfig
import com.sphereon.oauth2.server.authorization.impl.config.FederationProviderConfigBinder
import com.sphereon.oauth2.server.authorization.impl.provider.CompositeUserAuthenticationProvider
import com.sphereon.oauth2.server.authorization.impl.provider.FederatedUserAuthenticationProvider
import com.sphereon.oauth2.server.authorization.impl.provider.NoOpReconciliationCallbackHandler
import com.sphereon.oauth2.server.authorization.impl.provider.DefaultEmptyFederationProviderRegistry
import com.sphereon.oauth2.server.authorization.impl.provider.IdentityFederatedClaimMapper
import com.sphereon.oauth2.server.authorization.impl.provider.ReconciliationCallbackHandler
import com.sphereon.oauth2.server.authorization.provider.FederatedClaimMapper
import com.sphereon.oauth2.server.authorization.provider.FederationProviderRegistry
import com.sphereon.oauth2.server.authorization.provider.UserAuthenticationProvider
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.binding

/**
 * STS-specific DI bindings that wire up both federated and wallet authentication.
 *
 * Contributions in this file:
 * - [StsCompositeUserAuthenticationProvider] — registers the composite under the
 *   `UserAuthenticationProvider` multibinding key `"composite"`. Selected by the IDK delegate
 *   when `oauth2.user-provider.mode=composite`. The composite routes:
 *   - `login_hint=oid4vp:{sessionId}` to [StsWalletAuthProvider] (HTTP-based, calls auth bridge)
 *   - everything else to [FederatedUserAuthenticationProvider] (OIDC RP flow)
 * - [StsFederationProviderRegistry] — replaces the IDK empty default with a config-driven
 *   registry over `{namespace}.federation.providers.*` (and `FEDERATION_*` / `KEYCLOAK_*`
 *   environment fallbacks for local development).
 * - [StsFederatedClaimMapper] — replaces the IDK pass-through mapper with the STS canonical
 *   projection: extract upstream-specific keys, validate required attributes, and project
 *   the `project=true` subset plus reconciliation metadata.
 * - [StsReconciliationCallbackHandler] — replaces the IDK no-op handler so reconciliation
 *   completions can be forwarded to the auth-bridge.
 *
 * Federation attributes are:
 * 1. Extracted from upstream IdP using provider-specific extraction mappings
 * 2. Mapped to canonical names
 * 3. Projected using shared canonical attribute rules (project=true attributes only)
 */

/**
 * Composite [UserAuthenticationProvider] keyed `"composite"` in the IDK provider multibinding.
 * Delegates to a [CompositeUserAuthenticationProvider] wrapping the IDK federated provider and
 * the STS wallet HTTP provider. Selected by the IDK delegate when
 * `oauth2.user-provider.mode=composite`.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoMap(SessionScope::class, binding = binding<UserAuthenticationProvider>())
@StringKey("composite")
class StsCompositeUserAuthenticationProvider(
    federatedProvider: FederatedUserAuthenticationProvider,
    execution: SessionExecution,
) : UserAuthenticationProvider by CompositeUserAuthenticationProvider(
    federationProvider = federatedProvider,
    walletProvider = buildWalletProvider(execution),
) {
    companion object {
        private fun buildWalletProvider(execution: SessionExecution): UserAuthenticationProvider {
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
            return StsWalletAuthProvider(
                authBridgeBaseUrl = authBridgeUrl,
                walletSubjectClaimName = walletSubjectClaimName,
            )
        }
    }
}

/**
 * Config-driven [FederationProviderRegistry] for the STS. Reads provider definitions from the
 * `{namespace}.federation.providers.*` config tree first, then falls back to `FEDERATION_*` and
 * `KEYCLOAK_*` environment variables for local development.
 *
 * Replaces [DefaultEmptyFederationProviderRegistry] so federation actually works in the STS.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(
    SessionScope::class,
    binding = binding<FederationProviderRegistry>(),
    replaces = [DefaultEmptyFederationProviderRegistry::class],
)
class StsFederationProviderRegistry(
    private val execution: SessionExecution,
) : FederationProviderRegistry {

    private val resolved: List<FederationProviderConfig> by lazy { resolveProviders() }
    private val byId: Map<String, FederationProviderConfig> by lazy { resolved.associateBy { it.id } }

    override fun findById(providerId: String): FederationProviderConfig? = byId[providerId]

    override fun all(): List<FederationProviderConfig> = resolved

    override fun defaultProviderId(): String? = resolved.firstOrNull { it.enabled }?.id

    private fun resolveProviders(): List<FederationProviderConfig> {
        val configService = execution.conf.conf(ConfigLevel.APP)
        val namespace = (configService as? com.sphereon.core.api.conf.ConfigService)?.getNamespace() ?: "unknown"
        val testProp = configService.getPropertyAsString("$namespace.federation.providers.names", null)
        println("[STS-DEBUG] ConfigService class=${configService::class.simpleName}, namespace=$namespace, federation.providers.names=$testProp")
        val configs = FederationProviderConfigBinder(configService).bind()
        if (configs.isNotEmpty()) {
            return configs
        }

        // Fallback: construct from environment variables directly (local development convenience).
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
        return providers
    }
}

/**
 * STS canonical projection [FederatedClaimMapper]. Extracts upstream claims using
 * provider-specific source mappings, validates required canonical attributes, projects only the
 * `project=true` subset, and injects reconciliation metadata.
 *
 * Replaces [IdentityFederatedClaimMapper] so claims arriving in downstream commands are already
 * normalised to the STS canonical vocabulary.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(
    SessionScope::class,
    binding = binding<FederatedClaimMapper>(),
    replaces = [IdentityFederatedClaimMapper::class],
)
class StsFederatedClaimMapper(
    private val execution: SessionExecution,
    private val providerRegistry: FederationProviderRegistry,
) : FederatedClaimMapper {

    private val configService: PropertyResolver
        get() = execution.conf.conf(ConfigLevel.APP)

    private val ruleVersion: String by lazy {
        configService.getPropertyAsString("identity.reconciliation.rule-version", null) ?: "unknown"
    }

    private val canonicalRules: List<StsCanonicalAttributeRule> by lazy {
        val knownProviderIds = providerRegistry.all().map { it.id }.toSet()
        StsCanonicalAttributeRulesConfigBinder.bind(configService, knownProviderIds)
    }

    private val extractionMappings: List<FederationExtractionMapping> by lazy {
        val defaultProviderId = providerRegistry.defaultProviderId() ?: return@lazy emptyList()
        StsCanonicalAttributeRulesConfigBinder.deriveExtractionMappings(canonicalRules, defaultProviderId)
    }

    override fun map(rawClaims: Map<String, Any>): Map<String, Any> {
        if (extractionMappings.isEmpty()) {
            return rawClaims
        }
        val projected = applyCanonicalProjection(rawClaims, extractionMappings, canonicalRules)
        return projected + mapOf(
            "reconcile_rule_version" to ruleVersion,
            "reconcile_time" to kotlin.time.Clock.System.now().toString(),
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

            // Step 2: Validate required canonical attributes — fail closed on missing
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

/**
 * STS [ReconciliationCallbackHandler] that forwards reconciliation completions to the auth-bridge
 * over HTTP. Wraps [StsReconciliationHandler] so the URLs are resolved per-session from config.
 *
 * Replaces [NoOpReconciliationCallbackHandler] so reconciliation flows actually complete in the STS.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(
    SessionScope::class,
    binding = binding<ReconciliationCallbackHandler>(),
    replaces = [NoOpReconciliationCallbackHandler::class],
)
class StsReconciliationCallbackHandler(
    private val execution: SessionExecution,
) : ReconciliationCallbackHandler {

    private val delegate: StsReconciliationHandler by lazy {
        val configService = execution.conf.conf(ConfigLevel.APP)
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
        StsReconciliationHandler(authBridgeUrl, frontendUrl)
    }

    override suspend fun onReconciliationComplete(
        claims: Map<String, Any>,
        providerId: String,
        issuer: String,
        oid4vpSessionId: String,
    ): String = delegate.onReconciliationComplete(claims, providerId, issuer, oid4vpSessionId)
}
