package com.sphereon.portal.bridge.external

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
class ReconciliationApiDescriptorProvider : HttpAdapterDescriptorProvider {
    override val id: String = ReconciliationApiHttpAdapter.ID

    private val basePath = "/api/external/v1/reconciliation"

    override fun describe(): HttpAdapterDescription = HttpAdapterDescription(
        id = id,
        mount = HttpAdapterMount(
            serverPrefix = "",
            adapterBasePath = basePath
        ),
        endpoints = listOf(
            LookupIdentityCommand.ENDPOINT,
            GetIdentityCommand.ENDPOINT,
            GetClaimsCommand.ENDPOINT,
            GetAuxiliaryCommand.ENDPOINT,
            GetAuxCategoryCommand.ENDPOINT,
            PutAuxiliaryCommand.ENDPOINT,
            DeleteAuxCategoryCommand.ENDPOINT,
            DeleteIdentityCommand.ENDPOINT,
        ).map { endpoint ->
            endpoint.copy(pathPattern = basePath + endpoint.pathPattern)
        }
    )
}
