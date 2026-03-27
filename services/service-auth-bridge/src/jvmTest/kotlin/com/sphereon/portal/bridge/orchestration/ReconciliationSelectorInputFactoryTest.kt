package com.sphereon.portal.bridge.orchestration

import com.sphereon.identity.reconciliation.model.KnownHolderState
import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSession
import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSessionStatus
import com.sphereon.openid.oid4vp.universal.VerifiedClaimsValue
import com.sphereon.openid.oid4vp.universal.VerifiedData
import com.sphereon.portal.bridge.WalletAttributeMappings
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconciliationSelectorInputFactoryTest {

    @Test
    fun `build selector input includes richer verified-data context`() {
        val session = session(requestedProjection = "sts")
        val verifiedData = VerifiedData(
            credentialClaims = listOf(
                VerifiedClaimsValue(
                    id = "employee_credential",
                    type = "EmployeeCredential",
                    claims = mapOf(
                        "vct" to JsonPrimitive("EmployeeCredentialVct"),
                        "iss" to JsonPrimitive("https://issuer.example"),
                        "firstName" to JsonPrimitive("Ada"),
                        "emailAddress" to JsonPrimitive("ada@example.com"),
                    ),
                ),
                VerifiedClaimsValue(
                    id = "eduid_credential",
                    type = "EduIdCredential",
                    claims = mapOf(
                        "affiliation" to JsonPrimitive("employee"),
                    ),
                ),
            ),
        )

        val input = buildReconciliationSelectorInput(
            session = session,
            tenantId = "default",
            verifiedData = verifiedData,
            walletAttributeMappings = WalletAttributeMappings(
                mapOf(
                    "firstName" to "given_name",
                    "emailAddress" to "email",
                )
            ),
            requestedProjection = session.requestedProjection,
        )

        assertEquals("default", input.tenantId)
        assertEquals(OID4VP_SELECTOR_ENTRY_POINT, input.entryPointType)
        assertEquals(OID4VP_SELECTOR_TRIGGER, input.triggerType)
        assertEquals("kw1c-enrollment", input.queryId)
        assertEquals(setOf("employee_credential", "eduid_credential"), input.presentedCredentialIds)
        assertTrue(input.dcqlCredentialQueryIds.contains("employee_credential"))
        assertTrue(input.dcqlCredentialQueryIds.contains("eduid_credential"))
        assertTrue(input.presentedCredentialTypes.contains("EmployeeCredential"))
        assertTrue(input.presentedCredentialTypes.contains("EmployeeCredentialVct"))
        assertTrue(input.presentedCredentialTypes.contains("EduIdCredential"))
        assertEquals(setOf("https://issuer.example"), input.issuers)
        assertEquals(com.sphereon.identity.reconciliation.model.KnownHolderState.NOT_FOUND, input.knownHolderState)
        assertEquals("sts", input.requestedProjection)

        val attributes = input.availableAttributes?.attributes.orEmpty()
        assertEquals(JsonPrimitive("Ada"), attributes[com.sphereon.identity.idv.model.AttributePath("firstName")])
        assertEquals(JsonPrimitive("Ada"), attributes[com.sphereon.identity.idv.model.AttributePath("given_name")])
        assertEquals(JsonPrimitive("ada@example.com"), attributes[com.sphereon.identity.idv.model.AttributePath("emailAddress")])
        assertEquals(JsonPrimitive("ada@example.com"), attributes[com.sphereon.identity.idv.model.AttributePath("email")])
    }

    @Test
    fun `build selector input extracts credential set refs from authorization response`() {
        val verifiedData = VerifiedData(
            credentialClaims = listOf(
                VerifiedClaimsValue(
                    id = "student_credential",
                    type = "StudentCredential",
                    claims = mapOf("iss" to JsonPrimitive("https://issuer.example")),
                )
            ),
            authorizationResponse = JsonObject(
                mapOf(
                    "dcql_response" to JsonObject(
                        mapOf(
                            "credential_set_matches" to JsonArray(
                                listOf(
                                    JsonObject(
                                        mapOf(
                                            "credential_set_id" to JsonPrimitive("0"),
                                            "credential_id" to JsonPrimitive("student_credential"),
                                        )
                                    ),
                                    JsonObject(
                                        mapOf(
                                            "credential_set_id" to JsonPrimitive("1"),
                                            "credential_id" to JsonPrimitive("student_credential"),
                                        )
                                    ),
                                )
                            )
                        )
                    )
                )
            ),
        )

        val input = buildReconciliationSelectorInput(
            session = session(),
            tenantId = "default",
            verifiedData = verifiedData,
            walletAttributeMappings = WalletAttributeMappings(emptyMap()),
        )

        assertEquals(setOf("0", "1"), input.dcqlCredentialSetRefs)
    }

    @Test
    fun `force reconciliation changes trigger and clears translated known-holder state`() {
        val input = buildReconciliationSelectorInput(
            session = session(forceReconciliation = true),
            tenantId = "default",
            verifiedData = VerifiedData(
                credentialClaims = listOf(
                    VerifiedClaimsValue(
                        id = "credential",
                        type = "CredentialType",
                        claims = emptyMap(),
                    )
                )
            ),
            walletAttributeMappings = WalletAttributeMappings(emptyMap()),
        )

        assertEquals(OID4VP_FORCE_RECONCILIATION_TRIGGER, input.triggerType)
        assertNull(input.knownHolderState)
    }

    @Test
    fun `translated known-holder state uses persisted session state when available`() {
        val input = buildReconciliationSelectorInput(
            session = session(knownHolderState = KnownHolderState.MATCHED_CLAIM_TUPLE),
            tenantId = "default",
            verifiedData = VerifiedData(
                credentialClaims = listOf(
                    VerifiedClaimsValue(
                        id = "credential",
                        type = "CredentialType",
                        claims = emptyMap(),
                    )
                )
            ),
            walletAttributeMappings = WalletAttributeMappings(emptyMap()),
        )

        assertEquals(KnownHolderState.MATCHED_CLAIM_TUPLE, input.knownHolderState)
    }

    private fun session(
        forceReconciliation: Boolean = false,
        knownHolderState: KnownHolderState? = null,
        requestedProjection: String? = null,
        now: Instant = Clock.System.now(),
    ) = Oid4vpAuthSession(
        sessionId = "session-1",
        correlationId = "corr-1",
        queryId = "kw1c-enrollment",
        status = Oid4vpAuthSessionStatus.IDV_REQUIRED,
        knownHolderState = knownHolderState,
        requestedProjection = requestedProjection,
        forceReconciliation = forceReconciliation,
        createdAt = now,
        updatedAt = now,
        expiresAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 60_000),
    )
}
