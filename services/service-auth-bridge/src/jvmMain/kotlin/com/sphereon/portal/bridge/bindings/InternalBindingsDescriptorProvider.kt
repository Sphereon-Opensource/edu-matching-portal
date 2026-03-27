package com.sphereon.portal.bridge.bindings

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
class InternalBindingsDescriptorProvider : HttpAdapterDescriptorProvider {
    override val id: String = InternalBindingsHttpAdapter.ID

    private val basePath = "/internal/bindings"

    override fun describe(): HttpAdapterDescription = HttpAdapterDescription(
        id = id,
        mount = HttpAdapterMount(
            serverPrefix = "",
            adapterBasePath = basePath
        ),
        endpoints = listOf(
            ListBindingsCommand.ENDPOINT,
            DeleteBindingCommand.ENDPOINT,
        ).map { endpoint ->
            endpoint.copy(pathPattern = basePath + endpoint.pathPattern)
        }
    )
}
