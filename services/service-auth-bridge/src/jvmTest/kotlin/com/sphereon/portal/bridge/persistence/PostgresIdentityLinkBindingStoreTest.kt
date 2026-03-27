package com.sphereon.portal.bridge.persistence

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.identity.matching.model.AssuranceSummary
import com.sphereon.identity.matching.model.IdentityLinkBinding
import com.sphereon.identity.matching.model.PersistedAttributesEnvelope
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
class PostgresIdentityLinkBindingStoreTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15").apply {
            withDatabaseName("authbridge_test")
            withUsername("test")
            withPassword("test")
        }
    }

    private lateinit var database: AuthBridgeDatabase
    private lateinit var store: PostgresIdentityLinkBindingStore

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
        store = PostgresIdentityLinkBindingStore(database)
    }

    @AfterAll
    fun teardown() {
        postgres.stop()
    }

    private fun createBinding(
        matchId: String = Uuid.random().toString(),
        holderHash: String = "holder-${Uuid.random()}",
    ): IdentityLinkBinding {
        val now = Clock.System.now()
        return IdentityLinkBinding(
            id = Uuid.random().toString(),
            tenantId = "tenant-1",
            matchId = matchId,
            holderIdentifierHash = holderHash,
            holderHashKeyVersion = "v1",
            institutionIdentifierHash = "inst-hash-1",
            institutionHashKeyVersion = "v1",
            encryptedInstitutionId = EncryptedPayload(ciphertext = "encrypted-inst-id", keyVersion = "v1"),
            persistedAttributesEnvelope = PersistedAttributesEnvelope(
                encrypted = EncryptedPayload(ciphertext = "encrypted-claims", keyVersion = "v1"),
                canonicalSchemaVersion = "1.0",
                materialProfileVersion = "1",
                selectorRuleVersion = "2026-03-17",
                attributeNames = setOf("given_name", "family_name", "email"),
                materialFingerprints = setOf("fp-1"),
                updatedAt = now,
            ),
            providerId = "surf",
            institutionId = null,
            canonicalSchemaVersion = "1.0",
            materialProfileVersion = "1",
            selectorRuleVersion = "2026-03-17",
            persistedAttributeNames = setOf("given_name", "family_name"),
            materialFingerprints = setOf("fp-1"),
            assuranceSummary = AssuranceSummary(
                walletAssuranceLevel = "high",
                oidcAcr = "urn:sphereon:oid4vp:vp+idv",
                oidcAmr = listOf("vp", "idv"),
            ),
            createdAt = now,
            updatedAt = null,
            lastUsedAt = now,
        )
    }

    @Test
    fun createAndFindByMatchId() = runTest {
        val binding = createBinding()
        store.create(binding)

        val found = store.findByMatchId("tenant-1", binding.matchId)
        assertNotNull(found)
        assertEquals(binding.id, found!!.id)
        assertEquals(binding.holderIdentifierHash, found.holderIdentifierHash)
        assertEquals("encrypted-inst-id", found.encryptedInstitutionId?.ciphertext)
        assertNotNull(found.assuranceSummary)
        assertEquals("high", found.assuranceSummary!!.walletAssuranceLevel)
    }

    @Test
    fun findByHolderHash() = runTest {
        val holderHash = "holder-${Uuid.random()}"
        val binding = createBinding(holderHash = holderHash)
        store.create(binding)

        val found = store.findByHolderHash("tenant-1", holderHash)
        assertNotNull(found)
        assertEquals(binding.id, found!!.id)
    }

    @Test
    fun updateBinding() = runTest {
        val binding = createBinding()
        store.create(binding)

        val updated = binding.copy(
            updatedAt = Clock.System.now(),
            institutionId = "updated-label",
        )
        store.update(updated)

        val found = store.findByMatchId("tenant-1", binding.matchId)
        assertEquals("updated-label", found!!.institutionId)
        assertNotNull(found.updatedAt)
    }

    @Test
    fun deleteBinding() = runTest {
        val binding = createBinding()
        store.create(binding)

        assertTrue(store.delete("tenant-1", binding.id))
        assertNull(store.findByMatchId("tenant-1", binding.matchId))
    }

    @Test
    fun persistedAttributesEnvelopeRoundTrip() = runTest {
        val binding = createBinding()
        store.create(binding)

        val found = store.findByMatchId("tenant-1", binding.matchId)!!
        val envelope = found.persistedAttributesEnvelope
        assertEquals("encrypted-claims", envelope.encrypted.ciphertext)
        assertEquals("1.0", envelope.canonicalSchemaVersion)
        assertEquals(setOf("given_name", "family_name", "email"), envelope.attributeNames)
        assertEquals(setOf("fp-1"), envelope.materialFingerprints)
    }
}
