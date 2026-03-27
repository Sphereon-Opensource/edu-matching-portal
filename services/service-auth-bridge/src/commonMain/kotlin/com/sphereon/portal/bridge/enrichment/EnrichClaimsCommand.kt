package com.sphereon.portal.bridge.enrichment

import com.sphereon.core.api.http.command.HttpEndpointCommand
import com.sphereon.core.api.http.describe.HttpEndpointDescriptor
import com.sphereon.core.api.http.describe.HttpMethod
import com.sphereon.core.api.http.describe.MediaType

/**
 * POST /claims — Enrich token claims with canonical claims and auxiliary data.
 *
 * Internal API called by the STS after /complete. Not exposed externally.
 */
interface EnrichClaimsCommand : HttpEndpointCommand {
    companion object {
        const val COMMAND_ID = "internal.enrichment.enrich-claims"

        val ENDPOINT = HttpEndpointDescriptor(
            method = HttpMethod.POST,
            pathPattern = "/claims",
            consumes = setOf(MediaType.ApplicationJson),
            produces = setOf(MediaType.ApplicationJson),
            operationId = "enrichClaims",
            commandId = COMMAND_ID,
            tags = setOf("token-enrichment"),
            summary = "Enrich token claims with identity data"
        )
    }
}
