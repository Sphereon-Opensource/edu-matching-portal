package com.sphereon.portal.blobstore.catalog

import com.sphereon.data.store.blob.BlobStore
import com.sphereon.data.store.blob.BlobStoreCapabilities
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreScopeBinding
import com.sphereon.data.store.blob.impl.BlobStoreConfigBinder
import com.sphereon.data.store.blob.impl.BlobStoreManager
import com.sphereon.portal.blobstore.config.AwsIrsaAuthConfig
import com.sphereon.portal.blobstore.config.CamelStoreUiHints
import com.sphereon.portal.blobstore.config.PasswordAuthConfig
import com.sphereon.portal.blobstore.ftp.CamelSftpBlobStoreConfig
import com.sphereon.portal.blobstore.s3.CamelS3BlobStoreConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlobStoreCatalogServiceTest {

    private fun mockStore(caps: BlobStoreCapabilities = BlobStoreCapabilities.MINIMAL) = object : BlobStore {
        override val storeId = "mock"
        override val capabilities = caps
        override suspend fun put(target: com.sphereon.data.store.blob.BlobInfo, data: ByteArray, options: com.sphereon.data.store.blob.PutOptions) = throw UnsupportedOperationException()
        override suspend fun get(info: com.sphereon.data.store.blob.BlobInfo) = throw UnsupportedOperationException()
        override suspend fun delete(info: com.sphereon.data.store.blob.BlobInfo) = throw UnsupportedOperationException()
        override suspend fun stat(info: com.sphereon.data.store.blob.BlobInfo) = throw UnsupportedOperationException()
        override suspend fun list(info: com.sphereon.data.store.blob.BlobInfo, options: com.sphereon.data.store.blob.ListOptions) = throw UnsupportedOperationException()
        override suspend fun copy(source: com.sphereon.data.store.blob.BlobInfo, destination: com.sphereon.data.store.blob.BlobInfo) = throw UnsupportedOperationException()
        override suspend fun move(source: com.sphereon.data.store.blob.BlobInfo, destination: com.sphereon.data.store.blob.BlobInfo) = throw UnsupportedOperationException()
    }

    private fun catalogService(
        portalConfig: PortalBlobCatalogConfig = PortalBlobCatalogConfig(),
        resolveStore: (BlobStoreConfigBase) -> BlobStore = { mockStore() },
        failingStoreIds: Set<String> = emptySet(),
    ): BlobStoreCatalogService {
        val manager = object : BlobStoreManager {
            override fun resolve(config: BlobStoreConfigBase): BlobStore {
                if (config.id in failingStoreIds) throw RuntimeException("Store unavailable")
                return resolveStore(config)
            }
            override fun availableBackends(): List<String> = listOf("camel-s3", "camel-sftp")
            override fun createFromBlobStoreConfig(config: BlobStoreConfigBase) = resolve(config)
            override fun createFromBlobStoreConfig(config: BlobStoreConfigBase, execution: com.sphereon.core.api.context.SessionExecution?) = resolve(config)
            override fun createFromProperties(configService: com.sphereon.core.api.conf.ConfigService, execution: com.sphereon.core.api.context.SessionExecution?) = emptySet<BlobStore>()
        }
        val binder = object : BlobStoreConfigBinder {
            override fun getBlobStoreIds(configService: com.sphereon.core.api.conf.ConfigService) = emptyArray<String>()
            override fun getBlobStoreConfig(configService: com.sphereon.core.api.conf.ConfigService, storeId: String): BlobStoreConfigBase = throw UnsupportedOperationException()
            override fun getBlobStoreConfigs(configService: com.sphereon.core.api.conf.ConfigService) = emptyArray<BlobStoreConfigBase>()
        }
        return BlobStoreCatalogService(binder, manager, portalConfig)
    }

    private val s3Config = CamelS3BlobStoreConfig(
        id = "archive", bucket = "test", region = "eu-central-1", auth = AwsIrsaAuthConfig,
        ui = CamelStoreUiHints(label = "Archive S3", order = 10, readOnly = true),
    )

    private val sftpConfig = CamelSftpBlobStoreConfig(
        id = "exports", host = "sftp.example.com", basePath = "/exports",
        auth = PasswordAuthConfig("user", "pass"),
        ui = CamelStoreUiHints(label = "Student Exports", order = 20),
    )

    @Test
    fun catalogReturnsEnabledStores() {
        val service = catalogService()
        val catalog = service.getCatalog(arrayOf(s3Config, sftpConfig), tenantId = null)
        assertEquals(2, catalog.stores.size)
    }

    @Test
    fun catalogSkipsDisabledStores() {
        val disabled = s3Config.copy(enabled = false)
        val service = catalogService()
        val catalog = service.getCatalog(arrayOf(disabled, sftpConfig), tenantId = null)
        assertEquals(1, catalog.stores.size)
        assertEquals("exports", catalog.stores.first().storeId)
    }

    @Test
    fun catalogSkipsBrokenStores() {
        val service = catalogService(failingStoreIds = setOf("archive"))
        val catalog = service.getCatalog(arrayOf(s3Config, sftpConfig), tenantId = null)
        assertEquals(1, catalog.stores.size)
        assertEquals("exports", catalog.stores.first().storeId)
    }

    @Test
    fun catalogRespectsExposeInPortalFalse() {
        val hidden = s3Config.copy(ui = s3Config.ui.copy(exposeInPortal = false))
        val service = catalogService()
        val catalog = service.getCatalog(arrayOf(hidden, sftpConfig), tenantId = null)
        assertEquals(1, catalog.stores.size)
        assertEquals("exports", catalog.stores.first().storeId)
    }

    @Test
    fun catalogUsesUiHintsForLabels() {
        val service = catalogService()
        val catalog = service.getCatalog(arrayOf(s3Config), tenantId = null)
        assertEquals("Archive S3", catalog.stores.first().label)
    }

    @Test
    fun catalogUsesUiHintsForOrder() {
        val service = catalogService()
        val catalog = service.getCatalog(arrayOf(sftpConfig, s3Config), tenantId = null)
        // s3 has order=10, sftp has order=20, so s3 should come first
        assertEquals("archive", catalog.stores[0].storeId)
        assertEquals("exports", catalog.stores[1].storeId)
    }

    @Test
    fun catalogUsesUiHintsForReadOnly() {
        val service = catalogService()
        val catalog = service.getCatalog(arrayOf(s3Config), tenantId = null)
        assertTrue(catalog.stores.first().readOnly)
    }

    @Test
    fun catalogOverridesTakesPrecedenceOverUiHints() {
        val portalConfig = PortalBlobCatalogConfig(
            stores = mapOf("archive" to PortalBlobCatalogConfig.StoreOverride(
                label = "Custom Label", order = 99, readOnly = false,
            ))
        )
        val service = catalogService(portalConfig = portalConfig)
        val catalog = service.getCatalog(arrayOf(s3Config), tenantId = null)
        assertEquals("Custom Label", catalog.stores.first().label)
        assertEquals(99, catalog.stores.first().order)
        assertEquals(false, catalog.stores.first().readOnly)
    }

    @Test
    fun catalogDefaultStoreIdFromConfig() {
        val portalConfig = PortalBlobCatalogConfig(defaultStoreId = "archive")
        val service = catalogService(portalConfig = portalConfig)
        val catalog = service.getCatalog(arrayOf(s3Config, sftpConfig), tenantId = null)
        assertEquals("archive", catalog.defaultStoreId)
    }

    @Test
    fun catalogDefaultStoreIdFallsBackToFirst() {
        val service = catalogService()
        val catalog = service.getCatalog(arrayOf(s3Config, sftpConfig), tenantId = null)
        // First by order — s3Config has order=10
        assertEquals("archive", catalog.defaultStoreId)
    }

    @Test
    fun catalogExplicitExposedStoresFilterCorrectly() {
        val portalConfig = PortalBlobCatalogConfig(exposedStores = setOf("exports"))
        val service = catalogService(portalConfig = portalConfig)
        val catalog = service.getCatalog(arrayOf(s3Config, sftpConfig), tenantId = null)
        assertEquals(1, catalog.stores.size)
        assertEquals("exports", catalog.stores.first().storeId)
    }

    @Test
    fun catalogEmptyWhenNoStoresConfigured() {
        val service = catalogService()
        val catalog = service.getCatalog(emptyArray(), tenantId = null)
        assertTrue(catalog.stores.isEmpty())
        assertNull(catalog.defaultStoreId)
    }
}
