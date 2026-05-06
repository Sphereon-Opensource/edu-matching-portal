package com.sphereon.portal.bridge.migration

import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.model.PersistedAttributesEnvelope
import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataService
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

/**
 * Batch key migration service.
 *
 * Processes identity bindings, matches, and auxiliary data in configurable batches
 * to re-encrypt/re-hash data after a key rotation. Each record is processed independently
 * so a single failure does not abort the entire migration.
 */
class KeyMigrationService(
    private val database: AuthBridgeDatabase,
    private val matchStore: IdentityMatchStore,
    private val bindingStore: IdentityLinkBindingStore,
    private val auxiliaryDataService: AuxiliaryDataService,
    private val cryptoServiceProvider: () -> ReconciliationCryptoService,
    private val currentEncryptionKeyVersion: String,
    private val currentInstitutionKeyVersion: String,
    private val previousHolderKeyVersion: String?,
    private val batchSize: Int = 100,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tenantId = "default"

    suspend fun run(
        version: Int,
        operations: List<MigrationMode>,
        inactiveSince: Instant?,
    ): MigrationRecord {
        val record = MigrationRecord(
            version = version,
            operations = operations.joinToString(",") { it.name },
            status = MigrationStatus.RUNNING,
            startedAt = Clock.System.now(),
        )

        var processed = 0
        var failed = 0
        var skipped = 0
        var purged = 0

        try {
            val crypto = cryptoServiceProvider()

            for (mode in operations) {
                when (mode) {
                    MigrationMode.ENCRYPTION_ONLY, MigrationMode.FULL -> {
                        val reHashInstitution = mode == MigrationMode.FULL

                        // Phase 1: Re-encrypt bindings
                        val totalBindings = database.authBridgeQueries.countAllBindings(tenantId).executeAsOne()
                        println("[KEY-MIGRATION] Phase 1: $totalBindings bindings to process")

                        var offset = 0L
                        while (offset < totalBindings) {
                            val bindings = database.authBridgeQueries
                                .findAllBindingsPaged(tenantId, batchSize.toLong(), offset)
                                .executeAsList()
                            if (bindings.isEmpty()) break

                            for (row in bindings) {
                                try {
                                    val binding = bindingStore.findByMatchId(tenantId, row.match_id)
                                    if (binding == null) { skipped++; continue }

                                    // Skip if already on current encryption key version
                                    if (binding.persistedAttributesEnvelope.encrypted.keyVersion == currentEncryptionKeyVersion) {
                                        skipped++; continue
                                    }

                                    // Decrypt with old key, re-encrypt with new key
                                    val plainAttrs = crypto.decrypt(binding.persistedAttributesEnvelope.encrypted)
                                    val newAttrsPayload = crypto.encrypt(plainAttrs)

                                    var updatedBinding = binding.copy(
                                        persistedAttributesEnvelope = binding.persistedAttributesEnvelope.copy(
                                            encrypted = newAttrsPayload
                                        ),
                                        updatedAt = Clock.System.now(),
                                    )

                                    // Re-encrypt institution ID if present
                                    if (binding.encryptedInstitutionId != null) {
                                        val plainInstitutionId = crypto.decrypt(binding.encryptedInstitutionId!!)
                                        val newInstitutionPayload = crypto.encrypt(plainInstitutionId)
                                        updatedBinding = updatedBinding.copy(
                                            encryptedInstitutionId = newInstitutionPayload,
                                        )

                                        // Re-hash institution identifier if FULL mode
                                        if (reHashInstitution && plainInstitutionId.isNotBlank()) {
                                            val newHash = crypto.hashExternalIdentifier(plainInstitutionId)
                                            updatedBinding = updatedBinding.copy(
                                                institutionIdentifierHash = newHash.hash,
                                                institutionHashKeyVersion = newHash.keyVersion,
                                            )
                                        }
                                    }

                                    bindingStore.update(updatedBinding)
                                    processed++
                                    println("[AUDIT] key.migration.binding id=${binding.id} version=${currentEncryptionKeyVersion}")
                                } catch (e: Exception) {
                                    failed++
                                    println("[KEY-MIGRATION] ERROR binding ${row.id}: ${e.message}")
                                }
                            }
                            offset += batchSize
                        }

                        // Phase 2: Re-hash non-KEY matches (FULL mode only)
                        if (reHashInstitution) {
                            val totalMatches = database.authBridgeQueries.countNonKeyMatches(tenantId).executeAsOne()
                            println("[KEY-MIGRATION] Phase 2: $totalMatches institution matches to process")

                            offset = 0L
                            while (offset < totalMatches) {
                                val matches = database.authBridgeQueries
                                    .findNonKeyMatchesPaged(tenantId, batchSize.toLong(), offset)
                                    .executeAsList()
                                if (matches.isEmpty()) break

                                for (row in matches) {
                                    try {
                                        val match = matchStore.findById(tenantId, row.id) ?: continue

                                        if (match.hashKeyVersion == currentInstitutionKeyVersion) {
                                            skipped++; continue
                                        }

                                        // Find binding to get plaintext institution ID
                                        val binding = bindingStore.findByMatchId(tenantId, match.id)
                                        if (binding?.encryptedInstitutionId == null) { skipped++; continue }

                                        val plainId = crypto.decrypt(binding.encryptedInstitutionId!!)
                                        val newHash = crypto.hashExternalIdentifier(plainId)
                                        matchStore.update(match.copy(
                                            identifierHash = newHash.hash,
                                            hashKeyVersion = newHash.keyVersion,
                                            updatedAt = Clock.System.now(),
                                        ))
                                        processed++
                                    } catch (e: Exception) {
                                        failed++
                                        println("[KEY-MIGRATION] ERROR match ${row.id}: ${e.message}")
                                    }
                                }
                                offset += batchSize
                            }
                        }

                        // Phase 3: Re-encrypt auxiliary data
                        val totalAux = database.authBridgeQueries.countAllAux(tenantId).executeAsOne()
                        println("[KEY-MIGRATION] Phase 3: $totalAux auxiliary records to process")

                        offset = 0L
                        while (offset < totalAux) {
                            val auxRecords = database.authBridgeQueries
                                .findAllAuxPaged(tenantId, batchSize.toLong(), offset)
                                .executeAsList()
                            if (auxRecords.isEmpty()) break

                            for (row in auxRecords) {
                                try {
                                    if (row.key_version == currentEncryptionKeyVersion) {
                                        skipped++; continue
                                    }

                                    val oldPayload = EncryptedPayload(ciphertext = row.encrypted_data, keyVersion = row.key_version)
                                    val plaintext = crypto.decrypt(oldPayload)
                                    val newPayload = crypto.encrypt(plaintext)

                                    database.authBridgeQueries.updateAux(
                                        tenantId = tenantId,
                                        id = row.id,
                                        encryptedData = newPayload.ciphertext,
                                        keyVersion = newPayload.keyVersion,
                                        schemaVersion = row.schema_version,
                                        updatedAt = Clock.System.now(),
                                        expiresAt = row.expires_at,
                                    )
                                    processed++
                                } catch (e: Exception) {
                                    failed++
                                    println("[KEY-MIGRATION] ERROR aux ${row.id}: ${e.message}")
                                }
                            }
                            offset += batchSize
                        }
                    }

                    MigrationMode.PURGE_OLD_HOLDER_KEYS -> {
                        val oldVersion = previousHolderKeyVersion
                        if (oldVersion == null) {
                            println("[KEY-MIGRATION] PURGE skipped: no previous-holder-key-version configured")
                            continue
                        }
                        val cutoff = inactiveSince
                        if (cutoff == null) {
                            println("[KEY-MIGRATION] PURGE skipped: inactive-since not set")
                            continue
                        }

                        val count = database.authBridgeQueries
                            .countInactiveOldKeyMatches(tenantId, oldVersion, cutoff)
                            .executeAsOne()
                        println("[KEY-MIGRATION] Phase 4: $count inactive old holder key matches to purge (before $cutoff)")

                        val matches = database.authBridgeQueries
                            .findInactiveOldKeyMatches(tenantId, oldVersion, cutoff)
                            .executeAsList()

                        for (row in matches) {
                            try {
                                // Delete binding first
                                bindingStore.findByMatchId(tenantId, row.id)?.let { binding ->
                                    bindingStore.delete(tenantId, binding.id)
                                }
                                // Delete match
                                matchStore.delete(tenantId, row.id)
                                purged++
                                println("[AUDIT] key.migration.purged matchId=${row.id} lastUsed=${row.last_used_at}")
                            } catch (e: Exception) {
                                failed++
                                println("[KEY-MIGRATION] ERROR purge ${row.id}: ${e.message}")
                            }
                        }
                    }
                }
            }

            return record.copy(
                status = MigrationStatus.COMPLETED,
                recordsProcessed = processed,
                recordsFailed = failed,
                recordsSkipped = skipped,
                recordsPurged = purged,
                completedAt = Clock.System.now(),
            )
        } catch (e: Exception) {
            println("[KEY-MIGRATION] FATAL: ${e.message}")
            return record.copy(
                status = MigrationStatus.FAILED,
                recordsProcessed = processed,
                recordsFailed = failed,
                recordsSkipped = skipped,
                recordsPurged = purged,
                completedAt = Clock.System.now(),
                errorMessage = e.message,
            )
        }
    }
}
