package com.sphereon.portal.blobstore.strategy

import com.sphereon.core.api.Err
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.error.IdkError
import com.sphereon.data.store.blob.BlobDescriptor
import com.sphereon.data.store.blob.BlobInfo
import com.sphereon.data.store.blob.BlobStoreCapabilities
import com.sphereon.data.store.blob.BlobStoreError
import com.sphereon.data.store.blob.ListOptions
import com.sphereon.data.store.blob.ListResult
import com.sphereon.data.store.blob.PutOptions
import com.sphereon.data.store.blob.ResolvedBlobInfo
import org.apache.camel.ConsumerTemplate
import org.apache.camel.ProducerTemplate

/**
 * Per-protocol strategy that encapsulates Camel header setup, endpoint building,
 * and protocol-specific error handling. One generic [CamelBlobStore] delegates
 * to the appropriate strategy.
 */
interface CamelProtocolStrategy {
    val capabilities: BlobStoreCapabilities

    suspend fun list(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
        options: ListOptions,
    ): IdkResult<ListResult, IdkError>

    suspend fun get(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<ResolvedBlobInfo, IdkError>

    suspend fun stat(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
    ): IdkResult<BlobDescriptor, IdkError>

    suspend fun put(
        producerTemplate: ProducerTemplate,
        target: BlobInfo,
        data: ByteArray,
        options: PutOptions,
    ): IdkResult<BlobDescriptor, IdkError>

    suspend fun delete(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<Boolean, IdkError>

    suspend fun copy(
        producerTemplate: ProducerTemplate,
        source: BlobInfo,
        destination: BlobInfo,
    ): IdkResult<BlobDescriptor, IdkError> =
        Err(BlobStoreError.Unsupported("copy").toIdkError())

    suspend fun move(
        producerTemplate: ProducerTemplate,
        source: BlobInfo,
        destination: BlobInfo,
    ): IdkResult<BlobDescriptor, IdkError> =
        Err(BlobStoreError.Unsupported("move").toIdkError())
}
