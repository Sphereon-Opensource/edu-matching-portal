/*
 * Copyright 2025 Sphereon International B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sphereon.portal.bridge.orchestration

import com.sphereon.core.api.Err
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.HttpJson
import com.sphereon.identity.idv.model.AttributeBag
import com.sphereon.identity.idv.model.AttributePath
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.model.AssuranceSummary
import com.sphereon.identity.matching.model.IdentifierType
import com.sphereon.identity.matching.model.IdentityLinkBinding
import com.sphereon.identity.matching.model.PersistedAttributesEnvelope
import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.identity.reconciliation.command.CompleteReconciliationCommand
import com.sphereon.identity.reconciliation.command.CreateReconciliationSessionCommand
import com.sphereon.identity.reconciliation.command.GetReconciliationSessionCommand
import com.sphereon.identity.reconciliation.model.CompleteReconciliationArgs
import com.sphereon.identity.reconciliation.model.CreateReconciliationSessionArgs
import com.sphereon.identity.reconciliation.model.GetReconciliationSessionArgs
import com.sphereon.identity.reconciliation.api.ReconciliationSelector
import com.sphereon.identity.reconciliation.model.FailClosed
import com.sphereon.identity.reconciliation.model.KnownHolderState
import com.sphereon.identity.reconciliation.model.ReconciliationSelectorRule
import com.sphereon.identity.reconciliation.model.ResolvedIdentity
import com.sphereon.identity.reconciliation.model.RunIdv
import com.sphereon.identity.reconciliation.model.SkipReconciliation
import com.sphereon.identity.reconciliation.model.StepUp
import com.sphereon.identity.reconciliation.model.UseExistingBinding
import com.sphereon.identity.reconciliation.store.ReconciliationProviderStore
import com.sphereon.identity.reconciliation.store.ReconciliationSessionStore
import com.sphereon.openid.oid4vp.auth.config.Oid4vpAuthBridgeConfigProvider
import com.sphereon.openid.oid4vp.auth.error.Oid4vpAuthErrors
import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthResult
import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSessionStatus
import com.sphereon.openid.oid4vp.auth.orchestration.ResolvedKnownHolder
import com.sphereon.openid.oid4vp.auth.orchestration.ReconciliationCallbackResult
import com.sphereon.openid.oid4vp.auth.orchestration.ReconciliationInitiateResult
import com.sphereon.openid.oid4vp.auth.orchestration.ReconciliationOrchestratorApi
import com.sphereon.openid.oid4vp.auth.orchestration.ReconciliationStatusResult
import com.sphereon.openid.oid4vp.auth.store.Oid4vpAuthSessionStore
import com.sphereon.openid.oid4vp.universal.VerifiedData
import com.sphereon.identity.reconciliation.api.attributesToPersist
import com.sphereon.identity.reconciliation.api.attributesToProject
import com.sphereon.identity.reconciliation.api.validateRequiredAttributes
import com.sphereon.identity.reconciliation.api.ReconciliationMaterialService
import com.sphereon.identity.reconciliation.model.CanonicalAttributeBag
import com.sphereon.identity.reconciliation.model.CanonicalAttributeRule
import com.sphereon.identity.reconciliation.model.CanonicalMergeMode
import com.sphereon.identity.reconciliation.model.AttributeProvenanceSummary
import com.sphereon.identity.reconciliation.model.ReconciliationMaterialProfile
import com.sphereon.identity.reconciliation.model.ReconciliationPlan
import com.sphereon.portal.bridge.WalletAttributeMappings
import com.sphereon.openid.oid4vp.auth.model.ReconciliationPlanType
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.Inject
import org.slf4j.LoggerFactory
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Orchestrates the full reconciliation flow for identity verification (IDV).
 *
 * This class lives in the portal service (service-auth-bridge), NOT in IDK.
 * It coordinates IDK building blocks (commands, stores, crypto) to implement
 * the reconciliation lifecycle:
 *
 * 1. [initiateReconciliation] - Creates a reconciliation session and generates an OIDC auth URL
 * 2. [handleCallback] - Processes the OIDC callback, completes reconciliation, updates session
 * 3. [getStatus] - Checks reconciliation session status for a given OID4VP session
 *
 * The IDK IDV endpoint commands delegate to this orchestrator via the
 * [ReconciliationOrchestratorApi] interface.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ReconciliationOrchestratorApi>())
class ReconciliationOrchestrator(
    private val createReconciliationSessionCommand: CreateReconciliationSessionCommand,
    private val completeReconciliationCommand: CompleteReconciliationCommand,
    private val getReconciliationSessionCommand: GetReconciliationSessionCommand,
    private val selectorRules: List<ReconciliationSelectorRule>,
    private val selectorRuleVersion: String,
    private val oid4vpSessionStore: Oid4vpAuthSessionStore,
    private val reconciliationSessionStore: ReconciliationSessionStore,
    private val reconciliationCryptoService: ReconciliationCryptoService,
    private val identityMatchStore: IdentityMatchStore,
    private val identityLinkBindingStore: IdentityLinkBindingStore,
    private val walletAttributeMappings: WalletAttributeMappings,
    private val reconciliationProviderStore: ReconciliationProviderStore,
    private val canonicalAttributeRules: List<CanonicalAttributeRule>,
    private val materialProfiles: Map<String, ReconciliationMaterialProfile>,
    private val materialService: ReconciliationMaterialService,
    private val authBridgeConfigProvider: Oid4vpAuthBridgeConfigProvider,
    private val institutionLookupConfig: com.sphereon.portal.bridge.external.InstitutionLookupConfig,
) : ReconciliationOrchestratorApi {

    private val log = LoggerFactory.getLogger(ReconciliationOrchestrator::class.java)

    override suspend fun resolveKnownHolder(
        holderKeyHash: String,
        tenantId: String,
        rawHolderKey: String?,
        walletClaims: Map<String, JsonElement>?,
    ): IdkResult<ResolvedKnownHolder?, IdkError> {
        // 1. Look up existing identity link binding by holder hash
        log.info("resolveKnownHolder: looking up binding for hash=${holderKeyHash.take(16)}..., tenantId=$tenantId")
        var binding = identityLinkBindingStore.findByHolderHash(tenantId, holderKeyHash)
        var matchState = KnownHolderState.MATCHED_HOLDER_KEY

        // Dual-read fallback: try previous key version
        if (binding == null && rawHolderKey != null) {
            val previousHash = reconciliationCryptoService.hashHolderKeyWithPrevious(rawHolderKey)
            if (previousHash != null) {
                binding = identityLinkBindingStore.findByHolderHash(tenantId, previousHash.hash)
                if (binding != null) {
                    log.info("Dual-read fallback: found binding {} via previous key version for holder", binding.id)
                }
            }
        }

        // 2. Multi-material fallback: if holder-key lookup failed and wallet claims are available,
        //    derive claim-tuple materials and look up each in IdentityMatch
        if (binding == null && walletClaims != null) {
            val tupleLookupResult = attemptClaimTupleLookup(walletClaims, tenantId)
            if (tupleLookupResult != null) {
                binding = tupleLookupResult.first
                matchState = KnownHolderState.MATCHED_CLAIM_TUPLE
                log.info("Claim-tuple material match: found binding {} via match {}", binding.id, tupleLookupResult.second)
            }
        }

        if (binding == null) {
            return Ok(null)
        }

        // 3. Decrypt canonical claims
        val encryptedClaims = binding.persistedAttributesEnvelope.encrypted

        val canonicalClaims: Map<String, JsonElement> = try {
            val decryptedJson = reconciliationCryptoService.decrypt(encryptedClaims)
            HttpJson.restApi.decodeFromString(JsonObject.serializer(), decryptedJson)
        } catch (e: Exception) {
            log.error("Failed to decrypt canonical claims for binding {}: {}", binding.id, e.message)
            return Ok(null)
        }

        // 4. Decrypt the institution ID to get the user ID
        val userId = binding.encryptedInstitutionId?.let { encrypted ->
            try {
                reconciliationCryptoService.decrypt(encrypted)
            } catch (e: Exception) {
                log.error("Failed to decrypt institution ID for binding {}: {}", binding.id, e.message)
                null
            }
        } ?: return Ok(null)

        // 5. Update lastUsedAt
        val now = Clock.System.now()
        try {
            identityLinkBindingStore.update(binding.copy(lastUsedAt = now, updatedAt = now))
            val match = identityMatchStore.findById(tenantId, binding.matchId)
            if (match != null) {
                identityMatchStore.update(match.copy(lastUsedAt = now))
            }
        } catch (e: Exception) {
            log.warn("Failed to update lastUsedAt for binding {}: {}", binding.id, e.message)
        }

        log.info("Known holder resolved via binding {} for match {} (state={})", binding.id, binding.matchId, matchState)

        return Ok(ResolvedKnownHolder(
            userId = userId,
            canonicalAttributes = canonicalClaims,
            matchId = binding.matchId,
            bindingId = binding.id,
            assurance = binding.assuranceSummary,
            state = matchState,
            decryptedInstitutionId = userId,
            selectorRuleVersion = binding.selectorRuleVersion,
            reconcileTime = binding.createdAt,
        ))
    }

    /**
     * Attempt claim-tuple material lookup: derive materials from wallet claims,
     * look up each in IdentityMatch, and if a match is found, resolve its binding.
     *
     * Returns the (binding, matchId) pair if found, null otherwise.
     */
    private suspend fun attemptClaimTupleLookup(
        walletClaims: Map<String, JsonElement>,
        tenantId: String,
    ): Pair<IdentityLinkBinding, String>? {
        // Map wallet claims through configured mappings to canonical names
        val mappedClaims = buildMap {
            for ((key, value) in walletClaims) {
                val canonicalKey = walletAttributeMappings.resolve(key) ?: key
                put(canonicalKey, value)
            }
        }

        // Build a minimal canonical bag for material derivation
        val canonicalBag = CanonicalAttributeBag(
            attributes = mappedClaims,
            provenance = AttributeProvenanceSummary(),
        )

        // Try each material profile that has claim-tuple materials
        for ((_, profile) in materialProfiles) {
            try {
                val derivedMaterials = materialService.deriveMaterials(
                    profile = profile,
                    holderKey = null,
                    providerSubject = null,
                    canonicalAttributes = canonicalBag,
                )
                for (material in derivedMaterials) {
                    if (material.identifierType == IdentifierType.CLAIM_TUPLE) {
                        val match = identityMatchStore.findByIdentifierHash(
                            tenantId, material.hash.hash, material.identifierType,
                        )
                        if (match != null) {
                            // Found a claim-tuple match — resolve the binding via the match's identity
                            val binding = resolveBindingForMaterialMatch(tenantId, match)
                            if (binding != null) return binding to match.id
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("Claim-tuple lookup failed for profile {}: {}", profile.id, e.message)
            }
        }
        return null
    }

    private suspend fun resolveBindingForMaterialMatch(
        tenantId: String,
        match: com.sphereon.identity.matching.model.IdentityMatch,
    ): IdentityLinkBinding? {
        identityLinkBindingStore.findByMatchId(tenantId, match.id)?.let { return it }

        val siblingMatches = identityMatchStore.findByInternalIdentityId(tenantId, match.internalIdentityId)
        for (candidate in siblingMatches) {
            identityLinkBindingStore.findByMatchId(tenantId, candidate.id)?.let { return it }
        }

        return null
    }

    override suspend fun initiateReconciliation(
        oid4vpSessionId: String,
        redirectUri: String,
        baseUrl: String?
    ): IdkResult<ReconciliationInitiateResult, IdkError> {
        // 1. Get session and verify IDV_REQUIRED status
        val session = oid4vpSessionStore.get(oid4vpSessionId).getOrElse { error ->
            return Err(error)
        } ?: return Err(Oid4vpAuthErrors.sessionNotFound(oid4vpSessionId))

        if (session.status != Oid4vpAuthSessionStatus.IDV_REQUIRED) {
            return Err(Oid4vpAuthErrors.sessionNotIdvRequired(oid4vpSessionId))
        }

        val tenantId = "default"

        // 2. Build the richer selector context from the verified OID4VP session.
        val verifiedData = session.verifiedData
            ?: return Err(Oid4vpAuthErrors.verifiedDataMissing(oid4vpSessionId))

        // 3. Evaluate selector rules to determine reconciliation plan
        val selectorInput = buildReconciliationSelectorInput(
            session = session,
            tenantId = tenantId,
            verifiedData = verifiedData,
            walletAttributeMappings = walletAttributeMappings,
            requestedProjection = session.requestedProjection,
        )
        val plan = ReconciliationSelector.evaluate(selectorRules, selectorInput, selectorRuleVersion)
            ?: run {
                val credentialType = selectorInput.presentedCredentialTypes.firstOrNull() ?: "unknown"
                persistInitiationFailure(
                    session = session,
                    message = "No reconciliation selector rule matched for queryId=${selectorInput.queryId ?: "n/a"} " +
                        "credentialTypes=${selectorInput.presentedCredentialTypes} credentialIds=${selectorInput.presentedCredentialIds}"
                )
                return Err(Oid4vpAuthErrors.noReconciliationMapping(credentialType))
            }

        val providerId = when (plan) {
            is RunIdv -> plan.providerId
            is StepUp -> plan.providerId
            is FailClosed -> {
                persistInitiationFailure(session, "Reconciliation selector failed closed: ${plan.reason}")
                return Err(Oid4vpAuthErrors.reconciliationFailed(plan.reason))
            }
            is SkipReconciliation -> {
                // Wallet claims are sufficient — complete the session directly without OIDC IDV.
                return executeSkipReconciliation(session, verifiedData, tenantId)
            }
            is UseExistingBinding -> {
                // The selector says an existing binding should be used, but the fast-path
                // in the auth provider already checked and didn't find one. This means
                // the selector's known-holder state was stale or the binding expired.
                // Re-check once here; if still not found, fail closed.
                return executeUseExistingBinding(session, tenantId)
            }
        }

        log.info(
            "Selector resolved plan '{}' with provider '{}' for queryId={}, credentialIds={}, dcqlQueryIds={}, credentialSetRefs={}, knownHolder={}",
            plan::class.simpleName,
            providerId,
            selectorInput.queryId,
            selectorInput.presentedCredentialIds,
            selectorInput.dcqlCredentialQueryIds,
            selectorInput.dcqlCredentialSetRefs,
            selectorInput.knownHolderState
        )

        // 4. Build STS reconciliation authorize URL.
        // The STS is the single OIDC RP — the auth-bridge no longer talks to IdPs directly.
        // The STS handles authorization, code exchange, and claim extraction, then POSTs
        // the raw claims back to the auth-bridge's /reconciliation/complete endpoint.
        val config = authBridgeConfigProvider.getConfig()
        val stsBaseUrl = config.stsBaseUrl
            ?: System.getenv("STS_BASE_URL")
            ?: "http://localhost:8092"
        val stsAuthorizationUrl = "${stsBaseUrl.trimEnd('/')}/reconciliation/authorize" +
            "?oid4vp_session=$oid4vpSessionId&provider=$providerId"

        // 5. Update session with plan metadata (no reconciliation session needed — STS manages OIDC state)
        val now = Clock.System.now()
        val updatedSession = session.copy(
            materialProfileId = plan.materialProfileId,
            reconciliationPlanType = planTypeOf(plan),
            updatedAt = now
        )
        oid4vpSessionStore.put(oid4vpSessionId, updatedSession, (session.expiresAt - now).coerceAtLeast(60.seconds))

        log.info("[AUDIT] reconciliation.initiated: tenant={}, sessionId={}, providerId={}, plan={}",
            tenantId, oid4vpSessionId, providerId, plan::class.simpleName)

        return Ok(
            ReconciliationInitiateResult(
                sessionId = oid4vpSessionId,
                reconciliationSessionId = null,
                authorizationUrl = stsAuthorizationUrl,
                planType = planTypeOf(plan),
                idvRequirementReason = session.idvRequirementReason,
            )
        )
    }

    private suspend fun executeSkipReconciliation(
        session: com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSession,
        verifiedData: VerifiedData,
        tenantId: String,
    ): IdkResult<ReconciliationInitiateResult, IdkError> {
        val sessionId = session.sessionId
        val now = Clock.System.now()

        // Build canonical claims from wallet data only (no OIDC claims)
        val walletClaims = verifiedData.credentials.flatMap { cred ->
            cred.claims.entries
        }.associate { it.key to it.value }
        val canonicalBag = buildCanonicalClaims(walletClaims, emptyMap(), "none")

        // Validate required attributes
        val violations = validateRequiredAttributes(canonicalBag, canonicalAttributeRules)
        if (violations.isNotEmpty()) {
            val detail = violations.joinToString(", ") { it.canonicalName }
            persistInitiationFailure(session, "SkipReconciliation: required attributes missing: $detail")
            return Err(Oid4vpAuthErrors.reconciliationFailed("Required attributes missing: $detail"))
        }

        // Project attributes and complete the session directly
        val projectedClaims = attributesToProject(canonicalBag, canonicalAttributeRules)
        val updatedSession = session.copy(
            status = Oid4vpAuthSessionStatus.VERIFIED,
            resolvedUserId = projectedClaims["sub"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            },
            updatedAt = now,
            idvRequirementReason = null,
            idvMessage = null,
        )
        oid4vpSessionStore.put(sessionId, updatedSession, (session.expiresAt - now).coerceAtLeast(60.seconds))

        log.info("SkipReconciliation: session {} completed with wallet-only claims", sessionId)
        return Ok(ReconciliationInitiateResult(
            sessionId = sessionId,
            reconciliationSessionId = null,
            authorizationUrl = null,
            planType = session.reconciliationPlanType,
            idvRequirementReason = session.idvRequirementReason,
        ))
    }

    private suspend fun executeUseExistingBinding(
        session: com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSession,
        tenantId: String,
    ): IdkResult<ReconciliationInitiateResult, IdkError> {
        val sessionId = session.sessionId
        val holderKeyHash = session.holderIdentifierHash
            ?: run {
                persistInitiationFailure(session, "UseExistingBinding: no holder identifier hash")
                return Err(Oid4vpAuthErrors.reconciliationFailed("No holder identifier hash for UseExistingBinding"))
            }

        // Re-check for existing binding
        val binding = identityLinkBindingStore.findByHolderHash(tenantId, holderKeyHash)
        if (binding == null) {
            persistInitiationFailure(session, "UseExistingBinding: no binding found for holder")
            return Err(Oid4vpAuthErrors.reconciliationFailed("Selector returned UseExistingBinding but no binding exists"))
        }

        // Check version staleness before using stored claims
        val currentSchemaVersion = "1"
        if (binding.canonicalSchemaVersion != null && binding.canonicalSchemaVersion != currentSchemaVersion) {
            log.info("UseExistingBinding: stale canonicalSchemaVersion (stored={}, current={}) - triggering re-IDV",
                binding.canonicalSchemaVersion, currentSchemaVersion)
            persistInitiationFailure(session, "UseExistingBinding: stored claims have stale schema version, re-IDV required")
            return Err(Oid4vpAuthErrors.reconciliationFailed("Stored claims are stale (schema version mismatch), re-IDV required"))
        }
        val currentMaterialProfileVersion = session.materialProfileId?.let { materialProfiles[it]?.version }
        if (binding.materialProfileVersion != null && currentMaterialProfileVersion != null
            && binding.materialProfileVersion != currentMaterialProfileVersion) {
            log.info("UseExistingBinding: stale materialProfileVersion (stored={}, current={}) - materials need re-derivation",
                binding.materialProfileVersion, currentMaterialProfileVersion)
            // Material profile change doesn't require re-IDV if claims are still current,
            // but we log it. A future enhancement could re-derive materials here without full re-IDV.
        }

        // Decrypt stored canonical claims
        val canonicalClaims: Map<String, JsonElement> = decryptPersistedAttributes(binding) ?: run {
            persistInitiationFailure(session, "UseExistingBinding: could not decrypt binding attributes")
            return Err(Oid4vpAuthErrors.reconciliationFailed("Could not decrypt binding attributes"))
        }

        // Filter stored claims through projection rules (only project: true claims reach the session)
        val storedBag = CanonicalAttributeBag(
            attributes = canonicalClaims,
            provenance = AttributeProvenanceSummary(),
            canonicalSchemaVersion = binding.persistedAttributesEnvelope.canonicalSchemaVersion,
            selectorRuleVersion = binding.persistedAttributesEnvelope.selectorRuleVersion,
        )
        val projectedClaims = attributesToProject(storedBag, canonicalAttributeRules)

        // Decrypt user ID
        val userId = binding.encryptedInstitutionId?.let { encrypted ->
            try { reconciliationCryptoService.decrypt(encrypted) } catch (_: Exception) { null }
        }

        // Update binding lastUsedAt
        val now = Clock.System.now()
        try {
            identityLinkBindingStore.update(binding.copy(lastUsedAt = now, updatedAt = now))
        } catch (_: Exception) { /* non-fatal */ }

        // Complete the session with projected claims only
        val updatedSession = session.copy(
            status = Oid4vpAuthSessionStatus.VERIFIED,
            resolvedUserId = userId,
            updatedAt = now,
            idvRequirementReason = null,
            idvMessage = null,
        )
        oid4vpSessionStore.put(sessionId, updatedSession, (session.expiresAt - now).coerceAtLeast(60.seconds))

        log.info("UseExistingBinding: session {} completed via binding {}", sessionId, binding.id)
        return Ok(ReconciliationInitiateResult(
            sessionId = sessionId,
            reconciliationSessionId = null,
            authorizationUrl = null,
            planType = session.reconciliationPlanType,
            idvRequirementReason = session.idvRequirementReason,
        ))
    }

    private suspend fun decryptPersistedAttributes(binding: IdentityLinkBinding): Map<String, JsonElement>? {
        return try {
            val json = reconciliationCryptoService.decrypt(binding.persistedAttributesEnvelope.encrypted)
            HttpJson.restApi.decodeFromString(JsonObject.serializer(), json)
        } catch (e: Exception) {
            log.error("Failed to decrypt persisted attributes for binding {}: {}", binding.id, e.message)
            null
        }
    }

    private suspend fun createPersistedAttributesEnvelope(
        canonicalClaims: Map<String, JsonElement>,
        materialProfileVersion: String?,
        materialFingerprints: Set<String>,
        now: kotlinx.datetime.Instant,
    ): PersistedAttributesEnvelope {
        val canonicalClaimsJson = HttpJson.restApi.encodeToString(
            JsonObject.serializer(),
            JsonObject(canonicalClaims),
        )
        val encryptedClaims = reconciliationCryptoService.encrypt(canonicalClaimsJson)
        return PersistedAttributesEnvelope(
            encrypted = encryptedClaims,
            canonicalSchemaVersion = "1",
            materialProfileVersion = materialProfileVersion ?: "unknown",
            selectorRuleVersion = selectorRuleVersion,
            attributeNames = canonicalClaims.keys,
            materialFingerprints = materialFingerprints,
            updatedAt = now,
        )
    }

    private suspend fun persistInitiationFailure(
        session: com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSession,
        message: String,
    ) {
        val now = Clock.System.now()
        val failedSession = session.copy(
            status = Oid4vpAuthSessionStatus.ERROR,
            errorMessage = message,
            idvRequirementReason = null,
            idvMessage = message,
            updatedAt = now,
        )
        oid4vpSessionStore.put(
            session.sessionId,
            failedSession,
            (session.expiresAt - now).coerceAtLeast(60.seconds)
        )
    }

    override suspend fun handleCallback(
        code: String,
        state: String,
        walletClaims: Map<String, JsonElement>
    ): IdkResult<ReconciliationCallbackResult, IdkError> {
        log.info("IDV callback - looking up reconciliation session for state: {}", state)

        // 1. Find reconciliation session by state
        val reconciliationSession = reconciliationSessionStore.findByState("default", state)
            ?: return Err(Oid4vpAuthErrors.reconciliationFailed("No reconciliation session found for state: $state"))

        // 2. Find linked OID4VP session via reconciliation session ID
        val session = oid4vpSessionStore.findByReconciliationSessionId(reconciliationSession.id).getOrElse { error ->
            return Err(error)
        } ?: return Err(Oid4vpAuthErrors.reconciliationFailed("No OID4VP session linked to reconciliation session"))

        val sessionId = session.sessionId

        if (session.status != Oid4vpAuthSessionStatus.IDV_REQUIRED) {
            return Err(Oid4vpAuthErrors.sessionNotIdvRequired(sessionId))
        }

        // 3. Complete reconciliation (exchanges code for tokens, creates identity match)
        val holderKeyHash = session.holderIdentifierHash ?: "unknown"
        val holderKeyVersion = session.holderHashKeyVersion ?: "unknown"
        val tenantId = "default"

        log.info("handleCallback: completing reconciliation session={}, oid4vpSession={}, holderHash={}...",
            reconciliationSession.id, sessionId, holderKeyHash.take(16))

        val reconciliationResult = try {
            completeReconciliationCommand.execute(
                CompleteReconciliationArgs(
                    sessionId = reconciliationSession.id,
                    tenantId = tenantId,
                    authorizationCode = code,
                    state = state,
                    internalIdentityId = holderKeyHash,
                    hashKeyVersion = holderKeyVersion
                )
            ).getOrElse { error ->
                log.error("Reconciliation callback failed for session {}: {} (code={})",
                    sessionId, error.message.defaultMessage, error.code)
                return Err(error)
            }
        } catch (e: Exception) {
            log.error("handleCallback EXCEPTION during completeReconciliation: ${e::class.simpleName}: ${e.message}", e)
            return Err(Oid4vpAuthErrors.reconciliationFailed("Token exchange exception: ${e.message}"))
        }

        log.info("handleCallback: reconciliation completed, resolvedIdentity encrypted={}", reconciliationResult.session.encryptedIdentity != null)

        // Decrypt the resolved identity from the session's encrypted payload
        val resolvedIdentity: ResolvedIdentity? = reconciliationResult.session.encryptedIdentity?.let { encrypted ->
            try {
                val json = reconciliationCryptoService.decrypt(encrypted)
                HttpJson.restApi.decodeFromString(ResolvedIdentity.serializer(), json)
            } catch (e: Exception) {
                log.error("Failed to decrypt resolved identity for session {}: {}", reconciliationResult.session.id, e.message)
                null
            }
        }

        val reconciledSubject = resolvedIdentity?.externalSubject
        val providerId = reconciliationResult.session.providerId
        val now = Clock.System.now()

        // Resolve assurance metadata from the reconciliation provider's configuration.
        // The provider's assuranceAcr/assuranceAmr reflect the OIDC provider's reported
        // assurance level (e.g., SURF's ACR for institutional login).
        val provider = reconciliationProviderStore.findById(providerId)
        val providerAcr = provider?.assuranceAcr ?: Oid4vpAuthResult.RECONCILED_ACR
        val providerAmr = provider?.assuranceAmr ?: Oid4vpAuthResult.RECONCILED_AMR

        // 4. Merge wallet + OIDC claims into canonical claim bag with provenance.
        val effectiveWalletClaims = walletClaims.ifEmpty {
            session.verifiedData?.credentials?.flatMap { cred ->
                cred.claims.entries
            }?.associate { it.key to it.value } ?: emptyMap()
        }
        val oidcClaims = resolvedIdentity?.claims ?: emptyMap()
        val canonicalBag = buildCanonicalClaims(effectiveWalletClaims, oidcClaims, providerId)

        // 5. Validate required attributes before proceeding
        val violations = validateRequiredAttributes(canonicalBag, canonicalAttributeRules)
        if (violations.isNotEmpty()) {
            val violationDetails = violations.joinToString(", ") { "${it.canonicalName} (expected from: ${it.expectedSourceHint ?: "any"})" }
            log.warn("Required attributes missing after reconciliation: {}", violationDetails)
            return Err(Oid4vpAuthErrors.reconciliationFailed(
                "Required attributes missing: $violationDetails"
            ))
        }

        // 6. Derive materials per profile (if a profile was selected by the plan during initiation)
        val materialProfileId = session.materialProfileId
        val materialProfile = materialProfileId?.let { materialProfiles[it] }

        // Build credential-scoped attribute bags for CredentialAttributeTupleMaterial derivation
        val credentialScopedClaims = session.verifiedData?.credentials
            ?.filter { it.id.isNotBlank() }
            ?.associate { cred ->
                cred.id to AttributeBag(
                    attributes = cred.claims.mapKeys { (k, _) -> AttributePath(k) },
                )
            }

        val materialFingerprints = if (materialProfile != null) {
            try {
                val derivedMaterials = materialService.deriveMaterials(
                    profile = materialProfile,
                    holderKey = session.rawHolderKeyFingerprint,
                    providerSubject = reconciledSubject,
                    canonicalAttributes = canonicalBag,
                    credentialScopedAttributes = credentialScopedClaims,
                )
                // Store additional derived materials as IdentityMatch records
                for (material in derivedMaterials) {
                    if (material.hash.hash != holderKeyHash) {
                        val existingMatch = identityMatchStore.findByIdentifierHash(
                            tenantId, material.hash.hash, material.identifierType,
                        )
                        if (existingMatch == null) {
                            identityMatchStore.create(
                                com.sphereon.identity.matching.model.IdentityMatch(
                                    id = generateId(),
                                    identifierHash = material.hash.hash,
                                    identifierType = material.identifierType,
                                    internalIdentityId = reconciliationResult.match.internalIdentityId,
                                    tenantId = tenantId,
                                    hashKeyVersion = material.hash.keyVersion,
                                    createdAt = now,
                                    lastUsedAt = now,
                                ),
                            )
                            log.info("Created material-derived identity match: type={}, profile={}", material.materialType, materialProfileId)
                        }
                    }
                }
                // Exclude holder key hash — it's already the binding's primary identifier
                derivedMaterials.map { it.hash.hash }.filter { it != holderKeyHash }.toSet()
            } catch (e: Exception) {
                log.warn("Material derivation failed (non-fatal): {}", e.message)
                emptySet()
            }
        } else {
            emptySet()
        }

        // 6b. Create institution identifier match for reverse lookup (GDPR Art. 15)
        createInstitutionMatch(canonicalBag.attributes, reconciliationResult.match.internalIdentityId, tenantId, now)

        // 7. Determine which attributes to persist in the binding
        val attributesToPersistMap = attributesToPersist(canonicalBag, canonicalAttributeRules)

        // 8. Create or update identity link binding
        val isStepUp = session.reconciliationPlanType == ReconciliationPlanType.STEP_UP
        if (isStepUp) {
            // StepUp: the holder was matched via a candidate material (e.g., claim tuple).
            // Update the existing binding with refreshed claims rather than creating a new one.
            val existingBinding = identityLinkBindingStore.findByMatchId(tenantId, reconciliationResult.match.id)
            if (existingBinding != null) {
                val envelope = createPersistedAttributesEnvelope(
                    canonicalClaims = attributesToPersistMap,
                    materialProfileVersion = materialProfile?.version,
                    materialFingerprints = materialFingerprints,
                    now = now,
                )
                identityLinkBindingStore.update(existingBinding.copy(
                    persistedAttributesEnvelope = envelope,
                    canonicalSchemaVersion = envelope.canonicalSchemaVersion,
                    selectorRuleVersion = envelope.selectorRuleVersion,
                    persistedAttributeNames = envelope.attributeNames,
                    materialFingerprints = envelope.materialFingerprints.ifEmpty { null },
                    materialProfileVersion = envelope.materialProfileVersion,
                    updatedAt = now,
                    lastUsedAt = now,
                ))
                log.info("StepUp: updated existing binding {} with refreshed claims", existingBinding.id)
            } else {
                // No existing binding found for the match — fall through to create a new one
                log.info("StepUp: no existing binding for match {}, creating new binding", reconciliationResult.match.id)
                createIdentityLinkBinding(
                    tenantId = tenantId, holderKeyHash = holderKeyHash, holderKeyVersion = holderKeyVersion,
                    matchId = reconciliationResult.match.id, externalSubject = reconciledSubject,
                    canonicalClaims = attributesToPersistMap, reconciliationSessionId = reconciliationSession.id,
                    providerId = providerId, assuranceAcr = providerAcr, assuranceAmr = providerAmr,
                    materialFingerprints = materialFingerprints, materialProfileVersion = materialProfile?.version,
                    now = now,
                ).getOrElse { error -> return Err(error) }
            }
        } else {
            createIdentityLinkBinding(
                tenantId = tenantId, holderKeyHash = holderKeyHash, holderKeyVersion = holderKeyVersion,
                matchId = reconciliationResult.match.id, externalSubject = reconciledSubject,
                canonicalClaims = attributesToPersistMap, reconciliationSessionId = reconciliationSession.id,
                providerId = providerId, assuranceAcr = providerAcr, assuranceAmr = providerAmr,
                materialFingerprints = materialFingerprints, materialProfileVersion = materialProfile?.version,
                now = now,
            ).getOrElse { error -> return Err(error) }
        }

        // 9. Clear encryptedIdentity from reconciliation session now that claims are persisted to binding
        try {
            reconciliationSessionStore.update(reconciliationResult.session.copy(encryptedIdentity = null))
        } catch (e: Exception) {
            log.debug("Failed to clear encryptedIdentity from reconciliation session (non-fatal): {}", e.message)
        }

        // 10. Derive projected claims for the STS token (project: true attributes only)
        val projectedClaims = attributesToProject(canonicalBag, canonicalAttributeRules)

        // 11. Update OID4VP session back to VERIFIED
        val updatedSession = session.copy(
            status = Oid4vpAuthSessionStatus.VERIFIED,
            resolvedUserId = reconciledSubject,
            updatedAt = now,
            idvRequirementReason = null,
            idvMessage = null
        )
        oid4vpSessionStore.put(sessionId, updatedSession, (session.expiresAt - now).coerceAtLeast(60.seconds))

        log.info("[AUDIT] reconciliation.completed: tenant={}, sessionId={}, matchId={}, providerId={}, method=oidc-callback",
            tenantId, sessionId, reconciliationResult.match.id, providerId)

        return Ok(
            ReconciliationCallbackResult(
                oid4vpSessionId = sessionId,
                matchId = reconciliationResult.match.id,
                resolvedUserId = reconciledSubject,
                canonicalAttributes = projectedClaims,
                assurance = AssuranceSummary(
                    oidcAcr = providerAcr,
                    oidcAmr = providerAmr,
                    executionId = reconciliationSession.id
                )
            )
        )
    }

    override suspend fun handleCallbackWithClaims(
        oid4vpSessionId: String,
        claims: Map<String, JsonElement>,
        issuer: String,
        providerId: String,
    ): IdkResult<ReconciliationCallbackResult, IdkError> {
        log.info("handleCallbackWithClaims: processing pre-extracted claims for session {}, provider={}", oid4vpSessionId, providerId)

        // 1. Find OID4VP session
        val session = oid4vpSessionStore.get(oid4vpSessionId).getOrElse { error ->
            return Err(error)
        } ?: return Err(Oid4vpAuthErrors.sessionNotFound(oid4vpSessionId))

        if (session.status != Oid4vpAuthSessionStatus.IDV_REQUIRED) {
            return Err(Oid4vpAuthErrors.sessionNotIdvRequired(oid4vpSessionId))
        }

        val holderKeyHash = session.holderIdentifierHash ?: "unknown"
        val holderKeyVersion = session.holderHashKeyVersion ?: "unknown"
        val tenantId = "default"
        val now = Clock.System.now()

        // 2. Claims are already JsonElement — use directly for canonical merging
        val oidcClaims: Map<String, JsonElement> = claims

        // 3. Resolve assurance metadata from the reconciliation provider
        val provider = reconciliationProviderStore.findById(providerId)
        val providerAcr = provider?.assuranceAcr ?: Oid4vpAuthResult.RECONCILED_ACR
        val providerAmr = provider?.assuranceAmr ?: Oid4vpAuthResult.RECONCILED_AMR

        // 4. Apply provider attribute mappings to the OIDC claims (source → canonical-name)
        val mappedOidcClaims: Map<String, JsonElement> = if (provider != null) {
            val mappings = provider.attributeMappings
            if (mappings.isNotEmpty()) {
                buildMap {
                    for (mapping in mappings) {
                        val value = oidcClaims[mapping.source]
                        if (value != null) {
                            put(mapping.target, value)
                        }
                    }
                }
            } else oidcClaims
        } else oidcClaims

        // 5. Merge wallet + OIDC claims into canonical bag
        val effectiveWalletClaims = session.verifiedData?.credentials?.flatMap { cred ->
            cred.claims.entries
        }?.associate { it.key to it.value } ?: emptyMap()
        val canonicalBag = buildCanonicalClaims(effectiveWalletClaims, mappedOidcClaims, providerId)

        // 6. Validate required attributes
        val violations = validateRequiredAttributes(canonicalBag, canonicalAttributeRules)
        if (violations.isNotEmpty()) {
            val violationDetails = violations.joinToString(", ") { "${it.canonicalName} (expected from: ${it.expectedSourceHint ?: "any"})" }
            log.warn("Required attributes missing after reconciliation with claims: {}", violationDetails)
            return Err(Oid4vpAuthErrors.reconciliationFailed("Required attributes missing: $violationDetails"))
        }

        // 7. Create identity match
        val reconciledSubject = mappedOidcClaims[provider?.identifierAttributeName ?: "sub"]?.let {
            (it as? JsonPrimitive)?.content
        } ?: (claims["sub"] as? JsonPrimitive)?.content

        val reconciliationSessionId = session.reconciliationSessionId
        val matchId = generateId()
        val internalIdentityId = holderKeyHash
        identityMatchStore.create(
            com.sphereon.identity.matching.model.IdentityMatch(
                id = matchId,
                identifierHash = holderKeyHash,
                identifierType = IdentifierType.KEY,
                internalIdentityId = internalIdentityId,
                tenantId = tenantId,
                hashKeyVersion = holderKeyVersion,
                createdAt = now,
                lastUsedAt = now,
            ),
        )

        log.info("[AUDIT] identity.match.created: tenant={}, matchId={}, identifierType=KEY, providerId={}, sessionId={}",
            tenantId, matchId, providerId, oid4vpSessionId)

        // 8. Derive materials
        val materialProfileId = session.materialProfileId
        val materialProfile = materialProfileId?.let { materialProfiles[it] }
        val credentialScopedClaims = session.verifiedData?.credentials
            ?.filter { it.id.isNotBlank() }
            ?.associate { cred ->
                cred.id to AttributeBag(
                    attributes = cred.claims.mapKeys { (k, _) -> AttributePath(k) },
                )
            }

        val materialFingerprints = if (materialProfile != null) {
            try {
                val derivedMaterials = materialService.deriveMaterials(
                    profile = materialProfile,
                    holderKey = session.rawHolderKeyFingerprint,
                    providerSubject = reconciledSubject,
                    canonicalAttributes = canonicalBag,
                    credentialScopedAttributes = credentialScopedClaims,
                )
                for (material in derivedMaterials) {
                    if (material.hash.hash != holderKeyHash) {
                        val existingMatch = identityMatchStore.findByIdentifierHash(
                            tenantId, material.hash.hash, material.identifierType,
                        )
                        if (existingMatch == null) {
                            identityMatchStore.create(
                                com.sphereon.identity.matching.model.IdentityMatch(
                                    id = generateId(),
                                    identifierHash = material.hash.hash,
                                    identifierType = material.identifierType,
                                    internalIdentityId = internalIdentityId,
                                    tenantId = tenantId,
                                    hashKeyVersion = material.hash.keyVersion,
                                    createdAt = now,
                                    lastUsedAt = now,
                                ),
                            )
                        }
                    }
                }
                derivedMaterials.map { it.hash.hash }.filter { it != holderKeyHash }.toSet()
            } catch (e: Exception) {
                log.warn("Material derivation failed (non-fatal): {}", e.message)
                emptySet()
            }
        } else {
            emptySet()
        }

        // 8b. Create institution identifier match for reverse lookup (GDPR Art. 15)
        createInstitutionMatch(canonicalBag.attributes, internalIdentityId, tenantId, now)

        // 9. Persist binding
        val attributesToPersistMap = attributesToPersist(canonicalBag, canonicalAttributeRules)
        createIdentityLinkBinding(
            tenantId = tenantId, holderKeyHash = holderKeyHash, holderKeyVersion = holderKeyVersion,
            matchId = matchId, externalSubject = reconciledSubject,
            canonicalClaims = attributesToPersistMap, reconciliationSessionId = reconciliationSessionId ?: "sts-direct",
            providerId = providerId, assuranceAcr = providerAcr, assuranceAmr = providerAmr,
            materialFingerprints = materialFingerprints, materialProfileVersion = materialProfile?.version,
            now = now,
        ).getOrElse { error -> return Err(error) }

        // 10. Project claims and update session
        val projectedClaims = attributesToProject(canonicalBag, canonicalAttributeRules)
        val updatedSession = session.copy(
            status = Oid4vpAuthSessionStatus.VERIFIED,
            resolvedUserId = reconciledSubject,
            updatedAt = now,
            idvRequirementReason = null,
            idvMessage = null,
        )
        oid4vpSessionStore.put(oid4vpSessionId, updatedSession, (session.expiresAt - now).coerceAtLeast(60.seconds))

        log.info("[AUDIT] reconciliation.completed: tenant={}, sessionId={}, matchId={}, providerId={}, method=sts-direct",
            tenantId, oid4vpSessionId, matchId, providerId)

        return Ok(
            ReconciliationCallbackResult(
                oid4vpSessionId = oid4vpSessionId,
                matchId = matchId,
                resolvedUserId = reconciledSubject,
                canonicalAttributes = projectedClaims,
                assurance = AssuranceSummary(
                    oidcAcr = providerAcr,
                    oidcAmr = providerAmr,
                    executionId = reconciliationSessionId ?: "sts-direct",
                ),
            )
        )
    }

    /**
     * Merge wallet credential claims with OIDC claims into a [CanonicalAttributeBag] with provenance.
     *
     * For each [CanonicalAttributeRule], the merge mode determines which source is authoritative:
     * - WALLET_ONLY: only wallet attributes
     * - OIDC_ONLY: only OIDC attributes
     * - OIDC_WINS: OIDC if present, else wallet
     * - WALLET_WINS: wallet if present, else OIDC
     * - MERGE_ALL: OIDC takes precedence (same as OIDC_WINS for single-value attributes)
     *
     * Wallet attribute keys are translated through [walletAttributeMappings] before merging.
     * Attributes not covered by any rule are merged with OIDC taking precedence.
     */
    /**
     * Creates an identity match for the configured institution lookup attribute (e.g. eduid).
     * This enables reverse lookup via the External API: find a student by their known institution identifier.
     * Hashed with Key B (institution HMAC) for domain separation from holder key hashes (Key A).
     */
    private suspend fun createInstitutionMatch(
        canonicalBag: Map<String, JsonElement>,
        internalIdentityId: String,
        tenantId: String,
        now: kotlinx.datetime.Instant,
    ) {
        val lookupAttr = institutionLookupConfig.attributeName ?: return
        val lookupType = institutionLookupConfig.identifierType

        val element = canonicalBag[lookupAttr]
        val attrValue = when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> element.content
            else -> null
        }
        if (attrValue.isNullOrBlank()) return

        try {
            val hash = reconciliationCryptoService.hashExternalIdentifier(attrValue)
            val identifierType = com.sphereon.identity.matching.model.IdentifierType(lookupType)
            val existing = identityMatchStore.findByIdentifierHash(tenantId, hash.hash, identifierType)
            if (existing == null) {
                identityMatchStore.create(
                    com.sphereon.identity.matching.model.IdentityMatch(
                        id = generateId(),
                        identifierHash = hash.hash,
                        identifierType = identifierType,
                        internalIdentityId = internalIdentityId,
                        tenantId = tenantId,
                        hashKeyVersion = hash.keyVersion,
                        createdAt = now,
                        lastUsedAt = now,
                    ),
                )
                log.info("[AUDIT] identity.match.created type={} attr={} for identity={}", lookupType, lookupAttr, internalIdentityId)
            }
        } catch (e: Exception) {
            log.warn("Institution match creation failed (non-fatal): {}", e.message)
        }
    }

    private fun buildCanonicalClaims(
        walletClaims: Map<String, JsonElement>,
        oidcClaims: Map<String, JsonElement>,
        providerId: String,
    ): CanonicalAttributeBag {
        // Step 1: Map wallet attribute keys through configured mappings.
        val mappedWalletClaims = buildMap {
            for ((key, value) in walletClaims) {
                val canonicalKey = walletAttributeMappings.resolve(key) ?: key
                put(canonicalKey, value)
            }
        }

        // Build per-attribute rule lookup
        val ruleByName = canonicalAttributeRules.associateBy { it.canonicalName }

        // Step 2: Merge per claim rule
        val mergedClaims = mutableMapOf<String, JsonElement>()
        val provenanceSources = mutableMapOf<String, MutableSet<String>>()

        // Collect all canonical claim names from both sources and rules
        val allNames = (mappedWalletClaims.keys + oidcClaims.keys + ruleByName.keys).toSet()

        for (name in allNames) {
            val rule = ruleByName[name]
            val walletValue = mappedWalletClaims[name]
            val oidcValue = oidcClaims[name]
            val mode = rule?.mergeMode ?: CanonicalMergeMode.OIDC_WINS

            val (resolvedValue, sources) = when (mode) {
                CanonicalMergeMode.WALLET_ONLY -> {
                    walletValue to (if (walletValue != null) mutableSetOf("wallet:credential") else mutableSetOf())
                }
                CanonicalMergeMode.OIDC_ONLY -> {
                    oidcValue to (if (oidcValue != null) mutableSetOf("oidc:id_token") else mutableSetOf())
                }
                CanonicalMergeMode.OIDC_WINS, CanonicalMergeMode.MERGE_ALL -> {
                    val sources = mutableSetOf<String>()
                    if (walletValue != null) sources += "wallet:credential"
                    if (oidcValue != null) sources += "oidc:id_token"
                    (oidcValue ?: walletValue) to sources
                }
                CanonicalMergeMode.WALLET_WINS -> {
                    val sources = mutableSetOf<String>()
                    if (walletValue != null) sources += "wallet:credential"
                    if (oidcValue != null) sources += "oidc:id_token"
                    (walletValue ?: oidcValue) to sources
                }
            }

            if (resolvedValue != null) {
                mergedClaims[name] = resolvedValue
                provenanceSources[name] = sources
            }
        }

        return CanonicalAttributeBag(
            attributes = mergedClaims,
            provenance = AttributeProvenanceSummary(
                sources = provenanceSources.mapValues { (_, v) -> v.toSet() },
                providerIds = setOf(providerId),
            ),
            canonicalSchemaVersion = "1",
            selectorRuleVersion = selectorRuleVersion,
        )
    }

    /**
     * Create an encrypted identity link binding after successful reconciliation.
     *
     * @param tenantId Tenant context
     * @param holderKeyHash The HMAC hash of the holder key (already hashed by the auth provider)
     * @param holderKeyVersion The key version used to produce [holderKeyHash]
     * @param matchId The identity match ID
     * @param externalSubject The external subject identifier (e.g., institution user ID)
     * @param canonicalClaims Merged wallet + OIDC claims (already mapped and ownership-resolved)
     * @param reconciliationSessionId The reconciliation session ID
     * @param providerId The resolved reconciliation provider ID (e.g., "surf")
     * @param assuranceAcr ACR from the reconciliation provider's configuration
     * @param assuranceAmr AMR from the reconciliation provider's configuration
     * @param now Current timestamp
     */
    private suspend fun createIdentityLinkBinding(
        tenantId: String,
        holderKeyHash: String,
        holderKeyVersion: String,
        matchId: String,
        externalSubject: String?,
        canonicalClaims: Map<String, JsonElement>,
        reconciliationSessionId: String,
        providerId: String,
        assuranceAcr: String,
        assuranceAmr: List<String>,
        materialFingerprints: Set<String> = emptySet(),
        materialProfileVersion: String? = null,
        now: kotlinx.datetime.Instant,
    ): IdkResult<Unit, IdkError> {
        try {
            // holderKeyHash is already the HMAC hash — do NOT re-hash.

            // Hash + encrypt the institution identifier
            val institutionHash = externalSubject?.let { reconciliationCryptoService.hashExternalIdentifier(it) }
            val encryptedInstitutionId = externalSubject?.let { reconciliationCryptoService.encrypt(it) }

            val envelope = createPersistedAttributesEnvelope(
                canonicalClaims = canonicalClaims,
                materialProfileVersion = materialProfileVersion,
                materialFingerprints = materialFingerprints,
                now = now,
            )

            val assuranceSummary = AssuranceSummary(
                oidcAcr = assuranceAcr,
                oidcAmr = assuranceAmr,
                executionId = reconciliationSessionId
            )

            val binding = IdentityLinkBinding(
                id = generateId(),
                tenantId = tenantId,
                matchId = matchId,
                holderIdentifierHash = holderKeyHash,
                holderHashKeyVersion = holderKeyVersion,
                institutionIdentifierHash = institutionHash?.hash,
                institutionHashKeyVersion = institutionHash?.keyVersion,
                encryptedInstitutionId = encryptedInstitutionId,
                persistedAttributesEnvelope = envelope,
                providerId = providerId,
                institutionId = null,
                canonicalSchemaVersion = envelope.canonicalSchemaVersion,
                materialProfileVersion = envelope.materialProfileVersion,
                selectorRuleVersion = envelope.selectorRuleVersion,
                materialFingerprints = envelope.materialFingerprints.ifEmpty { null },
                persistedAttributeNames = envelope.attributeNames,
                assuranceSummary = assuranceSummary,
                createdAt = now,
                updatedAt = now,
                lastUsedAt = now,
            )

            identityLinkBindingStore.create(binding)
            log.info("Created identity link binding {} for match {} (provider={})",
                binding.id, matchId, providerId)
            return Ok(Unit)
        } catch (e: Exception) {
            log.error("Failed to create identity link binding: {}", e.message, e)
            return Err(Oid4vpAuthErrors.reconciliationFailed(
                "Failed to create identity link binding: ${e.message}"
            ))
        }
    }

    private fun generateId(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)
        return bytes.joinToString("") { byte ->
            val hex = (byte.toInt() and 0xFF).toString(16)
            if (hex.length == 1) "0$hex" else hex
        }
    }

    override suspend fun preEvaluateReconciliation(
        oid4vpSessionId: String,
        tenantId: String,
    ): IdkResult<ResolvedKnownHolder?, IdkError> {
        // 1. Get session (must already be in IDV_REQUIRED with holderIdentifierHash set)
        val session = oid4vpSessionStore.get(oid4vpSessionId).getOrElse { error ->
            return Err(error)
        } ?: return Err(Oid4vpAuthErrors.sessionNotFound(oid4vpSessionId))

        if (session.status != Oid4vpAuthSessionStatus.IDV_REQUIRED) {
            // Not in IDV_REQUIRED — nothing to pre-evaluate
            return Ok(null)
        }

        val verifiedData = session.verifiedData
            ?: return Ok(null) // No verified data to evaluate against

        // 2. Build selector input and evaluate
        val selectorInput = buildReconciliationSelectorInput(
            session = session,
            tenantId = tenantId,
            verifiedData = verifiedData,
            walletAttributeMappings = walletAttributeMappings,
            requestedProjection = session.requestedProjection,
        )
        val plan = ReconciliationSelector.evaluate(selectorRules, selectorInput, selectorRuleVersion)
            ?: return Ok(null) // No rule matched — fall through to IDV_REQUIRED

        log.info("Pre-evaluation selector resolved plan '{}' for session {}", plan::class.simpleName, oid4vpSessionId)

        // 3. Handle plans that don't need a redirect
        return when (plan) {
            is SkipReconciliation -> {
                val result = executeSkipReconciliation(session, verifiedData, tenantId)
                result.getOrElse { return Err(it) }
                // Session is now VERIFIED — build a ResolvedKnownHolder from projected claims
                val updatedSession = oid4vpSessionStore.get(oid4vpSessionId).getOrNull()
                val projectedClaims = updatedSession?.let { s ->
                    val walletClaims = verifiedData.credentials.flatMap { it.claims.entries }.associate { it.key to it.value }
                    val bag = buildCanonicalClaims(walletClaims, emptyMap(), "none")
                    attributesToProject(bag, canonicalAttributeRules)
                } ?: emptyMap()
                Ok(ResolvedKnownHolder(
                    userId = updatedSession?.resolvedUserId ?: "",
                    bindingId = "",
                    matchId = "",
                    canonicalAttributes = projectedClaims,
                    state = KnownHolderState.NOT_FOUND,
                ))
            }
            is UseExistingBinding -> {
                val result = executeUseExistingBinding(session, tenantId)
                result.getOrElse { return Err(it) }
                // Session is now VERIFIED — retrieve the binding claims
                val updatedSession = oid4vpSessionStore.get(oid4vpSessionId).getOrNull()
                val holderHash = session.holderIdentifierHash ?: return Ok(null)
                val binding = identityLinkBindingStore.findByHolderHash(tenantId, holderHash)
                val canonicalClaims = binding?.let { decryptPersistedAttributes(it) } ?: emptyMap()
                val projectedClaims = if (canonicalClaims.isNotEmpty()) {
                    val bag = CanonicalAttributeBag(
                        attributes = canonicalClaims,
                        provenance = AttributeProvenanceSummary(),
                        canonicalSchemaVersion = binding?.persistedAttributesEnvelope?.canonicalSchemaVersion ?: "1",
                        selectorRuleVersion = binding?.persistedAttributesEnvelope?.selectorRuleVersion,
                    )
                    attributesToProject(bag, canonicalAttributeRules)
                } else canonicalClaims
                Ok(ResolvedKnownHolder(
                    userId = updatedSession?.resolvedUserId ?: "",
                    bindingId = binding?.id ?: "",
                    matchId = "",
                    canonicalAttributes = projectedClaims,
                    assurance = binding?.assuranceSummary,
                    state = session.knownHolderState ?: KnownHolderState.NOT_FOUND,
                ))
            }
            is FailClosed -> {
                Err(Oid4vpAuthErrors.reconciliationFailed(plan.reason))
            }
            is RunIdv, is StepUp -> {
                // Redirect needed — caller should proceed to IDV_REQUIRED
                Ok(null)
            }
        }
    }

    private fun planTypeOf(plan: ReconciliationPlan): ReconciliationPlanType = when (plan) {
        is RunIdv -> ReconciliationPlanType.RUN_IDV
        is StepUp -> ReconciliationPlanType.STEP_UP
        is FailClosed -> ReconciliationPlanType.FAIL_CLOSED
        is SkipReconciliation -> ReconciliationPlanType.SKIP_RECONCILIATION
        is UseExistingBinding -> ReconciliationPlanType.USE_EXISTING_BINDING
    }

    override suspend fun getStatus(
        oid4vpSessionId: String
    ): IdkResult<ReconciliationStatusResult, IdkError> {
        // 1. Get OID4VP session
        val session = oid4vpSessionStore.get(oid4vpSessionId).getOrElse { error ->
            return Err(error)
        } ?: return Err(Oid4vpAuthErrors.sessionNotFound(oid4vpSessionId))

        // 2. Check for linked reconciliation session
        val reconciliationSessionId = session.reconciliationSessionId
            ?: return Err(Oid4vpAuthErrors.noReconciliationSession(oid4vpSessionId))

        // 3. Get reconciliation session status
        val reconciliationSession = getReconciliationSessionCommand.execute(
            GetReconciliationSessionArgs(
                sessionId = reconciliationSessionId,
                tenantId = "default"
            )
        ).getOrElse { error ->
            return Err(error)
        }

        return Ok(
            ReconciliationStatusResult(
                sessionId = oid4vpSessionId,
                status = reconciliationSession.status.name,
                message = reconciliationSession.errorMessage,
                planType = session.reconciliationPlanType,
                idvRequirementReason = session.idvRequirementReason,
            )
        )
    }
}
