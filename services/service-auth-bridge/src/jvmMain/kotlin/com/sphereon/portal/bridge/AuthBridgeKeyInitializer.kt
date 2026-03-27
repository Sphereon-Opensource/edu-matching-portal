package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.crypto.core.KeyInfo
import com.sphereon.crypto.core.KeyVisibility
import com.sphereon.crypto.core.ResolvedKeyInfo
import com.sphereon.crypto.core.generic.KeyOperations
import com.sphereon.crypto.core.generic.KeyTypeMapping
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.Jwk
import com.sphereon.crypto.core.jose.JwaAlgorithm
import com.sphereon.crypto.core.jose.JwaKeyType
import com.sphereon.crypto.core.jose.JwkSet
import com.sphereon.crypto.core.jose.JwkUse
import com.sphereon.crypto.core.ManagedKeyInfo
import com.sphereon.crypto.core.generic.ManagedKeyPair
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.did.methods.jwk.JwkDidProviderImpl
import com.sphereon.oauth2.common.model.ClientRegistration
import com.sphereon.oauth2.common.model.ClientType
import com.sphereon.oauth2.common.model.GrantType
import com.sphereon.oauth2.common.model.ResponseType
import com.sphereon.openid.oid4vp.common.ClientMetadata
import com.sphereon.openid.oid4vp.verifier.impl.Oid4VpVerifierServiceImpl
import com.sphereon.openid.oid4vp.verifier.model.ClientMetadataConfiguration
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.datetime.Clock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Initializes KMS keys and client metadata at application startup.
 *
 * This ensures three keys are registered in the KMS:
 * 1. An ECDSA (ES256) signing key for signing request objects (JAR)
 * 2. An HMAC-SHA256 key for reconciliation identifier hashing
 * 3. An AES-256-GCM key for reconciliation reversible encryption
 *
 * It also registers a [ClientMetadataConfiguration] with the public JWKS so wallets
 * can discover the verification key via `client_metadata` in the openid4vp:// URL.
 *
 * Configuration properties:
 * ```properties
 * oid4vp.request-uri.signing-key-alias=auth-bridge-jar-signing-key
 * oid4vp.request-uri.signing-key-provider-id=software
 * oid4vp.auth-bridge.client-id=portal-auth-bridge
 * identity.reconciliation.crypto.hmac-key-alias=reconciliation-hmac-key
 * identity.reconciliation.crypto.hmac-key-provider-id=software
 * identity.reconciliation.crypto.encryption-key-alias=reconciliation-encryption-key
 * identity.reconciliation.crypto.encryption-key-provider-id=software
 * ```
 */
object AuthBridgeKeyInitializer {
    private const val CLIENT_METADATA_ID = "portal-auth-bridge"

    private const val RECONCILIATION_CONFIG_PREFIX = "identity.reconciliation.crypto"
    private const val DEFAULT_HMAC_HOLDER_KEY_ALIAS = "reconciliation:holder"
    private const val DEFAULT_HMAC_INSTITUTION_KEY_ALIAS = "reconciliation:institution"
    private const val DEFAULT_HMAC_KEY_PROVIDER_ID = "software"
    private const val DEFAULT_ENCRYPTION_KEY_ALIAS = "reconciliation:encryption"
    private const val DEFAULT_ENCRYPTION_KEY_PROVIDER_ID = "software"

    /**
     * The did:jwk computed from the JAR signing key's public JWK.
     * Available after [initialize] completes. Used as client_id for OID4VP
     * with decentralized_identifier scheme.
     */
    @Volatile
    var didJwk: String? = null
        private set

