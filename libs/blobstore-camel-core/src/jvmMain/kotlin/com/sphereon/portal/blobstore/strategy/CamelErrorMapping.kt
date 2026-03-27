package com.sphereon.portal.blobstore.strategy

import com.sphereon.core.api.Err
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.error.IdkError
import com.sphereon.data.store.blob.BlobStoreError

/**
 * Wraps a Camel strategy operation, catching exceptions and mapping to [IdkError].
 * Protocol-specific strategies should catch their own SDK exceptions for precision;
 * this provides generic fallback mapping by message pattern.
 */
inline fun <T> catchCamel(operation: String, path: String?, block: () -> T): IdkResult<T, IdkError> =
    try {
        Ok(block())
    } catch (e: BlobStoreError) {
        Err(e.toIdkError())
    } catch (e: Exception) {
        Err(CamelErrorMapping.map(operation, path, e))
    }

object CamelErrorMapping {

    fun map(operation: String, path: String?, throwable: Throwable): IdkError {
        val msg = throwable.message.orEmpty()

        // Check for well-known message patterns across all backends
        return when {
            throwable is IllegalArgumentException ->
                BlobStoreError.IoError("Invalid path: ${path ?: ""}").toIdkError()

            // Common "not found" patterns
            msg.contains("No such file", ignoreCase = true) ||
            msg.contains("NoSuchKey", ignoreCase = true) ||
            msg.contains("BlobNotFound", ignoreCase = true) ||
            msg.contains("404") && msg.contains("not found", ignoreCase = true) ->
                BlobStoreError.NotFound(path ?: "").toIdkError()

            // Common "permission" patterns
            msg.contains("Permission denied", ignoreCase = true) ||
            msg.contains("Access denied", ignoreCase = true) ||
            msg.contains("403") && msg.contains("forbidden", ignoreCase = true) ->
                BlobStoreError.PermissionDenied(path ?: "").toIdkError()

            // Common "conflict" patterns
            msg.contains("File exists", ignoreCase = true) ||
            msg.contains("already exists", ignoreCase = true) ||
            msg.contains("409") ->
                BlobStoreError.AlreadyExists(path ?: "").toIdkError()

            // Network
            throwable is java.net.SocketTimeoutException ->
                BlobStoreError.BackendError("Timeout during $operation").toIdkError()
            throwable is java.net.ConnectException ->
                BlobStoreError.BackendError("Connection failed during $operation").toIdkError()

            // Unknown — protocol-specific exceptions (FTP, S3, Azure, GCS) should be
            // caught by each protocol strategy's catchCamel block before reaching here.
            else -> BlobStoreError.BackendError(
                "${throwable::class.simpleName} during $operation: $msg"
            ).toIdkError()
        }
    }
}
