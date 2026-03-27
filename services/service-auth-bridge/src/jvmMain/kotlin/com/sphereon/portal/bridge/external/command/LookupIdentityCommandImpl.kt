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
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.model.IdentifierType
import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.portal.bridge.external.AuthError
import com.sphereon.portal.bridge.external.ExternalApiAuthService
import com.sphereon.portal.bridge.external.ExternalErrorResponse
import com.sphereon.portal.bridge.external.ExternalIdentityResponse
import com.sphereon.portal.bridge.external.ExternalLookupRequest
import com.sphereon.portal.bridge.external.LookupIdentityCommand
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

private val jsonHeaders = mapOf("Content-Type" to "application/json")

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<LookupIdentityCommand>())
class LookupIdentityCommandImpl(
    execution: SessionExecution,
    private val authService: ExternalApiAuthService,
    private val matchStore: IdentityMatchStore,
    private val bindingStore: IdentityLinkBindingStore,
    private val cryptoService: ReconciliationCryptoService,
) : HttpEndpointCommandAdapter(
    id = LookupIdentityCommand.COMMAND_ID,
    execution = execution,
    endpoint = LookupIdentityCommand.ENDPOINT,
), LookupIdentityCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest,
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val auth = authService.authenticate(request).getOrElse { error ->
            val e = error as AuthError
            return Ok(GenericHttpResponse(
                statusCode = e.statusCode,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse(e.error, e.description)),
                headers = jsonHeaders,
            ))
        }

        val lookupRequest = try {
            HttpJson.restApi.decodeFromString(ExternalLookupRequest.serializer(), request.body ?: "")
        } catch (e: Exception) {
            return Ok(GenericHttpResponse(
                statusCode = 400,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("invalid_request_body", e.message)),
                headers = jsonHeaders,
            ))
        }

        println("[AUDIT] api.lookup: client=${auth.clientId}, tenant=${auth.tenantId}, identifierType=${lookupRequest.identifierType}")

        val hash = when {
            lookupRequest.identifier != null -> cryptoService.hashExternalIdentifier(lookupRequest.identifier).hash
            lookupRequest.identifierHash != null -> lookupRequest.identifierHash
            else -> return Ok(GenericHttpResponse(
                statusCode = 400,
                body = HttpJson.restApi.encodeToString(ExternalErrorResponse.serializer(),
                    ExternalErrorResponse("invalid_request_body", "Either 'identifier' or 'identifierHash' is required")),
                headers = jsonHeaders,
            ))
        }

        val identifierType = IdentifierType(lookupRequest.identifierType)
        val match = matchStore.findByIdentifierHash(auth.tenantId, hash, identifierType)

        if (match == null) {
            return Ok(GenericHttpResponse(
                statusCode = 200,
                body = HttpJson.restApi.encodeToString(ExternalIdentityResponse.serializer(),
                    ExternalIdentityResponse(found = false)),
                headers = jsonHeaders,
            ))
        }

        // Try binding by match ID first, then fall back to any binding for the same internal identity
        val binding = bindingStore.findByMatchId(auth.tenantId, match.id)
            ?: bindingStore.findByHolderHash(auth.tenantId, match.internalIdentityId)
        val claims = binding?.let { authService.projectClaims(it, auth.projection) } ?: emptyMap()
        val auxiliary = authService.projectAuxiliary(auth.tenantId, match.internalIdentityId, auth.projection)

        return Ok(GenericHttpResponse(
            statusCode = 200,
            body = HttpJson.restApi.encodeToString(ExternalIdentityResponse.serializer(),
                ExternalIdentityResponse(
                    found = true,
                    internalIdentityId = match.internalIdentityId,
                    claims = claims,
                    auxiliary = auxiliary,
                    assurance = binding?.assuranceSummary,
                    lastVerifiedAt = binding?.updatedAt?.toString() ?: binding?.createdAt?.toString(),
                )),
            headers = jsonHeaders,
        ))
    }
}