    suspend fun initialize(graph: AuthBridgeAppGraph) {
        val configService = (graph as? AppConfigService.Graph)?.appConfigService

        val keyAlias = configService?.getPropertyAsString(
            "${AuthBridgeRequestUriSigningConfigBinder.CONFIG_PREFIX}.signing-key-alias",
            AuthBridgeRequestUriSigningConfigBinder.DEFAULT_KEY_ALIAS
        ) ?: AuthBridgeRequestUriSigningConfigBinder.DEFAULT_KEY_ALIAS

        val providerId = configService?.getPropertyAsString(
            "${AuthBridgeRequestUriSigningConfigBinder.CONFIG_PREFIX}.signing-key-provider-id",
            AuthBridgeRequestUriSigningConfigBinder.DEFAULT_PROVIDER_ID
        ) ?: AuthBridgeRequestUriSigningConfigBinder.DEFAULT_PROVIDER_ID

        val clientId = configService?.getPropertyAsString("oid4vp.auth-bridge.client-id", CLIENT_METADATA_ID)
            ?: CLIENT_METADATA_ID

        // Create a setup session to access KMS and stores
        val userContext = graph.userContextManager.getAnonymous()
        val session = userContext.sessionContextManager.createOrGetFromId("key-init")
        val kms = session.graph.asKeyManagerServiceGraph().keyManagerService

        // 1. JAR signing key (ES256)
        val existing = kms.getKeyResult(KeyInfo<Nothing>(alias = keyAlias, providerId = providerId))
        if (existing.getOrNull() != null) {
            println("JAR signing key already exists: alias=$keyAlias, provider=$providerId")
            val resolvedKey = existing.getOrNull()!!
            val keyObj = resolvedKey.key

            // Extract public JWK — key may be ManagedKeyPair (freshly generated),
            // ManagedKeyInfo (loaded from PKCS12 keystore), or raw Jwk
            val publicJwk: Jwk? = when (keyObj) {
                is ManagedKeyPair -> keyObj.jose.publicJwk
                is ManagedKeyInfo<*> -> {
                    // ManagedKeyInfo wraps a ResolvedKeyInfo — drill into the inner key
                    when (val innerKey = keyObj.key) {
                        is Jwk -> innerKey.toPublicKey() as? Jwk
                        else -> null
                    }
                }
                is Jwk -> keyObj.toPublicKey() as? Jwk
                else -> null
            }

            if (publicJwk != null) {
                val originalKid = publicJwk.kid
                publicJwk.kid = null
                didJwk = JwkDidProviderImpl.didFromJwk(publicJwk)
                publicJwk.kid = originalKid
                println("Restored JAR signing key did:jwk from PKCS12: did=$didJwk")

                // Register client metadata if we have a ManagedKeyPair
                val keyPair = keyObj as? ManagedKeyPair
                if (keyPair != null) {
                    registerClientMetadata(session.graph, clientId, keyPair)
                }
            } else {
                println("WARNING: Could not extract public JWK from existing key (type=${keyObj?.let { it::class.simpleName }})")
            }
        } else {
            val keyPair = kms.generateKeyAsync(
                providerId = providerId,
                alias = keyAlias,
                use = JwkUse.sig,
                keyOperations = arrayOf(KeyOperations.SIGN, KeyOperations.VERIFY),
                alg = SignatureAlgorithm.ECDSA_SHA256,
                keyVisibility = KeyVisibility.PRIVATE
            )
            // Compute did:jwk from public key for use as client_id.
            // Per did:jwk spec, the JWK should not contain a kid when computing the DID.
            val publicJwk = keyPair.jose.publicJwk
            val originalKid = publicJwk.kid
            publicJwk.kid = null
            didJwk = JwkDidProviderImpl.didFromJwk(publicJwk)
            publicJwk.kid = originalKid // Restore
            println("Initialized JAR signing key: alias=$keyAlias, alg=ES256, provider=$providerId, did=$didJwk")

            // Register client metadata with public JWKS
            registerClientMetadata(session.graph, clientId, keyPair)
        }

        // 2 & 3. Register reconciliation HMAC and encryption keys (always, independent of JAR key state)
        registerReconciliationKeys(kms, configService)
    }

    /**
     * Registers the reconciliation KMS keys used for identity matching crypto:
     * - HMAC-SHA256 key for holder identifier hashing (Key A: reconciliation:holder)
     * - HMAC-SHA256 key for institution identifier hashing (Key B: reconciliation:institution)
     * - AES-256-GCM key for reversible encryption of binding payloads (Key C: reconciliation:encryption)
     *
     * Key aliases match [KmsBackedReconciliationCryptoService] defaults.
     */
    private suspend fun registerReconciliationKeys(kms: KeyManagerService, configService: AppConfigService?) {
        val holderHmacAlias = configService?.getPropertyAsString(
            "$RECONCILIATION_CONFIG_PREFIX.holder-hmac-key-alias", DEFAULT_HMAC_HOLDER_KEY_ALIAS
        ) ?: DEFAULT_HMAC_HOLDER_KEY_ALIAS

        val institutionHmacAlias = configService?.getPropertyAsString(
            "$RECONCILIATION_CONFIG_PREFIX.institution-hmac-key-alias", DEFAULT_HMAC_INSTITUTION_KEY_ALIAS
        ) ?: DEFAULT_HMAC_INSTITUTION_KEY_ALIAS

        val hmacProviderId = configService?.getPropertyAsString(
            "$RECONCILIATION_CONFIG_PREFIX.hmac-key-provider-id", DEFAULT_HMAC_KEY_PROVIDER_ID
        ) ?: DEFAULT_HMAC_KEY_PROVIDER_ID

        val encAlias = configService?.getPropertyAsString(
            "$RECONCILIATION_CONFIG_PREFIX.encryption-key-alias", DEFAULT_ENCRYPTION_KEY_ALIAS
        ) ?: DEFAULT_ENCRYPTION_KEY_ALIAS

        val encProviderId = configService?.getPropertyAsString(
            "$RECONCILIATION_CONFIG_PREFIX.encryption-key-provider-id", DEFAULT_ENCRYPTION_KEY_PROVIDER_ID
        ) ?: DEFAULT_ENCRYPTION_KEY_PROVIDER_ID

        // Key A: HMAC-SHA256 for holder identifier hashing (symmetric oct key, 32 bytes)
        registerSymmetricKeyIfAbsent(
            kms, holderHmacAlias, hmacProviderId, "holder HMAC",
            keySize = 32, use = JwkUse.sig, alg = JwaAlgorithm.HS256,
            keyOperations = arrayOf(KeyOperations.SIGN, KeyOperations.VERIFY)
        )

        // Key B: HMAC-SHA256 for institution identifier hashing (symmetric oct key, 32 bytes)
        registerSymmetricKeyIfAbsent(
            kms, institutionHmacAlias, hmacProviderId, "institution HMAC",
            keySize = 32, use = JwkUse.sig, alg = JwaAlgorithm.HS256,
            keyOperations = arrayOf(KeyOperations.SIGN, KeyOperations.VERIFY)
        )

        // Key C: AES-256-GCM encryption key (symmetric oct key, 32 bytes for AES-256)
        registerSymmetricKeyIfAbsent(
            kms, encAlias, encProviderId, "encryption",
            keySize = 32, use = JwkUse.enc, alg = JwaAlgorithm.A256GCMKW,
            keyOperations = arrayOf(KeyOperations.ENCRYPT, KeyOperations.DECRYPT)
        )
    }

