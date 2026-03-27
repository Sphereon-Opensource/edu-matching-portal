package com.sphereon.portal.bridge.retention

import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Background job that hard-deletes soft-deleted records past the retention period.
 *
 * Per architecture §7.2 (Art. 17): "Erasure uses soft-delete with a deleted_at
 * timestamp followed by hard-delete after the retention period."
 *
 * Default retention: 30 days after soft-delete before hard-delete.
 */
class SoftDeletePurgeJob(
    private val database: AuthBridgeDatabase,
    private val retentionDays: Long = 30,
    private val intervalHours: Long = 24,
) {
    private var job: Job? = null

    fun start() {
        job = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            while (isActive) {
                try {
                    purge()
                } catch (e: Exception) {
                    println("[PURGE] Error: ${e.message}")
                }
                delay(intervalHours.hours)
            }
        }
        println("[PURGE] Soft-delete purge started (retention=${retentionDays}d, interval=${intervalHours}h)")
    }

    private fun purge() {
        val cutoff = Clock.System.now() - retentionDays.days

        val bindings = database.authBridgeQueries.findSoftDeletedBindingsPastRetention(cutoff).executeAsList()
        for (binding in bindings) {
            database.authBridgeQueries.hardDeleteBinding(binding.tenant_id, binding.id)
        }

        val matches = database.authBridgeQueries.findSoftDeletedMatchesPastRetention(cutoff).executeAsList()
        for (match in matches) {
            database.authBridgeQueries.hardDeleteMatch(match.tenant_id, match.id)
        }

        if (bindings.isNotEmpty() || matches.isNotEmpty()) {
            println("[PURGE] Hard-deleted ${bindings.size} bindings and ${matches.size} matches past retention ($cutoff)")
        }
    }

    fun stop() {
        job?.cancel()
    }
}
