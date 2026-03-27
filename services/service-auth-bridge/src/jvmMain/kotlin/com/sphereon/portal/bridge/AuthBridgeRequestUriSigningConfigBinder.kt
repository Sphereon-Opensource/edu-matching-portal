package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.conf.PrincipalConfigService
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.crypto.core.KeyInfo
import com.sphereon.crypto.core.KeyInfoType
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.oid4vp.verifier.requesturi.RequestUriSigningConfig
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Config-driven [RequestUriSigningConfig] for the Auth Bridge.
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
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<RequestUriSigningConfig>())
class AuthBridgeRequestUriSigningConfigBinder(
    private val execution: SessionExecution
) : RequestUriSigningConfig {

    companion object {
        const val CONFIG_PREFIX = "oid4vp.request-uri"
        const val DEFAULT_KEY_ALIAS = "auth-bridge-jar-signing-key"
        const val DEFAULT_PROVIDER_ID = "software"
        const val DEFAULT_EXPIRATION_SECONDS = 300L
    }

    private val configService: PrincipalConfigService
        get() = execution.conf.conf(ConfigLevel.PRINCIPAL) as PrincipalConfigService

    override val signingKey: KeyInfoType<*> by lazy {
        val alias = configService.getPropertyAsString("$CONFIG_PREFIX.signing-key-alias", DEFAULT_KEY_ALIAS)
            ?: DEFAULT_KEY_ALIAS
        val providerId = configService.getPropertyAsString("$CONFIG_PREFIX.signing-key-provider-id", DEFAULT_PROVIDER_ID)
            ?: DEFAULT_PROVIDER_ID
        KeyInfo<Nothing>(alias = alias, providerId = providerId)
    }

    override val audience: String by lazy {
        configService.getPropertyAsString("$CONFIG_PREFIX.audience", "") ?: ""
    }

    override val expirationSeconds: Long by lazy {
        configService.getProperty("$CONFIG_PREFIX.expiration-seconds", Long::class, DEFAULT_EXPIRATION_SECONDS)
            ?: DEFAULT_EXPIRATION_SECONDS
    }

    override val enabled: Boolean by lazy {
        configService.getProperty("$CONFIG_PREFIX.signing-jar-enabled", Boolean::class, true) ?: true
    }
}
