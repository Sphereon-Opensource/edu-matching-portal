package com.sphereon.portal.sts

import com.sphereon.crypto.core.KeyInfo
import com.sphereon.crypto.core.KeyType
import com.sphereon.crypto.core.KeyVisibility
import com.sphereon.crypto.core.ManagedKeyInfo
import com.sphereon.crypto.core.generic.KeyOperations
import com.sphereon.crypto.core.generic.ManagedKeyPair
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.Jwk
import com.sphereon.crypto.core.jose.JwkUse
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.oauth2.server.authorization.storage.OAuth2SigningKey
import com.sphereon.oauth2.server.authorization.storage.OAuth2SigningKeyState
import kotlin.time.Clock

/**
 * Bootstraps the STS's OAuth2 signing key at application startup.
 *
 * The IDK auth-server moved away from the legacy `signing-key-alias` config field — the
 * AS now reads its signer exclusively from [com.sphereon.oauth2.server.authorization.storage.SigningKeyStore].
 * Without an ACTIVE entry there:
 *  - `/.well-known/jwks.json` returns `{"keys": []}`
 *  - the AS still issues ID tokens (with whatever fallback the sign path resolves), but
 *    relying parties cannot verify them — NextAuth surfaces this as
 *    `error=Configuration` on the callback.
 *
 * This initializer ensures one ES256 key exists in the software KMS and is registered
 * as ACTIVE in the SigningKeyStore for tenant `default`. Idempotent across restarts as
 * long as the keystore preserves the key (in-memory keystore regenerates each run; a
 * persistent keystore makes this a one-shot).
 */
object StsKeyInitializer {
    private const val DEFAULT_TENANT_ID = "default"
    private const val DEFAULT_KEY_ALIAS = "sts-id-token-signing"
    private const val DEFAULT_PROVIDER_ID = "software"
    /**
     * Wire-visible kid for the JWS header / JWKS entry. RPs cache the JWKS by `kid`,
     * so this needs to be stable as long as the underlying key material is reused. The
     * in-memory keystore regenerates each run (different x/y), but we keep the kid
     * fixed — both the JWS header and the published JWK carry the same value, so RPs
     * always pick the right entry from the JWKS as long as they refresh after the
     * service restarts.
     */
    private const val DEFAULT_KEY_KID = "sts-id-token-signing-1"

    suspend fun initialize(graph: StsAppGraph) {
        val userContext = graph.userContextManager.getAnonymous()
        val session = userContext.sessionContextManager.createOrGetFromId("sts-key-init")
        val kms = session.graph.asKeyManagerServiceGraph().keyManagerService

        // Reuse an existing key if the keystore persisted one across restart, otherwise
        // generate a fresh ES256 keypair. The KMS handles private bytes; SigningKeyStore
        // only stores the descriptor (kid + alias + alg).
        val existing = kms.getKeyResult(KeyInfo<Nothing>(alias = DEFAULT_KEY_ALIAS, providerId = DEFAULT_PROVIDER_ID))
        val publicJwk: Jwk =
            if (existing.getOrNull() != null) {
                println("STS signing key already present in KMS: alias=$DEFAULT_KEY_ALIAS, provider=$DEFAULT_PROVIDER_ID")
                extractPublicJwk(existing.getOrNull()!!.key)
                    ?: error("STS signing key '$DEFAULT_KEY_ALIAS' did not resolve to a JWK")
            } else {
                val keyPair =
                    kms.generateKeyAsync(
                        providerId = DEFAULT_PROVIDER_ID,
                        alias = DEFAULT_KEY_ALIAS,
                        use = JwkUse.sig,
                        keyOperations = arrayOf(KeyOperations.SIGN, KeyOperations.VERIFY),
                        alg = SignatureAlgorithm.ECDSA_SHA256,
                        keyVisibility = KeyVisibility.PRIVATE,
                    )
                println("Generated STS signing key: alias=$DEFAULT_KEY_ALIAS, alg=ES256, provider=$DEFAULT_PROVIDER_ID")
                keyPair.jose.publicJwk
            }

        // Pin the wire-visible kid before registering with SigningKeyStore so the JWS
        // header and JWKS entry always agree. The original kid (whatever the KMS chose)
        // is irrelevant — RPs only see what's in the header / JWKS.
        publicJwk.kid = DEFAULT_KEY_KID

        @Suppress("UNCHECKED_CAST")
        val signingKey =
            OAuth2SigningKey(
                tenantId = DEFAULT_TENANT_ID,
                keyInfo =
                    KeyInfo<KeyType>(
                        alias = DEFAULT_KEY_ALIAS,
                        kid = DEFAULT_KEY_KID,
                        providerId = DEFAULT_PROVIDER_ID,
                        signatureAlgorithm = SignatureAlgorithm.ECDSA_SHA256,
                    ),
                state = OAuth2SigningKeyState.ACTIVE,
                priority = 100,
                createdAt = Clock.System.now(),
                notBefore = Clock.System.now(),
            )

        // listPublishable + register: don't double-register on restart with a persistent
        // store. Treat any failure as fatal — without a publishable key, nothing the AS
        // signs is verifiable downstream.
        val existingForKid =
            graph.signingKeyStore
                .findByKid(DEFAULT_TENANT_ID, DEFAULT_KEY_KID)
                .getOrNull()
        if (existingForKid != null) {
            println("STS signing key already registered with SigningKeyStore: kid=$DEFAULT_KEY_KID, state=${existingForKid.state}")
            return
        }
        val registerResult = graph.signingKeyStore.register(signingKey)
        check(registerResult.isOk) {
            "Failed to register STS signing key with SigningKeyStore: ${registerResult.error}"
        }
        println("Registered STS signing key with SigningKeyStore: kid=$DEFAULT_KEY_KID, tenant=$DEFAULT_TENANT_ID")
    }

    private fun extractPublicJwk(keyObj: Any?): Jwk? =
        when (keyObj) {
            is ManagedKeyPair -> keyObj.jose.publicJwk
            is ManagedKeyInfo<*> ->
                when (val inner = keyObj.key) {
                    is Jwk -> inner.toPublicKey() as? Jwk
                    else -> null
                }
            is Jwk -> keyObj.toPublicKey() as? Jwk
            else -> null
        }
}
