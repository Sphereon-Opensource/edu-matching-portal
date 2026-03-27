package com.sphereon.portal.bridge.auxiliary

import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Orchestrates encryption/decryption of auxiliary data via [AuxiliaryDataStore]
 * and [ReconciliationCryptoService] (Key C).
 *
 * Instantiated manually during startup (not DI-managed) because it needs
 * a [ReconciliationCryptoService] which is session-scoped in the IDK framework.
 */
@OptIn(ExperimentalUuidApi::class)
class AuxiliaryDataServiceImpl(
    private val store: AuxiliaryDataStore,
    private val cryptoServiceProvider: () -> ReconciliationCryptoService,
) : AuxiliaryDataService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun store(
        tenantId: String,
        internalIdentityId: String,
        category: String,
        data: Map<String, JsonElement>,
        expiresAt: Instant?,
    ): AuxiliaryDataRecord {
        val plaintext = json.encodeToString(
            kotlinx.serialization.serializer<Map<String, JsonElement>>(),
            data,
        )
        val encrypted = cryptoServiceProvider().encrypt(plaintext)
        val now = Clock.System.now()

        val record = AuxiliaryDataRecord(
            id = Uuid.random().toString(),
            tenantId = tenantId,
            internalIdentityId = internalIdentityId,
            category = category,
            encryptedPayload = encrypted,
            schemaVersion = "1",
            createdAt = now,
            updatedAt = now,
            expiresAt = expiresAt,
        )
        return store.create(record)
    }

    override suspend fun getDecrypted(
        tenantId: String,
        internalIdentityId: String,
        category: String?,
    ): List<DecryptedAuxiliaryData> {
        val records = store.findByIdentity(tenantId, internalIdentityId, category)
        return records.map { record ->
            val plaintext = cryptoServiceProvider().decrypt(record.encryptedPayload)
            val data: Map<String, JsonElement> = json.decodeFromString(plaintext)
            DecryptedAuxiliaryData(
                id = record.id,
                category = record.category,
                data = data,
                schemaVersion = record.schemaVersion,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
            )
        }
    }

    override suspend fun deleteAll(tenantId: String, internalIdentityId: String): Int {
        return store.deleteByIdentity(tenantId, internalIdentityId)
    }

    override suspend fun delete(tenantId: String, id: String): Boolean {
        return store.delete(tenantId, id)
    }

    override suspend fun findExpired(tenantId: String, cutoff: Instant): List<AuxiliaryDataRecord> {
        return store.findExpired(tenantId, cutoff)
    }
}
