package com.sphereon.portal.bridge.auxiliary

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.crypto.HashedIdentifier
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import com.sphereon.portal.bridge.persistence.createTestDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuxiliaryDataServiceImplTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15").apply {
            withDatabaseName("authbridge_test")
            withUsername("test")
            withPassword("test")
        }
    }

    private lateinit var database: AuthBridgeDatabase
    private lateinit var store: PostgresAuxiliaryDataStore
    private lateinit var service: AuxiliaryDataServiceImpl

    private val testCryptoService = object : ReconciliationCryptoService {
        @OptIn(ExperimentalEncodingApi::class)
        override suspend fun encrypt(plaintext: String) =
            EncryptedPayload(ciphertext = Base64.UrlSafe.encode(plaintext.encodeToByteArray()), keyVersion = "test-v1")

        @OptIn(ExperimentalEncodingApi::class)
        override suspend fun decrypt(payload: EncryptedPayload) =
            Base64.UrlSafe.decode(payload.ciphertext).decodeToString()

        override suspend fun hashHolderKey(holderKey: String) = HashedIdentifier(hash = "h:$holderKey", keyVersion = "v1")
        override suspend fun hashExternalIdentifier(identifier: String) = HashedIdentifier(hash = "e:$identifier", keyVersion = "v1")
        override suspend fun hashHolderKeyWithPrevious(holderKey: String): HashedIdentifier? = null
        override suspend fun hashExternalIdentifierWithPrevious(identifier: String): HashedIdentifier? = null
    }

    @BeforeAll
    fun setup() {
        postgres.start()
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        })
        val driver = ds.asJdbcDriver()
        database = createTestDatabase(driver)
        store = PostgresAuxiliaryDataStore(database)
        service = AuxiliaryDataServiceImpl(
            store = store,
            cryptoServiceProvider = { testCryptoService },
        )
    }

    @AfterAll
    fun teardown() {
        postgres.stop()
    }

    @Test
    fun storeAndGetDecrypted() = runTest {
        val fields = mapOf(
            "grade" to JsonPrimitive("A"),
            "course" to JsonPrimitive("CS101"),
        )

        val record = service.store("tenant-1", "identity-1", "grades", fields)
        assertEquals("grades", record.category)
        assertEquals("1", record.schemaVersion)

        val decrypted = service.getDecrypted("tenant-1", "identity-1", "grades")
        assertEquals(1, decrypted.size)
        assertEquals("grades", decrypted[0].category)
        assertEquals(JsonPrimitive("A"), decrypted[0].data["grade"])
        assertEquals(JsonPrimitive("CS101"), decrypted[0].data["course"])
    }

    @Test
    fun getDecryptedReturnsEmptyForMissing() = runTest {
        val decrypted = service.getDecrypted("tenant-1", "nonexistent", "grades")
        assertTrue(decrypted.isEmpty())
    }

    @Test
    fun getDecryptedAllCategories() = runTest {
        service.store("tenant-1", "identity-2", "enrollment", mapOf("status" to JsonPrimitive("active")))
        service.store("tenant-1", "identity-2", "grades", mapOf("gpa" to JsonPrimitive("3.8")))

        val all = service.getDecrypted("tenant-1", "identity-2")
        assertEquals(2, all.size)
        val categories = all.map { it.category }.toSet()
        assertTrue("enrollment" in categories)
        assertTrue("grades" in categories)
    }

    @Test
    fun deleteAll() = runTest {
        service.store("tenant-1", "identity-3", "a", mapOf("x" to JsonPrimitive("1")))
        service.store("tenant-1", "identity-3", "b", mapOf("y" to JsonPrimitive("2")))

        val deleted = service.deleteAll("tenant-1", "identity-3")
        assertEquals(2, deleted)

        val remaining = service.getDecrypted("tenant-1", "identity-3")
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun upsertOverwrites() = runTest {
        service.store("tenant-1", "identity-4", "grades", mapOf("grade" to JsonPrimitive("B")))
        service.store("tenant-1", "identity-4", "grades", mapOf("grade" to JsonPrimitive("A")))

        val decrypted = service.getDecrypted("tenant-1", "identity-4", "grades")
        assertEquals(1, decrypted.size)
        assertEquals(JsonPrimitive("A"), decrypted[0].data["grade"])
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun dataIsEncryptedInDatabase() = runTest {
        service.store("tenant-1", "identity-5", "secret", mapOf("ssn" to JsonPrimitive("123-45-6789")))

        val row = database.authBridgeQueries
            .findAuxByIdentityAndCategory("tenant-1", "identity-5", "secret")
            .executeAsOne()

        assertFalse(row.encrypted_data.contains("123-45-6789"))
        assertEquals("test-v1", row.key_version)
        assertEquals("1", row.schema_version)
    }

    @Test
    fun storeWithExpiry() = runTest {
        val expiresAt = kotlin.time.Clock.System.now()
            .plus(kotlin.time.Duration.parse("1h"))

        val record = service.store(
            "tenant-1", "identity-6", "temp",
            mapOf("x" to JsonPrimitive("1")),
            expiresAt = expiresAt,
        )
        assertNotNull(record.expiresAt)
    }
}
