package com.sphereon.portal.bridge.auxiliary

import kotlinx.datetime.Instant

/**
 * Store abstraction for [AuxiliaryDataRecord] persistence.
 * Implementations handle raw encrypted records — encryption/decryption
 * is orchestrated by [AuxiliaryDataService].
 */
interface AuxiliaryDataStore {

    suspend fun findByIdentity(
        tenantId: String,
        internalIdentityId: String,
        category: String? = null,
    ): List<AuxiliaryDataRecord>

    suspend fun findById(tenantId: String, id: String): AuxiliaryDataRecord?

    suspend fun create(record: AuxiliaryDataRecord): AuxiliaryDataRecord

    suspend fun update(record: AuxiliaryDataRecord): AuxiliaryDataRecord

    suspend fun delete(tenantId: String, id: String): Boolean

    suspend fun deleteByIdentity(tenantId: String, internalIdentityId: String): Int

    suspend fun findExpired(tenantId: String, cutoff: Instant): List<AuxiliaryDataRecord>
}
