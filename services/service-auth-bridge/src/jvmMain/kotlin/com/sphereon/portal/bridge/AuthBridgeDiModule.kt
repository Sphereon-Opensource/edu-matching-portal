package com.sphereon.portal.bridge

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataService
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataServiceImpl
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataStore
import com.sphereon.portal.bridge.external.ExternalApiAuthService
import com.sphereon.portal.bridge.external.InstitutionLookupConfig
import com.sphereon.portal.bridge.external.ExternalApiProjectionConfigBinder
import com.sphereon.portal.bridge.external.ExternalApiProjectionConfigProvider
import com.sphereon.portal.bridge.persistence.InstantColumnAdapter
import com.sphereon.credential.claims.mapper.api.store.ClaimMappingConfigurationStore
import com.sphereon.credential.claims.mapper.api.store.QueryConfigurationStore
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.crypto.core.kms.command.GenerateMacCommand
import com.sphereon.di.session.SessionScope
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.impl.crypto.KmsBackedReconciliationCryptoService
import com.sphereon.identity.matching.impl.crypto.ReconciliationCryptoModule
import com.sphereon.identity.reconciliation.api.AttributeMappingDeriver
import com.sphereon.identity.reconciliation.api.NormalizationService
import com.sphereon.identity.reconciliation.api.ReconciliationMaterialService
import com.sphereon.identity.reconciliation.impl.crypto.KmsBackedReconciliationMaterialService
import com.sphereon.identity.reconciliation.api.OidcConnectionResolver
import com.sphereon.identity.reconciliation.api.ResolvedOidcConnection
import com.sphereon.identity.reconciliation.api.CanonicalAttributeRulesConfigBinder
import com.sphereon.identity.reconciliation.model.CanonicalAttributeRule
import com.sphereon.identity.reconciliation.model.ReconciliationMaterialProfile
import com.sphereon.identity.reconciliation.model.ReconciliationSelectorRule
import com.sphereon.identity.reconciliation.store.OidcClientConfigStore
import com.sphereon.oauth2.server.authorization.provider.*
import com.sphereon.openid.oid4vp.auth.bridge.Oid4vpAuthBridge
import com.sphereon.openid.oid4vp.auth.config.Oid4vpAuthBridgeConfigProvider
import com.sphereon.openid.oid4vp.auth.impl.config.Oid4vpAuthBridgeConfigBinder
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.time.Clock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

private const val CRYPTO_PREFIX = "identity.reconciliation.crypto"

/**
 * Service-level DI module providing config bindings for the Auth Bridge.
 *
 * These bindings satisfy DI requirements from transitive IDK modules:
 * - Identity reconciliation (OidcConnectionResolver)
 * - Claims mapper (ClaimMappingConfigurationStore)
 * - OAuth2 server (UserAuthenticationProvider)
 * - Clock
 *
 * Note: RequestUriSigningConfig is provided by [AuthBridgeRequestUriSigningConfigBinder]
 * via @ContributesBinding.
 */

@ContributesTo(AppScope::class)
interface AuthBridgeAppModule {

