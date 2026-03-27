package com.sphereon.portal.bridge.external

import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.HttpAdapter
import com.sphereon.core.api.http.command.CommandBackedHttpAdapter
import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpAdapterMount
import com.sphereon.core.api.http.describe.OpenApiHints
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * HTTP adapter for the external reconciliation API.
 *
 * Mounts at /api/external/v1/reconciliation with endpoints for:
 * - POST /lookup — identity lookup by identifier/hash
 * - GET /{internalIdentityId} — full projected identity
 * - GET /{internalIdentityId}/claims — projected claims only
 * - GET/PUT/DELETE /{internalIdentityId}/auxiliary/{category} — auxiliary data
 * - DELETE /{internalIdentityId} — GDPR erasure
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(SessionScope::class, binding = binding<HttpAdapter>())
class ReconciliationApiHttpAdapter(
    execution: SessionExecution,
    private val lookupIdentityCommand: LookupIdentityCommand,
    private val getIdentityCommand: GetIdentityCommand,
    private val getClaimsCommand: GetClaimsCommand,
    private val getAuxiliaryCommand: GetAuxiliaryCommand,
    private val getAuxCategoryCommand: GetAuxCategoryCommand,
    private val putAuxiliaryCommand: PutAuxiliaryCommand,
    private val deleteAuxCategoryCommand: DeleteAuxCategoryCommand,
    private val deleteIdentityCommand: DeleteIdentityCommand,
) : CommandBackedHttpAdapter(
    id = ID,
    execution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = "/api/external/v1/reconciliation"
    )
) {
    companion object {
        const val ID = "reconciliation.api.http.adapter"
    }

    override val endpointCommands: List<HttpEndpointCommand> = listOf(
        lookupIdentityCommand,
        getIdentityCommand,
        getClaimsCommand,
        getAuxiliaryCommand,
        getAuxCategoryCommand,
        putAuxiliaryCommand,
        deleteAuxCategoryCommand,
        deleteIdentityCommand,
    )

    override val openApiHints = OpenApiHints(
        tags = setOf("reconciliation-api"),
        operationIdPrefix = "reconciliationApi"
    )
}
