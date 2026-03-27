package com.sphereon.portal.blobstore.azure

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
import com.sphereon.portal.blobstore.config.AzureAccountKeyAuthConfig
import com.sphereon.portal.blobstore.config.AzureConnectionStringAuthConfig
import com.sphereon.portal.blobstore.config.AzureManagedIdentityAuthConfig
import com.sphereon.portal.blobstore.config.AzureSasTokenAuthConfig
import com.sphereon.portal.blobstore.strategy.CamelProtocolStrategy
import com.sphereon.portal.blobstore.strategy.catchCamel
import kotlinx.datetime.Instant
import org.apache.camel.ConsumerTemplate
import org.apache.camel.Exchange
import org.apache.camel.ProducerTemplate
import java.io.InputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class AzureBlobProtocolStrategy(
    private val config: CamelAzureBlobStoreConfig,
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
        append("azure-storage-blob://${config.accountName}/${config.containerName}?operation=listBlobs")
        when (val auth = config.resolvedAuth) {
            AzureManagedIdentityAuthConfig -> {
                // Managed identity — Camel uses DefaultAzureCredential; no extra params needed
                append("&credentialType=AZURE_IDENTITY")
            }
            is AzureAccountKeyAuthConfig -> {
                append("&accessKey=RAW(${auth.accountKey})")
            }
            is AzureSasTokenAuthConfig -> {
                append("&credentialType=AZURE_SAS")
                append("&sasToken=RAW(${auth.sasToken})")
            }
            is AzureConnectionStringAuthConfig -> {
                append("&connectionString=RAW(${auth.connectionString})")
            }
        }
    }

    // ── Operations ───────────────────────────────────────────────────────────

    override suspend fun list(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
        options: ListOptions,
    ): IdkResult<ListResult, IdkError> = catchCamel("list", info.path) {
        val prefix = buildBlobPrefix(options.prefix ?: info.path, config.prefix)
        val exchange = producerTemplate.send(endpointUri) { ex ->
            ex.message.setHeader("CamelAzureStorageBlobOperation", "listBlobs")
            if (prefix.isNotEmpty()) {
                ex.message.setHeader("CamelAzureStorageBlobPrefix", prefix)
            }
            ex.message.setHeader("CamelAzureStorageBlobMaxResultsPerPage", options.maxResults)
        }
        if (exchange.isFailed) throw exchange.exception ?: RuntimeException("listBlobs failed")
        exchangeToListResult(exchange, info.storeId ?: config.id)
    }

    override suspend fun get(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<ResolvedBlobInfo, IdkError> = catchCamel("get", info.path) {
        val blobName = requireBlobName(info.path, config.prefix)
        val exchange = producerTemplate.request(endpointUri) { ex ->
            ex.message.setHeader("CamelAzureStorageBlobOperation", "getBlob")
            ex.message.setHeader("CamelAzureStorageBlobBlobName", blobName)
        }
        exchangeToResolvedBlob(exchange, info)
    }

    override suspend fun stat(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
    ): IdkResult<BlobDescriptor, IdkError> = catchCamel("stat", info.path) {
        val blobName = requireBlobName(info.path, config.prefix)
        val exchange = producerTemplate.request(endpointUri) { ex ->
            ex.message.setHeader("CamelAzureStorageBlobOperation", "getBlobProperties")
            ex.message.setHeader("CamelAzureStorageBlobBlobName", blobName)
        }
        exchangeToDescriptor(exchange, info)
    }

    override suspend fun put(
        producerTemplate: ProducerTemplate,
        target: BlobInfo,
        data: ByteArray,
        options: PutOptions,
    ): IdkResult<BlobDescriptor, IdkError> = catchCamel("put", target.path) {
        val blobName = requireBlobName(target.path, config.prefix)
        producerTemplate.send(endpointUri) { ex ->
            ex.message.body = data
            ex.message.setHeader("CamelAzureStorageBlobOperation", "uploadBlockBlob")
            ex.message.setHeader("CamelAzureStorageBlobBlobName", blobName)
            target.contentType?.let {
                ex.message.setHeader("CamelAzureStorageBlobBlobContentType", it)
            }
        }
        // Stat after put to return an accurate descriptor
        val statResult = stat(producerTemplate, producerTemplate.camelContext.createConsumerTemplate(), target)
        if (statResult.isErr) throw BlobStoreError.BackendError("stat after put failed for ${target.path}")
        statResult.value
    }

    override suspend fun delete(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<Boolean, IdkError> = catchCamel("delete", info.path) {
        val blobName = requireBlobName(info.path, config.prefix)
        producerTemplate.send(endpointUri) { ex ->
            ex.message.setHeader("CamelAzureStorageBlobOperation", "deleteBlob")
            ex.message.setHeader("CamelAzureStorageBlobBlobName", blobName)
        }
        true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildBlobPrefix(path: String?, configPrefix: String): String {
        val base = configPrefix.trimEnd('/')
        val sub = path?.trimStart('/') ?: ""
        return if (base.isEmpty()) sub else if (sub.isEmpty()) "$base/" else "$base/$sub"
    }

    private fun requireBlobName(path: String?, configPrefix: String): String {
        val sanitized = path?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Path must not be empty for Azure Blob operations")
        val base = configPrefix.trimEnd('/')
        return if (base.isEmpty()) sanitized else "$base/$sanitized"
    }

    private fun exchangeToListResult(exchange: Exchange, storeId: String): ListResult {
        val body = exchange.message.body
        if (body is Iterable<*>) {
            val descriptors = body.filterIsInstance<com.azure.storage.blob.models.BlobItem>().map { item ->
                val rawName = item.name ?: ""
                val displayPath = if (config.prefix.isNotEmpty()) {
                    rawName.removePrefix(config.prefix.trimEnd('/')).removePrefix("/")
                } else rawName
                val props = item.properties
                BlobDescriptor(
                    path = displayPath,
                    storeId = storeId,
                    sizeBytes = props?.contentLength ?: 0L,
                    contentType = props?.contentType,
                    etag = props?.eTag,
                    lastModified = props?.lastModified?.toInstant()
                        ?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) },
                )
            }
            return ListResult(descriptors = descriptors)
        }
        return ListResult(descriptors = emptyList())
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun exchangeToResolvedBlob(exchange: Exchange, info: BlobInfo): ResolvedBlobInfo {
        val bytes = when (val body = exchange.message.body) {
            is ByteArray -> body
            is InputStream -> body.use { it.readBytes() }
            else -> throw BlobStoreError.IoError("Unexpected Azure response body type: ${body?.javaClass}")
        }
        val contentType = exchange.message.getHeader("CamelAzureStorageBlobBlobContentType", String::class.java)
        val etag = exchange.message.getHeader("CamelAzureStorageBlobETag", String::class.java)
        val contentLength = exchange.message.getHeader("CamelAzureStorageBlobBlobSize", Long::class.java)
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

    private fun exchangeToDescriptor(exchange: Exchange, info: BlobInfo): BlobDescriptor {
        val contentType = exchange.message.getHeader("CamelAzureStorageBlobBlobContentType", String::class.java)
        val etag = exchange.message.getHeader("CamelAzureStorageBlobETag", String::class.java)
        val contentLength = exchange.message.getHeader("CamelAzureStorageBlobBlobSize", Long::class.java) ?: 0L
        val lastModified = exchange.message.getHeader("CamelAzureStorageBlobLastModified", java.util.Date::class.java)

        return BlobDescriptor(
            path = info.path ?: "",
            storeId = info.storeId ?: config.id,
            sizeBytes = contentLength,
            contentType = contentType,
            etag = etag,
            lastModified = lastModified?.let { Instant.fromEpochMilliseconds(it.time) },
        )
    }
}
