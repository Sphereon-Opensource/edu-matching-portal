package com.sphereon.portal.bridge.external

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * GET /{internalIdentityId} — List all linked holder key matches.
 */
interface GetDevicesCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.device.get-devices"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{internalIdentityId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getDevices",
            commandId = COMMAND_ID,
            tags = setOf("device-api"),
            summary = "List all linked holder key matches for an identity"
        )
    }
}

/**
 * DELETE /{internalIdentityId}/{matchId} — Revoke a specific holder key.
 */
interface DeleteDeviceCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.device.delete-device"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/{internalIdentityId}/{matchId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteDevice",
            commandId = COMMAND_ID,
            tags = setOf("device-api"),
            summary = "Revoke a specific holder key match"
        )
    }
}
