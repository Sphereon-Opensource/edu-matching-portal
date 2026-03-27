package com.sphereon.portal.blobstore.routes

import com.sphereon.core.api.Ok
import com.sphereon.core.api.error.IdkError
import com.sphereon.data.store.blob.BlobDescriptor
import com.sphereon.data.store.blob.BlobInfo
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreError
import com.sphereon.data.store.blob.ListOptions
import com.sphereon.data.store.blob.PutOptions
import com.sphereon.data.store.blob.impl.BlobStoreConfigBinder
import com.sphereon.data.store.blob.impl.BlobStoreManager
import com.sphereon.portal.blobstore.catalog.BlobStoreCatalogService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val logger = KotlinLogging.logger {}

private const val MAX_BLOB_UPLOAD_BYTES: Long = 50L * 1024L * 1024L

// ── Request / Response DTOs ─────────────────────────────────────────────────

@Serializable
data class BlobPutRequest(
    val tenantId: String = "default",
    val path: String = "",
    val dataBase64: String = "",
    val metadata: BlobPutMetadata = BlobPutMetadata(),
)

@Serializable
data class BlobPutMetadata(
    val contentType: String? = null,
)

@Serializable
data class BlobCopyMoveRequest(
    val destination: String,
)

@Serializable
data class BlobDeleteResponse(val deleted: Boolean)

@Serializable
data class ErrorResponse(val error: String, val message: String?)

// ── Route installation ──────────────────────────────────────────────────────

