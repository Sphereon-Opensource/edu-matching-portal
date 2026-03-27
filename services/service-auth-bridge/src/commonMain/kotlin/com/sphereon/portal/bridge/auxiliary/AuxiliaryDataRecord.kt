package com.sphereon.portal.bridge.auxiliary

import com.sphereon.identity.matching.crypto.EncryptedPayload
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A single auxiliary data record linked to an internal identity.
 * The [encryptedPayload] is AES-256-GCM encrypted via Key C.
 */
@Serializable
data class AuxiliaryDataRecord(
    val id: String,
    val tenantId: String,
    val internalIdentityId: String,
    val category: String,
    val encryptedPayload: EncryptedPayload,
    val schemaVersion: String = "1",
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant? = null,
)

/**
 * Decrypted view of an [AuxiliaryDataRecord], returned by [AuxiliaryDataService].
 * Never exposes the raw [EncryptedPayload].
 */
@Serializable
data class DecryptedAuxiliaryData(
    val id: String,
    val category: String,
    val data: Map<String, kotlinx.serialization.json.JsonElement>,
    val schemaVersion: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
