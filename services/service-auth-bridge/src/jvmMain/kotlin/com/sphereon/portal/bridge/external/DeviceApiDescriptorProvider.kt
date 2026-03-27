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
class DeviceApiDescriptorProvider : HttpAdapterDescriptorProvider {
    override val id: String = DeviceApiHttpAdapter.ID

    private val basePath = "/api/external/v1/devices"

    override fun describe(): HttpAdapterDescription = HttpAdapterDescription(
        id = id,
        mount = HttpAdapterMount(
            serverPrefix = "",
            adapterBasePath = basePath
        ),
        endpoints = listOf(
            GetDevicesCommand.ENDPOINT,
            DeleteDeviceCommand.ENDPOINT,
        ).map { endpoint ->
            endpoint.copy(pathPattern = basePath + endpoint.pathPattern)
        }
    )
}
