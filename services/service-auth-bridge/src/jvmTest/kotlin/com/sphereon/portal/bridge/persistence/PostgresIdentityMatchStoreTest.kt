package com.sphereon.portal.bridge.persistence

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.identity.matching.model.IdentifierType
import com.sphereon.identity.matching.model.IdentityMatch
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresIdentityMatchStoreTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15").apply {
            withDatabaseName("authbridge_test")
            withUsername("test")
            withPassword("test")
        }
    }

    private lateinit var database: AuthBridgeDatabase
    private lateinit var store: PostgresIdentityMatchStore

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
        database = com.sphereon.portal.bridge.persistence.createTestDatabase(driver)
        store = PostgresIdentityMatchStore(database)
    }

    @AfterAll
    fun teardown() {
        postgres.stop()
    }

    @Test
    fun createAndFindById() = runTest {
        val match = IdentityMatch(
            id = Uuid.random().toString(),
            tenantId = "tenant-1",
            identifierHash = "hash-${Uuid.random()}",
            identifierType = IdentifierType.KEY,
            internalIdentityId = "identity-1",
            metadata = mapOf("source" to "test"),
            hashKeyVersion = "v1",
            createdAt = Clock.System.now(),
        )

        store.create(match)
        val found = store.findById("tenant-1", match.id)

        assertNotNull(found)
        assertEquals(match.id, found!!.id)
        assertEquals(match.identifierHash, found.identifierHash)
        assertEquals(match.identifierType, found.identifierType)
        assertEquals(match.internalIdentityId, found.internalIdentityId)
        assertEquals("test", found.metadata["source"])
    }

    @Test
    fun findByIdentifierHash() = runTest {
        val hash = "hash-${Uuid.random()}"
        val match = IdentityMatch(
            id = Uuid.random().toString(),
            tenantId = "tenant-1",
            identifierHash = hash,
            identifierType = IdentifierType.SUBJECT_ID,
            internalIdentityId = "identity-2",
            createdAt = Clock.System.now(),
        )

        store.create(match)
        val found = store.findByIdentifierHash("tenant-1", hash, IdentifierType.SUBJECT_ID)

        assertNotNull(found)
        assertEquals(match.id, found!!.id)
    }

    @Test
    fun findByInternalIdentityId() = runTest {
        val identityId = "identity-${Uuid.random()}"
        val match1 = IdentityMatch(
            id = Uuid.random().toString(),
            tenantId = "tenant-1",
            identifierHash = "hash-${Uuid.random()}",
            identifierType = IdentifierType.KEY,
            internalIdentityId = identityId,
            createdAt = Clock.System.now(),
        )
        val match2 = IdentityMatch(
            id = Uuid.random().toString(),
            tenantId = "tenant-1",
            identifierHash = "hash-${Uuid.random()}",
            identifierType = IdentifierType.EMAIL,
            internalIdentityId = identityId,
            createdAt = Clock.System.now(),
        )

        store.create(match1)
        store.create(match2)
        val found = store.findByInternalIdentityId("tenant-1", identityId)

        assertEquals(2, found.size)
    }

    @Test
    fun updateMatch() = runTest {
        val match = IdentityMatch(
            id = Uuid.random().toString(),
            tenantId = "tenant-1",
            identifierHash = "hash-${Uuid.random()}",
            identifierType = IdentifierType.KEY,
            internalIdentityId = "identity-3",
            createdAt = Clock.System.now(),
        )

        store.create(match)
        val updated = match.copy(
            internalIdentityId = "identity-3-updated",
            updatedAt = Clock.System.now(),
        )
        store.update(updated)

        val found = store.findById("tenant-1", match.id)
        assertEquals("identity-3-updated", found!!.internalIdentityId)
        assertNotNull(found.updatedAt)
    }

    @Test
    fun deleteMatch() = runTest {
        val match = IdentityMatch(
            id = Uuid.random().toString(),
            tenantId = "tenant-1",
            identifierHash = "hash-${Uuid.random()}",
            identifierType = IdentifierType.KEY,
            internalIdentityId = "identity-4",
            createdAt = Clock.System.now(),
        )

        store.create(match)
        assertTrue(store.delete("tenant-1", match.id))

        val found = store.findById("tenant-1", match.id)
        assertNull(found)
    }

    @Test
    fun tenantIsolation() = runTest {
        val hash = "hash-${Uuid.random()}"
        val match = IdentityMatch(
            id = Uuid.random().toString(),
            tenantId = "tenant-a",
            identifierHash = hash,
            identifierType = IdentifierType.KEY,
            internalIdentityId = "identity-5",
            createdAt = Clock.System.now(),
        )

        store.create(match)

        assertNotNull(store.findByIdentifierHash("tenant-a", hash, IdentifierType.KEY))
        assertNull(store.findByIdentifierHash("tenant-b", hash, IdentifierType.KEY))
    }
}
