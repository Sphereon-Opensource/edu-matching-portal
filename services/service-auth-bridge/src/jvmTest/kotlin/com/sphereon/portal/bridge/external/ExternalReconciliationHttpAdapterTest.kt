package com.sphereon.portal.bridge.external

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.identity.matching.crypto.EncryptedPayload
import com.sphereon.identity.matching.crypto.HashedIdentifier
import com.sphereon.identity.matching.crypto.ReconciliationCryptoService
import com.sphereon.identity.matching.model.IdentifierType
import com.sphereon.identity.matching.model.IdentityMatch
import com.sphereon.oauth2.jwt.validation.AccessTokenValidationOptions
import com.sphereon.oauth2.jwt.validation.IdTokenValidationOptions
import com.sphereon.oauth2.jwt.validation.JwtValidationService
import com.sphereon.oauth2.jwt.validation.JwtValidationError
import com.sphereon.oauth2.jwt.validation.TokenClaims
import com.sphereon.oauth2.jwt.validation.ValidatedAccessToken
import com.sphereon.oauth2.jwt.validation.ValidatedIdToken
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataService
import com.sphereon.portal.bridge.auxiliary.AuxiliaryDataServiceImpl
import com.sphereon.portal.bridge.auxiliary.PostgresAuxiliaryDataStore
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import com.sphereon.portal.bridge.persistence.PostgresIdentityLinkBindingStore
import com.sphereon.portal.bridge.persistence.PostgresIdentityMatchStore
import com.sphereon.portal.bridge.persistence.createTestDatabase
import com.sphereon.core.api.http.GenericHttpBody
import com.sphereon.core.api.http.GenericHttpRequest
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Integration test for the external reconciliation API commands.
 *
 * Tests authentication, authorization, projection, and business logic
 * by invoking [ExternalApiAuthService] and command-level logic directly
 * with a real PostgreSQL database (TestContainers).
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalReconciliationHttpAdapterTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>("postgres:15").apply {
            withDatabaseName("authbridge_test")
            withUsername("test")
            withPassword("test")
        }
    }

    private lateinit var database: AuthBridgeDatabase
    private lateinit var matchStore: PostgresIdentityMatchStore
    private lateinit var bindingStore: PostgresIdentityLinkBindingStore
    private lateinit var auxiliaryService: AuxiliaryDataService
    private lateinit var authService: ExternalApiAuthService

    private val testCryptoService = object : ReconciliationCryptoService {
        override suspend fun encrypt(plaintext: String) =
            EncryptedPayload(ciphertext = Base64.UrlSafe.encode(plaintext.encodeToByteArray()), keyVersion = "v1")
        override suspend fun decrypt(payload: EncryptedPayload) =
            Base64.UrlSafe.decode(payload.ciphertext).decodeToString()
        override suspend fun hashHolderKey(holderKey: String) =
            HashedIdentifier(hash = "h:$holderKey", keyVersion = "v1")
        override suspend fun hashExternalIdentifier(identifier: String) =
            HashedIdentifier(hash = "e:$identifier", keyVersion = "v1")
        override suspend fun hashHolderKeyWithPrevious(holderKey: String): HashedIdentifier? = null
        override suspend fun hashExternalIdentifierWithPrevious(identifier: String): HashedIdentifier? = null
    }

    private val testProjectionConfig = object : ExternalApiProjectionConfigProvider {
        override fun getConfig() = ExternalApiProjectionConfig(
            clients = mapOf(
                "test-client" to ClientProjection(
                    clientId = "test-client",
                    allowedClaims = setOf("given_name", "family_name"),
                    allowedAuxiliary = mapOf(
                        "enrollment" to setOf("status"),
                        "grades" to null,
                    ),
                    canWrite = true,
                ),
                "readonly-client" to ClientProjection(
                    clientId = "readonly-client",
                    allowedAuxiliary = mapOf("enrollment" to null),
                    canWrite = false,
                ),
            ),
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private val base64NoPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    @OptIn(ExperimentalEncodingApi::class)
    private val testJwtValidationService = object : JwtValidationService {
        override suspend fun validateAccessToken(
            token: String,
            options: AccessTokenValidationOptions,
        ): IdkResult<ValidatedAccessToken, JwtValidationError> {
            val parts = token.split(".")
            val payload = String(base64NoPadding.decode(parts[1]))
            val payloadJson = Json.decodeFromString<kotlinx.serialization.json.JsonObject>(payload)
            val clientId = (payloadJson["client_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "unknown"
            val scope = (payloadJson["scope"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            return Ok(ValidatedAccessToken(
                subject = clientId,
                issuer = "test",
                audiences = listOf("test"),
                expiresAt = Long.MAX_VALUE,
                issuedAt = 0,
                notBefore = null,
                scopes = scope.split(" ", ",").filter { it.isNotEmpty() }.toSet(),
                tenantId = null,
                clientId = clientId,
                jwtId = null,
                rawToken = token,
                claims = emptyMap(),
                idpId = "test",
            ))
        }

        override suspend fun validateIdToken(token: String, options: IdTokenValidationOptions): IdkResult<ValidatedIdToken, JwtValidationError> {
            throw UnsupportedOperationException("Not needed in test")
        }

        override suspend fun extractClaims(token: String): IdkResult<TokenClaims, JwtValidationError> {
            throw UnsupportedOperationException("Not needed in test")
        }
    }

    private fun createTestToken(clientId: String): String {
        val header = Base64.UrlSafe.encode("""{"alg":"none","typ":"JWT"}""".encodeToByteArray()).trimEnd('=')
        val payload = Base64.UrlSafe.encode("""{"client_id":"$clientId","sub":"test","scope":"reconciliation:read"}""".encodeToByteArray()).trimEnd('=')
        return "$header.$payload.sig"
    }

    private fun request(
        method: String = "GET",
        path: String = "/",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): GenericHttpRequest = GenericHttpRequest(
        method = method,
        path = path,
        headers = headers,
        bodyContent = body?.let { GenericHttpBody.ofText(it) } ?: GenericHttpBody.Empty,
    )

    private fun authedRequest(
        clientId: String = "test-client",
        method: String = "GET",
        path: String = "/",
        body: String? = null,
    ): GenericHttpRequest = request(
        method = method,
        path = path,
        body = body,
        headers = mapOf("Authorization" to "Bearer ${createTestToken(clientId)}"),
    )

    @BeforeAll
    fun setup() = runTest {
        postgres.start()
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        })
        val driver = ds.asJdbcDriver()
        database = createTestDatabase(driver)

        matchStore = PostgresIdentityMatchStore(database)
        bindingStore = PostgresIdentityLinkBindingStore(database)
        val auxStore = PostgresAuxiliaryDataStore(database)
        auxiliaryService = AuxiliaryDataServiceImpl(auxStore) { testCryptoService }

        authService = ExternalApiAuthService(
            jwtValidationService = testJwtValidationService,
            projectionConfigProvider = testProjectionConfig,
            cryptoServiceProvider = { testCryptoService },
            auxiliaryDataService = auxiliaryService,
        )

        matchStore.create(
            IdentityMatch(
                id = "match-1",
                tenantId = "default",
                identifierHash = "test-hash",
                identifierType = IdentifierType.KEY,
                internalIdentityId = "internal-1",
                createdAt = Clock.System.now(),
            ),
        )

        auxiliaryService.store("default", "internal-1", "enrollment", mapOf("status" to JsonPrimitive("active")))
    }

    @AfterAll
    fun teardown() {
        postgres.stop()
    }

    // ── Authentication ──────────────────────────────────────────────────

    @Test
    fun unauthorizedWithoutBearer() = runTest {
        val result = authService.authenticate(request())
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as AuthError
        assertEquals(401, error.statusCode)
    }

    @Test
    fun forbiddenWithUnknownClient() = runTest {
        val result = authService.authenticate(authedRequest(clientId = "unknown-client"))
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as AuthError
        assertEquals(403, error.statusCode)
    }

    @Test
    fun authenticatesValidClient() = runTest {
        val result = authService.authenticate(authedRequest())
        assertTrue(result.isSuccess)
        val auth = result.getOrThrow()
        assertEquals("test-client", auth.clientId)
        assertTrue(auth.projection.canWrite)
    }

    // ── Lookup ──────────────────────────────────────────────────────────

    @Test
    fun lookupByIdentifierHash() = runTest {
        val auth = authService.authenticate(authedRequest()).getOrThrow()
        val match = matchStore.findByIdentifierHash(auth.tenantId, "test-hash", IdentifierType.KEY)
        assertNotNull(match)
        assertEquals("internal-1", match!!.internalIdentityId)
    }

    @Test
    fun lookupByPlaintextIdentifier() = runTest {
        matchStore.create(
            IdentityMatch(
                id = "match-subj",
                tenantId = "default",
                identifierHash = "e:student@kw1c.nl",
                identifierType = IdentifierType("SUBJECT_ID"),
                internalIdentityId = "internal-1",
                createdAt = Clock.System.now(),
            ),
        )

        val hashed = testCryptoService.hashExternalIdentifier("student@kw1c.nl")
        val match = matchStore.findByIdentifierHash("default", hashed.hash, IdentifierType("SUBJECT_ID"))
        assertNotNull(match)
        assertEquals("internal-1", match!!.internalIdentityId)
    }

    @Test
    fun lookupUnknownHashReturnsNull() = runTest {
        val match = matchStore.findByIdentifierHash("default", "nonexistent-hash", IdentifierType.KEY)
        assertNull(match)
    }

    // ── Identity + Claims ───────────────────────────────────────────────

    @Test
    fun getIdentityFindsExisting() = runTest {
        val matches = matchStore.findByInternalIdentityId("default", "internal-1")
        assertFalse(matches.isEmpty())
    }

    @Test
    fun getIdentityNotFoundForUnknown() = runTest {
        val matches = matchStore.findByInternalIdentityId("default", "nonexistent")
        assertTrue(matches.isEmpty())
    }

    // ── Auxiliary Data ──────────────────────────────────────────────────

    @Test
    fun getAuxiliary() = runTest {
        val auth = authService.authenticate(authedRequest()).getOrThrow()
        val auxiliary = authService.projectAuxiliary(auth.tenantId, "internal-1", auth.projection)
        assertTrue("enrollment" in auxiliary)
        assertEquals(JsonPrimitive("active"), auxiliary["enrollment"]?.get("status"))
    }

    @Test
    fun getAuxiliaryAppliesFieldProjection() = runTest {
        auxiliaryService.store("default", "internal-1", "enrollment",
            mapOf("status" to JsonPrimitive("active"), "programme_code" to JsonPrimitive("ICT")))

        val auth = authService.authenticate(authedRequest()).getOrThrow()
        val auxiliary = authService.projectAuxiliary(auth.tenantId, "internal-1", auth.projection)
        val enrollment = auxiliary["enrollment"]!!
        assertTrue("status" in enrollment)
        assertFalse("programme_code" in enrollment) // Not in projection allowedAuxiliary["enrollment"]
    }

    @Test
    fun writeAuxiliaryData() = runTest {
        val record = auxiliaryService.store("default", "internal-1", "grades",
            mapOf("gpa" to JsonPrimitive("8.5"), "credits" to JsonPrimitive("60")))
        assertEquals("grades", record.category)
    }

    @Test
    fun writeForbiddenForReadonlyClient() = runTest {
        val auth = authService.authenticate(authedRequest(clientId = "readonly-client")).getOrThrow()
        assertFalse(auth.projection.canWrite)
    }

    @Test
    fun getAuxiliaryCategoryForbidden() = runTest {
        val auth = authService.authenticate(authedRequest()).getOrThrow()
        // "unknown_category" is not in allowedAuxiliary
        assertTrue(auth.projection.allowedAuxiliary != null && "unknown_category" !in auth.projection.allowedAuxiliary!!)
    }

    // ── Devices ─────────────────────────────────────────────────────────

    @Test
    fun listDevices() = runTest {
        val matches = matchStore.findByInternalIdentityId("default", "internal-1")
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.any { it.identifierType == IdentifierType.KEY })
    }

    @Test
    fun revokeDevice() = runTest {
        val revokeMatchId = "match-revoke-${Uuid.random()}"
        matchStore.create(
            IdentityMatch(
                id = revokeMatchId,
                tenantId = "default",
                identifierHash = "revoke-hash-${Uuid.random()}",
                identifierType = IdentifierType.KEY,
                internalIdentityId = "internal-1",
                createdAt = Clock.System.now(),
            ),
        )

        // Verify it exists
        assertNotNull(matchStore.findById("default", revokeMatchId))

        // Delete it
        matchStore.delete("default", revokeMatchId)

        // Verify it's gone
        assertNull(matchStore.findById("default", revokeMatchId))
    }

    // ── GDPR Erasure ────────────────────────────────────────────────────

    @Test
    fun gdprErasureDeletesAll() = runTest {
        val erasureId = "internal-erasure-${Uuid.random()}"
        matchStore.create(
            IdentityMatch(
                id = "match-erasure-${Uuid.random()}",
                tenantId = "default",
                identifierHash = "erasure-hash-${Uuid.random()}",
                identifierType = IdentifierType.KEY,
                internalIdentityId = erasureId,
                createdAt = Clock.System.now(),
            ),
        )
        auxiliaryService.store("default", erasureId, "enrollment", mapOf("status" to JsonPrimitive("active")))

        // Erase
        auxiliaryService.deleteAll("default", erasureId)
        val matches = matchStore.findByInternalIdentityId("default", erasureId)
        for (match in matches) {
            matchStore.delete("default", match.id)
        }

        // Verify gone
        assertTrue(matchStore.findByInternalIdentityId("default", erasureId).isEmpty())
        assertTrue(auxiliaryService.getDecrypted("default", erasureId).isEmpty())
    }
}
