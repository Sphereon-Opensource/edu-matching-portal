package com.sphereon.portal.bridge.migration

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.identity.matching.crypto.HashedIdentifier
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.model.IdentifierType
import com.sphereon.identity.matching.model.IdentityLinkBinding
import com.sphereon.identity.matching.model.IdentityMatch
import com.sphereon.identity.matching.model.PersistedAttributesEnvelope
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataServiceImpl
import com.sphereon.portal.bridge.auxiliary.PostgresAuxiliaryDataStore
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import com.sphereon.portal.bridge.persistence.PostgresIdentityLinkBindingStore
import com.sphereon.portal.bridge.persistence.PostgresIdentityMatchStore
import com.sphereon.portal.bridge.persistence.createTestDatabase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyMigrationServiceTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15").apply {
            withDatabaseName("migration_test")
            withUsername("test")
            withPassword("test")
        }
    }

    private lateinit var database: AuthBridgeDatabase
    private lateinit var matchStore: PostgresIdentityMatchStore
    private lateinit var bindingStore: PostgresIdentityLinkBindingStore
    private lateinit var historyStore: KeyMigrationHistoryStore

    /** Simulates v1 crypto: encrypt prefixes with "v1:", decrypt strips it */
    private val v1CryptoService = object : ReconciliationCryptoService {
        override suspend fun encrypt(plaintext: String) =
            EncryptedPayload(ciphertext = Base64.UrlSafe.encode("v1:$plaintext".encodeToByteArray()), keyVersion = "v1")
        override suspend fun decrypt(payload: EncryptedPayload): String {
            val decoded = Base64.UrlSafe.decode(payload.ciphertext).decodeToString()
            return decoded.removePrefix("v1:").removePrefix("v2:")
        }
        override suspend fun hashHolderKey(holderKey: String) =
            HashedIdentifier(hash = "hk1:$holderKey", keyVersion = "v1")
        override suspend fun hashExternalIdentifier(identifier: String) =
            HashedIdentifier(hash = "ek1:$identifier", keyVersion = "v1")
        override suspend fun hashHolderKeyWithPrevious(holderKey: String): HashedIdentifier? = null
        override suspend fun hashExternalIdentifierWithPrevious(identifier: String): HashedIdentifier? = null
    }

    /** Simulates v2 crypto: encrypt prefixes with "v2:", can still decrypt v1 */
    private val v2CryptoService = object : ReconciliationCryptoService {
        override suspend fun encrypt(plaintext: String) =
            EncryptedPayload(ciphertext = Base64.UrlSafe.encode("v2:$plaintext".encodeToByteArray()), keyVersion = "v2")
        override suspend fun decrypt(payload: EncryptedPayload): String {
            val decoded = Base64.UrlSafe.decode(payload.ciphertext).decodeToString()
            return decoded.removePrefix("v1:").removePrefix("v2:")
        }
        override suspend fun hashHolderKey(holderKey: String) =
            HashedIdentifier(hash = "hk2:$holderKey", keyVersion = "v2")
        override suspend fun hashExternalIdentifier(identifier: String) =
            HashedIdentifier(hash = "ek2:$identifier", keyVersion = "v2")
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
        database = createTestDatabase(ds.asJdbcDriver())
        matchStore = PostgresIdentityMatchStore(database)
        bindingStore = PostgresIdentityLinkBindingStore(database)
        historyStore = KeyMigrationHistoryStore(database)
    }

    @AfterAll
    fun teardown() {
        postgres.stop()
    }

    private fun createMigrationService(
        cryptoService: ReconciliationCryptoService = v2CryptoService,
        encryptionKeyVersion: String = "v2",
        institutionKeyVersion: String = "v2",
        previousHolderKeyVersion: String? = "v1",
    ) = KeyMigrationService(
        database = database,
        matchStore = matchStore,
        bindingStore = bindingStore,
        auxiliaryDataService = AuxiliaryDataServiceImpl(
            store = PostgresAuxiliaryDataStore(database),
            cryptoServiceProvider = { cryptoService },
        ),
        cryptoServiceProvider = { cryptoService },
        currentEncryptionKeyVersion = encryptionKeyVersion,
        currentInstitutionKeyVersion = institutionKeyVersion,
        previousHolderKeyVersion = previousHolderKeyVersion,
        batchSize = 10,
    )

    private suspend fun createTestBinding(id: String, matchId: String, identityId: String): IdentityLinkBinding {
        val now = Clock.System.now()
        matchStore.create(IdentityMatch(
            id = matchId, tenantId = "default",
            identifierHash = "hash-$matchId", identifierType = IdentifierType.KEY,
            internalIdentityId = identityId, hashKeyVersion = "v1",
            createdAt = now,
        ))

        val encrypted = v1CryptoService.encrypt("institution-$id")
        val attrsPayload = v1CryptoService.encrypt("""{"given_name":"Test"}""")
        val binding = IdentityLinkBinding(
            id = "binding-$id", tenantId = "default", matchId = matchId,
            holderIdentifierHash = "hk1:holder-$id", holderHashKeyVersion = "v1",
            institutionIdentifierHash = "ek1:institution-$id", institutionHashKeyVersion = "v1",
            encryptedInstitutionId = encrypted,
            persistedAttributesEnvelope = PersistedAttributesEnvelope(
                encrypted = attrsPayload,
                canonicalSchemaVersion = "1", materialProfileVersion = "1",
                selectorRuleVersion = "1", attributeNames = setOf("given_name"),
                updatedAt = now,
            ),
            providerId = "surf", institutionId = null, assuranceSummary = null,
            createdAt = now, updatedAt = null, lastUsedAt = null,
        )
        return bindingStore.create(binding)
    }

    @Test
    fun encryptionOnlyReEncryptsData() = runTest {
        val binding = createTestBinding("enc1", "match-enc1", "id-enc1")
        val service = createMigrationService()

        val result = service.run(100, listOf(MigrationMode.ENCRYPTION_ONLY), null)

        assertEquals(MigrationStatus.COMPLETED, result.status)
        assertTrue(result.recordsProcessed > 0)

        val updated = bindingStore.findByMatchId("default", "match-enc1")!!
        assertEquals("v2", updated.persistedAttributesEnvelope.encrypted.keyVersion)
        assertEquals("v2", updated.encryptedInstitutionId!!.keyVersion)
        // Institution hash should NOT change in ENCRYPTION_ONLY mode
        assertEquals("ek1:institution-enc1", updated.institutionIdentifierHash)
    }

    @Test
    fun fullMigrationReHashesInstitutionId() = runTest {
        val binding = createTestBinding("full1", "match-full1", "id-full1")
        val service = createMigrationService()

        val result = service.run(101, listOf(MigrationMode.FULL), null)

        assertEquals(MigrationStatus.COMPLETED, result.status)

        val updated = bindingStore.findByMatchId("default", "match-full1")!!
        assertEquals("v2", updated.persistedAttributesEnvelope.encrypted.keyVersion)
        // Institution hash SHOULD change in FULL mode
        assertTrue(updated.institutionIdentifierHash!!.startsWith("ek2:"))
        assertEquals("v2", updated.institutionHashKeyVersion)
    }

    @Test
    fun skipsAlreadyMigratedRecords() = runTest {
        // Create a binding already on v2
        val now = Clock.System.now()
        matchStore.create(IdentityMatch(
            id = "match-skip", tenantId = "default",
            identifierHash = "hash-skip", identifierType = IdentifierType.KEY,
            internalIdentityId = "id-skip", hashKeyVersion = "v2", createdAt = now,
        ))
        val v2Encrypted = v2CryptoService.encrypt("institution-skip")
        val v2Attrs = v2CryptoService.encrypt("""{"name":"Skip"}""")
        bindingStore.create(IdentityLinkBinding(
            id = "binding-skip", tenantId = "default", matchId = "match-skip",
            holderIdentifierHash = "hk2:skip", holderHashKeyVersion = "v2",
            institutionIdentifierHash = null, institutionHashKeyVersion = null,
            encryptedInstitutionId = v2Encrypted,
            persistedAttributesEnvelope = PersistedAttributesEnvelope(
                encrypted = v2Attrs, canonicalSchemaVersion = "1",
                materialProfileVersion = "1", selectorRuleVersion = "1",
                attributeNames = setOf("name"), updatedAt = now,
            ),
            providerId = "surf", institutionId = null, assuranceSummary = null,
            createdAt = now, updatedAt = null, lastUsedAt = null,
        ))

        val service = createMigrationService()
        val result = service.run(102, listOf(MigrationMode.ENCRYPTION_ONLY), null)

        assertEquals(MigrationStatus.COMPLETED, result.status)
        assertTrue(result.recordsSkipped > 0)
    }

    @Test
    fun purgeDeletesInactiveOldKeyMatches() = runTest {
        val twoYearsAgo = Instant.parse("2024-01-01T00:00:00Z")
        val now = Clock.System.now()

        // Old match (inactive)
        matchStore.create(IdentityMatch(
            id = "match-purge-old", tenantId = "default",
            identifierHash = "hash-purge-old", identifierType = IdentifierType.KEY,
            internalIdentityId = "id-purge", hashKeyVersion = "v1",
            createdAt = Instant.parse("2023-01-01T00:00:00Z"),
            lastUsedAt = Instant.parse("2023-06-01T00:00:00Z"),
        ))

        // Recent match (should NOT be purged)
        matchStore.create(IdentityMatch(
            id = "match-purge-recent", tenantId = "default",
            identifierHash = "hash-purge-recent", identifierType = IdentifierType.KEY,
            internalIdentityId = "id-purge", hashKeyVersion = "v1",
            createdAt = now, lastUsedAt = now,
        ))

        val service = createMigrationService()
        val result = service.run(103, listOf(MigrationMode.PURGE_OLD_HOLDER_KEYS), twoYearsAgo)

        assertEquals(MigrationStatus.COMPLETED, result.status)
        assertTrue(result.recordsPurged >= 1, "Expected at least 1 purged record, got ${result.recordsPurged}")

        // Old match deleted
        assertNull(matchStore.findById("default", "match-purge-old"))
        // Recent match still exists
        assertNotNull(matchStore.findById("default", "match-purge-recent"))
    }

    @Test
    fun migrationHistoryPreventsRerun() = runTest {
        historyStore.insert(MigrationRecord(
            version = 200,
            operations = "FULL",
            status = MigrationStatus.COMPLETED,
            startedAt = Clock.System.now(),
            completedAt = Clock.System.now(),
        ))

        val lastCompleted = historyStore.getLastCompletedVersion()
        assertTrue(lastCompleted >= 200)
    }
}
