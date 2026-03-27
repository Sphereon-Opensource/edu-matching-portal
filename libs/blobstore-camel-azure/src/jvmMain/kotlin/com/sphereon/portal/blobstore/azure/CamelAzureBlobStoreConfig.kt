package com.sphereon.portal.blobstore.azure

import com.sphereon.data.store.blob.AbstractBlobStoreConfig
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreScopeBinding
import com.sphereon.portal.blobstore.config.AzureAuthConfig
import com.sphereon.portal.blobstore.config.AzureManagedIdentityAuthConfig
import com.sphereon.portal.blobstore.config.AzureSasTokenAuthConfig
import com.sphereon.portal.blobstore.config.CamelStoreUiHints
import com.sphereon.portal.blobstore.config.HasCamelUiHints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("camel-azure-blob")
data class CamelAzureBlobStoreConfig(
    override val id: String = "camel-azure-blob",
    @SerialName("scopebinding")
    override val scopeBinding: BlobStoreScopeBinding = BlobStoreScopeBinding.TENANT,
    override val enabled: Boolean = true,
    val accountName: String,
    val containerName: String,
    val prefix: String = "",
    val serviceEndpoint: String? = null,
    val auth: AzureAuthConfig = AzureManagedIdentityAuthConfig,
    /** Shorthand for SAS token auth — when set, overrides [auth] to [AzureSasTokenAuthConfig]. */
    val sasToken: String? = null,
    override val ui: CamelStoreUiHints = CamelStoreUiHints(),
) : AbstractBlobStoreConfig(), BlobStoreConfigBase, HasCamelUiHints {
    override val backendId: String = "camel-azure-blob"

    /** Resolved auth: if [sasToken] is set, use SAS token auth; otherwise use [auth]. */
    val resolvedAuth: AzureAuthConfig
        get() = if (sasToken != null) AzureSasTokenAuthConfig(sasToken) else auth
}
