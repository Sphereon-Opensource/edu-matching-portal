package com.sphereon.portal.bridge.bindings

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * GET /{internalIdentityId} — List linked wallet bindings for a user.
 * Internal API called by the portal BFF to populate the profile page.
 */
interface ListBindingsCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "internal.bindings.list"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{internalIdentityId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "listBindings",
            commandId = COMMAND_ID,
            tags = setOf("internal-bindings"),
            summary = "List linked wallet bindings for a user"
        )
    }
}

/**
 * DELETE /{internalIdentityId}/{bindingId} — Revoke a wallet binding (soft-delete).
 */
interface DeleteBindingCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "internal.bindings.delete"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/{internalIdentityId}/{bindingId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteBinding",
            commandId = COMMAND_ID,
            tags = setOf("internal-bindings"),
            summary = "Revoke a wallet binding"
        )
    }
}
