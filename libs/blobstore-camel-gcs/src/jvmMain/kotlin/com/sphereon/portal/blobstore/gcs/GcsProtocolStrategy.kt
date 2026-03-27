package com.sphereon.portal.blobstore.gcs

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.error.IdkError
import com.sphereon.data.store.blob.BlobDescriptor
import com.sphereon.data.store.blob.BlobInfo
import com.sphereon.data.store.blob.BlobStoreCapabilities
import com.sphereon.data.store.blob.BlobStoreError
import com.sphereon.data.store.blob.ListOptions
import com.sphereon.data.store.blob.ListResult
import com.sphereon.data.store.blob.PutOptions
import com.sphereon.data.store.blob.ResolvedBlobInfo
import com.sphereon.portal.blobstore.config.GcsApplicationDefaultAuthConfig
import com.sphereon.portal.blobstore.config.GcsServiceAccountKeyAuthConfig
import com.sphereon.portal.blobstore.strategy.CamelProtocolStrategy
import com.sphereon.portal.blobstore.strategy.catchCamel
import kotlinx.datetime.Instant
import org.apache.camel.ConsumerTemplate
import org.apache.camel.Exchange
import org.apache.camel.ProducerTemplate
import java.io.InputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class GcsProtocolStrategy(
    private val config: CamelGcsBlobStoreConfig,
) : CamelProtocolStrategy {

    override val capabilities = BlobStoreCapabilities(
        supportsListing = true,
        supportsMetadata = true,
        supportsCopy = false,
        supportsMove = false,
        supportsTempUrls = false,
        supportsBulkDelete = false,
        supportsEtag = true,
        maxBlobSizeBytes = 50L * 1024L * 1024L,
    )

    private val endpointUri = buildString {
        append("google-storage://${config.bucket}")
        append("?autoCreateBucket=false")
        when (val auth = config.auth) {
            GcsApplicationDefaultAuthConfig -> { /* no additional credentials param */ }
            is GcsServiceAccountKeyAuthConfig -> {
                append("&serviceAccountKey=file:${auth.keyFilePath}")
            }
        }
    }

    override suspend fun list(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
        options: ListOptions,
    ): IdkResult<ListResult, IdkError> = catchCamel("list", info.path) {
        val prefix = buildKeyPrefix(options.prefix ?: info.path, config.prefix)
        val exchange = producerTemplate.request(endpointUri) { ex ->
            ex.message.setHeader("CamelGoogleCloudStorageOperation", "listObjects")
            ex.message.setHeader("CamelGoogleCloudStoragePrefix", prefix)
        }
        exchangeToGcsListResult(exchange, info.storeId ?: config.id)
    }

    override suspend fun get(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<ResolvedBlobInfo, IdkError> = catchCamel("get", info.path) {
        val key = requireKey(info.path, config.prefix)
        val exchange = producerTemplate.request(endpointUri) { ex ->
            ex.message.setHeader("CamelGoogleCloudStorageOperation", "getObject")
            ex.message.setHeader("CamelGoogleCloudStorageObjectName", key)
        }
        exchangeToResolvedBlob(exchange, info)
    }

    override suspend fun stat(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
    ): IdkResult<BlobDescriptor, IdkError> = catchCamel("stat", info.path) {
        val key = requireKey(info.path, config.prefix)
        val exchange = producerTemplate.request(endpointUri) { ex ->
            ex.message.setHeader("CamelGoogleCloudStorageOperation", "getObject")
            ex.message.setHeader("CamelGoogleCloudStorageObjectName", key)
        }
        exchangeToGcsDescriptor(exchange, info)
    }

    override suspend fun put(
        producerTemplate: ProducerTemplate,
        target: BlobInfo,
        data: ByteArray,
        options: PutOptions,
    ): IdkResult<BlobDescriptor, IdkError> = catchCamel("put", target.path) {
        val key = requireKey(target.path, config.prefix)
        producerTemplate.send(endpointUri) { ex ->
            ex.message.body = data
            ex.message.setHeader("CamelGoogleCloudStorageObjectName", key)
            target.contentType?.let { ex.message.setHeader("CamelGoogleCloudStorageContentType", it) }
            ex.message.setHeader("CamelGoogleCloudStorageContentLength", data.size.toLong())
        }
        val statResult = stat(producerTemplate, producerTemplate.camelContext.createConsumerTemplate(), target)
        if (statResult.isErr) throw BlobStoreError.BackendError("stat after put failed for ${target.path}")
        statResult.value
    }

    override suspend fun delete(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<Boolean, IdkError> = catchCamel("delete", info.path) {
        val key = requireKey(info.path, config.prefix)
        producerTemplate.send(endpointUri) { ex ->
            ex.message.setHeader("CamelGoogleCloudStorageOperation", "deleteObject")
            ex.message.setHeader("CamelGoogleCloudStorageObjectName", key)
        }
        true
    }

    // -- Helpers ---------------------------------------------------------------

    private fun buildKeyPrefix(path: String?, configPrefix: String): String {
        val base = configPrefix.trimEnd('/')
        val sub = path?.trimStart('/') ?: ""
        return if (base.isEmpty()) sub else if (sub.isEmpty()) "$base/" else "$base/$sub"
    }

    private fun requireKey(path: String?, configPrefix: String): String {
        val sanitized = path?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Path must not be empty for GCS operations")
        val base = configPrefix.trimEnd('/')
        return if (base.isEmpty()) sanitized else "$base/$sanitized"
    }

    private fun exchangeToGcsListResult(exchange: Exchange, storeId: String): ListResult {
        val body = exchange.message.body
        val descriptors = when (body) {
            is Iterable<*> -> body.filterIsInstance<com.google.cloud.storage.Blob>().map { blob ->
                val rawName = blob.name ?: ""
                val displayPath = if (config.prefix.isNotEmpty()) {
                    rawName.removePrefix(config.prefix.trimEnd('/')).removePrefix("/")
                } else rawName
                BlobDescriptor(
                    path = displayPath,
                    storeId = storeId,
                    sizeBytes = blob.size ?: 0L,
                    contentType = blob.contentType,
                    etag = blob.etag,
                    lastModified = blob.updateTimeOffsetDateTime?.toInstant()
                        ?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) },
                )
            }
            else -> emptyList()
        }
        return ListResult(descriptors = descriptors)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun exchangeToResolvedBlob(exchange: Exchange, info: BlobInfo): ResolvedBlobInfo {
        val bytes = when (val body = exchange.message.body) {
            is ByteArray -> body
            is InputStream -> body.use { it.readBytes() }
            else -> throw BlobStoreError.IoError("Unexpected GCS response body type: ${body?.javaClass}")
        }
        val contentType = exchange.message.getHeader("CamelGoogleCloudStorageContentType", String::class.java)
        val etag = exchange.message.getHeader("CamelGoogleCloudStorageETag", String::class.java)
        val contentLength = exchange.message.getHeader("CamelGoogleCloudStorageContentLength", Long::class.java)
            ?: bytes.size.toLong()

        val descriptor = BlobDescriptor(
            path = info.path ?: "",
            storeId = info.storeId ?: config.id,
            sizeBytes = contentLength,
            contentType = contentType,
            etag = etag,
        )
        return ResolvedBlobInfo(
            info = info,
            dataBase64 = Base64.encode(bytes),
            descriptor = descriptor,
        )
    }

    private fun exchangeToGcsDescriptor(exchange: Exchange, info: BlobInfo): BlobDescriptor {
        val contentType = exchange.message.getHeader("CamelGoogleCloudStorageContentType", String::class.java)
        val etag = exchange.message.getHeader("CamelGoogleCloudStorageETag", String::class.java)
        val contentLength = exchange.message.getHeader("CamelGoogleCloudStorageContentLength", Long::class.java) ?: 0L

        return BlobDescriptor(
            path = info.path ?: "",
            storeId = info.storeId ?: config.id,
            sizeBytes = contentLength,
            contentType = contentType,
            etag = etag,
        )
    }
}
