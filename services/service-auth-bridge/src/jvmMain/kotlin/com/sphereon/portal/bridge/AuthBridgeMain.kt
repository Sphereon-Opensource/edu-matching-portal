package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.ktor.server.inject.KotlinInjectPlugin
import com.sphereon.ktor.server.inject.installUniversalHttpAdapters
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Auth Bridge entry point.
 *
 * All HTTP endpoints (OID4VP auth, external reconciliation API, device API,
 * token enrichment) are served by IDK's Universal HTTP Adapter framework,
 * auto-discovered via @ContributesIntoSet on the classpath.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8090
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val profile = System.getenv("APP_PROFILE") ?: "development"

    val graph = initAuthBridgeAppGraph(
        appId = "portal-auth-bridge",
        profile = profile
    )

    runBlocking {
        AuthBridgeKeyInitializer.initialize(graph)
        AuthBridgeReconciliationInitializer.initialize(graph)
    }

    // Start periodic cleanup of expired reconciliation sessions
    val configService = (graph as? com.sphereon.core.api.conf.AppConfigService.Graph)?.appConfigService
    val cleanupIntervalMinutes = configService
        ?.getPropertyAsString("identity.reconciliation.session-cleanup.interval-minutes", "5")
        ?.toLongOrNull() ?: 5L
    val sessionCleanupJob = com.sphereon.identity.reconciliation.impl.job.ReconciliationSessionCleanupJob(
        sessionStore = com.sphereon.portal.bridge.persistence.PostgresReconciliationSessionStore(graph.authBridgeDatabase),
        tenantIdProvider = { listOf("default") }
    )
    sessionCleanupJob.start(intervalMinutes = cleanupIntervalMinutes)
    println("Reconciliation session cleanup job started (interval: $cleanupIntervalMinutes minutes)")

    // Run key migration if configured
    if (configService != null) {
        val cryptoPrefix = "identity.reconciliation.crypto"
        fun cfg(key: String): String? = configService.getPropertyAsString("$cryptoPrefix.$key", null)

        val migrationHistoryStore = com.sphereon.portal.bridge.migration.KeyMigrationHistoryStore(graph.authBridgeDatabase)
        val migrationService = com.sphereon.portal.bridge.migration.KeyMigrationService(
            database = graph.authBridgeDatabase,
            matchStore = graph.identityMatchStore,
            bindingStore = graph.identityLinkBindingStore,
            auxiliaryDataService = com.sphereon.portal.bridge.auxiliary.AuxiliaryDataServiceImpl(
                store = com.sphereon.portal.bridge.auxiliary.PostgresAuxiliaryDataStore(graph.authBridgeDatabase),
                cryptoServiceProvider = { throw IllegalStateException("Aux crypto not available in migration context") },
            ),
            cryptoServiceProvider = {
                val userContext = graph.userContextManager.getAnonymous()
                val session = userContext.sessionContextManager.createOrGetFromId("key-migration")
                val sessionGraph = session.graph
                val kms = session.graph.asKeyManagerServiceGraph().keyManagerService
                val macCmd = sessionGraph::class.members
                    .firstOrNull { m -> m.returnType.toString().contains("GenerateMacCommand") }
                    ?.call(sessionGraph) as? com.sphereon.crypto.core.kms.command.GenerateMacCommand
                    ?: throw IllegalStateException("Cannot resolve GenerateMacCommand for migration")

                com.sphereon.identity.matching.impl.crypto.KmsBackedReconciliationCryptoService(
                    generateMacCommand = macCmd,
                    keyManagerService = kms,
                    holderKeyAlias = cfg("holder-hmac-key-alias") ?: "reconciliation:holder",
                    institutionKeyAlias = cfg("institution-hmac-key-alias") ?: "reconciliation:institution",
                    encryptionKeyAlias = cfg("encryption-key-alias") ?: "reconciliation:encryption",
                    holderKeyVersion = cfg("holder-key-version") ?: "v1",
                    institutionKeyVersion = cfg("institution-key-version") ?: "v1",
                    encryptionKeyVersion = cfg("encryption-key-version") ?: "v1",
                    previousHolderKeyAlias = cfg("previous-holder-hmac-key-alias"),
                    previousInstitutionKeyAlias = cfg("previous-institution-hmac-key-alias"),
                    previousEncryptionKeyAlias = cfg("previous-encryption-key-alias"),
                    previousHolderKeyVersion = cfg("previous-holder-key-version"),
                    previousInstitutionKeyVersion = cfg("previous-institution-key-version"),
                    previousEncryptionKeyVersion = cfg("previous-encryption-key-version"),
                    providerId = cfg("hmac-key-provider-id") ?: "software",
                )
            },
            currentEncryptionKeyVersion = cfg("encryption-key-version") ?: "v1",
            currentInstitutionKeyVersion = cfg("institution-key-version") ?: "v1",
            previousHolderKeyVersion = cfg("previous-holder-key-version"),
            batchSize = configService.getPropertyAsString("identity.reconciliation.migration.batch-size", "100")
                ?.toIntOrNull() ?: 100,
        )
        val migrationJob = com.sphereon.portal.bridge.migration.KeyMigrationJob(
            migrationService = migrationService,
            historyStore = migrationHistoryStore,
            configService = configService,
        )
        runBlocking { migrationJob.runIfNeeded() }
    }

    // Audit event store (append-only, per architecture §6.6)
    val auditEventStore = com.sphereon.portal.bridge.audit.AuditEventStore(graph.authBridgeDatabase)

    // Inactive binding cleanup (per architecture §7.1 — 2-year retention)
    val retentionDays = configService
        ?.getPropertyAsString("identity.reconciliation.retention.inactive-days", "730")
        ?.toLongOrNull() ?: 730L
    val retentionIntervalMinutes = configService
        ?.getPropertyAsString("identity.reconciliation.retention.cleanup-interval-minutes", "60")
        ?.toLongOrNull() ?: 60L
    val inactiveCleanupJob = com.sphereon.portal.bridge.retention.InactiveBindingCleanupJob(
        database = graph.authBridgeDatabase,
        matchStore = graph.identityMatchStore,
        bindingStore = graph.identityLinkBindingStore,
        auditEventStore = auditEventStore,
        retentionDays = retentionDays,
        intervalMinutes = retentionIntervalMinutes,
    )
    inactiveCleanupJob.start()

    // Soft-delete purge (per architecture §7.2 Art. 17 — hard-delete after retention)
    val purgeRetentionDays = configService
        ?.getPropertyAsString("identity.reconciliation.retention.soft-delete-days", "30")
        ?.toLongOrNull() ?: 30L
    val purgeJob = com.sphereon.portal.bridge.retention.SoftDeletePurgeJob(
        database = graph.authBridgeDatabase,
        retentionDays = purgeRetentionDays,
    )
    purgeJob.start()

    embeddedServer(CIO, port = port, host = host) {
        configureAuthBridge(graph)
    }.start(wait = true)
}

