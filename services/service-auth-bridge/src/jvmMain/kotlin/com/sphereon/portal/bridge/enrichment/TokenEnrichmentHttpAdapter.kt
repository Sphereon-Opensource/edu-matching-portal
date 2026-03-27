package com.sphereon.portal.bridge.enrichment

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
 * HTTP adapter for STS token enrichment.
 *
 * Mounts at /internal/enrichment with:
 * - POST /claims — enrich base claims with canonical claims and auxiliary data
 *
 * Internal API (not exposed externally) — called only by the co-located STS service.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(SessionScope::class, binding = binding<HttpAdapter>())
class TokenEnrichmentHttpAdapter(
    execution: SessionExecution,
    private val enrichClaimsCommand: EnrichClaimsCommand,
) : CommandBackedHttpAdapter(
    id = ID,
    execution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = "/internal/enrichment"
    )
) {
    companion object {
        const val ID = "token.enrichment.http.adapter"
    }

    override val endpointCommands: List<HttpEndpointCommand> = listOf(
        enrichClaimsCommand,
    )

    override val openApiHints = OpenApiHints(
        tags = setOf("token-enrichment"),
        operationIdPrefix = "tokenEnrichment"
    )
}
