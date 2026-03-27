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
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataService
import com.sphereon.portal.bridge.external.AuthError
import com.sphereon.portal.bridge.external.DeleteIdentityCommand
import com.sphereon.portal.bridge.external.ExternalApiAuthService
import com.sphereon.portal.bridge.external.ExternalErrorResponse
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

private val jsonHeaders = mapOf("Content-Type" to "application/json")

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<DeleteIdentityCommand>())
class DeleteIdentityCommandImpl(
    execution: SessionExecution,
    private val authService: ExternalApiAuthService,
    private val matchStore: IdentityMatchStore,
    private val bindingStore: IdentityLinkBindingStore,
    private val auxiliaryDataService: AuxiliaryDataService,
) : HttpEndpointCommandAdapter(
    id = DeleteIdentityCommand.COMMAND_ID,
    execution = execution,
    endpoint = DeleteIdentityCommand.ENDPOINT,
), DeleteIdentityCommand {

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

        auxiliaryDataService.deleteAll(auth.tenantId, internalIdentityId)

        val matches = matchStore.findByInternalIdentityId(auth.tenantId, internalIdentityId)
        for (match in matches) {
            bindingStore.findByMatchId(auth.tenantId, match.id)?.let { binding ->
                bindingStore.delete(auth.tenantId, binding.id)
            }
            matchStore.delete(auth.tenantId, match.id)
        }

        println("[AUDIT] gdpr.erasure: tenant=${auth.tenantId}, identityId=$internalIdentityId, matchesDeleted=${matches.size}")

        return Ok(GenericHttpResponse(statusCode = 204, body = "", headers = emptyMap()))
    }
}
