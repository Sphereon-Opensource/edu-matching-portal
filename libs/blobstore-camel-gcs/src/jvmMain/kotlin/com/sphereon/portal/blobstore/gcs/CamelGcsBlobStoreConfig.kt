package com.sphereon.portal.blobstore.gcs

import com.sphereon.data.store.blob.AbstractBlobStoreConfig
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreScopeBinding
import com.sphereon.portal.blobstore.config.GcsAuthConfig
import com.sphereon.portal.blobstore.config.GcsApplicationDefaultAuthConfig
import com.sphereon.portal.blobstore.config.CamelStoreUiHints
import com.sphereon.portal.blobstore.config.HasCamelUiHints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("camel-gcs")
data class CamelGcsBlobStoreConfig(
    override val id: String = "camel-gcs",
    @SerialName("scopebinding")
    override val scopeBinding: BlobStoreScopeBinding = BlobStoreScopeBinding.TENANT,
    override val enabled: Boolean = true,
    val bucket: String,
    val prefix: String = "",
    val auth: GcsAuthConfig = GcsApplicationDefaultAuthConfig,
    override val ui: CamelStoreUiHints = CamelStoreUiHints(),
) : AbstractBlobStoreConfig(), BlobStoreConfigBase, HasCamelUiHints {
    override val backendId: String = "camel-gcs"
}
