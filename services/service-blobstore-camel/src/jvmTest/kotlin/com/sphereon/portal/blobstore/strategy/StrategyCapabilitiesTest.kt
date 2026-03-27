package com.sphereon.portal.blobstore.strategy

import com.sphereon.portal.blobstore.config.AwsIrsaAuthConfig
import com.sphereon.portal.blobstore.config.PasswordAuthConfig
import com.sphereon.portal.blobstore.ftp.CamelFtpBlobStoreConfig
import com.sphereon.portal.blobstore.ftp.CamelSftpBlobStoreConfig
import com.sphereon.portal.blobstore.ftp.FtpProtocolStrategy
import com.sphereon.portal.blobstore.ftp.SftpProtocolStrategy
import com.sphereon.portal.blobstore.s3.CamelS3BlobStoreConfig
import com.sphereon.portal.blobstore.s3.S3ProtocolStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrategyCapabilitiesTest {

    @Test
    fun s3CapabilitiesMatchPlan() {
        val strategy = S3ProtocolStrategy(
            CamelS3BlobStoreConfig(bucket = "test", region = "eu-central-1", auth = AwsIrsaAuthConfig)
        )
        val caps = strategy.capabilities
        assertTrue(caps.supportsListing)
        assertTrue(caps.supportsMetadata)
        assertTrue(caps.supportsEtag)
        assertFalse(caps.supportsCopy, "S3 copy disabled in v1")
        assertFalse(caps.supportsMove, "S3 move disabled in v1")
        assertFalse(caps.supportsTempUrls, "S3 temp URLs disabled in v1")
        assertFalse(caps.supportsBulkDelete, "S3 bulk delete disabled in v1")
        assertEquals(50L * 1024L * 1024L, caps.maxBlobSizeBytes)
    }

    @Test
    fun sftpCapabilitiesMatchPlan() {
        val strategy = SftpProtocolStrategy(
            CamelSftpBlobStoreConfig(
                host = "localhost", basePath = "/data",
                auth = PasswordAuthConfig("user", "pass"),
            )
        )
        val caps = strategy.capabilities
        assertTrue(caps.supportsListing)
        assertTrue(caps.supportsMetadata)
        assertTrue(caps.supportsMove, "SFTP supports rename")
        assertFalse(caps.supportsCopy, "SFTP has no server-side copy")
        assertFalse(caps.supportsEtag)
        assertFalse(caps.supportsTempUrls)
        assertEquals(50L * 1024L * 1024L, caps.maxBlobSizeBytes)
    }

    @Test
    fun ftpCapabilitiesMatchPlan() {
        val strategy = FtpProtocolStrategy(
            CamelFtpBlobStoreConfig(
                host = "localhost", basePath = "/data",
                auth = PasswordAuthConfig("user", "pass"),
            )
        )
        val caps = strategy.capabilities
        assertTrue(caps.supportsListing)
        assertTrue(caps.supportsMetadata)
        assertTrue(caps.supportsMove, "FTP supports rename")
        assertFalse(caps.supportsCopy)
        assertFalse(caps.supportsEtag)
        assertEquals(50L * 1024L * 1024L, caps.maxBlobSizeBytes)
    }
}
