package com.sphereon.portal.bridge.persistence

import com.sphereon.identity.matching.model.IdentifierType
import com.sphereon.identity.matching.model.IdentityMatch
import com.sphereon.identity.matching.impl.store.InMemoryIdentityMatchStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(
    AppScope::class,
    binding = binding<IdentityMatchStore>(),
    replaces = [InMemoryIdentityMatchStore::class]
)
class PostgresIdentityMatchStore(
    private val database: AuthBridgeDatabase,
) : IdentityMatchStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findByIdentifierHash(
        tenantId: String,
        identifierHash: String,
        identifierType: IdentifierType,
    ): IdentityMatch? {
        return database.authBridgeQueries
            .findMatchByIdentifierHash(tenantId, identifierHash, identifierType.value)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun findById(tenantId: String, matchId: String): IdentityMatch? {
        return database.authBridgeQueries
            .findMatchById(tenantId, matchId)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun findByInternalIdentityId(
        tenantId: String,
        internalIdentityId: String,
    ): List<IdentityMatch> {
        return database.authBridgeQueries
            .findMatchesByInternalIdentityId(tenantId, internalIdentityId)
            .executeAsList()
            .map { it.toDomain() }
    }

    override suspend fun create(match: IdentityMatch): IdentityMatch {
        val metadataStr = if (match.metadata.isNotEmpty()) {
            json.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), match.metadata)
        } else null

        database.authBridgeQueries.insertMatch(
            id = match.id,
            tenantId = match.tenantId,
            identifierHash = match.identifierHash,
            identifierType = match.identifierType.value,
            internalIdentityId = match.internalIdentityId,
            hashKeyVersion = match.hashKeyVersion,
            metadataJson = metadataStr,
            createdAt = match.createdAt,
            updatedAt = match.updatedAt,
            lastUsedAt = match.lastUsedAt,
        )
        return match
    }

    override suspend fun update(match: IdentityMatch): IdentityMatch {
        val metadataStr = if (match.metadata.isNotEmpty()) {
            json.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), match.metadata)
        } else null

        database.authBridgeQueries.updateMatch(
            matchId = match.id,
            tenantId = match.tenantId,
            identifierHash = match.identifierHash,
            identifierType = match.identifierType.value,
            internalIdentityId = match.internalIdentityId,
            hashKeyVersion = match.hashKeyVersion,
            metadataJson = metadataStr,
            updatedAt = match.updatedAt,
            lastUsedAt = match.lastUsedAt,
        )
        return match
    }

    override suspend fun delete(tenantId: String, matchId: String): Boolean {
        database.authBridgeQueries.deleteMatch(tenantId, matchId)
        return true
    }

    private fun com.sphereon.portal.bridge.db.Identity_match.toDomain(): IdentityMatch {
        val metadata: Map<String, String> = if (metadata_json != null) {
            json.decodeFromString(metadata_json)
        } else emptyMap()

        return IdentityMatch(
            id = id,
            tenantId = tenant_id,
            identifierHash = identifier_hash,
            identifierType = IdentifierType(identifier_type),
            internalIdentityId = internal_identity_id,
            hashKeyVersion = hash_key_version,
            metadata = metadata,
            createdAt = created_at,
            updatedAt = updated_at,
            lastUsedAt = last_used_at,
        )
    }
}
