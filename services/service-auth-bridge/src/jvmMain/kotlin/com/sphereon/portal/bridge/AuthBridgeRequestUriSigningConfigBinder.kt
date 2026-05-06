package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.conf.PrincipalConfigService
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.crypto.core.KeyInfo
import com.sphereon.crypto.core.KeyInfoType
import com.sphereon.crypto.core.ManagedKeyInfo
import com.sphereon.crypto.core.generic.ManagedKeyPair
import com.sphereon.crypto.core.jose.Jwk
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.di.session.SessionScope
import com.sphereon.did.methods.jwk.JwkDidProviderImpl
import com.sphereon.openid.oid4vp.common.ClientIdScheme
import com.sphereon.openid.oid4vp.verifier.impl.ConfigDrivenRequestObjectSigningConfig
import com.sphereon.openid.oid4vp.verifier.impl.http.DefaultRequestObjectSigningConfigBinding
import com.sphereon.openid.oid4vp.verifier.requesturi.RequestObjectSigningConfig
import com.sphereon.openid.oid4vp.verifier.requesturi.VerifierSignerBinding
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Config-driven [RequestObjectSigningConfig] for the Auth Bridge.
 *
 * Reads JAR signing configuration from properties:
 *
 * ```properties
 * oid4vp.request-uri.signing-key-alias=auth-bridge-jar-signing-key
 * oid4vp.request-uri.signing-key-provider-id=software
 * oid4vp.request-uri.expiration-seconds=300
 * oid4vp.request-uri.signing-jar-enabled=true
 * ```
 *
 * The signing key must exist in the KMS before any session uses this config.
 * See [AuthBridgeKeyInitializer] for startup key generation.
 *
 * Replaces the IDK default [ConfigDrivenRequestObjectSigningConfig] so the auth bridge
 * keeps its `oid4vp.request-uri.*` property prefix instead of the IDK
 * `oid4vp.verifier.request-object.*` prefix.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(
    SessionScope::class,
    binding = binding<RequestObjectSigningConfig>(),
    replaces = [ConfigDrivenRequestObjectSigningConfig::class, DefaultRequestObjectSigningConfigBinding::class],
)
class AuthBridgeRequestUriSigningConfigBinder(
    private val execution: SessionExecution,
    private val kms: KeyManagerService,
) : RequestObjectSigningConfig {

    companion object {
        const val CONFIG_PREFIX = "oid4vp.request-uri"
        const val DEFAULT_KEY_ALIAS = "auth-bridge-jar-signing-key"
        const val DEFAULT_PROVIDER_ID = "software"
        const val DEFAULT_EXPIRATION_SECONDS = 300L
    }

    private val configService: PrincipalConfigService
        get() = execution.conf.conf(ConfigLevel.PRINCIPAL) as PrincipalConfigService

    private fun keyAlias(): String =
        configService.getPropertyAsString("$CONFIG_PREFIX.signing-key-alias", DEFAULT_KEY_ALIAS)
            ?: DEFAULT_KEY_ALIAS

    private fun providerId(): String =
        configService.getPropertyAsString("$CONFIG_PREFIX.signing-key-provider-id", DEFAULT_PROVIDER_ID)
            ?: DEFAULT_PROVIDER_ID

    override suspend fun resolveSigningKey(): KeyInfoType<*> =
        KeyInfo<Nothing>(alias = keyAlias(), providerId = providerId())

    override val audience: String
        get() = configService.getPropertyAsString("$CONFIG_PREFIX.audience", "") ?: ""

    override val expirationSeconds: Long
        get() = configService.getProperty("$CONFIG_PREFIX.expiration-seconds", Long::class, DEFAULT_EXPIRATION_SECONDS)
            ?: DEFAULT_EXPIRATION_SECONDS

    override val enabled: Boolean
        get() = configService.getProperty("$CONFIG_PREFIX.signing-jar-enabled", Boolean::class, true) ?: true

    /**
     * Auth bridge always self-identifies as a `did:jwk` derived from the JAR signing key
     * (matches the client metadata registered in [AuthBridgeKeyInitializer.registerClientMetadata]).
     * Other [ClientIdScheme]s are not supported here — would require an X.509 chain we don't issue.
     */
    override suspend fun resolveSignerBinding(scheme: ClientIdScheme?): VerifierSignerBinding? {
        if (!enabled) return null
        if (scheme != null && scheme != ClientIdScheme.DECENTRALIZED_IDENTIFIER) {
            error(
                "Auth bridge JAR signing only supports decentralized_identifier (did:jwk); " +
                    "requested scheme=$scheme is not configured.",
            )
        }
        val alias = keyAlias()
        val publicJwk = loadPublicJwk(alias)
        // did:jwk DID is computed from the public JWK with no kid (per did:jwk spec).
        val originalKid = publicJwk.kid
        publicJwk.kid = null
        val did = JwkDidProviderImpl.didFromJwk(publicJwk)
        publicJwk.kid = originalKid
        // did:jwk's verification method fragment is always `0`.
        return VerifierSignerBinding.Did(did = did, verificationMethodId = "$did#0")
    }

    private suspend fun loadPublicJwk(alias: String): Jwk {
        val keyResult = kms.getKeyResult(KeyInfo<Nothing>(alias = alias))
        check(keyResult.isOk) { "Failed to load JAR signing key '$alias' from KMS: ${keyResult.error}" }
        val keyObj = keyResult.value.key
        val jwk: Jwk? = when (keyObj) {
            is ManagedKeyPair -> keyObj.jose.publicJwk
            is ManagedKeyInfo<*> -> {
                when (val inner = keyObj.key) {
                    is Jwk -> inner.toPublicKey() as? Jwk
                    else -> null
                }
            }
            is Jwk -> keyObj.toPublicKey() as? Jwk
            else -> null
        }
        return jwk ?: error(
            "JAR signing key '$alias' did not yield a public JWK (got ${keyObj?.let { it::class.simpleName }})",
        )
    }
}