fun Route.installBlobRoutes(
    configBinder: BlobStoreConfigBinder,
    storeManager: BlobStoreManager,
    configs: () -> Array<BlobStoreConfigBase>,
    catalogService: BlobStoreCatalogService,
) {
    // ── Catalog ─────────────────────────────────────────────────────────
    get("/api/blob-stores/catalog") {
        val tenantId = call.request.headers["X-Tenant-Id"]
        val catalog = catalogService.getCatalog(configs(), tenantId)
        call.respond(catalog)
    }

    // ── Blob operations ─────────────────────────────────────────────────
    route("/api/blob-stores/{storeId}") {

        // List blobs
        get("/blobs") {
            val storeId = call.parameters["storeId"]!!
            val tenantId = call.request.headers["X-Tenant-Id"] ?: "default"
            val store = resolveStore(storeManager, configs(), storeId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Store '$storeId' not found"))
                return@get
            }

            val options = ListOptions(
                prefix = call.request.queryParameters["prefix"],
                pageToken = call.request.queryParameters["pageToken"],
                maxResults = (call.request.queryParameters["maxResults"]?.toIntOrNull() ?: 100).coerceIn(1, 1000),
                recursive = call.request.queryParameters["recursive"]?.toBooleanStrictOrNull() ?: false,
            )

            val result = store.list(BlobInfo(storeId = storeId, tenantId = tenantId), options)
            if (result is Ok) {
                call.respond(result.value)
            } else {
                respondError(call, result.error)
            }
        }

        // Get blob content as binary stream
        get("/blobs/{path...}/content") {
            val storeId = call.parameters["storeId"]!!
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val tenantId = call.request.headers["X-Tenant-Id"] ?: "default"

            val safePath = try {
                BlobInfo.sanitizePath(path)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", e.message))
                return@get
            }

            val store = resolveStore(storeManager, configs(), storeId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Store '$storeId' not found"))
                return@get
            }

            val result = store.get(BlobInfo(storeId = storeId, path = safePath, tenantId = tenantId))
            if (result is Ok) {
                val resolved = result.value
                val contentType = resolved.descriptor.contentType?.let {
                    try { ContentType.parse(it) } catch (_: Exception) { ContentType.Application.OctetStream }
                } ?: ContentType.Application.OctetStream
                val filename = safePath.substringAfterLast('/')
                call.response.headers.append("Content-Disposition", "attachment; filename=\"$filename\"")
                call.respondBytes(resolved.data, contentType)
            } else {
                respondError(call, result.error)
            }
        }

        // Get blob as JSON (base64 content)
        get("/blobs/{path...}") {
            val storeId = call.parameters["storeId"]!!
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val tenantId = call.request.headers["X-Tenant-Id"] ?: "default"

            val safePath = try {
                BlobInfo.sanitizePath(path)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", e.message))
                return@get
            }

            val store = resolveStore(storeManager, configs(), storeId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Store '$storeId' not found"))
                return@get
            }

            val result = store.get(BlobInfo(storeId = storeId, path = safePath, tenantId = tenantId))
            if (result is Ok) {
                call.respond(result.value)
            } else {
                respondError(call, result.error)
            }
        }

        // Upload blob
        @OptIn(ExperimentalEncodingApi::class)
        put("/blobs/{path...}") {
            val storeId = call.parameters["storeId"]!!
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val tenantId = call.request.headers["X-Tenant-Id"] ?: "default"

            val safePath = try {
                BlobInfo.sanitizePath(path)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", e.message))
                return@put
            }

            val store = resolveStore(storeManager, configs(), storeId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Store '$storeId' not found"))
                return@put
            }

            val body = call.receive<BlobPutRequest>()
            val data = try {
                Base64.decode(body.dataBase64)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_DATA", "Invalid Base64 data: ${e.message}"))
                return@put
            }
            val maxSize = store.capabilities.maxBlobSizeBytes.coerceAtMost(MAX_BLOB_UPLOAD_BYTES)
            if (data.size > maxSize) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("PAYLOAD_TOO_LARGE",
                    "Blob size ${data.size} exceeds maximum $maxSize bytes"))
                return@put
            }

            val result = store.put(
                BlobInfo(storeId = storeId, path = safePath, tenantId = tenantId, contentType = body.metadata.contentType),
                data,
                PutOptions.DEFAULT,
            )
            if (result is Ok) {
                call.respond(HttpStatusCode.Created, result.value)
            } else {
                respondError(call, result.error)
            }
        }

        // Delete blob
        delete("/blobs/{path...}") {
            val storeId = call.parameters["storeId"]!!
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val tenantId = call.request.headers["X-Tenant-Id"] ?: "default"

            val safePath = try {
                BlobInfo.sanitizePath(path)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", e.message))
                return@delete
            }

            val store = resolveStore(storeManager, configs(), storeId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Store '$storeId' not found"))
                return@delete
            }

            val result = store.delete(BlobInfo(storeId = storeId, path = safePath, tenantId = tenantId))
            if (result is Ok) {
                call.respond(BlobDeleteResponse(deleted = result.value))
            } else {
                respondError(call, result.error)
            }
        }

        // Stat blob
        get("/blobs/{path...}/stat") {
            val storeId = call.parameters["storeId"]!!
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val tenantId = call.request.headers["X-Tenant-Id"] ?: "default"

            val safePath = try {
                BlobInfo.sanitizePath(path)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", e.message))
                return@get
            }

            val store = resolveStore(storeManager, configs(), storeId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Store '$storeId' not found"))
                return@get
            }

            val result = store.stat(BlobInfo(storeId = storeId, path = safePath, tenantId = tenantId))
            if (result is Ok) {
                call.respond(result.value)
            } else {
                respondError(call, result.error)
            }
        }

        // Copy blob
        post("/blobs/{path...}/copy") {
            val storeId = call.parameters["storeId"]!!
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val tenantId = call.request.headers["X-Tenant-Id"] ?: "default"

            val safePath = try { BlobInfo.sanitizePath(path) } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", e.message))
                return@post
            }

            val store = resolveStore(storeManager, configs(), storeId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Store '$storeId' not found"))
                return@post
            }

            val body = call.receive<BlobCopyMoveRequest>()
            val safeDest = try { BlobInfo.sanitizePath(body.destination) } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", "Invalid destination: ${e.message}"))
                return@post
            }

            val result = store.copy(
                BlobInfo(storeId = storeId, path = safePath, tenantId = tenantId),
                BlobInfo(storeId = storeId, path = safeDest, tenantId = tenantId),
            )
            if (result is Ok) {
                call.respond(result.value)
            } else {
                respondError(call, result.error)
            }
        }

        // Move blob
        post("/blobs/{path...}/move") {
            val storeId = call.parameters["storeId"]!!
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val tenantId = call.request.headers["X-Tenant-Id"] ?: "default"

            val safePath = try { BlobInfo.sanitizePath(path) } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", e.message))
                return@post
            }

            val store = resolveStore(storeManager, configs(), storeId) ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("NOT_FOUND", "Store '$storeId' not found"))
                return@post
            }

            val body = call.receive<BlobCopyMoveRequest>()
            val safeDest = try { BlobInfo.sanitizePath(body.destination) } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_PATH", "Invalid destination: ${e.message}"))
                return@post
            }

            val result = store.move(
                BlobInfo(storeId = storeId, path = safePath, tenantId = tenantId),
                BlobInfo(storeId = storeId, path = safeDest, tenantId = tenantId),
            )
            if (result is Ok) {
                call.respond(result.value)
            } else {
                respondError(call, result.error)
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun resolveStore(
    storeManager: BlobStoreManager,
    configs: Array<BlobStoreConfigBase>,
    storeId: String,
): com.sphereon.data.store.blob.BlobStore? {
    val config = configs.firstOrNull { it.id == storeId } ?: return null
    return try {
        storeManager.resolve(config)
    } catch (e: Exception) {
        logger.warn(e) { "Failed to resolve store '$storeId': ${e.message}" }
        null
    }
}

private suspend fun respondError(call: io.ktor.server.application.ApplicationCall, error: IdkError) {
    val code = error.code ?: "UNKNOWN"
    val status = when {
        code.contains("NOT_FOUND") -> HttpStatusCode.NotFound
        code.contains("PERMISSION_DENIED") -> HttpStatusCode.Forbidden
        code.contains("ALREADY_EXISTS") -> HttpStatusCode.Conflict
        code.contains("UNSUPPORTED") -> HttpStatusCode.BadRequest
        code.contains("QUOTA_EXCEEDED") -> HttpStatusCode.PayloadTooLarge
        else -> HttpStatusCode.InternalServerError
    }
    call.respond(status, ErrorResponse(error = code, message = error.message?.toString()))
}
