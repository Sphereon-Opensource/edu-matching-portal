package com.sphereon.portal.blobstore.ftp

import com.sphereon.data.store.blob.AbstractBlobStoreConfig
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreScopeBinding
import com.sphereon.portal.blobstore.config.SftpLikeAuthConfig
import com.sphereon.portal.blobstore.config.CamelStoreUiHints
import com.sphereon.portal.blobstore.config.HasCamelUiHints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("camel-sftp")
data class CamelSftpBlobStoreConfig(
    override val id: String = "camel-sftp",
    @SerialName("scopebinding")
    override val scopeBinding: BlobStoreScopeBinding = BlobStoreScopeBinding.TENANT,
    override val enabled: Boolean = true,
    val host: String,
    val port: Int = 22,
    val basePath: String,
    val strictHostKeyChecking: Boolean = true,
    val knownHostsPath: String? = null,
    val auth: SftpLikeAuthConfig,
    override val ui: CamelStoreUiHints = CamelStoreUiHints(),
) : AbstractBlobStoreConfig(), BlobStoreConfigBase, HasCamelUiHints {
    override val backendId: String = "camel-sftp"
}
