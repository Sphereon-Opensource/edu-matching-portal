package com.sphereon.portal.bridge.persistence

import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.identity.matching.model.AssuranceSummary
import com.sphereon.identity.matching.model.IdentityLinkBinding
import com.sphereon.identity.matching.model.PersistedAttributesEnvelope
import com.sphereon.identity.matching.impl.store.IdentityLinkBindingStoreModule
import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.datetime.Instant
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
    binding = binding<IdentityLinkBindingStore>(),
    replaces = [IdentityLinkBindingStoreModule::class]
)
class PostgresIdentityLinkBindingStore(
    private val database: AuthBridgeDatabase,
) : IdentityLinkBindingStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(binding: IdentityLinkBinding): IdentityLinkBinding {
        database.authBridgeQueries.insertBinding(
            id = binding.id,
            tenantId = binding.tenantId,
            matchId = binding.matchId,
            holderIdentifierHash = binding.holderIdentifierHash,
            holderHashKeyVersion = binding.holderHashKeyVersion,
            institutionIdentifierHash = binding.institutionIdentifierHash,
            institutionHashKeyVersion = binding.institutionHashKeyVersion,
            encryptedInstitutionId = binding.encryptedInstitutionId?.ciphertext,
            encryptedInstitutionIdKeyVersion = binding.encryptedInstitutionId?.keyVersion,
            persistedAttributesEnvelope = json.encodeToString(PersistedAttributesEnvelope.serializer(), binding.persistedAttributesEnvelope),
            providerId = binding.providerId,
            institutionIdLabel = binding.institutionId,
            canonicalSchemaVersion = binding.canonicalSchemaVersion,
            materialProfileVersion = binding.materialProfileVersion,
            selectorRuleVersion = binding.selectorRuleVersion,
            persistedAttributeNames = binding.persistedAttributeNames?.let { json.encodeToString(kotlinx.serialization.serializer<Set<String>>(), it) },
            materialFingerprints = binding.materialFingerprints?.let { json.encodeToString(kotlinx.serialization.serializer<Set<String>>(), it) },
            assuranceSummary = binding.assuranceSummary?.let { json.encodeToString(AssuranceSummary.serializer(), it) },
            createdAt = binding.createdAt,
            updatedAt = binding.updatedAt,
            lastUsedAt = binding.lastUsedAt,
            reconcileTime = binding.createdAt, // Reconciliation happened at binding creation
        )
        return binding
    }

    override suspend fun findByMatchId(tenantId: String, matchId: String): IdentityLinkBinding? {
        return database.authBridgeQueries
            .findBindingByMatchId(tenantId, matchId)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun findByHolderHash(tenantId: String, holderHash: String): IdentityLinkBinding? {
        return database.authBridgeQueries
            .findBindingByHolderHash(tenantId, holderHash)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun update(binding: IdentityLinkBinding): IdentityLinkBinding {
        database.authBridgeQueries.updateBinding(
            bindingId = binding.id,
            tenantId = binding.tenantId,
            matchId = binding.matchId,
            holderIdentifierHash = binding.holderIdentifierHash,
            holderHashKeyVersion = binding.holderHashKeyVersion,
            institutionIdentifierHash = binding.institutionIdentifierHash,
            institutionHashKeyVersion = binding.institutionHashKeyVersion,
            encryptedInstitutionId = binding.encryptedInstitutionId?.ciphertext,
            encryptedInstitutionIdKeyVersion = binding.encryptedInstitutionId?.keyVersion,
            persistedAttributesEnvelope = json.encodeToString(PersistedAttributesEnvelope.serializer(), binding.persistedAttributesEnvelope),
            providerId = binding.providerId,
            institutionIdLabel = binding.institutionId,
            canonicalSchemaVersion = binding.canonicalSchemaVersion,
            materialProfileVersion = binding.materialProfileVersion,
            selectorRuleVersion = binding.selectorRuleVersion,
            persistedAttributeNames = binding.persistedAttributeNames?.let { json.encodeToString(kotlinx.serialization.serializer<Set<String>>(), it) },
            materialFingerprints = binding.materialFingerprints?.let { json.encodeToString(kotlinx.serialization.serializer<Set<String>>(), it) },
            assuranceSummary = binding.assuranceSummary?.let { json.encodeToString(AssuranceSummary.serializer(), it) },
            updatedAt = binding.updatedAt,
            lastUsedAt = binding.lastUsedAt,
        )
        return binding
    }

    override suspend fun delete(tenantId: String, bindingId: String): Boolean {
        database.authBridgeQueries.deleteBinding(tenantId, bindingId)
        return true
    }

    override suspend fun findExpired(tenantId: String, inactiveSince: Instant): List<IdentityLinkBinding> {
        return database.authBridgeQueries
            .findExpiredBindings(tenantId, inactiveSince)
            .executeAsList()
            .map { row ->
                @Suppress("USELESS_CAST")
                (row as com.sphereon.portal.bridge.db.Identity_link_binding).toDomain()
            }
    }

    private fun com.sphereon.portal.bridge.db.Identity_link_binding.toDomain(): IdentityLinkBinding {
        val encryptedInstId = if (encrypted_institution_id != null && encrypted_institution_id_key_version != null) {
            EncryptedPayload(ciphertext = encrypted_institution_id, keyVersion = encrypted_institution_id_key_version)
        } else null

        return IdentityLinkBinding(
            id = id,
            tenantId = tenant_id,
            matchId = match_id,
            holderIdentifierHash = holder_identifier_hash,
            holderHashKeyVersion = holder_hash_key_version,
            institutionIdentifierHash = institution_identifier_hash,
            institutionHashKeyVersion = institution_hash_key_version,
            encryptedInstitutionId = encryptedInstId,
            persistedAttributesEnvelope = json.decodeFromString(PersistedAttributesEnvelope.serializer(), persisted_attributes_envelope),
            providerId = provider_id,
            institutionId = institution_id_label,
            canonicalSchemaVersion = canonical_schema_version,
            materialProfileVersion = material_profile_version,
            selectorRuleVersion = selector_rule_version,
            persistedAttributeNames = persisted_attribute_names?.let { json.decodeFromString<Set<String>>(it) },
            materialFingerprints = material_fingerprints?.let { json.decodeFromString<Set<String>>(it) },
            assuranceSummary = assurance_summary?.let { json.decodeFromString(AssuranceSummary.serializer(), it) },
            createdAt = created_at,
            updatedAt = updated_at,
            lastUsedAt = last_used_at,
        )
    }
}
