package com.sphereon.portal.bridge.external

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
 * HTTP adapter for device (wallet) management.
 *
 * Mounts at /api/external/v1/devices with endpoints for:
 * - GET /{internalIdentityId} — list linked holder keys
 * - DELETE /{internalIdentityId}/{matchId} — revoke a holder key
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesIntoSet(SessionScope::class, binding = binding<HttpAdapter>())
class DeviceApiHttpAdapter(
    execution: SessionExecution,
    private val getDevicesCommand: GetDevicesCommand,
    private val deleteDeviceCommand: DeleteDeviceCommand,
) : CommandBackedHttpAdapter(
    id = ID,
    execution = execution,
    mount = HttpAdapterMount(
        serverPrefix = "",
        adapterBasePath = "/api/external/v1/devices"
    )
) {
    companion object {
        const val ID = "device.api.http.adapter"
    }

    override val endpointCommands: List<HttpEndpointCommand> = listOf(
        getDevicesCommand,
        deleteDeviceCommand,
    )

    override val openApiHints = OpenApiHints(
        tags = setOf("device-api"),
        operationIdPrefix = "deviceApi"
    )
}
