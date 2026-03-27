package com.sphereon.portal.blobstore.ftp

import com.sphereon.data.store.blob.AbstractBlobStoreConfig
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreScopeBinding
import com.sphereon.portal.blobstore.config.PasswordAuthConfig
import com.sphereon.portal.blobstore.config.CamelStoreUiHints
import com.sphereon.portal.blobstore.config.HasCamelUiHints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("camel-ftp")
data class CamelFtpBlobStoreConfig(
    override val id: String = "camel-ftp",
    @SerialName("scopebinding")
    override val scopeBinding: BlobStoreScopeBinding = BlobStoreScopeBinding.TENANT,
    override val enabled: Boolean = true,
    val host: String,
    val port: Int = 21,
    val basePath: String,
    val passiveMode: Boolean = true,
    val auth: PasswordAuthConfig,
    override val ui: CamelStoreUiHints = CamelStoreUiHints(),
) : AbstractBlobStoreConfig(), BlobStoreConfigBase, HasCamelUiHints {
    override val backendId: String = "camel-ftp"
}