    /**
     * Generates and stores a symmetric (oct) JWK key if one does not already exist at the given alias.
     *
     * The KMS `generateKeyAsync` API only supports asymmetric key generation (EC, RSA).
     * For symmetric keys (HMAC, AES), we generate raw random bytes, wrap them in a JWK
     * with `kty=oct`, and store them via `storeKeyResult`.
     *
     * @param kms The key manager service
     * @param alias The key alias
     * @param providerId The KMS provider ID
     * @param domain Human-readable domain label for logging
     * @param keySize The symmetric key size in bytes (e.g., 32 for 256-bit)
     * @param use The JWK key use (sig for HMAC, enc for AES)
     * @param alg The JWA algorithm identifier (e.g., HS256, A256GCMKW)
     * @param keyOperations The allowed key operations
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun registerSymmetricKeyIfAbsent(
        kms: KeyManagerService,
        alias: String,
        providerId: String,
        domain: String,
        keySize: Int,
        use: JwkUse,
        alg: JwaAlgorithm,
        keyOperations: Array<KeyOperations>,
    ) {
        val existing = kms.getKeyResult(KeyInfo<Nothing>(alias = alias, providerId = providerId))
        if (existing.getOrNull() != null) {
            println("Reconciliation $domain key already exists: alias=$alias")
            return
        }

        // Generate cryptographically random key material
        val keyBytes = CryptographyRandom.nextBytes(keySize)
        val kBase64Url = Base64.UrlSafe.encode(keyBytes).trimEnd('=')

        // Create a symmetric JWK (kty=oct) with the raw key in the 'k' field
        val jwk = Jwk(
            kty = JwaKeyType.oct,
            k = kBase64Url,
            alg = alg,
            use = use.value,
            key_ops = keyOperations.map { it.jose }.toTypedArray(),
            kid = alias,
        )

        val keyInfo = ResolvedKeyInfo(
            key = jwk,
            kid = alias,
            alias = alias,
            providerId = providerId,
            keyVisibility = KeyVisibility.PRIVATE,
            keyType = KeyTypeMapping.Symmetric,
            signatureAlgorithm = if (use == JwkUse.sig) SignatureAlgorithm.HMAC_SHA256 else null,
        )

        kms.storeKeyResult(keyInfo, providerId = providerId, alias = alias)
        println("Registered reconciliation $domain key: alias=$alias, alg=${alg.value}, provider=$providerId")
    }

    private suspend fun registerClientMetadata(
        sessionGraph: Any,
        clientId: String,
        keyPair: ManagedKeyPair
    ) {
        val verifierService = (sessionGraph as? Oid4VpVerifierServiceImpl.Graph)
            ?.oid4vpVerifierService ?: run {
            println("WARNING: Oid4vpVerifierService not available, skipping client metadata JWKS registration")
            return
        }

        val publicJwk = keyPair.jose.publicJwk
        val now = Clock.System.now().toEpochMilliseconds()

        val config = ClientMetadataConfiguration(
            clientMetadataId = CLIENT_METADATA_ID,
            clientId = clientId,
            name = "Portal Auth Bridge",
            clientMetadata = ClientMetadata(
                baseMetadata = ClientRegistration(
                    clientId = clientId,
                    clientName = "Portal Auth Bridge",
                    clientType = ClientType.CONFIDENTIAL,
                    grantTypes = listOf(GrantType.AUTHORIZATION_CODE),
                    responseTypes = listOf(ResponseType.CODE),
                    jwks = JwkSet(keys = arrayOf(publicJwk))
                )
            ),
            createdAt = now,
            updatedAt = now
        )

        verifierService.clientMetadataConfigurationStore.upsert(CLIENT_METADATA_ID, config)
        println("Registered client metadata: clientId=$clientId, kid=${publicJwk.kid}")
    }
}
