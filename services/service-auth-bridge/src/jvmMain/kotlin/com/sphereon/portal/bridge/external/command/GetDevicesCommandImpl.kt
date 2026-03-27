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
import com.sphereon.portal.bridge.external.ExternalApiAuthService
import com.sphereon.portal.bridge.external.ExternalErrorResponse
import com.sphereon.portal.bridge.external.GetDevicesCommand
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

private val jsonHeaders = mapOf("Content-Type" to "application/json")

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<GetDevicesCommand>())
class GetDevicesCommandImpl(
    execution: SessionExecution,
    private val authService: ExternalApiAuthService,
    private val matchStore: IdentityMatchStore,
    private val bindingStore: IdentityLinkBindingStore,
) : HttpEndpointCommandAdapter(
    id = GetDevicesCommand.COMMAND_ID,
    execution = execution,
    endpoint = GetDevicesCommand.ENDPOINT,
), GetDevicesCommand {

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

        val internalIdentityId = request.pathParams["internalIdentityId"]
            ?: return Ok(GenericHttpResponse(statusCode = 400,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("bad_request", "Missing path parameter: internalIdentityId")),
                headers = jsonHeaders))

        val matches = matchStore.findByInternalIdentityId(auth.tenantId, internalIdentityId)
        if (matches.isEmpty()) {
            return Ok(GenericHttpResponse(statusCode = 404,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("not_found", "Identity not found")),
                headers = jsonHeaders))
        }

        val devices = matches.map { match ->
            val binding = bindingStore.findByMatchId(auth.tenantId, match.id)
            JsonObject(mapOf(
                "matchId" to JsonPrimitive(match.id),
                "identifierType" to JsonPrimitive(match.identifierType.value),
                "linkedAt" to JsonPrimitive(match.createdAt.toString()),
                "lastUsedAt" to (match.lastUsedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
                "providerId" to (binding?.providerId?.let { JsonPrimitive(it) } ?: JsonNull),
            ))
        }

        val responseBody = JsonObject(mapOf(
            "devices" to kotlinx.serialization.json.JsonArray(devices)
        ))

        return Ok(GenericHttpResponse(
            statusCode = 200,
            body = responseBody.toString(),
            headers = jsonHeaders,
        ))
    }
}
