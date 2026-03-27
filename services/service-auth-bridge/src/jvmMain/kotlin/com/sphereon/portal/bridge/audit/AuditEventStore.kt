package com.sphereon.portal.bridge.audit

import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Append-only audit event store backed by PostgreSQL.
 *
 * Per architecture §6.6: each security-relevant operation emits an audit event
 * persisted in an append-only audit log. Events are INSERT-only — the database
 * should be configured with a trigger that rejects UPDATE and DELETE on the
 * audit_event table in production.
 */
class AuditEventStore(private val database: AuthBridgeDatabase) {

    @OptIn(ExperimentalUuidApi::class)
    fun record(
        tenantId: String = "default",
        eventType: String,
        correlationId: String? = null,
        subjectHash: String? = null,
        clientId: String? = null,
        detail: String? = null,
    ) {
        database.authBridgeQueries.insertAuditEvent(
            id = Uuid.random().toString(),
            tenantId = tenantId,
            eventType = eventType,
            correlationId = correlationId,
            subjectHash = subjectHash,
            clientId = clientId,
            detail = detail,
            createdAt = Clock.System.now(),
        )
    }
}
