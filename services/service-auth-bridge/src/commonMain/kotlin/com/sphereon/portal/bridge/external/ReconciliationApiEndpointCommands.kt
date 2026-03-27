package com.sphereon.portal.bridge.external

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * POST /lookup — Lookup identity by identifier or identifier hash.
 */
interface LookupIdentityCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.reconciliation.lookup-identity"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/lookup",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "lookupIdentity",
            commandId = COMMAND_ID,
            tags = setOf("reconciliation-api"),
            summary = "Lookup identity by identifier or identifier hash"
        )
    }
}

/**
 * GET /{internalIdentityId} — Full projected identity (claims + auxiliary).
 */
interface GetIdentityCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.reconciliation.get-identity"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{internalIdentityId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getIdentity",
            commandId = COMMAND_ID,
            tags = setOf("reconciliation-api"),
            summary = "Get full projected identity"
        )
    }
}

/**
 * GET /{internalIdentityId}/claims — Only projected canonical claims.
 */
interface GetClaimsCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.reconciliation.get-claims"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{internalIdentityId}/claims",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getClaims",
            commandId = COMMAND_ID,
            tags = setOf("reconciliation-api"),
            summary = "Get projected canonical claims"
        )
    }
}

/**
 * GET /{internalIdentityId}/auxiliary — All projected auxiliary categories.
 */
interface GetAuxiliaryCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.reconciliation.get-auxiliary"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{internalIdentityId}/auxiliary",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getAuxiliary",
            commandId = COMMAND_ID,
            tags = setOf("reconciliation-api"),
            summary = "Get all projected auxiliary categories"
        )
    }
}

/**
 * GET /{internalIdentityId}/auxiliary/{category} — Single projected category.
 */
interface GetAuxCategoryCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.reconciliation.get-aux-category"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.GET,
            pathPattern = "/{internalIdentityId}/auxiliary/{category}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "getAuxiliaryCategory",
            commandId = COMMAND_ID,
            tags = setOf("reconciliation-api"),
            summary = "Get single projected auxiliary category"
        )
    }
}

/**
 * PUT /{internalIdentityId}/auxiliary/{category} — Store/update auxiliary data.
 */
interface PutAuxiliaryCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.reconciliation.put-auxiliary"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.PUT,
            pathPattern = "/{internalIdentityId}/auxiliary/{category}",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "putAuxiliary",
            commandId = COMMAND_ID,
            tags = setOf("reconciliation-api"),
            summary = "Store or update auxiliary data for a category"
        )
    }
}

/**
 * DELETE /{internalIdentityId}/auxiliary/{category} — Delete auxiliary category.
 */
interface DeleteAuxCategoryCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.reconciliation.delete-aux-category"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/{internalIdentityId}/auxiliary/{category}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteAuxiliaryCategory",
            commandId = COMMAND_ID,
            tags = setOf("reconciliation-api"),
            summary = "Delete auxiliary data for a category"
        )
    }
}

/**
 * DELETE /{internalIdentityId} — GDPR erasure.
 */
interface DeleteIdentityCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "external.reconciliation.delete-identity"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.DELETE,
            pathPattern = "/{internalIdentityId}",
            produces = setOf(MediaType.ApplicationJson),
            operationId = "deleteIdentity",
            commandId = COMMAND_ID,
            tags = setOf("reconciliation-api"),
            summary = "Delete identity and all associated data (GDPR erasure)"
        )
    }
}
