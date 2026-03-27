package com.sphereon.portal.bridge.migration

import com.sphereon.core.api.conf.AppConfigService
import kotlinx.datetime.Instant

/**
 * Config-driven key migration job. Runs on startup when a target-version
 * is configured that hasn't been executed yet.
 *
 * Configuration (application.yml):
 * ```yaml
 * identity:
 *   reconciliation:
 *     migration:
 *       target-version: 2
 *       operations: FULL
 *       inactive-since: "2024-01-01T00:00:00Z"
 *       batch-size: 100
 * ```
 *
 * The version is compared against the `key_migration_history` table.
 * If the target version has already been completed, the job is a no-op.
 */
class KeyMigrationJob(
    private val migrationService: KeyMigrationService,
    private val historyStore: KeyMigrationHistoryStore,
    private val configService: AppConfigService,
) {
    private val prefix = "identity.reconciliation.migration"

    suspend fun runIfNeeded() {
        val targetVersion = configService.getPropertyAsString("$prefix.target-version", null)
            ?.toIntOrNull() ?: return

        val lastCompleted = historyStore.getLastCompletedVersion()
        if (targetVersion <= lastCompleted) {
            println("[KEY-MIGRATION] Version $targetVersion already completed (last=$lastCompleted), skipping")
            return
        }

        val operationsStr = configService.getPropertyAsString("$prefix.operations", "FULL") ?: "FULL"
        val operations = operationsStr.split(",").map { MigrationMode.valueOf(it.trim().uppercase()) }

        val inactiveSince = configService.getPropertyAsString("$prefix.inactive-since", null)
            ?.let { Instant.parse(it) }

        println("[KEY-MIGRATION] Starting migration to version $targetVersion: operations=$operations")

        // Record start
        val startRecord = MigrationRecord(
            version = targetVersion,
            operations = operationsStr,
            status = MigrationStatus.RUNNING,
            startedAt = kotlinx.datetime.Clock.System.now(),
            configSnapshot = buildConfigSnapshot(),
        )
        historyStore.insert(startRecord)

        // Run migration
        val result = migrationService.run(targetVersion, operations, inactiveSince)

        // Record result
        historyStore.updateStatus(result)

        println("[AUDIT] key.migration.completed version=$targetVersion " +
                "status=${result.status} processed=${result.recordsProcessed} " +
                "failed=${result.recordsFailed} skipped=${result.recordsSkipped} " +
                "purged=${result.recordsPurged}")

        if (result.status == MigrationStatus.FAILED) {
            println("[KEY-MIGRATION] Migration FAILED: ${result.errorMessage}")
            println("[KEY-MIGRATION] Fix the issue and bump target-version to retry")
        }
    }

    private fun buildConfigSnapshot(): String {
        val cryptoPrefix = "identity.reconciliation.crypto"
        val keys = listOf(
            "holder-hmac-key-alias", "holder-key-version",
            "institution-hmac-key-alias", "institution-key-version",
            "encryption-key-alias", "encryption-key-version",
            "previous-holder-hmac-key-alias", "previous-holder-key-version",
            "previous-institution-hmac-key-alias", "previous-institution-key-version",
            "previous-encryption-key-alias", "previous-encryption-key-version",
        )
        return keys.mapNotNull { key ->
            configService.getPropertyAsString("$cryptoPrefix.$key", null)?.let { "$key=$it" }
        }.joinToString("; ")
    }
}
