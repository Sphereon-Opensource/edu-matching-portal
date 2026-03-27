package com.sphereon.portal.bridge.persistence

import app.cash.sqldelight.db.SqlDriver
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import com.sphereon.portal.bridge.db.Auxiliary_data
import com.sphereon.portal.bridge.db.Identity_link_binding
import com.sphereon.portal.bridge.db.Identity_match
import com.sphereon.portal.bridge.db.Key_migration_history
import com.sphereon.portal.bridge.db.Audit_event
import com.sphereon.portal.bridge.db.Reconciliation_session

/**
 * Creates an [AuthBridgeDatabase] with all required Instant column adapters.
 * Shared by all test classes to avoid boilerplate.
 */
fun createTestDatabase(driver: SqlDriver): AuthBridgeDatabase {
    AuthBridgeDatabase.Schema.create(driver)
    return AuthBridgeDatabase(
        driver = driver,
        identity_matchAdapter = Identity_match.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
            last_used_atAdapter = InstantColumnAdapter,
            deleted_atAdapter = InstantColumnAdapter,
        ),
        identity_link_bindingAdapter = Identity_link_binding.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
            last_used_atAdapter = InstantColumnAdapter,
            deleted_atAdapter = InstantColumnAdapter,
        ),
        reconciliation_sessionAdapter = Reconciliation_session.Adapter(
            created_atAdapter = InstantColumnAdapter,
            expires_atAdapter = InstantColumnAdapter,
        ),
        auxiliary_dataAdapter = Auxiliary_data.Adapter(
            created_atAdapter = InstantColumnAdapter,
            updated_atAdapter = InstantColumnAdapter,
            expires_atAdapter = InstantColumnAdapter,
        ),
        key_migration_historyAdapter = Key_migration_history.Adapter(
            started_atAdapter = InstantColumnAdapter,
            completed_atAdapter = InstantColumnAdapter,
        ),
        audit_eventAdapter = Audit_event.Adapter(
            created_atAdapter = InstantColumnAdapter,
        ),
    )
}
