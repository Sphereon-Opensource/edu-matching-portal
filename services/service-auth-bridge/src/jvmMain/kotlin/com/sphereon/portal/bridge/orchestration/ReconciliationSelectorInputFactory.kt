package com.sphereon.portal.bridge.orchestration

import com.sphereon.identity.idv.model.AttributeBag
import com.sphereon.identity.idv.model.AttributePath
import com.sphereon.identity.reconciliation.model.KnownHolderState
import com.sphereon.identity.reconciliation.model.ReconciliationSelectorInput
import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSession
import com.sphereon.openid.oid4vp.universal.VerifiedData
import com.sphereon.portal.bridge.WalletAttributeMappings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val OID4VP_SELECTOR_ENTRY_POINT = "oid4vp"
internal const val OID4VP_SELECTOR_TRIGGER = "idv_required"
internal const val OID4VP_FORCE_RECONCILIATION_TRIGGER = "force_reconciliation"

internal fun buildReconciliationSelectorInput(
    session: Oid4vpAuthSession,
    tenantId: String,
    verifiedData: VerifiedData,
    walletAttributeMappings: WalletAttributeMappings,
    requestedProjection: String? = null,
): ReconciliationSelectorInput {
    return ReconciliationSelectorInput(
        tenantId = tenantId,
        entryPointType = OID4VP_SELECTOR_ENTRY_POINT,
        queryId = session.queryId,
        triggerType = if (session.forceReconciliation) {
            OID4VP_FORCE_RECONCILIATION_TRIGGER
        } else {
            OID4VP_SELECTOR_TRIGGER
        },
        dcqlCredentialQueryIds = extractDcqlCredentialQueryIds(verifiedData),
        dcqlCredentialSetRefs = extractDcqlCredentialSetRefs(verifiedData.authorizationResponse),
        presentedCredentialIds = verifiedData.credentials.map { it.id }.filter { it.isNotBlank() }.toSet(),
        presentedCredentialTypes = extractPresentedCredentialTypes(verifiedData),
        issuers = extractIssuers(verifiedData),
        availableAttributes = buildAvailableAttributesBag(verifiedData, walletAttributeMappings),
        knownHolderState = translateKnownHolderState(session),
        requestedProjection = requestedProjection,
    )
}

internal fun translateKnownHolderState(session: Oid4vpAuthSession): KnownHolderState? {
    // A force-reconciliation session intentionally bypasses the reusable-binding decision.
    if (session.forceReconciliation) return null
    // By the time initiateReconciliation is called, the fast path has already been evaluated.
    // Carry the observed runtime state forward when available so selector rules can distinguish
    // candidate tuple matches from a plain "no binding found" case.
    return session.knownHolderState ?: KnownHolderState.NOT_FOUND
}

private fun extractPresentedCredentialTypes(verifiedData: VerifiedData): Set<String> {
    return buildSet {
        for (credential in verifiedData.credentials) {
            credential.type?.takeIf { it.isNotBlank() }?.let(::add)
            (credential.claims["vct"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }
}

private fun extractIssuers(verifiedData: VerifiedData): Set<String> {
    return verifiedData.credentials
        .mapNotNull { credential -> (credential.claims["iss"] as? JsonPrimitive)?.contentOrNull }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun buildAvailableAttributesBag(
    verifiedData: VerifiedData,
    walletAttributeMappings: WalletAttributeMappings,
): AttributeBag? {
    val attributes = linkedMapOf<AttributePath, JsonElement>()

    for (credential in verifiedData.credentials) {
        for ((key, value) in credential.claims) {
            if (key.isBlank()) {
                continue
            }
            attributes[AttributePath(key)] = value

            val mappedKey = walletAttributeMappings.resolve(key)
            if (!mappedKey.isNullOrBlank() && mappedKey != key) {
                attributes[AttributePath(mappedKey)] = value
            }
        }
    }

    return attributes.takeIf { it.isNotEmpty() }?.let { AttributeBag(attributes = it) }
}

private fun extractDcqlCredentialQueryIds(verifiedData: VerifiedData): Set<String> {
    val vpTokenQueryIds = (verifiedData.authorizationResponse?.get("vp_token") as? JsonObject)
        ?.keys
        .orEmpty()
    val credentialIds = verifiedData.credentials.map { it.id }

    return (vpTokenQueryIds + credentialIds)
        .filter { it.isNotBlank() }
        .toSet()
}

private fun extractDcqlCredentialSetRefs(authorizationResponse: JsonObject?): Set<String> {
    if (authorizationResponse == null) {
        return emptySet()
    }

    return buildSet {
        extractCredentialSetIds(authorizationResponse["credential_set_matches"])?.let { addAll(it) }
        val dcqlResponse = authorizationResponse["dcql_response"] as? JsonObject
        extractCredentialSetIds(dcqlResponse?.get("credential_set_matches"))?.let { addAll(it) }
    }
}

private fun extractCredentialSetIds(element: JsonElement?): Set<String>? {
    val matches = element as? JsonArray ?: return null
    return matches.mapNotNullTo(linkedSetOf()) { match ->
        (match as? JsonObject)
            ?.get("credential_set_id")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }
}
