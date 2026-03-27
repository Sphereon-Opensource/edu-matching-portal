package com.sphereon.portal.blobstore.s3

import com.sphereon.data.store.blob.AbstractBlobStoreConfig
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreScopeBinding
import com.sphereon.portal.blobstore.config.AwsAuthConfig
import com.sphereon.portal.blobstore.config.AwsIrsaAuthConfig
import com.sphereon.portal.blobstore.config.CamelStoreUiHints
import com.sphereon.portal.blobstore.config.HasCamelUiHints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("camel-s3")
data class CamelS3BlobStoreConfig(
    override val id: String = "camel-s3",
    @SerialName("scopebinding")
    override val scopeBinding: BlobStoreScopeBinding = BlobStoreScopeBinding.TENANT,
    override val enabled: Boolean = true,
    val bucket: String,
    val region: String,
    val prefix: String = "",
    val endpointOverride: String? = null,
    val auth: AwsAuthConfig = AwsIrsaAuthConfig,
    override val ui: CamelStoreUiHints = CamelStoreUiHints(),
) : AbstractBlobStoreConfig(), BlobStoreConfigBase, HasCamelUiHints {
    override val backendId: String = "camel-s3"
}
