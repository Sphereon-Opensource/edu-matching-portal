package com.sphereon.portal.blobstore.ftp

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
import com.sphereon.portal.blobstore.config.PasswordAuthConfig
import com.sphereon.portal.blobstore.strategy.CamelProtocolStrategy
import com.sphereon.portal.blobstore.strategy.catchCamel
import kotlinx.datetime.Instant
import org.apache.camel.ConsumerTemplate
import org.apache.camel.ProducerTemplate
import org.apache.camel.component.file.GenericFile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Camel FTP protocol strategy.
 *
 * Uses `ftp://` endpoints from the `camel-ftp` component. Supports password
 * authentication and passive mode. Move is implemented as get + put + delete
 * since FTP RENAME is unreliable across different server implementations.
 */
class FtpProtocolStrategy(
    private val config: CamelFtpBlobStoreConfig,
) : CamelProtocolStrategy {

    override val capabilities = BlobStoreCapabilities(
        supportsListing = true,
        supportsMetadata = true,
        supportsMove = true,        // Emulated via get+put+delete
        supportsCopy = false,       // No server-side copy in FTP
        supportsEtag = false,
        supportsTempUrls = false,
        supportsBulkDelete = false,
        maxBlobSizeBytes = 50L * 1024L * 1024L,
    )

    // ── Endpoint URI construction ────────────────────────────────────────────

    /**
     * Builds the base FTP endpoint URI with auth and connection options.
     */
    private fun endpointUri(directory: String, extraParams: String = ""): String = buildString {
        val auth = config.auth
        append("ftp://${auth.username}@${config.host}:${config.port}/$directory")
        append("?binary=true")
        append("&disconnect=true")
        append("&stepwise=false")
        append("&password=RAW(${auth.password})")
        append("&passiveMode=${config.passiveMode}")

        if (extraParams.isNotEmpty()) append("&$extraParams")
    }

    /** Splits a blob path into (directory, filename). */
    private fun splitPath(path: String): Pair<String, String> {
        val normalized = path.trimStart('/')
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash < 0) {
            config.basePath.trimStart('/') to normalized
        } else {
            val dir = config.basePath.trimStart('/') + "/" + normalized.substring(0, lastSlash)
            val file = normalized.substring(lastSlash + 1)
            dir to file
        }
    }

    // ── Operations ───────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    override suspend fun list(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
        options: ListOptions,
    ): IdkResult<ListResult, IdkError> = catchCamel("list", info.path) {
        val subPath = (options.prefix ?: info.path)?.trimStart('/') ?: ""
        val directory = if (subPath.isEmpty()) {
            config.basePath.trimStart('/')
        } else {
            config.basePath.trimStart('/') + "/" + subPath
        }

        val uri = endpointUri(
            directory,
            "noop=true&idempotent=false&recursive=${options.recursive}"
        )

        val descriptors = mutableListOf<BlobDescriptor>()
        val storeId = info.storeId ?: config.id
        var count = 0

        // Poll all available files from the directory
        while (count < options.maxResults) {
            val exchange = consumerTemplate.receive(uri, 2000L) ?: break
            val body = exchange.message.body ?: break

            val genericFile = body as? GenericFile<*>
            if (genericFile != null) {
                val relativePath = genericFile.relativeFilePath?.replace('\\', '/') ?: genericFile.fileName
                val displayPath = if (subPath.isEmpty()) relativePath else "$subPath/$relativePath"

                descriptors.add(
                    BlobDescriptor(
                        path = displayPath,
                        storeId = storeId,
                        sizeBytes = genericFile.fileLength,
                        lastModified = genericFile.lastModified.takeIf { it > 0 }
                            ?.let { Instant.fromEpochMilliseconds(it) },
                    )
                )
                count++
            } else {
                break
            }
        }

        ListResult(descriptors = descriptors)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun get(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<ResolvedBlobInfo, IdkError> = catchCamel("get", info.path) {
        val path = info.path
            ?: throw IllegalArgumentException("Path must not be null for FTP get")
        val (directory, filename) = splitPath(path)

        val uri = endpointUri(directory, "fileName=$filename&noop=true&idempotent=false")

        val exchange = producerTemplate.request(uri) { ex ->
            ex.message.body = null
        }

        val body = exchange.message.body
        val bytes = when (body) {
            is ByteArray -> body
            is GenericFile<*> -> (body.body as? ByteArray)
                ?: java.io.File(body.absoluteFilePath).readBytes()
            is java.io.InputStream -> body.use { it.readBytes() }
            else -> throw BlobStoreError.IoError("Unexpected FTP response body type: ${body?.javaClass}")
        }

        val descriptor = BlobDescriptor(
            path = info.path ?: "",
            storeId = info.storeId ?: config.id,
            sizeBytes = bytes.size.toLong(),
            contentType = info.contentType,
        )
        ResolvedBlobInfo(
            info = info,
            dataBase64 = Base64.encode(bytes),
            descriptor = descriptor,
        )
    }

    override suspend fun stat(
        producerTemplate: ProducerTemplate,
        consumerTemplate: ConsumerTemplate,
        info: BlobInfo,
    ): IdkResult<BlobDescriptor, IdkError> = catchCamel("stat", info.path) {
        // FTP has no dedicated stat command; list the parent directory and filter
        val path = info.path
            ?: throw IllegalArgumentException("Path must not be null for FTP stat")
        val (directory, filename) = splitPath(path)

        val uri = endpointUri(
            directory,
            "noop=true&idempotent=false&recursive=false"
        )

        var found: BlobDescriptor? = null
        // Poll entries until we find the target or exhaust the listing
        repeat(500) {
            if (found != null) return@repeat
            val exchange = consumerTemplate.receive(uri, 2000L) ?: return@repeat
            val body = exchange.message.body ?: return@repeat

            @Suppress("UNCHECKED_CAST")
            val genericFile = body as? GenericFile<*> ?: return@repeat
            val entryName = genericFile.fileName
            if (entryName == filename) {
                found = BlobDescriptor(
                    path = info.path ?: "",
                    storeId = info.storeId ?: config.id,
                    sizeBytes = genericFile.fileLength,
                    lastModified = genericFile.lastModified.takeIf { it > 0 }
                        ?.let { Instant.fromEpochMilliseconds(it) },
                    contentType = info.contentType,
                )
            }
        }

        found ?: throw BlobStoreError.NotFound(info.path ?: "")
    }

    override suspend fun put(
        producerTemplate: ProducerTemplate,
        target: BlobInfo,
        data: ByteArray,
        options: PutOptions,
    ): IdkResult<BlobDescriptor, IdkError> = catchCamel("put", target.path) {
        val path = target.path
            ?: throw IllegalArgumentException("Path must not be null for FTP put")
        val (directory, filename) = splitPath(path)

        val uri = endpointUri(directory, "fileName=$filename")

        producerTemplate.send(uri) { ex ->
            ex.message.body = data
            ex.message.setHeader("CamelFileName", filename)
        }

        BlobDescriptor(
            path = target.path ?: "",
            storeId = target.storeId ?: config.id,
            sizeBytes = data.size.toLong(),
            contentType = target.contentType,
        )
    }

    override suspend fun delete(
        producerTemplate: ProducerTemplate,
        info: BlobInfo,
    ): IdkResult<Boolean, IdkError> = catchCamel("delete", info.path) {
        val path = info.path
            ?: throw IllegalArgumentException("Path must not be null for FTP delete")
        val (directory, filename) = splitPath(path)

        val uri = endpointUri(directory, "fileName=$filename&delete=true")

        producerTemplate.send(uri) { ex ->
            ex.message.body = null
            ex.message.setHeader("CamelFileName", filename)
        }

        // Use the Camel endpoint's remote file operations for reliable deletion
        val ops = producerTemplate.camelContext.getEndpoint(uri)
            ?.let { it as? org.apache.camel.component.file.remote.RemoteFileEndpoint<*> }
            ?.createRemoteFileOperations()

        if (ops != null) {
            try {
                ops.connect(
                    producerTemplate.camelContext.getEndpoint(uri)
                        ?.let { it as? org.apache.camel.component.file.remote.RemoteFileEndpoint<*> }
                        ?.configuration,
                    null
                )
                ops.deleteFile("$directory/$filename")
            } finally {
                ops.disconnect()
            }
        }

        true
    }

    override suspend fun move(
        producerTemplate: ProducerTemplate,
        source: BlobInfo,
        destination: BlobInfo,
    ): IdkResult<BlobDescriptor, IdkError> = catchCamel("move", source.path) {
        // FTP RENAME is unreliable across server implementations, so we emulate
        // move as get + put + delete for maximum compatibility.
        val getResult = get(producerTemplate, source)
        if (getResult.isErr) throw BlobStoreError.NotFound(source.path ?: "")
        val resolved = getResult.value

        @OptIn(ExperimentalEncodingApi::class)
        val data = Base64.decode(resolved.dataBase64)

        val putResult = put(producerTemplate, destination, data, PutOptions.DEFAULT)
        if (putResult.isErr) throw BlobStoreError.IoError("Failed to write destination during move: ${destination.path}")

        val deleteResult = delete(producerTemplate, source)
        if (deleteResult.isErr) throw BlobStoreError.IoError(
            "Move partially completed: destination written but source not deleted: ${source.path}"
        )

        putResult.value
    }
}
