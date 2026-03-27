package com.sphereon.portal.blobstore.factory

import com.sphereon.portal.blobstore.config.AwsIrsaAuthConfig
import com.sphereon.portal.blobstore.config.PasswordAuthConfig
import com.sphereon.portal.blobstore.ftp.CamelFtpBlobStoreConfig
import com.sphereon.portal.blobstore.ftp.CamelFtpBlobStoreFactory
import com.sphereon.portal.blobstore.ftp.CamelSftpBlobStoreConfig
import com.sphereon.portal.blobstore.ftp.CamelSftpBlobStoreFactory
import com.sphereon.portal.blobstore.s3.CamelS3BlobStoreConfig
import com.sphereon.portal.blobstore.s3.CamelS3BlobStoreFactory
import com.sphereon.portal.blobstore.strategy.CamelBlobStore
import org.apache.camel.impl.DefaultCamelContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class CamelBlobStoreFactoriesTest {

    private val camelContext = DefaultCamelContext().apply { disableJMX() }

    @Test
    fun s3FactoryHasCorrectBackendId() {
        assertEquals("camel-s3", CamelS3BlobStoreFactory(camelContext).backendId)
    }

    @Test
    fun sftpFactoryHasCorrectBackendId() {
        assertEquals("camel-sftp", CamelSftpBlobStoreFactory(camelContext).backendId)
    }

    @Test
    fun ftpFactoryHasCorrectBackendId() {
        assertEquals("camel-ftp", CamelFtpBlobStoreFactory(camelContext).backendId)
    }

    @Test
    fun s3FactoryCreatesCamelBlobStore() {
        val config = CamelS3BlobStoreConfig(id = "my-s3", bucket = "test", region = "us-east-1", auth = AwsIrsaAuthConfig)
        val store = CamelS3BlobStoreFactory(camelContext).create(config)
        assertIs<CamelBlobStore>(store)
        assertEquals("my-s3", store.storeId)
    }

    @Test
    fun sftpFactoryCreatesCamelBlobStore() {
        val config = CamelSftpBlobStoreConfig(
            id = "my-sftp", host = "sftp.example.com", basePath = "/data",
            auth = PasswordAuthConfig("user", "pass"),
        )
        val store = CamelSftpBlobStoreFactory(camelContext).create(config)
        assertIs<CamelBlobStore>(store)
        assertEquals("my-sftp", store.storeId)
    }

    @Test
    fun ftpFactoryCreatesCamelBlobStore() {
        val config = CamelFtpBlobStoreConfig(
            id = "my-ftp", host = "ftp.example.com", basePath = "/files",
            auth = PasswordAuthConfig("user", "pass"),
        )
        val store = CamelFtpBlobStoreFactory(camelContext).create(config)
        assertIs<CamelBlobStore>(store)
        assertEquals("my-ftp", store.storeId)
    }

    @Test
    fun s3FactoryRejectsWrongConfigType() {
        val wrongConfig = CamelSftpBlobStoreConfig(
            id = "oops", host = "h", basePath = "/",
            auth = PasswordAuthConfig("u", "p"),
        )
        assertFailsWith<IllegalStateException> {
            CamelS3BlobStoreFactory(camelContext).create(wrongConfig)
        }
    }
}
