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
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataService
import com.sphereon.portal.bridge.external.AuthError
import com.sphereon.portal.bridge.external.ExternalApiAuthService
import com.sphereon.portal.bridge.external.ExternalAuxiliaryWriteRequest
import com.sphereon.portal.bridge.external.ExternalErrorResponse
import com.sphereon.portal.bridge.external.PutAuxiliaryCommand
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

private val jsonHeaders = mapOf("Content-Type" to "application/json")

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PutAuxiliaryCommand>())
class PutAuxiliaryCommandImpl(
    execution: SessionExecution,
    private val authService: ExternalApiAuthService,
    private val auxiliaryDataService: AuxiliaryDataService,
    private val matchStore: IdentityMatchStore,
) : HttpEndpointCommandAdapter(
    id = PutAuxiliaryCommand.COMMAND_ID,
    execution = execution,
    endpoint = PutAuxiliaryCommand.ENDPOINT,
), PutAuxiliaryCommand {

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

        // Reject writes to soft-deleted identities
        val activeMatches = matchStore.findByInternalIdentityId(auth.tenantId, internalIdentityId)
        if (activeMatches.isEmpty()) {
            return Ok(GenericHttpResponse(statusCode = 404,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("not_found", "Identity not found or has been deleted")),
                headers = jsonHeaders))
        }

        val category = request.pathParams["category"]
            ?: return Ok(GenericHttpResponse(statusCode = 400,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("bad_request", "Missing path parameter: category")),
                headers = jsonHeaders))

        if (auth.projection.allowedAuxiliary != null && category !in auth.projection.allowedAuxiliary) {
            return Ok(GenericHttpResponse(statusCode = 403,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("forbidden", "Category not allowed")),
                headers = jsonHeaders))
        }

        val writeRequest = try {
            HttpJson.restApi.decodeFromString(ExternalAuxiliaryWriteRequest.serializer(), request.body ?: "")
        } catch (e: Exception) {
            return Ok(GenericHttpResponse(statusCode = 400,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("invalid_request_body", e.message)),
                headers = jsonHeaders))
        }

        val record = auxiliaryDataService.store(auth.tenantId, internalIdentityId, category, writeRequest.fields)
        println("[AUDIT] api.write: tenant=${auth.tenantId}, identityId=$internalIdentityId, category=$category")

        return Ok(GenericHttpResponse(
            statusCode = 200,
            body = HttpJson.restApi.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(),
                mapOf("id" to record.id, "category" to record.category)),
            headers = jsonHeaders,
        ))
    }
}
