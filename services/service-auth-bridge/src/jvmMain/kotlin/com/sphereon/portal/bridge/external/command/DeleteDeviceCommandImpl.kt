package com.sphereon.portal.bridge.external.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.http.GenericHttpResponse
import com.sphereon.core.api.http.HttpJson
import com.sphereon.core.api.http.command.HttpEndpointCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.portal.bridge.external.AuthError
import com.sphereon.portal.bridge.external.DeleteDeviceCommand
import com.sphereon.portal.bridge.external.ExternalApiAuthService
import com.sphereon.portal.bridge.external.ExternalErrorResponse
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

private val jsonHeaders = mapOf("Content-Type" to "application/json")

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteDeviceCommand>())
class DeleteDeviceCommandImpl(
    execution: SessionExecution,
    private val authService: ExternalApiAuthService,
    private val matchStore: IdentityMatchStore,
    private val bindingStore: IdentityLinkBindingStore,
) : HttpEndpointCommandAdapter(
    id = DeleteDeviceCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteDeviceCommand.ENDPOINT,
), DeleteDeviceCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest,
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args).withExtractedParams(endpoint.pathPattern)

        val auth = authService.authenticate(request).getOrElse { error ->
            val e = error as AuthError
            return Ok(GenericHttpResponse(
                statusCode = e.statusCode,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse(e.error, e.description)),
                headers = jsonHeaders,
            ))
        }

        if (!auth.projection.canWrite) {
            return Ok(GenericHttpResponse(statusCode = 403,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("forbidden", "Write access not allowed")),
                headers = jsonHeaders))
        }

        val internalIdentityId = request.pathParams["internalIdentityId"]
            ?: return Ok(GenericHttpResponse(statusCode = 400,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("bad_request", "Missing path parameter: internalIdentityId")),
                headers = jsonHeaders))

        val matchId = request.pathParams["matchId"]
            ?: return Ok(GenericHttpResponse(statusCode = 400,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("bad_request", "Missing path parameter: matchId")),
                headers = jsonHeaders))

        val match = matchStore.findById(auth.tenantId, matchId)
        if (match == null || match.internalIdentityId != internalIdentityId) {
            return Ok(GenericHttpResponse(statusCode = 404,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("not_found", "Device not found")),
                headers = jsonHeaders))
        }

        bindingStore.findByMatchId(auth.tenantId, matchId)?.let { binding ->
            bindingStore.delete(auth.tenantId, binding.id)
        }
        matchStore.delete(auth.tenantId, matchId)

        println("[AUDIT] device.revoked: tenant=${auth.tenantId}, identityId=$internalIdentityId, matchId=$matchId")

        return Ok(GenericHttpResponse(statusCode = 204, body = "", headers = emptyMap()))
    }
}