    /**
     * Provides the PostgreSQL-backed AuthBridgeDatabase singleton.
     *
     * Creates the schema on first connect. Connection pool managed by HikariCP.
     * Config properties:
     * - `database.url` (default: jdbc:postgresql://localhost:5432/authbridge)
     * - `database.username` (default: postgres)
     * - `database.password` (default: postgres)
     * - `database.max-pool-size` (default: 5)
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideAuthBridgeDatabase(
        appConfigService: AppConfigService,
    ): AuthBridgeDatabase {
        val url = appConfigService.getPropertyAsString("database.url")
            ?: System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/authbridge"
        val user = appConfigService.getPropertyAsString("database.username")
            ?: System.getenv("DATABASE_USERNAME")
            ?: "postgres"
        val pass = appConfigService.getPropertyAsString("database.password")
            ?: System.getenv("DATABASE_PASSWORD")
            ?: "postgres"
        val maxPoolSize = appConfigService.getPropertyAsString("database.max-pool-size")
            ?.toIntOrNull() ?: 5

        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = user
            password = pass
            maximumPoolSize = maxPoolSize
            isAutoCommit = true
        })

        val driver = ds.asJdbcDriver()
        AuthBridgeDatabase.Schema.create(driver)
        applyMigrations(driver)
        return AuthBridgeDatabase(
            driver = driver,
            identity_matchAdapter = com.sphereon.portal.bridge.db.Identity_match.Adapter(
                created_atAdapter = InstantColumnAdapter,
                updated_atAdapter = InstantColumnAdapter,
                last_used_atAdapter = InstantColumnAdapter,
                deleted_atAdapter = InstantColumnAdapter,
            ),
            identity_link_bindingAdapter = com.sphereon.portal.bridge.db.Identity_link_binding.Adapter(
                created_atAdapter = InstantColumnAdapter,
                updated_atAdapter = InstantColumnAdapter,
                last_used_atAdapter = InstantColumnAdapter,
                deleted_atAdapter = InstantColumnAdapter,
                reconcile_timeAdapter = InstantColumnAdapter,
            ),
            reconciliation_sessionAdapter = com.sphereon.portal.bridge.db.Reconciliation_session.Adapter(
                created_atAdapter = InstantColumnAdapter,
                expires_atAdapter = InstantColumnAdapter,
            ),
            auxiliary_dataAdapter = com.sphereon.portal.bridge.db.Auxiliary_data.Adapter(
                created_atAdapter = InstantColumnAdapter,
                updated_atAdapter = InstantColumnAdapter,
                expires_atAdapter = InstantColumnAdapter,
            ),
            key_migration_historyAdapter = com.sphereon.portal.bridge.db.Key_migration_history.Adapter(
                started_atAdapter = InstantColumnAdapter,
                completed_atAdapter = InstantColumnAdapter,
            ),
            audit_eventAdapter = com.sphereon.portal.bridge.db.Audit_event.Adapter(
                created_atAdapter = InstantColumnAdapter,
            ),
        )
    }

    /**
     * Apply incremental schema migrations for columns added after the initial CREATE TABLE.
     * Uses ADD COLUMN IF NOT EXISTS (Postgres 9.6+) to be idempotent.
     */
    private fun applyMigrations(driver: app.cash.sqldelight.driver.jdbc.JdbcDriver) {
        val migrations = listOf(
            // v1 → v2: soft-delete support
            "ALTER TABLE identity_link_binding ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE",
            "ALTER TABLE identity_link_binding ADD COLUMN IF NOT EXISTS deletion_reason TEXT",
            "ALTER TABLE identity_match ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE",
            "ALTER TABLE identity_match ADD COLUMN IF NOT EXISTS deletion_reason TEXT",
            // v2 → v3: reconciliation timestamp
            "ALTER TABLE identity_link_binding ADD COLUMN IF NOT EXISTS reconcile_time TIMESTAMP WITH TIME ZONE",
            // v3 → v4: replace unique constraint with partial index (exclude soft-deleted)
            "ALTER TABLE identity_match DROP CONSTRAINT IF EXISTS uq_match_hash",
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_match_hash_active ON identity_match (tenant_id, identifier_hash, identifier_type) WHERE deleted_at IS NULL",
        )
        for (sql in migrations) {
            try {
                driver.execute(null, sql, 0)
            } catch (e: Exception) {
                println("[AuthBridge] Migration warning: ${e.message}")
            }
        }
        println("[AuthBridge] Schema migrations applied")
    }

