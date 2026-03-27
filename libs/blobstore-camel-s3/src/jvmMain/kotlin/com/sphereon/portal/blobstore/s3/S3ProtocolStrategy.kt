package com.sphereon.portal.blobstore.s3

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
import com.sphereon.portal.blobstore.config.AwsAccessKeyAuthConfig
import com.sphereon.portal.blobstore.config.AwsIrsaAuthConfig
import com.sphereon.portal.blobstore.strategy.CamelProtocolStrategy
import com.sphereon.portal.blobstore.strategy.catchCamel
import kotlinx.datetime.Instant
import org.apache.camel.ConsumerTemplate
import org.apache.camel.Exchange
import org.apache.camel.ProducerTemplate
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import java.io.InputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class S3ProtocolStrategy(
    private val config: CamelS3BlobStoreConfig,
) : CamelProtocolStrategy {

    override val capabilities = BlobStoreCapabilities(
        supportsListing = true,
        supportsMetadata = true,
        supportsCopy = false,       // Disabled in v1 until tested
        supportsMove = false,       // Disabled in v1
        supportsTempUrls = false,   // Disabled in v1
        supportsBulkDelete = false, // Disabled in v1
        supportsEtag = true,
        maxBlobSizeBytes = 50L * 1024L * 1024L,
    )

    private val endpointUri = buildString {
        append("aws2-s3://${config.bucket}")
        append("?region=${config.region}")
        if (config.endpointOverride != null) {
            append("&overrideEndpoint=true&uriEndpointOverride=${config.endpointOverride}")
        }
        when (val auth = config.auth) {
            AwsIrsaAuthConfig -> append("&useDefaultCredentialsProvider=true")
            is AwsAccessKeyAuthConfig -> {
                append("&accessKey=RAW(${auth.accessKeyId})")
                append("&secretKey=RAW(${auth.secretAccessKey})")
                auth.sessionToken?.let { append("&sessionToken=RAW($it)") }
            }
        }
    }

    override suspend fun list(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
        options: ListOptions,
    ): IdkResult<ListResult, IdkError> = catchCamel("list", info.path) {
        // Prefer options.prefix over info.path for the S3 prefix filter
        val prefix = buildKeyPrefix(options.prefix ?: info.path, config.prefix)
        val exchange = producerTemplate.request(endpointUri) { ex ->
            ex.message.setHeader("CamelAwsS3Operation", "listObjects")
            ex.message.setHeader("CamelAwsS3Prefix", prefix)
            ex.message.setHeader("CamelAwsS3MaxKeys", options.maxResults)
            options.delimiter?.let { ex.message.setHeader("CamelAwsS3Delimiter", it) }
            options.pageToken?.let { ex.message.setHeader("CamelAwsS3ContinuationToken", it) }
        }
        exchangeToS3ListResult(exchange, info.storeId ?: config.id)
    }

    override suspend fun get(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<ResolvedBlobInfo, IdkError> = catchCamel("get", info.path) {
        val key = requireKey(info.path, config.prefix)
        val exchange = producerTemplate.request(endpointUri) { ex ->
            ex.message.setHeader("CamelAwsS3Operation", "getObject")
            ex.message.setHeader("CamelAwsS3Key", key)
        }
        exchangeToResolvedBlob(exchange, info)
    }

    override suspend fun stat(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
    ): IdkResult<BlobDescriptor, IdkError> = catchCamel("stat", info.path) {
        val key = requireKey(info.path, config.prefix)
        // Use headObject — fetches metadata only, avoids downloading the full object body
        val exchange = producerTemplate.request(endpointUri) { ex ->
            ex.message.setHeader("CamelAwsS3Operation", "headObject")
            ex.message.setHeader("CamelAwsS3Key", key)
        }
        exchangeToS3Descriptor(exchange, info)
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
            ex.message.setHeader("CamelAwsS3Operation", "putObject")
            ex.message.setHeader("CamelAwsS3Key", key)
            target.contentType?.let { ex.message.setHeader("CamelAwsS3ContentType", it) }
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
            ex.message.setHeader("CamelAwsS3Operation", "deleteObject")
            ex.message.setHeader("CamelAwsS3Key", key)
        }
        true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildKeyPrefix(path: String?, configPrefix: String): String {
        val base = configPrefix.trimEnd('/')
        val sub = path?.trimStart('/') ?: ""
        return if (base.isEmpty()) sub else if (sub.isEmpty()) "$base/" else "$base/$sub"
    }

    private fun requireKey(path: String?, configPrefix: String): String {
        val sanitized = path?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Path must not be empty for S3 operations")
        val base = configPrefix.trimEnd('/')
        return if (base.isEmpty()) sanitized else "$base/$sanitized"
    }

    private fun exchangeToS3ListResult(exchange: Exchange, storeId: String): ListResult {
        val body = exchange.message.body
        if (body is ListObjectsV2Response) {
            val descriptors = body.contents().map { obj ->
                s3ObjectToDescriptor(obj, storeId)
            }
            return ListResult(
                descriptors = descriptors,
                nextPageToken = body.nextContinuationToken(),
            )
        }
        return ListResult(descriptors = emptyList())
    }

    private fun s3ObjectToDescriptor(obj: S3Object, storeId: String): BlobDescriptor {
        val rawKey = obj.key()
        val displayPath = if (config.prefix.isNotEmpty()) {
            rawKey.removePrefix(config.prefix.trimEnd('/')).removePrefix("/")
        } else rawKey
        return BlobDescriptor(
            path = displayPath,
            storeId = storeId,
            sizeBytes = obj.size(),
            etag = obj.eTag(),
            lastModified = obj.lastModified()?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()) },
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun exchangeToResolvedBlob(exchange: Exchange, info: BlobInfo): ResolvedBlobInfo {
        val bytes = when (val body = exchange.message.body) {
            is ByteArray -> body
            is InputStream -> body.use { it.readBytes() }
            else -> throw BlobStoreError.IoError("Unexpected S3 response body type: ${body?.javaClass}")
        }
        val contentType = exchange.message.getHeader("CamelAwsS3ContentType", String::class.java)
        val etag = exchange.message.getHeader("CamelAwsS3ETag", String::class.java)
        val contentLength = exchange.message.getHeader("CamelAwsS3ContentLength", Long::class.java) ?: bytes.size.toLong()

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

    private fun exchangeToS3Descriptor(exchange: Exchange, info: BlobInfo): BlobDescriptor {
        val contentType = exchange.message.getHeader("CamelAwsS3ContentType", String::class.java)
        val etag = exchange.message.getHeader("CamelAwsS3ETag", String::class.java)
        val contentLength = exchange.message.getHeader("CamelAwsS3ContentLength", Long::class.java) ?: 0L
        val lastModified = exchange.message.getHeader("CamelAwsS3LastModified", java.util.Date::class.java)

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