fun Application.configureAuthBridge(
    graph: AuthBridgeAppGraph,
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        val configService = (graph as? com.sphereon.core.api.conf.AppConfigService.Graph)?.appConfigService
        val allowedOrigins = configService?.getPropertyAsString("cors.allowed-origins", null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: System.getenv("FRONTEND_URL")?.let { listOf(it.trimEnd('/')) }

        if (allowedOrigins.isNullOrEmpty()) {
            anyHost()
        } else {
            for (origin in allowedOrigins) {
                allowHost(origin.removePrefix("https://").removePrefix("http://"),
                    schemes = listOf("http", "https"))
            }
        }
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }

    // Security headers on all responses
    install(createApplicationPlugin("SecurityHeaders") {
        onCall { call ->
            call.response.headers.apply {
                append("X-Content-Type-Options", "nosniff")
                append("X-Frame-Options", "DENY")
                append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
                append("Referrer-Policy", "strict-origin-when-cross-origin")
                append("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                append("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'")
            }
        }
    })

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(
                text = """{"error": "internal_server_error", "error_description": "${cause.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError
            )
        }
    }


    install(KotlinInjectPlugin) {
        appGraph = graph
        tenantResolver = com.sphereon.ktor.server.inject.resolver.FixedTenantResolver("default")
    }

    routing {
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }
        get("/ready") {
            val checks = mutableMapOf<String, String>()
            var allUp = true

            // Database connectivity
            try {
                graph.authBridgeDatabase.authBridgeQueries.countMatches("default").executeAsOne()
                checks["database"] = "UP"
            } catch (e: Exception) {
                checks["database"] = "DOWN: ${e.message}"
                allUp = false
            }

            // OIDC provider reachability
            val configService = (graph as? com.sphereon.core.api.conf.AppConfigService.Graph)?.appConfigService
            val discoveryUrl = configService?.getPropertyAsString(
                "identity.reconciliation.oidc-clients.surf-oidc.discovery-url", null
            )
            if (discoveryUrl != null) {
                try {
                    val url = java.net.URI(discoveryUrl).toURL()
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    checks["oidc_provider"] = if (code == 200) "UP" else "DOWN: HTTP $code"
                    if (code != 200) allUp = false
                } catch (e: Exception) {
                    checks["oidc_provider"] = "DOWN: ${e.message}"
                    allUp = false
                }
            } else {
                checks["oidc_provider"] = "UNCONFIGURED"
            }

            val status = if (allUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            val json = checks.entries.joinToString(",\n    ") { """"${it.key}": "${it.value}"""" }
            call.respondText(
                text = """{"status": "${if (allUp) "UP" else "DEGRADED"}",\n  "components": {\n    $json\n  }\n}""",
                contentType = ContentType.Application.Json,
                status = status
            )
        }

        // All HTTP adapters (OID4VP auth, external reconciliation, device, enrichment)
        // are auto-discovered via @ContributesIntoSet and dispatched by the Universal HTTP Adapter
        installUniversalHttpAdapters()

        // Test-only VP simulation endpoint (development profile only)
        if (System.getenv("APP_PROFILE")?.lowercase() != "production") {
            TestSimulationEndpoints.install(this, graph.oid4vpAuthSessionStore)
        }
    }
}