    /**
     * Resolves OIDC client config references into usable connection details.
     * Looks up [OidcClientConfig] from the store, then resolves the
     * [ConfigReference] and [SecretReference] fields by reading
     * environment variables named by their key/path.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideOidcConnectionResolver(
        oidcClientConfigStore: OidcClientConfigStore
    ): OidcConnectionResolver = object : OidcConnectionResolver {
        override suspend fun resolve(oidcClientId: String): ResolvedOidcConnection? {
            val config = oidcClientConfigStore.findById(oidcClientId) ?: return null
            val clientId = System.getenv(config.clientIdRef.key) ?: return null
            val clientSecret = config.clientSecretRef.path.let { System.getenv(it) }
            return ResolvedOidcConnection(
                discoveryUrl = config.discoveryUrl,
                clientId = clientId,
                clientSecret = clientSecret,
                scopes = config.scopes,
                userInfoEnabled = config.userInfoEnabled,
                authorizationEndpointOverride = config.authorizationEndpointOverride,
                tokenEndpointOverride = config.tokenEndpointOverride,
            )
        }
    }

    /**
     * Canonical attribute rules loaded from config via [CanonicalAttributeRulesConfigBinder].
     * Reads from indexed `identity.reconciliation.attribute-rules[*]` config entries.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideCanonicalAttributeRules(
        appConfigService: AppConfigService
    ): List<CanonicalAttributeRule> {
        // Collect known provider IDs from reconciliation provider config for source-alias lookup
        val providerNames = appConfigService.getPropertyAsString("identity.reconciliation.providers.names", null)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
        return CanonicalAttributeRulesConfigBinder(appConfigService, providerNames).bind()
    }

    /**
     * Wallet attribute mappings derived from canonical attribute rules.
     * Replaces manual `wallet-attribute-mappings[*]` config with auto-derivation.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideWalletAttributeMappings(
        canonicalRules: List<CanonicalAttributeRule>
    ): WalletAttributeMappings {
        val derived = AttributeMappingDeriver.deriveForProvider(
            canonicalRules, providerId = "wallet", allowedModes = AttributeMappingDeriver.WALLET_MODES
        )
        return WalletAttributeMappings(derived.associate { it.source to it.target })
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideMaterialProfiles(
        appConfigService: AppConfigService
    ): Map<String, ReconciliationMaterialProfile> =
        AuthBridgeMaterialProfileConfigBinder.bind(appConfigService)

    @Provides
    @SingleIn(AppScope::class)
    fun provideReconciliationSelectorRules(
        appConfigService: AppConfigService
    ): List<ReconciliationSelectorRule> =
        AuthBridgeReconciliationSelectorConfigBinder.bindSelectorRules(appConfigService)

    @Provides
    @SingleIn(AppScope::class)
    fun provideReconciliationSelectorRuleVersion(
        appConfigService: AppConfigService
    ): String =
        AuthBridgeReconciliationSelectorConfigBinder.bindRuleVersion(appConfigService)

    @Provides
    @SingleIn(AppScope::class)
    fun provideExternalApiProjectionConfigProvider(
        appConfigService: AppConfigService,
    ): ExternalApiProjectionConfigProvider = ExternalApiProjectionConfigBinder(appConfigService)

    /**
     * Build the [com.sphereon.oauth2.jwt.validation.JwtValidationConfig] consumed by IDK's
     * `DefaultIdpRegistry` (AppScope) from `external-api.jwt.*` configuration. The default
     * IdP is the JWT issuer used to authenticate inbound external-API traffic.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideJwtValidationConfig(
        appConfigService: AppConfigService,
    ): com.sphereon.oauth2.jwt.validation.JwtValidationConfig {
        val issuer = appConfigService.getPropertyAsString("external-api.jwt.issuer")
        val jwksUri = appConfigService.getPropertyAsString("external-api.jwt.jwks-uri")
        val idp = if (issuer != null) {
            com.sphereon.oauth2.jwt.validation.IdpConfig(
                id = "external-api",
                issuer = issuer,
                jwksUri = jwksUri,
            )
        } else null
        return com.sphereon.oauth2.jwt.validation.JwtValidationConfig(
            defaultIdp = idp,
        )
    }
}

@ContributesTo(SessionScope::class, replaces = [Oid4vpAuthBridgeConfigBinder::class, ReconciliationCryptoModule::class])
interface AuthBridgeSessionModule {

    /**
     * Clock for session-scoped components.
     */
    @Provides
    @SingleIn(SessionScope::class)
    fun provideClock(): Clock = Clock.System

    /**
     * Bind ClaimMappingConfigurationStore to the QueryConfigurationStore impl.
     */
    @Provides
    fun provideClaimMappingConfigurationStore(
        store: QueryConfigurationStore
    ): ClaimMappingConfigurationStore = store

    /**
     * The auth bridge IS the UserAuthenticationProvider (OID4VP-based).
     * Bind it from the Oid4vpAuthBridge which is contributed by
     * Oid4vpUserAuthenticationProviderImpl.
     */
    @Provides
    fun provideUserAuthenticationProvider(
        bridge: Oid4vpAuthBridge
    ): UserAuthenticationProvider = bridge as UserAuthenticationProvider

