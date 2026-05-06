package com.sphereon.portal.bridge.bindings

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.time.Clock

private val jsonHeaders = mapOf("Content-Type" to "application/json")

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteBindingCommand>())
class DeleteBindingCommandImpl(
    execution: SessionExecution,
    private val database: AuthBridgeDatabase,
) : HttpEndpointCommandAdapter(
    id = DeleteBindingCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteBindingCommand.ENDPOINT,
), DeleteBindingCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest,
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val bindingId = request.pathParameters["bindingId"]
            ?: request.path.split("/").lastOrNull()?.takeIf { it.isNotBlank() }
            ?: return Ok(GenericHttpResponse(
                statusCode = 400,
                body = """{"error":"missing_parameter","message":"bindingId is required"}""",
                headers = jsonHeaders,
            ))

        val tenantId = "default"
        val now = Clock.System.now()

        // Look up the binding to get the match_id before soft-deleting
        val binding = database.authBridgeQueries
            .findBindingByMatchId(tenantId, bindingId) // bindingId is actually the binding ID
            .executeAsOneOrNull()
            // Try direct lookup if findByMatchId didn't work (it expects matchId, not bindingId)
            ?: database.authBridgeQueries
                .findAllBindingsPaged(tenantId, 1000, 0)
                .executeAsList()
                .firstOrNull { it.id == bindingId && it.deleted_at == null }

        // Soft-delete the binding
        database.authBridgeQueries.softDeleteBinding(
            deletedAt = now,
            deletionReason = "user_revoked",
            tenantId = tenantId,
            bindingId = bindingId,
        )

        // Also soft-delete the associated match so re-reconciliation can create a new one
        if (binding != null) {
            database.authBridgeQueries.softDeleteMatch(
                deletedAt = now,
                deletionReason = "user_revoked",
                tenantId = tenantId,
                matchId = binding.match_id,
            )
        }

        return Ok(GenericHttpResponse(
            statusCode = 200,
            body = """{"deleted":true,"bindingId":"$bindingId"}""",
            headers = jsonHeaders,
        ))
    }
}
