package com.sphereon.portal.bridge.auxiliary

import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.datetime.Instant
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AuxiliaryDataStore>())
class PostgresAuxiliaryDataStore(
    private val database: AuthBridgeDatabase,
) : AuxiliaryDataStore {

    override suspend fun findByIdentity(
        tenantId: String,
        internalIdentityId: String,
        category: String?,
    ): List<AuxiliaryDataRecord> {
        val rows = if (category != null) {
            val row = database.authBridgeQueries
                .findAuxByIdentityAndCategory(tenantId, internalIdentityId, category)
                .executeAsOneOrNull()
            if (row != null) listOf(row) else emptyList()
        } else {
            database.authBridgeQueries
                .findAuxByIdentity(tenantId, internalIdentityId)
                .executeAsList()
        }
        return rows.map { it.toDomain() }
    }

    override suspend fun findById(tenantId: String, id: String): AuxiliaryDataRecord? {
        return database.authBridgeQueries
            .findAuxById(tenantId, id)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun create(record: AuxiliaryDataRecord): AuxiliaryDataRecord {
        database.authBridgeQueries.upsertAux(
            id = record.id,
            tenantId = record.tenantId,
            identityId = record.internalIdentityId,
            category = record.category,
            encryptedData = record.encryptedPayload.ciphertext,
            keyVersion = record.encryptedPayload.keyVersion,
            schemaVersion = record.schemaVersion,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
            expiresAt = record.expiresAt,
        )
        return record
    }

    override suspend fun update(record: AuxiliaryDataRecord): AuxiliaryDataRecord {
        database.authBridgeQueries.updateAux(
            id = record.id,
            tenantId = record.tenantId,
            encryptedData = record.encryptedPayload.ciphertext,
            keyVersion = record.encryptedPayload.keyVersion,
            schemaVersion = record.schemaVersion,
            updatedAt = record.updatedAt,
            expiresAt = record.expiresAt,
        )
        return record
    }

    override suspend fun delete(tenantId: String, id: String): Boolean {
        val exists = database.authBridgeQueries.findAuxById(tenantId, id).executeAsOneOrNull() != null
        if (exists) database.authBridgeQueries.deleteAuxById(tenantId, id)
        return exists
    }

    override suspend fun deleteByIdentity(tenantId: String, internalIdentityId: String): Int {
        val count = database.authBridgeQueries
            .countAuxByIdentity(tenantId, internalIdentityId)
            .executeAsOne()
            .toInt()
        database.authBridgeQueries.deleteAuxByIdentity(tenantId, internalIdentityId)
        return count
    }

    override suspend fun findExpired(tenantId: String, cutoff: Instant): List<AuxiliaryDataRecord> {
        return database.authBridgeQueries
            .findExpiredAux(tenantId, cutoff)
            .executeAsList()
            .map { row ->
                @Suppress("USELESS_CAST")
                (row as com.sphereon.portal.bridge.db.Auxiliary_data).toDomain()
            }
    }

    private fun com.sphereon.portal.bridge.db.Auxiliary_data.toDomain(): AuxiliaryDataRecord {
        return AuxiliaryDataRecord(
            id = id,
            tenantId = tenant_id,
            internalIdentityId = identity_id,
            category = category,
            encryptedPayload = EncryptedPayload(ciphertext = encrypted_data, keyVersion = key_version),
            schemaVersion = schema_version,
            createdAt = created_at,
            updatedAt = updated_at,
            expiresAt = expires_at,
        )
    }
}
