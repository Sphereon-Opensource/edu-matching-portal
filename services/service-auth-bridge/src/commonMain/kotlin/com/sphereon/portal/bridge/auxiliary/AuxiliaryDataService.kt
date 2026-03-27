package com.sphereon.portal.bridge.auxiliary

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement

/**
 * Orchestrates encryption/decryption of auxiliary data.
 * Does NOT expose raw [com.sphereon.identity.matching.crypto.EncryptedPayload] to callers.
 *
 * Uses [AuxiliaryDataStore] for persistence and
 * [com.sphereon.identity.matching.crypto.ReconciliationCryptoService] (Key C) for encryption.
 */
interface AuxiliaryDataService {

    /** Store arbitrary data encrypted with Key C. */
    suspend fun store(
        tenantId: String,
        internalIdentityId: String,
        category: String,
        data: Map<String, JsonElement>,
        expiresAt: Instant? = null,
    ): AuxiliaryDataRecord

    /** Retrieve and decrypt all auxiliary data for an identity, optionally filtered by category. */
    suspend fun getDecrypted(
        tenantId: String,
        internalIdentityId: String,
        category: String? = null,
    ): List<DecryptedAuxiliaryData>

    /** Delete all auxiliary data for an identity (GDPR erasure). Returns count deleted. */
    suspend fun deleteAll(tenantId: String, internalIdentityId: String): Int

    /** Delete a single record by ID. */
    suspend fun delete(tenantId: String, id: String): Boolean

    /** Find expired auxiliary data records for cleanup. */
    suspend fun findExpired(tenantId: String, cutoff: Instant): List<AuxiliaryDataRecord>
}
