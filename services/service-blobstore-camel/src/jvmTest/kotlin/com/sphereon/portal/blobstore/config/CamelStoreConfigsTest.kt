package com.sphereon.portal.blobstore.config

import com.sphereon.data.store.blob.BlobStoreScopeBinding
import com.sphereon.portal.blobstore.ftp.CamelFtpBlobStoreConfig
import com.sphereon.portal.blobstore.ftp.CamelSftpBlobStoreConfig
import com.sphereon.portal.blobstore.s3.CamelS3BlobStoreConfig
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CamelStoreConfigsTest {

    @Test
    fun s3ConfigDefaults() {
        val config = CamelS3BlobStoreConfig(bucket = "my-bucket", region = "eu-central-1")
        assertEquals("camel-s3", config.backendId)
        assertEquals("camel-s3", config.id)
        assertEquals(BlobStoreScopeBinding.TENANT, config.scopeBinding)
        assertEquals(true, config.enabled)
        assertEquals("", config.prefix)
        assertNull(config.endpointOverride)
        assertIs<AwsIrsaAuthConfig>(config.auth)
    }

    @Test
    fun sftpConfigDefaults() {
        val config = CamelSftpBlobStoreConfig(
            host = "sftp.example.com", basePath = "/data",
            auth = PasswordAuthConfig("user", "pass"),
        )
        assertEquals("camel-sftp", config.backendId)
        assertEquals(22, config.port)
        assertEquals(true, config.strictHostKeyChecking)
        assertNull(config.knownHostsPath)
    }

    @Test
    fun ftpConfigDefaults() {
        val config = CamelFtpBlobStoreConfig(
            host = "ftp.example.com", basePath = "/files",
            auth = PasswordAuthConfig("user", "pass"),
        )
        assertEquals("camel-ftp", config.backendId)
        assertEquals(21, config.port)
        assertEquals(true, config.passiveMode)
    }

    @Test
    fun awsIrsaAuthSerialization() {
        val json = Json { classDiscriminator = "type" }
        val serialized = json.encodeToString(AwsAuthConfig.serializer(), AwsIrsaAuthConfig)
        val deserialized = json.decodeFromString(AwsAuthConfig.serializer(), serialized)
        assertIs<AwsIrsaAuthConfig>(deserialized)
    }

    @Test
    fun awsAccessKeyAuthSerialization() {
        val json = Json { classDiscriminator = "type" }
        val auth = AwsAccessKeyAuthConfig("AKIA...", "secret123")
        val serialized = json.encodeToString(AwsAuthConfig.serializer(), auth)
        val deserialized = json.decodeFromString(AwsAuthConfig.serializer(), serialized)
        assertIs<AwsAccessKeyAuthConfig>(deserialized)
        assertEquals("AKIA...", deserialized.accessKeyId)
        assertEquals("secret123", deserialized.secretAccessKey)
    }

    @Test
    fun passwordAuthSerialization() {
        val json = Json { classDiscriminator = "type" }
        val auth = PasswordAuthConfig("admin", "s3cret")
        val serialized = json.encodeToString(SftpLikeAuthConfig.serializer(), auth)
        val deserialized = json.decodeFromString(SftpLikeAuthConfig.serializer(), serialized)
        assertIs<PasswordAuthConfig>(deserialized)
        assertEquals("admin", deserialized.username)
    }

    @Test
    fun privateKeyAuthSerialization() {
        val json = Json { classDiscriminator = "type" }
        val auth = PrivateKeyAuthConfig("deploy", "/run/secrets/key", passphrase = "p4ss")
        val serialized = json.encodeToString(SftpLikeAuthConfig.serializer(), auth)
        val deserialized = json.decodeFromString(SftpLikeAuthConfig.serializer(), serialized)
        assertIs<PrivateKeyAuthConfig>(deserialized)
        assertEquals("deploy", deserialized.username)
        assertEquals("/run/secrets/key", deserialized.privateKeyPath)
        assertEquals("p4ss", deserialized.passphrase)
    }

    @Test
    fun uiHintsDefaults() {
        val hints = CamelStoreUiHints()
        assertNull(hints.label)
        assertNull(hints.description)
        assertNull(hints.order)
        assertNull(hints.readOnly)
        assertEquals(true, hints.exposeInPortal)
    }
}
