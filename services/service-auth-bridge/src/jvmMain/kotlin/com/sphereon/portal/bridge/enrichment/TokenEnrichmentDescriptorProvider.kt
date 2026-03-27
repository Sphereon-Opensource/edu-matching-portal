package com.sphereon.portal.bridge.enrichment

import com.sphereon.core.api.http.describe.HttpAdapterDescription
import com.sphereon.core.api.http.describe.HttpAdapterDescriptorProvider
import com.sphereon.core.api.http.describe.HttpAdapterMount
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<HttpAdapterDescriptorProvider>())
class TokenEnrichmentDescriptorProvider : HttpAdapterDescriptorProvider {
    override val id: String = TokenEnrichmentHttpAdapter.ID

    private val basePath = "/internal/enrichment"

    override fun describe(): HttpAdapterDescription = HttpAdapterDescription(
        id = id,
        mount = HttpAdapterMount(
            serverPrefix = "",
            adapterBasePath = basePath
        ),
        endpoints = listOf(
            EnrichClaimsCommand.ENDPOINT,
        ).map { endpoint ->
            endpoint.copy(pathPattern = basePath + endpoint.pathPattern)
        }
    )
}
