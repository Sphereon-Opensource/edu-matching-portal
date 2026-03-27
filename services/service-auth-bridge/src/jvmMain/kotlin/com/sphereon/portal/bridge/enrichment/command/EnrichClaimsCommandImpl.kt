package com.sphereon.portal.bridge.enrichment.command

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
import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataService
import com.sphereon.portal.bridge.enrichment.EnrichClaimsCommand
import com.sphereon.portal.bridge.enrichment.EnrichClaimsRequest
import com.sphereon.portal.bridge.enrichment.EnrichClaimsResponse
import com.sphereon.portal.bridge.enrichment.EnrichmentConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

private val jsonHeaders = mapOf("Content-Type" to "application/json")

/**
 * Enriches a claim set with stored canonical claims and auxiliary data.
 *
 * Absorbs the logic previously in TokenEnrichmentService:
 * 1. Decrypt PersistedAttributesEnvelope (canonical claims from reconciliation)
 * 2. Decrypt AuxiliaryDataRecords (portal-specific institution data)
 * 3. Merge into token claims per EnrichmentConfig
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<EnrichClaimsCommand>())
class EnrichClaimsCommandImpl(
    execution: SessionExecution,
    private val matchStore: IdentityMatchStore,
    private val bindingStore: IdentityLinkBindingStore,
    private val auxiliaryDataService: AuxiliaryDataService,
    private val cryptoService: ReconciliationCryptoService,
) : HttpEndpointCommandAdapter(
    id = EnrichClaimsCommand.COMMAND_ID,
    execution = execution,
    endpoint = EnrichClaimsCommand.ENDPOINT,
), EnrichClaimsCommand {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doExecute(
        args: GenericHttpRequest,
        applyDuring: (GenericHttpRequest) -> GenericHttpRequest,
    ): IdkResult<GenericHttpResponse, IdkError> {
        val request = applyDuring(args)

        val enrichRequest = try {
            HttpJson.restApi.decodeFromString(EnrichClaimsRequest.serializer(), request.body ?: "")
        } catch (e: Exception) {
            return Ok(GenericHttpResponse(
                statusCode = 400,
                body = """{"error":"invalid_request_body","errorDescription":"${e.message}"}""",
                headers = jsonHeaders,
            ))
        }

        val config = enrichRequest.config ?: EnrichmentConfig()
        val enriched = enrichClaims(
            tenantId = enrichRequest.tenantId,
            internalIdentityId = enrichRequest.internalIdentityId,
            baseClaims = enrichRequest.baseClaims,
            config = config,
        )

        return Ok(GenericHttpResponse(
            statusCode = 200,
            body = HttpJson.restApi.encodeToString(EnrichClaimsResponse.serializer(),
                EnrichClaimsResponse(claims = enriched)),
            headers = jsonHeaders,
        ))
    }

    private suspend fun enrichClaims(
        tenantId: String,
        internalIdentityId: String,
        baseClaims: Map<String, JsonElement>,
        config: EnrichmentConfig,
    ): Map<String, JsonElement> {
        val result = baseClaims.toMutableMap()

        // 1. Enrich with canonical claims from the identity link binding
        val matches = matchStore.findByInternalIdentityId(tenantId, internalIdentityId)
        if (matches.isNotEmpty()) {
            val binding = bindingStore.findByMatchId(tenantId, matches.first().id)
            if (binding != null) {
                try {
                    val plaintext = cryptoService.decrypt(binding.persistedAttributesEnvelope.encrypted)
                    val canonical: Map<String, JsonElement> = json.decodeFromString(plaintext)

                    val filtered = if (config.canonicalClaims.isNotEmpty()) {
                        canonical.filterKeys { it in config.canonicalClaims }
                    } else canonical

                    for ((key, value) in filtered) {
                        if (key !in result) {
                            result[key] = value
                        }
                    }
                } catch (_: Exception) {
                    // Decryption failure — skip canonical enrichment
                }
            }
        }

        // 2. Enrich with auxiliary data
        if (config.auxiliaryCategories.isNotEmpty()) {
            val allDecrypted = auxiliaryDataService.getDecrypted(tenantId, internalIdentityId)

            for (dec in allDecrypted) {
                val allowedFields = config.auxiliaryCategories[dec.category] ?: continue
                val fields = if (allowedFields.isNotEmpty()) {
                    dec.data.filterKeys { it in allowedFields }
                } else dec.data

                for ((key, value) in fields) {
                    val enrichedKey = "${config.auxiliaryPrefix}${key}"
                    result[enrichedKey] = value
                }
            }
        }

        return result
    }
}
