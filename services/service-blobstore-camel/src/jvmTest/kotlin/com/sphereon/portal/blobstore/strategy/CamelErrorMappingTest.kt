package com.sphereon.portal.blobstore.strategy

import com.sphereon.data.store.blob.BlobStoreError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CamelErrorMappingTest {

    @Test
    fun messageNoSuchFileMapsToNotFound() {
        val ex = RuntimeException("No such file or directory")
        val error = CamelErrorMapping.map("get", "/missing.txt", ex)
        assertTrue(error.code?.contains("NOT_FOUND") == true, "Expected NOT_FOUND, got ${error.code}")
    }

    @Test
    fun messagePermissionDeniedMapsCorrectly() {
        val ex = RuntimeException("Permission denied on /secret.txt")
        val error = CamelErrorMapping.map("put", "/secret.txt", ex)
        assertTrue(error.code?.contains("PERMISSION_DENIED") == true, "Expected PERMISSION_DENIED, got ${error.code}")
    }

    @Test
    fun messageFileExistsMapsToAlreadyExists() {
        val ex = RuntimeException("File exists: /dup.txt")
        val error = CamelErrorMapping.map("put", "/dup.txt", ex)
        assertTrue(error.code?.contains("ALREADY_EXISTS") == true, "Expected ALREADY_EXISTS, got ${error.code}")
    }

    @Test
    fun messageNoSuchKeyMapsToNotFound() {
        val ex = RuntimeException("NoSuchKey: The specified key does not exist")
        val error = CamelErrorMapping.map("get", "/missing", ex)
        assertTrue(error.code?.contains("NOT_FOUND") == true, "Expected NOT_FOUND, got ${error.code}")
    }

    @Test
    fun messageAccessDeniedMapsToPermissionDenied() {
        val ex = RuntimeException("Access denied for resource")
        val error = CamelErrorMapping.map("get", "/secret", ex)
        assertTrue(error.code?.contains("PERMISSION_DENIED") == true, "Expected PERMISSION_DENIED, got ${error.code}")
    }

    @Test
    fun socketTimeoutMapsToBackendError() {
        val ex = java.net.SocketTimeoutException("Read timed out")
        val error = CamelErrorMapping.map("get", "/slow.txt", ex)
        assertTrue(error.code?.contains("BACKEND_ERROR") == true, "Expected BACKEND_ERROR, got ${error.code}")
    }

    @Test
    fun connectExceptionMapsToBackendError() {
        val ex = java.net.ConnectException("Connection refused")
        val error = CamelErrorMapping.map("list", "/", ex)
        assertTrue(error.code?.contains("BACKEND_ERROR") == true, "Expected BACKEND_ERROR, got ${error.code}")
    }

    @Test
    fun illegalArgumentMapsToIoError() {
        val ex = IllegalArgumentException("bad path")
        val error = CamelErrorMapping.map("get", "../escape", ex)
        assertTrue(error.code?.contains("IO_ERROR") == true, "Expected IO_ERROR, got ${error.code}")
    }

    @Test
    fun unknownExceptionMapsToBackendError() {
        val ex = RuntimeException("Something weird")
        val error = CamelErrorMapping.map("delete", "/file.txt", ex)
        assertTrue(error.code?.contains("BACKEND_ERROR") == true, "Expected BACKEND_ERROR, got ${error.code}")
    }

    @Test
    fun catchCamelWrapsSuccessAsOk() {
        val result = catchCamel("test", "/path") { "hello" }
        assertTrue(result.isOk)
        assertEquals("hello", result.value)
    }

    @Test
    fun catchCamelWrapsBlobStoreErrorAsErr() {
        val result = catchCamel<String>("test", "/path") {
            throw BlobStoreError.NotFound("/path")
        }
        assertTrue(result.isErr)
        assertTrue(result.error.code?.contains("NOT_FOUND") == true)
    }

    @Test
    fun catchCamelWrapsGenericExceptionAsErr() {
        val result = catchCamel<String>("test", "/path") {
            throw RuntimeException("boom")
        }
        assertTrue(result.isErr)
        assertTrue(result.error.code?.contains("BACKEND_ERROR") == true)
    }
}
