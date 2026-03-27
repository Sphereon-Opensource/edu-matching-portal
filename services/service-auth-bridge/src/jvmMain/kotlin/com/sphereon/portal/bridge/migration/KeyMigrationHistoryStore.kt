package com.sphereon.portal.bridge.migration

import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.datetime.Instant

class KeyMigrationHistoryStore(private val database: AuthBridgeDatabase) {

    fun getLastCompletedVersion(): Int {
        return database.authBridgeQueries.getLastCompletedVersion().executeAsOne()
    }

    fun insert(record: MigrationRecord) {
        database.authBridgeQueries.insertMigrationRecord(
            version = record.version,
            operations = record.operations,
            status = record.status.name,
            recordsProcessed = record.recordsProcessed,
            recordsFailed = record.recordsFailed,
            recordsSkipped = record.recordsSkipped,
            recordsPurged = record.recordsPurged,
            startedAt = record.startedAt,
            completedAt = record.completedAt,
            errorMessage = record.errorMessage,
            configSnapshot = record.configSnapshot,
        )
    }

    fun updateStatus(record: MigrationRecord) {
        database.authBridgeQueries.updateMigrationStatus(
            version = record.version,
            status = record.status.name,
            recordsProcessed = record.recordsProcessed,
            recordsFailed = record.recordsFailed,
            recordsSkipped = record.recordsSkipped,
            recordsPurged = record.recordsPurged,
            completedAt = record.completedAt,
            errorMessage = record.errorMessage,
        )
    }
}

data class MigrationRecord(
    val version: Int,
    val operations: String,
    val status: MigrationStatus,
    val recordsProcessed: Int = 0,
    val recordsFailed: Int = 0,
    val recordsSkipped: Int = 0,
    val recordsPurged: Int = 0,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val errorMessage: String? = null,
    val configSnapshot: String? = null,
)

enum class MigrationStatus {
    RUNNING, COMPLETED, FAILED
}

enum class MigrationMode {
    ENCRYPTION_ONLY,
    FULL,
    PURGE_OLD_HOLDER_KEYS,
}
