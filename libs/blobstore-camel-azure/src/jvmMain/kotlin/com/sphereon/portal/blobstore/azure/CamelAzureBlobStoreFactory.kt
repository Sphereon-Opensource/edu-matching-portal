package com.sphereon.portal.blobstore.azure

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
class CamelAzureBlobStoreFactory(
    private val camelContext: CamelContext,
) : BlobStoreFactory {
    override val backendId: String = "camel-azure-blob"
    override fun create(config: BlobStoreConfigBase, execution: SessionExecution?): BlobStore {
        val typed = config as? CamelAzureBlobStoreConfig
            ?: error("CamelAzureBlob factory requires CamelAzureBlobStoreConfig. Ensure 'blob.stores.${config.id}.type=camel-azure-blob'")
        return CamelBlobStore(typed.id, AzureBlobProtocolStrategy(typed), camelContext)
    }
}
