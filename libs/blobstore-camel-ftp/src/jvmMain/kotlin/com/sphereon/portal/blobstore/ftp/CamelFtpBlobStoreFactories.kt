package com.sphereon.portal.blobstore.ftp

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.data.store.blob.BlobStore
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreFactory
import com.sphereon.portal.blobstore.strategy.CamelBlobStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import org.apache.camel.CamelContext

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<BlobStoreFactory>())
class CamelSftpBlobStoreFactory(
    private val camelContext: CamelContext,
) : BlobStoreFactory {
    override val backendId: String = "camel-sftp"
    override fun create(config: BlobStoreConfigBase, execution: SessionExecution?): BlobStore {
        val typed = config as? CamelSftpBlobStoreConfig
            ?: error("CamelSftp factory requires CamelSftpBlobStoreConfig. Ensure 'blob.stores.${config.id}.type=camel-sftp'")
        return CamelBlobStore(typed.id, SftpProtocolStrategy(typed), camelContext)
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<BlobStoreFactory>())
class CamelFtpBlobStoreFactory(
    private val camelContext: CamelContext,
) : BlobStoreFactory {
    override val backendId: String = "camel-ftp"
    override fun create(config: BlobStoreConfigBase, execution: SessionExecution?): BlobStore {
        val typed = config as? CamelFtpBlobStoreConfig
            ?: error("CamelFtp factory requires CamelFtpBlobStoreConfig. Ensure 'blob.stores.${config.id}.type=camel-ftp'")
        return CamelBlobStore(typed.id, FtpProtocolStrategy(typed), camelContext)
    }
}