    /**
     * Provide KMS-backed ReconciliationCryptoService for encrypted identity link bindings.
     * Uses the HMAC and encryption keys registered by [AuthBridgeKeyInitializer].
     *
     * Configuration properties (under `identity.reconciliation.crypto.*`):
     * - `holder-hmac-key-alias` / `institution-hmac-key-alias` / `encryption-key-alias` — current key aliases
     * - `holder-key-version` / `institution-key-version` / `encryption-key-version` — current key versions
     * - `previous-holder-hmac-key-alias` / `previous-institution-hmac-key-alias` / `previous-encryption-key-alias` — rotation
     * - `previous-holder-key-version` / `previous-institution-key-version` / `previous-encryption-key-version` — rotation
     * - `hmac-key-provider-id` / `encryption-key-provider-id` — KMS provider IDs
     */
    @Provides
    @SingleIn(SessionScope::class)
    fun provideReconciliationCryptoService(
        generateMacCommand: GenerateMacCommand,
        keyManagerService: KeyManagerService,
        appConfigService: AppConfigService,
    ): ReconciliationCryptoService {
        fun cfg(key: String, default: String? = null): String? =
            appConfigService.getPropertyAsString("$CRYPTO_PREFIX.$key", default) ?: default

        return KmsBackedReconciliationCryptoService(
            generateMacCommand = generateMacCommand,
            keyManagerService = keyManagerService,
            holderKeyAlias = cfg("holder-hmac-key-alias", KmsBackedReconciliationCryptoService.DEFAULT_HOLDER_KEY_ALIAS)!!,
            institutionKeyAlias = cfg("institution-hmac-key-alias", KmsBackedReconciliationCryptoService.DEFAULT_INSTITUTION_KEY_ALIAS)!!,
            encryptionKeyAlias = cfg("encryption-key-alias", KmsBackedReconciliationCryptoService.DEFAULT_ENCRYPTION_KEY_ALIAS)!!,
            holderKeyVersion = cfg("holder-key-version", "v1")!!,
            institutionKeyVersion = cfg("institution-key-version", "v1")!!,
            encryptionKeyVersion = cfg("encryption-key-version", "v1")!!,
            previousHolderKeyAlias = cfg("previous-holder-hmac-key-alias"),
            previousInstitutionKeyAlias = cfg("previous-institution-hmac-key-alias"),
            previousEncryptionKeyAlias = cfg("previous-encryption-key-alias"),
            previousHolderKeyVersion = cfg("previous-holder-key-version"),
            previousInstitutionKeyVersion = cfg("previous-institution-key-version"),
            previousEncryptionKeyVersion = cfg("previous-encryption-key-version"),
            providerId = cfg("hmac-key-provider-id", "software")!!,
        )
    }

    @Provides
    @SingleIn(SessionScope::class)
    fun provideAuxiliaryDataService(
        store: AuxiliaryDataStore,
        cryptoService: ReconciliationCryptoService,
    ): AuxiliaryDataService = AuxiliaryDataServiceImpl(
        store = store,
        cryptoServiceProvider = { cryptoService },
    )

    @Provides
    @SingleIn(SessionScope::class)
    fun provideExternalApiAuthService(
        jwtValidationService: com.sphereon.oauth2.jwt.validation.JwtValidationService,
        projectionConfigProvider: ExternalApiProjectionConfigProvider,
        cryptoService: ReconciliationCryptoService,
        auxiliaryDataService: AuxiliaryDataService,
    ): ExternalApiAuthService = ExternalApiAuthService(
        jwtValidationService = jwtValidationService,
        projectionConfigProvider = projectionConfigProvider,
        cryptoServiceProvider = { cryptoService },
        auxiliaryDataService = auxiliaryDataService,
    )

    @Provides
    @SingleIn(SessionScope::class)
    fun provideReconciliationMaterialService(
        generateMacCommand: GenerateMacCommand,
        normalizationService: NormalizationService,
        appConfigService: AppConfigService,
    ): ReconciliationMaterialService {
        fun cfg(key: String, default: String): String =
            appConfigService.getPropertyAsString("$CRYPTO_PREFIX.$key", default) ?: default

        return KmsBackedReconciliationMaterialService(
            generateMacCommand = generateMacCommand,
            normalizationService = normalizationService,
            keyVersionsByDomain = mapOf(
                "holder" to cfg("holder-key-version", "v1"),
                "institution" to cfg("institution-key-version", "v1"),
                "material" to cfg("material-key-version", "v1"),
            ),
            providerId = cfg("hmac-key-provider-id", "software"),
        )
    }

    /**
     * Override config provider to inject runtime-computed did:jwk as client_id.
     * The did:jwk is computed from the JAR signing key at startup by [AuthBridgeKeyInitializer].
     * Replaces the IDK-default [Oid4vpAuthBridgeConfigBinder] binding.
     */
    @Provides
    @SingleIn(SessionScope::class)
    fun provideOid4vpAuthBridgeConfigProvider(
        binder: Oid4vpAuthBridgeConfigBinder
    ): Oid4vpAuthBridgeConfigProvider = object : Oid4vpAuthBridgeConfigProvider {
        override fun getConfig() = binder.getConfig().let { base ->
            val didJwk = AuthBridgeKeyInitializer.didJwk
            if (didJwk != null) base.copy(clientId = didJwk) else base
        }
    }

    /**
     * Provides institution lookup config for reverse lookup (GDPR Art. 15).
     * Injected into ReconciliationOrchestrator to create institution identifier matches.
     */
    @Provides
    @SingleIn(SessionScope::class)
    fun provideInstitutionLookupConfig(
        appConfigService: AppConfigService
    ): InstitutionLookupConfig = InstitutionLookupConfig(
        attributeName = appConfigService.getPropertyAsString("identity.reconciliation.institution-lookup-attribute", null),
        identifierType = appConfigService.getPropertyAsString("identity.reconciliation.institution-lookup-identifier-type", "SUBJECT_ID") ?: "SUBJECT_ID",
    )
}
