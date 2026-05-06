package com.sphereon.portal.bridge.retention

import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.portal.bridge.audit.AuditEventStore
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.coroutines.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Background job that removes identity mappings for inactive users.
 *
 * Per architecture §7.1 (Storage Limitation): "Mappings for inactive users
 * (no login for 2 years) are automatically removed."
 *
 * Runs periodically and soft-deletes bindings + matches where last_used_at
 * is older than the configured retention period.
 */
class InactiveBindingCleanupJob(
    private val database: AuthBridgeDatabase,
    private val matchStore: IdentityMatchStore,
    private val bindingStore: IdentityLinkBindingStore,
    private val auditEventStore: AuditEventStore,
    private val tenantId: String = "default",
    private val retentionDays: Long = 730, // 2 years
    private val intervalMinutes: Long = 60, // run hourly
) {
    private var job: Job? = null

    fun start() {
        job = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            while (isActive) {
                try {
                    cleanup()
                } catch (e: Exception) {
                    println("[RETENTION] Cleanup error: ${e.message}")
                }
                delay(intervalMinutes.minutes)
            }
        }
        println("[RETENTION] Inactive binding cleanup started (retention=${retentionDays}d, interval=${intervalMinutes}min)")
    }

    private suspend fun cleanup() {
        val cutoff = Clock.System.now() - retentionDays.days
        val count = database.authBridgeQueries.countInactiveBindings(tenantId, cutoff).executeAsOne()
        if (count <= 0) return

        println("[RETENTION] Found $count inactive bindings older than $cutoff")

        val bindings = database.authBridgeQueries.findInactiveBindings(tenantId, cutoff).executeAsList()
        var cleaned = 0

        for (binding in bindings) {
            try {
                // Soft-delete the binding
                database.authBridgeQueries.softDeleteBinding(
                    tenantId = tenantId,
                    bindingId = binding.id,
                    deletedAt = Clock.System.now(),
                    deletionReason = "inactive_retention",
                )

                // Soft-delete associated match
                database.authBridgeQueries.softDeleteMatch(
                    tenantId = tenantId,
                    matchId = binding.match_id,
                    deletedAt = Clock.System.now(),
                    deletionReason = "inactive_retention",
                )

                auditEventStore.record(
                    eventType = "retention.inactive.cleaned",
                    detail = "bindingId=${binding.id}, matchId=${binding.match_id}",
                )
                cleaned++
            } catch (e: Exception) {
                println("[RETENTION] Error cleaning binding ${binding.id}: ${e.message}")
            }
        }

        println("[RETENTION] Cleaned $cleaned inactive bindings")
    }

    fun stop() {
        job?.cancel()
    }
}
