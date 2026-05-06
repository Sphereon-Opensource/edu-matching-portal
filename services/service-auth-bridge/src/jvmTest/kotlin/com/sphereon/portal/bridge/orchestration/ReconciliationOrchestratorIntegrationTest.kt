package com.sphereon.portal.bridge.orchestration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.error.IdkError
import com.sphereon.core.api.http.HttpJson
import com.sphereon.attribute.flow.AttributeBag
import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.openid.oid4vp.auth.config.Oid4vpAuthBridgeConfigProvider
import com.sphereon.identity.matching.crypto.HashedIdentifier
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.model.AssuranceSummary
import com.sphereon.identity.matching.model.IdentifierType
import com.sphereon.identity.matching.model.IdentityLinkBinding
import com.sphereon.identity.matching.model.IdentityMatch
import com.sphereon.identity.matching.model.PersistedAttributesEnvelope
import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.identity.reconciliation.api.DerivedMaterial
import com.sphereon.identity.reconciliation.api.ReconciliationMaterialService
import com.sphereon.identity.reconciliation.command.CompleteReconciliationCommand
import com.sphereon.identity.reconciliation.command.CreateReconciliationSessionCommand
import com.sphereon.identity.reconciliation.command.GetReconciliationSessionCommand
import com.sphereon.identity.reconciliation.model.BindingPolicy
import com.sphereon.identity.reconciliation.model.CanonicalAttributeBag
import com.sphereon.identity.reconciliation.model.CanonicalAttributeRule
import com.sphereon.identity.reconciliation.model.CanonicalMergeMode
import com.sphereon.identity.reconciliation.model.AttributeTupleMaterial
import com.sphereon.identity.reconciliation.model.CompleteReconciliationArgs
import com.sphereon.identity.reconciliation.model.CompleteReconciliationResult
import com.sphereon.identity.reconciliation.model.CreateReconciliationSessionArgs
import com.sphereon.identity.reconciliation.model.CreateReconciliationSessionResult
import com.sphereon.identity.reconciliation.model.GetReconciliationSessionArgs
import com.sphereon.identity.reconciliation.model.KnownHolderState
import com.sphereon.identity.reconciliation.model.ReconciliationDecision
import com.sphereon.identity.reconciliation.model.ReconciliationPlanTemplate
import com.sphereon.identity.reconciliation.model.ReconciliationProvider
import com.sphereon.identity.reconciliation.model.ReconciliationMaterialProfile
import com.sphereon.identity.reconciliation.model.ReconciliationSelectorRule
import com.sphereon.identity.reconciliation.model.ReconciliationSession
import com.sphereon.identity.reconciliation.model.ReconciliationSessionStatus
import com.sphereon.identity.reconciliation.model.ResolvedIdentity
import com.sphereon.identity.reconciliation.store.ReconciliationProviderStore
import com.sphereon.identity.reconciliation.store.ReconciliationSessionStore
import com.sphereon.openid.oid4vp.auth.model.IdvRequirementReason
import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSession
import com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthSessionStatus
import com.sphereon.openid.oid4vp.auth.model.ReconciliationPlanType
import com.sphereon.openid.oid4vp.auth.store.Oid4vpAuthSessionStore
import com.sphereon.openid.oid4vp.universal.VerifiedClaimsValue
import com.sphereon.openid.oid4vp.universal.VerifiedData
import com.sphereon.portal.bridge.WalletAttributeMappings
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class ReconciliationOrchestratorIntegrationTest {

    @Test
    fun `initiateReconciliation routes using dcql credential set refs`() = runTest {
        val now = Clock.System.now()
        val sessionStore = InMemoryOid4vpAuthSessionStore()
        val session = baseSession(now).copy(
            status = Oid4vpAuthSessionStatus.IDV_REQUIRED,
            holderIdentifierHash = "holder:student",
            holderHashKeyVersion = "v1",
            verifiedData = VerifiedData(
                credentialClaims = listOf(
                    VerifiedClaimsValue(
                        id = "student_credential",
                        type = "StudentCredential",
                        claims = mapOf("given_name" to JsonPrimitive("Alice")),
                    )
                ),
                authorizationResponse = JsonObject(
                    mapOf(
                        "dcql_response" to JsonObject(
                            mapOf(
                                "credential_set_matches" to kotlinx.serialization.json.JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "credential_set_id" to JsonPrimitive("student-set"),
                                                "credential_id" to JsonPrimitive("student_credential"),
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
            ),
        )
        sessionStore.put(session.sessionId, session, 10.minutes)

        var capturedProviderId: String? = null
        val createCommand = object : TestCreateReconciliationSessionCommand() {
            override suspend fun execute(args: CreateReconciliationSessionArgs): IdkResult<CreateReconciliationSessionResult, IdkError> {
                capturedProviderId = args.providerId
                return Ok(
                    CreateReconciliationSessionResult(
                        session = reconciliationSession(
                            id = "recon-student",
                            providerId = args.providerId,
                            identifierHash = args.identifierHash,
                            redirectUri = args.redirectUri,
                            now = now,
                        ),
                        authorizationUrl = "https://idp.example.com/student"
                    )
                )
            }
        }

        val orchestrator = orchestrator(
            createCommand = createCommand,
            getCommand = object : TestGetReconciliationSessionCommand() {
                override suspend fun execute(args: GetReconciliationSessionArgs) =
                    Ok(reconciliationSession("recon-student", "surf-student", "holder:student", "/callback", now))
            },
            sessionStore = sessionStore,
            selectorRules = listOf(
                ReconciliationSelectorRule(
                    id = "student-set-rule",
                    priority = 100,
                    dcqlCredentialSetRefs = setOf("student-set"),
                    plan = ReconciliationPlanTemplate(
                        decision = ReconciliationDecision.RUN_IDV,
                        providerId = "surf-student",
                        materialProfileId = "holder-v1",
                    ),
                ),
                ReconciliationSelectorRule(
                    id = "fallback-rule",
                    priority = 10,
                    plan = ReconciliationPlanTemplate(
                        decision = ReconciliationDecision.RUN_IDV,
                        providerId = "generic-provider",
                        materialProfileId = "holder-v1",
                    ),
                ),
            ),
        )

        val result = orchestrator.initiateReconciliation(session.sessionId, "", "https://portal.example.com")
        assertTrue(result.isOk)
        // Reconciliation now goes through the STS — authorizationUrl points to STS
        assertTrue(result.value.authorizationUrl!!.contains("/reconciliation/authorize"))
        assertTrue(result.value.authorizationUrl!!.contains("provider=surf-student"))
    }

    @Test
    fun `initiateReconciliation preserves step-up plan type and typed reason`() = runTest {
        val now = Clock.System.now()
        val sessionStore = InMemoryOid4vpAuthSessionStore()
        val session = baseSession(now).copy(
            status = Oid4vpAuthSessionStatus.IDV_REQUIRED,
            holderIdentifierHash = "holder:abc",
            holderHashKeyVersion = "v1",
            knownHolderState = KnownHolderState.MATCHED_CLAIM_TUPLE,
            idvRequirementReason = IdvRequirementReason.CANDIDATE_MATCH_CONFIRMATION,
            idvMessage = "Identity verification is required to confirm the existing wallet candidate match",
            verifiedData = walletVerifiedData(
                "employee_credential",
                mapOf("given_name" to JsonPrimitive("Alice"))
            ),
        )
        sessionStore.put(session.sessionId, session, 10.minutes)

        val createCommand = object : TestCreateReconciliationSessionCommand() {
            override suspend fun execute(args: CreateReconciliationSessionArgs): IdkResult<CreateReconciliationSessionResult, IdkError> =
                Ok(
                    CreateReconciliationSessionResult(
                        session = reconciliationSession(
                            id = "recon-1",
                            providerId = args.providerId,
                            identifierHash = args.identifierHash,
                            redirectUri = args.redirectUri,
                            now = now,
                        ),
                        authorizationUrl = "https://idp.example.com/authorize?state=abc"
                    )
                )
        }

        val orchestrator = orchestrator(
            createCommand = createCommand,
            getCommand = object : TestGetReconciliationSessionCommand() {
                override suspend fun execute(args: GetReconciliationSessionArgs) =
                    Ok(reconciliationSession("recon-1", "surf", "holder:abc", "/callback", now))
            },
            sessionStore = sessionStore,
            selectorRules = listOf(
                ReconciliationSelectorRule(
                    id = "tuple-step-up",
                    priority = 100,
                    knownHolderStates = setOf(KnownHolderState.MATCHED_CLAIM_TUPLE),
                    plan = ReconciliationPlanTemplate(
                        decision = ReconciliationDecision.STEP_UP,
                        providerId = "surf",
                        materialProfileId = "holder-v1",
                        requiredAttributeNames = emptySet(),
                        bindingPolicy = BindingPolicy.REUSE_OR_CREATE,
                    ),
                )
            ),
        )

        val result = orchestrator.initiateReconciliation(session.sessionId, "", "https://portal.example.com")
        assertTrue(result.isOk)
        assertEquals(ReconciliationPlanType.STEP_UP, result.value.planType)
        assertEquals(IdvRequirementReason.CANDIDATE_MATCH_CONFIRMATION, result.value.idvRequirementReason)
        // Reconciliation now goes through the STS — authorizationUrl points to STS /reconciliation/authorize
        assertTrue(result.value.authorizationUrl!!.contains("/reconciliation/authorize"))
        assertTrue(result.value.authorizationUrl!!.contains("provider=surf"))

        val updatedSession = sessionStore.get(session.sessionId).value!!
        // No reconciliation session created — STS manages OIDC state
        assertNull(updatedSession.reconciliationSessionId)
        assertEquals(ReconciliationPlanType.STEP_UP, updatedSession.reconciliationPlanType)
        assertEquals(IdvRequirementReason.CANDIDATE_MATCH_CONFIRMATION, updatedSession.idvRequirementReason)
    }

    @Test
    fun `handleCallback updates existing binding during step-up`() = runTest {
        val now = Clock.System.now()
        val sessionStore = InMemoryOid4vpAuthSessionStore()
        val reconciliationSessionStore = InMemoryReconciliationSessionStore()
        val bindingStore = InMemoryIdentityLinkBindingStore()
        val matchStore = InMemoryIdentityMatchStore()
        val crypto = PassthroughCryptoService()

        val authSession = baseSession(now).copy(
            status = Oid4vpAuthSessionStatus.IDV_REQUIRED,
            reconciliationSessionId = "recon-1",
            reconciliationPlanType = ReconciliationPlanType.STEP_UP,
            holderIdentifierHash = "holder:abc",
            holderHashKeyVersion = "v1",
            materialProfileId = null,
            knownHolderState = KnownHolderState.MATCHED_CLAIM_TUPLE,
            idvRequirementReason = IdvRequirementReason.CANDIDATE_MATCH_CONFIRMATION,
            verifiedData = walletVerifiedData(
                "employee_credential",
                mapOf("given_name" to JsonPrimitive("Alice"))
            ),
        )
        sessionStore.put(authSession.sessionId, authSession, 10.minutes)

        val storedReconciliationSession = reconciliationSession(
            id = "recon-1",
            providerId = "surf",
            identifierHash = "holder:abc",
            redirectUri = "/callback",
            now = now,
            state = "state-123",
        )
        reconciliationSessionStore.create(storedReconciliationSession)

        val existingBinding = identityLinkBinding(
            id = "binding-1",
            matchId = "match-1",
            holderIdentifierHash = "holder:abc",
            encryptedInstitutionId = crypto.encrypt("subject-1"),
            persistedAttributesEnvelope = persistedAttributesEnvelope(
                crypto = crypto,
                attributes = mapOf("given_name" to JsonPrimitive("Old"), "email" to JsonPrimitive("old@example.com")),
                now = now,
            ),
            now = now,
        )
        bindingStore.create(existingBinding)

        val encryptedIdentity = crypto.encrypt(
            HttpJson.restApi.encodeToString(
                ResolvedIdentity.serializer(),
                ResolvedIdentity(
                    externalSubject = "subject-1",
                    externalIssuer = "surf",
                    claims = mapOf("email" to JsonPrimitive("alice@example.com")),
                )
            )
        )

        val completeCommand = object : TestCompleteReconciliationCommand() {
            override suspend fun execute(args: CompleteReconciliationArgs): IdkResult<CompleteReconciliationResult, IdkError> =
                Ok(
                    CompleteReconciliationResult(
                        session = storedReconciliationSession.copy(
                            status = ReconciliationSessionStatus.COMPLETED,
                            encryptedIdentity = encryptedIdentity,
                        ),
                        match = IdentityMatch(
                            id = "match-1",
                            identifierHash = "holder:abc",
                            identifierType = IdentifierType.KEY,
                            internalIdentityId = "subject-1",
                            tenantId = "default",
                            hashKeyVersion = "v1",
                            createdAt = now,
                        ),
                    )
                )
        }

        val orchestrator = orchestrator(
            completeCommand = completeCommand,
            getCommand = object : TestGetReconciliationSessionCommand() {
                override suspend fun execute(args: GetReconciliationSessionArgs) = Ok(storedReconciliationSession)
            },
            sessionStore = sessionStore,
            reconciliationSessionStore = reconciliationSessionStore,
            bindingStore = bindingStore,
            matchStore = matchStore,
            providerStore = StaticReconciliationProviderStore(
                ReconciliationProvider(
                    id = "surf",
                    oidcClientId = "surf-client",
                    assuranceAcr = "loa2",
                    assuranceAmr = listOf("pwd")
                )
            ),
            canonicalRules = listOf(
                CanonicalAttributeRule("given_name", CanonicalMergeMode.WALLET_ONLY, persist = true, project = true),
                CanonicalAttributeRule("email", CanonicalMergeMode.OIDC_ONLY, persist = true, project = true),
            ),
        )

        val result = orchestrator.handleCallback("code-123", "state-123", emptyMap())
        assertTrue(result.isOk)
        assertEquals("match-1", result.value.matchId)
        assertEquals("subject-1", result.value.resolvedUserId)
        assertEquals("Alice", (result.value.canonicalAttributes["given_name"] as JsonPrimitive).content)
        assertEquals("alice@example.com", (result.value.canonicalAttributes["email"] as JsonPrimitive).content)

        assertEquals(1, bindingStore.createCount)
        assertEquals(1, bindingStore.updateCount)

        val updatedBinding = bindingStore.findByMatchId("default", "match-1")
        assertNotNull(updatedBinding)
        assertSame(existingBinding.id, updatedBinding.id)
        assertEquals(setOf("given_name", "email"), updatedBinding.persistedAttributesEnvelope.attributeNames)

        val decryptedPersisted = HttpJson.restApi.decodeFromString(
            JsonObject.serializer(),
            crypto.decrypt(updatedBinding.persistedAttributesEnvelope.encrypted)
        )
        assertEquals("Alice", (decryptedPersisted["given_name"] as JsonPrimitive).content)
        assertEquals("alice@example.com", (decryptedPersisted["email"] as JsonPrimitive).content)

        val updatedSession = sessionStore.get(authSession.sessionId).value!!
        assertEquals(Oid4vpAuthSessionStatus.VERIFIED, updatedSession.status)
        assertNull(updatedSession.idvRequirementReason)
    }

    @Test
    fun `durable binding reuse resolves by holder key and claim tuple`() = runTest {
        val now = Clock.System.now()
        val sessionStore = InMemoryOid4vpAuthSessionStore()
        val reconciliationSessionStore = InMemoryReconciliationSessionStore()
        val bindingStore = InMemoryIdentityLinkBindingStore()
        val matchStore = InMemoryIdentityMatchStore()
        val crypto = PassthroughCryptoService()
        val materialProfile = ReconciliationMaterialProfile(
            id = "tuple-profile",
            version = "1",
            materials = listOf(
                AttributeTupleMaterial(
                    attributePaths = listOf("email"),
                    normalizationProfile = "email-lowercase-v1",
                    saltRef = "secret:email",
                    hmacDomain = "claim-tuple",
                    minRequiredAttributes = 1,
                )
            ),
        )

        val authSession = baseSession(now).copy(
            status = Oid4vpAuthSessionStatus.IDV_REQUIRED,
            reconciliationSessionId = "recon-create",
            reconciliationPlanType = ReconciliationPlanType.RUN_IDV,
            holderIdentifierHash = "holder:first-device",
            holderHashKeyVersion = "v1",
            rawHolderKeyFingerprint = "first-device",
            materialProfileId = materialProfile.id,
            verifiedData = walletVerifiedData(
                "employee_credential",
                mapOf("email" to JsonPrimitive("alice@example.com")),
            ),
        )
        sessionStore.put(authSession.sessionId, authSession, 10.minutes)

        val storedReconciliationSession = reconciliationSession(
            id = "recon-create",
            providerId = "surf",
            identifierHash = "holder:first-device",
            redirectUri = "/callback",
            now = now,
            state = "state-create",
        )
        reconciliationSessionStore.create(storedReconciliationSession)

        val primaryMatch = IdentityMatch(
            id = "match-primary",
            identifierHash = "holder:first-device",
            identifierType = IdentifierType.KEY,
            internalIdentityId = "identity-1",
            tenantId = "default",
            hashKeyVersion = "v1",
            createdAt = now,
        )
        val encryptedIdentity = crypto.encrypt(
            HttpJson.restApi.encodeToString(
                ResolvedIdentity.serializer(),
                ResolvedIdentity(
                    externalSubject = "subject-1",
                    externalIssuer = "surf",
                    claims = mapOf("email" to JsonPrimitive("alice@example.com")),
                )
            )
        )

        val completeCommand = object : TestCompleteReconciliationCommand() {
            override suspend fun execute(args: CompleteReconciliationArgs): IdkResult<CompleteReconciliationResult, IdkError> {
                matchStore.create(primaryMatch)
                return Ok(
                    CompleteReconciliationResult(
                        session = storedReconciliationSession.copy(
                            status = ReconciliationSessionStatus.COMPLETED,
                            encryptedIdentity = encryptedIdentity,
                        ),
                        match = primaryMatch,
                    )
                )
            }
        }

        val orchestrator = orchestrator(
            completeCommand = completeCommand,
            getCommand = object : TestGetReconciliationSessionCommand() {
                override suspend fun execute(args: GetReconciliationSessionArgs) = Ok(storedReconciliationSession)
            },
            sessionStore = sessionStore,
            reconciliationSessionStore = reconciliationSessionStore,
            bindingStore = bindingStore,
            matchStore = matchStore,
            canonicalRules = listOf(
                CanonicalAttributeRule("email", CanonicalMergeMode.OIDC_WINS, required = true, persist = true, project = true),
            ),
            materialProfiles = mapOf(materialProfile.id to materialProfile),
            materialService = EmailTupleMaterialService(),
        )

        val callbackResult = orchestrator.handleCallback("code-123", "state-create", emptyMap())
        assertTrue(callbackResult.isOk)
        assertEquals(1, bindingStore.createCount)
        assertFalse(matchStore.findByInternalIdentityId("default", "identity-1").isEmpty())

        val byHolder = orchestrator.resolveKnownHolder(
            holderKeyHash = "holder:first-device",
            tenantId = "default",
            walletClaims = mapOf("email" to JsonPrimitive("alice@example.com")),
        )
        assertTrue(byHolder.isOk)
        assertEquals(KnownHolderState.MATCHED_HOLDER_KEY, byHolder.value!!.state)
        assertEquals("subject-1", byHolder.value!!.userId)

        val byTuple = orchestrator.resolveKnownHolder(
            holderKeyHash = "holder:second-device",
            tenantId = "default",
            walletClaims = mapOf("email" to JsonPrimitive("alice@example.com")),
        )
        assertTrue(byTuple.isOk)
        assertNotNull(byTuple.value)
        assertEquals(KnownHolderState.MATCHED_CLAIM_TUPLE, byTuple.value!!.state)
        assertEquals(byHolder.value!!.bindingId, byTuple.value!!.bindingId)
        assertEquals("subject-1", byTuple.value!!.userId)
    }

    private fun orchestrator(
        createCommand: CreateReconciliationSessionCommand = object : TestCreateReconciliationSessionCommand() {
            override suspend fun execute(args: CreateReconciliationSessionArgs): IdkResult<CreateReconciliationSessionResult, IdkError> =
                error("unused in this test")
        },
        completeCommand: CompleteReconciliationCommand = object : TestCompleteReconciliationCommand() {
            override suspend fun execute(args: CompleteReconciliationArgs): IdkResult<CompleteReconciliationResult, IdkError> =
                error("unused in this test")
        },
        getCommand: GetReconciliationSessionCommand = object : TestGetReconciliationSessionCommand() {
            override suspend fun execute(args: GetReconciliationSessionArgs): IdkResult<ReconciliationSession, IdkError> =
                error("unused in this test")
        },
        selectorRules: List<ReconciliationSelectorRule> = emptyList(),
        sessionStore: Oid4vpAuthSessionStore = InMemoryOid4vpAuthSessionStore(),
        reconciliationSessionStore: ReconciliationSessionStore = InMemoryReconciliationSessionStore(),
        crypto: ReconciliationCryptoService = PassthroughCryptoService(),
        matchStore: IdentityMatchStore = InMemoryIdentityMatchStore(),
        bindingStore: InMemoryIdentityLinkBindingStore = InMemoryIdentityLinkBindingStore(),
        providerStore: ReconciliationProviderStore = StaticReconciliationProviderStore(
            ReconciliationProvider(id = "surf", oidcClientId = "surf-client")
        ),
        canonicalRules: List<CanonicalAttributeRule> = emptyList(),
        materialProfiles: Map<String, ReconciliationMaterialProfile> = emptyMap(),
        materialService: ReconciliationMaterialService = NoOpMaterialService(),
    ): ReconciliationOrchestrator {
        return ReconciliationOrchestrator(
            createReconciliationSessionCommand = createCommand,
            completeReconciliationCommand = completeCommand,
            getReconciliationSessionCommand = getCommand,
            selectorRules = selectorRules,
            selectorRuleVersion = "rules-v1",
            oid4vpSessionStore = sessionStore,
            reconciliationSessionStore = reconciliationSessionStore,
            reconciliationCryptoService = crypto,
            identityMatchStore = matchStore,
            identityLinkBindingStore = bindingStore,
            walletAttributeMappings = WalletAttributeMappings(emptyMap()),
            reconciliationProviderStore = providerStore,
            canonicalAttributeRules = canonicalRules,
            materialProfiles = materialProfiles,
            materialService = materialService,
            authBridgeConfigProvider = object : Oid4vpAuthBridgeConfigProvider {
                override fun getConfig() = com.sphereon.openid.oid4vp.auth.model.Oid4vpAuthBridgeConfig(
                    stsBaseUrl = "http://localhost:8092"
                )
            },
            institutionLookupConfig = com.sphereon.portal.bridge.external.InstitutionLookupConfig(
                attributeName = null,
            ),
        )
    }

    private fun baseSession(now: Instant) = Oid4vpAuthSession(
        sessionId = "oid4vp-session-1",
        correlationId = "corr-1",
        queryId = "kw1c-enrollment",
        status = Oid4vpAuthSessionStatus.PENDING,
        createdAt = now,
        updatedAt = now,
        expiresAt = now + 10.minutes,
    )

    private fun walletVerifiedData(credentialId: String, claims: Map<String, JsonPrimitive>) = VerifiedData(
        credentialClaims = listOf(
            VerifiedClaimsValue(
                id = credentialId,
                type = "vc+sd-jwt",
                claims = claims,
            )
        )
    )

    private fun reconciliationSession(
        id: String,
        providerId: String,
        identifierHash: String,
        redirectUri: String,
        now: Instant,
        state: String? = null,
    ) = ReconciliationSession(
        id = id,
        tenantId = "default",
        status = ReconciliationSessionStatus.CREATED,
        identifierHash = identifierHash,
        identifierType = IdentifierType.KEY,
        providerId = providerId,
        authorizationUrl = "https://idp.example.com/authorize?state=$id",
        state = state,
        redirectUri = redirectUri,
        createdAt = now,
        expiresAt = now + 10.minutes,
    )

    private fun identityLinkBinding(
        id: String,
        matchId: String,
        holderIdentifierHash: String,
        encryptedInstitutionId: EncryptedPayload?,
        persistedAttributesEnvelope: PersistedAttributesEnvelope,
        now: Instant,
    ) = IdentityLinkBinding(
        id = id,
        tenantId = "default",
        matchId = matchId,
        holderIdentifierHash = holderIdentifierHash,
        holderHashKeyVersion = "v1",
        institutionIdentifierHash = "ext:subject-1",
        institutionHashKeyVersion = "v1",
        encryptedInstitutionId = encryptedInstitutionId,
        persistedAttributesEnvelope = persistedAttributesEnvelope,
        providerId = "surf",
        institutionId = null,
        canonicalSchemaVersion = persistedAttributesEnvelope.canonicalSchemaVersion,
        materialProfileVersion = persistedAttributesEnvelope.materialProfileVersion,
        selectorRuleVersion = persistedAttributesEnvelope.selectorRuleVersion,
        persistedAttributeNames = persistedAttributesEnvelope.attributeNames,
        materialFingerprints = persistedAttributesEnvelope.materialFingerprints,
        assuranceSummary = AssuranceSummary(oidcAcr = "loa1", oidcAmr = listOf("pwd")),
        createdAt = now,
        updatedAt = now,
        lastUsedAt = now,
    )

    private suspend fun persistedAttributesEnvelope(
        crypto: ReconciliationCryptoService,
        attributes: Map<String, JsonPrimitive>,
        now: Instant,
    ) = PersistedAttributesEnvelope(
        encrypted = crypto.encrypt(
            HttpJson.restApi.encodeToString(JsonObject.serializer(), JsonObject(attributes))
        ),
        canonicalSchemaVersion = "1",
        materialProfileVersion = "profile-v1",
        selectorRuleVersion = "rules-v1",
        attributeNames = attributes.keys,
        updatedAt = now,
    )

    private class InMemoryOid4vpAuthSessionStore : Oid4vpAuthSessionStore {
        private val sessions = linkedMapOf<String, Oid4vpAuthSession>()

        override suspend fun put(sessionId: String, session: Oid4vpAuthSession, ttl: Duration): IdkResult<Unit, IdkError> {
            sessions[sessionId] = session
            return Ok(Unit)
        }

        override suspend fun get(sessionId: String): IdkResult<Oid4vpAuthSession?, IdkError> = Ok(sessions[sessionId])

        override suspend fun delete(sessionId: String): IdkResult<Unit, IdkError> {
            sessions.remove(sessionId)
            return Ok(Unit)
        }

        override suspend fun exists(sessionId: String): IdkResult<Boolean, IdkError> = Ok(sessionId in sessions)

        override suspend fun findByReconciliationSessionId(reconciliationSessionId: String): IdkResult<Oid4vpAuthSession?, IdkError> =
            Ok(sessions.values.firstOrNull { it.reconciliationSessionId == reconciliationSessionId })
    }

    private class InMemoryReconciliationSessionStore : ReconciliationSessionStore {
        private val byId = linkedMapOf<String, ReconciliationSession>()

        override suspend fun findById(tenantId: String, sessionId: String): ReconciliationSession? =
            byId[sessionId]?.takeIf { it.tenantId == tenantId }

        override suspend fun findByState(tenantId: String, state: String): ReconciliationSession? =
            byId.values.firstOrNull { it.tenantId == tenantId && it.state == state }

        override suspend fun create(session: ReconciliationSession): ReconciliationSession {
            byId[session.id] = session
            return session
        }

        override suspend fun update(session: ReconciliationSession): ReconciliationSession {
            byId[session.id] = session
            return session
        }

        override suspend fun delete(tenantId: String, sessionId: String): Boolean =
            byId.remove(sessionId)?.tenantId == tenantId

        override suspend fun findExpired(tenantId: String, cutoff: Instant): List<ReconciliationSession> =
            byId.values.filter { it.tenantId == tenantId && it.expiresAt < cutoff }
    }

    private class InMemoryIdentityLinkBindingStore : IdentityLinkBindingStore {
        private val bindings = linkedMapOf<String, IdentityLinkBinding>()
        var createCount: Int = 0
            private set
        var updateCount: Int = 0
            private set

        override suspend fun create(binding: IdentityLinkBinding): IdentityLinkBinding {
            createCount += 1
            bindings[binding.id] = binding
            return binding
        }

        override suspend fun findByMatchId(tenantId: String, matchId: String): IdentityLinkBinding? =
            bindings.values.firstOrNull { it.tenantId == tenantId && it.matchId == matchId }

        override suspend fun findByHolderHash(tenantId: String, holderHash: String): IdentityLinkBinding? =
            bindings.values.firstOrNull { it.tenantId == tenantId && it.holderIdentifierHash == holderHash }

        override suspend fun update(binding: IdentityLinkBinding): IdentityLinkBinding {
            updateCount += 1
            bindings[binding.id] = binding
            return binding
        }

        override suspend fun delete(tenantId: String, bindingId: String): Boolean =
            bindings.remove(bindingId)?.tenantId == tenantId

        override suspend fun findExpired(tenantId: String, inactiveSince: Instant): List<IdentityLinkBinding> =
            bindings.values.filter { it.tenantId == tenantId && (it.lastUsedAt ?: it.createdAt) < inactiveSince }
    }

    private class InMemoryIdentityMatchStore : IdentityMatchStore {
        private val matches = linkedMapOf<String, IdentityMatch>()

        override suspend fun findByIdentifierHash(tenantId: String, identifierHash: String, identifierType: IdentifierType): IdentityMatch? =
            matches.values.firstOrNull {
                it.tenantId == tenantId && it.identifierHash == identifierHash && it.identifierType == identifierType
            }

        override suspend fun findById(tenantId: String, matchId: String): IdentityMatch? =
            matches[matchId]?.takeIf { it.tenantId == tenantId }

        override suspend fun findByInternalIdentityId(tenantId: String, internalIdentityId: String): List<IdentityMatch> =
            matches.values.filter { it.tenantId == tenantId && it.internalIdentityId == internalIdentityId }

        override suspend fun create(match: IdentityMatch): IdentityMatch {
            matches[match.id] = match
            return match
        }

        override suspend fun update(match: IdentityMatch): IdentityMatch {
            matches[match.id] = match
            return match
        }

        override suspend fun delete(tenantId: String, matchId: String): Boolean =
            matches.remove(matchId)?.tenantId == tenantId
    }

    private class StaticReconciliationProviderStore(
        private val provider: ReconciliationProvider,
    ) : ReconciliationProviderStore {
        override suspend fun findById(providerId: String): ReconciliationProvider? = provider.takeIf { it.id == providerId }
        override suspend fun findAll(): List<ReconciliationProvider> = listOf(provider)
        override suspend fun save(provider: ReconciliationProvider): ReconciliationProvider = provider
        override suspend fun delete(providerId: String): Boolean = provider.id == providerId
    }

    private abstract class TestCreateReconciliationSessionCommand : CreateReconciliationSessionCommand {
        override val inputTypeToken = typeToken<CreateReconciliationSessionArgs>()
        override val outputTypeToken = typeToken<CreateReconciliationSessionResult>()
        override val isEnabled: Boolean = true
    }

    private abstract class TestCompleteReconciliationCommand : CompleteReconciliationCommand {
        override val inputTypeToken = typeToken<CompleteReconciliationArgs>()
        override val outputTypeToken = typeToken<CompleteReconciliationResult>()
        override val isEnabled: Boolean = true
    }

    private abstract class TestGetReconciliationSessionCommand : GetReconciliationSessionCommand {
        override val inputTypeToken = typeToken<GetReconciliationSessionArgs>()
        override val outputTypeToken = typeToken<ReconciliationSession>()
        override val isEnabled: Boolean = true
    }

    private class PassthroughCryptoService : ReconciliationCryptoService {
        override suspend fun hashHolderKey(holderKey: String): HashedIdentifier =
            HashedIdentifier(hash = "holder:$holderKey", keyVersion = "v1")

        override suspend fun hashExternalIdentifier(identifier: String): HashedIdentifier =
            HashedIdentifier(hash = "ext:$identifier", keyVersion = "v1")

        override suspend fun encrypt(plaintext: String): EncryptedPayload =
            EncryptedPayload(ciphertext = plaintext, keyVersion = "v1")

        override suspend fun decrypt(payload: EncryptedPayload): String = payload.ciphertext

        override suspend fun hashHolderKeyWithPrevious(holderKey: String): HashedIdentifier? = null
        override suspend fun hashExternalIdentifierWithPrevious(identifier: String): HashedIdentifier? = null
    }

    private class NoOpMaterialService : ReconciliationMaterialService {
        override suspend fun deriveMaterials(
            profile: com.sphereon.identity.reconciliation.model.ReconciliationMaterialProfile,
            holderKey: String?,
            providerSubject: String?,
            canonicalAttributes: CanonicalAttributeBag,
            credentialScopedAttributes: Map<String, AttributeBag>?,
        ): List<DerivedMaterial> = emptyList()

        override suspend fun deriveMaterialsWithPrevious(
            profile: com.sphereon.identity.reconciliation.model.ReconciliationMaterialProfile,
            holderKey: String?,
            providerSubject: String?,
            canonicalAttributes: CanonicalAttributeBag,
            credentialScopedAttributes: Map<String, AttributeBag>?,
        ): List<DerivedMaterial> = emptyList()
    }

    private class EmailTupleMaterialService : ReconciliationMaterialService {
        override suspend fun deriveMaterials(
            profile: ReconciliationMaterialProfile,
            holderKey: String?,
            providerSubject: String?,
            canonicalAttributes: CanonicalAttributeBag,
            credentialScopedAttributes: Map<String, AttributeBag>?,
        ): List<DerivedMaterial> {
            val email = canonicalAttributes.attributes["email"] as? JsonPrimitive ?: return emptyList()
            return listOf(
                DerivedMaterial(
                    hash = HashedIdentifier("tuple:${email.content.lowercase()}", "v1"),
                    identifierType = IdentifierType.CLAIM_TUPLE,
                    materialType = "attribute_tuple",
                    profileId = profile.id,
                    profileVersion = profile.version,
                )
            )
        }

        override suspend fun deriveMaterialsWithPrevious(
            profile: ReconciliationMaterialProfile,
            holderKey: String?,
            providerSubject: String?,
            canonicalAttributes: CanonicalAttributeBag,
            credentialScopedAttributes: Map<String, AttributeBag>?,
        ): List<DerivedMaterial> = deriveMaterials(
            profile = profile,
            holderKey = holderKey,
            providerSubject = providerSubject,
            canonicalAttributes = canonicalAttributes,
            credentialScopedAttributes = credentialScopedAttributes,
        )
    }
}
