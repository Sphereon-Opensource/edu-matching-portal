package com.sphereon.portal.bridge

import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSession
import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSessionStatus
import com.sphereon.openid.oid4vp.auth.store.Oid4vpAuthSessionStore
import com.sphereon.openid.oid4vp.universal.VerifiedClaimsValue
import com.sphereon.openid.oid4vp.universal.VerifiedData
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.minutes

/**
 * Test-only endpoints for simulating wallet VP presentation in E2E tests.
 *
 * These endpoints allow Playwright tests to simulate a wallet scanning a QR code
 * and presenting a VP, without requiring an actual mobile wallet.
 *
 * ONLY active in development profile. Must NOT be deployed to production.
 */
object TestSimulationEndpoints {

    @Serializable
    data class SimulateVpRequest(
        val vpToken: String? = null,
        val holderDid: String? = null,
        val claims: Map<String, String>? = null,
    )

    fun install(routing: Route, sessionStore: Oid4vpAuthSessionStore) {
        routing.post("/auth/oid4vp/sessions/{sessionId}/present") {
            val sessionId = call.parameters["sessionId"]
                ?: return@post call.respondText(
                    """{"error": "missing_session_id"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )

            val body = try {
                Json.decodeFromString<SimulateVpRequest>(call.receiveText())
            } catch (_: Exception) {
                SimulateVpRequest()
            }

            val sessionResult = sessionStore.get(sessionId)
            val session = sessionResult.getOrNull()
                ?: return@post call.respondText(
                    """{"error": "session_not_found", "message": "Session $sessionId not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )

            if (session.status != Oid4vpAuthSessionStatus.PENDING &&
                session.status != Oid4vpAuthSessionStatus.INTERACTION_STARTED
            ) {
                return@post call.respondText(
                    """{"error": "invalid_state", "message": "Session is in ${session.status} state, expected PENDING or INTERACTION_STARTED"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict
                )
            }

            val now = Clock.System.now()
            val holderDid = body.holderDid ?: "did:key:z6MkSimulatedHolder${sessionId.take(8)}"
            val holderKeyFingerprint = holderDid.substringAfterLast(":")

            // Build simulated VerifiedData so completeAuthentication() can process the session
            val simulatedClaims = (body.claims ?: emptyMap()).mapValues { (_, v) -> JsonPrimitive(v) }
            val verifiedData = VerifiedData(
                credentialClaims = listOf(
                    VerifiedClaimsValue(
                        id = "simulated-credential",
                        type = "SimulatedCredential",
                        claims = simulatedClaims,
                    )
                )
            )

            val updatedSession = session.copy(
                status = Oid4vpAuthSessionStatus.VERIFIED,
                rawHolderKeyFingerprint = holderKeyFingerprint,
                verifiedData = verifiedData,
                updatedAt = now,
            )

            sessionStore.put(sessionId, updatedSession, 5.minutes)

            call.respondText(
                """{"status": "VERIFIED", "sessionId": "$sessionId", "holderDid": "$holderDid", "simulated": true}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }

        // Test-only status endpoint that reads directly from the session store
        // (bypasses Universal OID4VP poll which requires actual VP submission)
        routing.get("/auth/oid4vp/sessions/{sessionId}/_test-status") {
            val sessionId = call.parameters["sessionId"]
                ?: return@get call.respondText(
                    """{"error": "missing_session_id"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )

            val sessionResult = sessionStore.get(sessionId)
            val session = sessionResult.getOrNull()
                ?: return@get call.respondText(
                    """{"error": "session_not_found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )

            call.respondText(
                """{"sessionId": "${session.sessionId}", "status": "${session.status.name}", "holderIdentifierHash": ${session.holderIdentifierHash?.let { "\"$it\"" } ?: "null"}, "expiresAt": ${session.expiresAt.toEpochMilliseconds()}}""",
                ContentType.Application.Json,
                HttpStatusCode.OK
            )
        }
    }
}
