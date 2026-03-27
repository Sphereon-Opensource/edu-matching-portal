package com.sphereon.portal.bridge.bindings

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
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val jsonHeaders = mapOf("Content-Type" to "application/json")

@Serializable
data class LinkedWalletBinding(
    val id: String,
    val providerId: String,
    val institutionId: String? = null,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val holderHashPrefix: String? = null,
    val reconcileRuleVersion: String? = null,
)

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ListBindingsCommand>())
class ListBindingsCommandImpl(
    execution: SessionExecution,
    private val database: AuthBridgeDatabase,
    private val cryptoService: ReconciliationCryptoService,
) : HttpEndpointCommandAdapter(
    id = ListBindingsCommand.COMMAND_ID,
    execution = execution,
    endpoint = ListBindingsCommand.ENDPOINT,
), ListBindingsCommand {

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest,
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)
        val internalIdentityId = request.pathParameters["internalIdentityId"]
            ?: request.path.trimStart('/').takeIf { it.isNotBlank() }
            ?: return Ok(GenericHttpResponse(
                statusCode = 400,
                body = """{"error":"missing_parameter","message":"internalIdentityId is required"}""",
                headers = jsonHeaders,
            ))

        val tenantId = "default"
        // Hash the raw identifier with the institution HMAC key (Key B)
        val institutionHash = cryptoService.hashExternalIdentifier(internalIdentityId).hash
        val bindings = database.authBridgeQueries
            .findBindingsByInstitutionHash(tenantId, institutionHash)
            .executeAsList()

        val result = bindings.map { row ->
            LinkedWalletBinding(
                id = row.id,
                providerId = row.provider_id,
                institutionId = row.institution_id_label,
                createdAt = row.created_at.toString(),
                lastUsedAt = row.last_used_at?.toString(),
                holderHashPrefix = row.holder_identifier_hash.take(16),
                reconcileRuleVersion = row.selector_rule_version,
            )
        }

        return Ok(GenericHttpResponse(
            statusCode = 200,
            body = HttpJson.restApi.encodeToString(ListSerializer(LinkedWalletBinding.serializer()), result),
            headers = jsonHeaders,
        ))
    }
}
