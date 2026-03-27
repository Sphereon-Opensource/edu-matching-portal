package com.sphereon.portal.blobstore.strategy

import com.sphereon.data.store.blob.BlobInfo
import com.sphereon.data.store.blob.BlobStore
import com.sphereon.data.store.blob.BlobStoreCapabilities
import com.sphereon.data.store.blob.ListOptions
import com.sphereon.data.store.blob.PutOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.camel.CamelContext

/**
 * Generic [BlobStore] backed by a [CamelProtocolStrategy].
 *
 * All operations dispatch to [Dispatchers.IO] because Camel component calls
 * are blocking I/O. Without this, Ktor event-loop threads block and concurrent
 * request latency degrades.
 */
class CamelBlobStore(
    override val storeId: String,
    private val strategy: CamelProtocolStrategy,
    private val camelContext: CamelContext,
) : BlobStore {

    override val capabilities: BlobStoreCapabilities = strategy.capabilities

    private val producerTemplate = camelContext.createProducerTemplate()
    private val consumerTemplate = camelContext.createConsumerTemplate()

    override suspend fun list(info: BlobInfo, options: ListOptions) =
        withContext(Dispatchers.IO) { strategy.list(producerTemplate, consumerTemplate, info, options) }

    override suspend fun get(info: BlobInfo) =
        withContext(Dispatchers.IO) { strategy.get(producerTemplate, info) }

    override suspend fun stat(info: BlobInfo) =
        withContext(Dispatchers.IO) { strategy.stat(producerTemplate, consumerTemplate, info) }

    override suspend fun put(target: BlobInfo, data: ByteArray, options: PutOptions) =
        withContext(Dispatchers.IO) { strategy.put(producerTemplate, target, data, options) }

    override suspend fun delete(info: BlobInfo) =
        withContext(Dispatchers.IO) { strategy.delete(producerTemplate, info) }

    override suspend fun copy(source: BlobInfo, destination: BlobInfo) =
        withContext(Dispatchers.IO) { strategy.copy(producerTemplate, source, destination) }

    override suspend fun move(source: BlobInfo, destination: BlobInfo) =
        withContext(Dispatchers.IO) { strategy.move(producerTemplate, source, destination) }
}
