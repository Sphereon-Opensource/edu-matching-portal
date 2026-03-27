package com.sphereon.portal.blobstore.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── UI Hints (embedded in store config) ─────────────────────────────────────

@Serializable
data class CamelStoreUiHints(
    val label: String? = null,
    val description: String? = null,
    val order: Int? = null,
    val readOnly: Boolean? = null,
    val exposeInPortal: Boolean = true,
)

/**
 * Marker interface for Camel blob store configs that carry UI hints.
 * The catalog service uses this to extract hints without knowing specific config types.
 */
interface HasCamelUiHints {
    val ui: CamelStoreUiHints
}

// ── AWS Auth (used by S3) ───────────────────────────────────────────────────

@Serializable
sealed interface AwsAuthConfig

@Serializable @SerialName("irsa")
data object AwsIrsaAuthConfig : AwsAuthConfig

@Serializable @SerialName("access_key")
data class AwsAccessKeyAuthConfig(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
) : AwsAuthConfig

// ── SFTP / FTP Auth ─────────────────────────────────────────────────────────

@Serializable
sealed interface SftpLikeAuthConfig

@Serializable @SerialName("password")
data class PasswordAuthConfig(
    val username: String,
    val password: String,
) : SftpLikeAuthConfig

@Serializable @SerialName("private_key")
data class PrivateKeyAuthConfig(
    val username: String,
    val privateKeyPath: String,
    val passphrase: String? = null,
) : SftpLikeAuthConfig

// ── Azure Auth ──────────────────────────────────────────────────────────────

@Serializable
sealed interface AzureAuthConfig

@Serializable @SerialName("managed_identity")
data object AzureManagedIdentityAuthConfig : AzureAuthConfig

@Serializable @SerialName("account_key")
data class AzureAccountKeyAuthConfig(val accountKey: String) : AzureAuthConfig

@Serializable @SerialName("sas_token")
data class AzureSasTokenAuthConfig(val sasToken: String) : AzureAuthConfig

@Serializable @SerialName("connection_string")
data class AzureConnectionStringAuthConfig(val connectionString: String) : AzureAuthConfig

// ── GCS Auth ────────────────────────────────────────────────────────────────

@Serializable
sealed interface GcsAuthConfig

@Serializable @SerialName("application_default")
data object GcsApplicationDefaultAuthConfig : GcsAuthConfig

@Serializable @SerialName("service_account_key")
data class GcsServiceAccountKeyAuthConfig(val keyFilePath: String) : GcsAuthConfig
