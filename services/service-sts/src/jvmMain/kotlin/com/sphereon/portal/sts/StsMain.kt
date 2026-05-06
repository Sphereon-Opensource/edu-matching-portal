package com.sphereon.portal.sts

import com.sphereon.ktor.server.inject.KotlinInjectPlugin
import com.sphereon.ktor.server.inject.installUniversalHttpAdapters
import com.sphereon.oauth2.common.model.ClientAuthenticationMethod
import com.sphereon.oauth2.common.model.GrantType
import com.sphereon.oauth2.common.model.ResponseType
import com.sphereon.oauth2.server.authorization.model.ClientRegistration
import com.sphereon.oauth2.server.authorization.model.ClientType
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.sphereon.ktor.server.inject.sessionInstance
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * STS (Security Token Service) entry point.
 *
 * OAuth2/OIDC endpoints are served by IDK's OAuth2HttpAdapter, which is
 * auto-discovered via @ContributesBinding on the classpath. The adapter
 * handles:
 *   POST /token          — Token endpoint (RFC 6749)
 *   GET  /authorize      — Authorization endpoint
 *   POST /par            — Pushed Authorization Request (RFC 9126)
 *   POST /introspect     — Token introspection (RFC 7662)
 *   POST /revoke         — Token revocation (RFC 7009)
 *   GET  /.well-known/oauth-authorization-server — Discovery (RFC 8414)
 *   GET  /.well-known/openid-configuration — OIDC Discovery
 *   GET  /userinfo       — OIDC UserInfo endpoint
 *   GET  /.well-known/jwks.json — JWKS endpoint
 *   GET  /federation/callback — Federation callback from upstream IdP
 *
 * Upstream federation (OIDC RP to Keycloak/SURF) is handled by IDK's
 * FederatedUserAuthenticationProvider, wired via StsAuthProvidersModule.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val profile = System.getenv("APP_PROFILE") ?: "development"

    val graph = initStsAppGraph(
        appId = "portal-sts",
        profile = profile
    )

    // Register an ACTIVE OAuth2 signing key with the IDK SigningKeyStore so the JWKS
    // endpoint actually publishes a verification key and downstream RPs can verify
    // ID tokens. Without this the AS issues unverifiable tokens (NextAuth surfaces
    // it as `error=Configuration` on the OIDC callback).
    runBlocking { StsKeyInitializer.initialize(graph) }

    // Bootstrap OAuth2 clients from environment/defaults
    runBlocking { bootstrapClients(graph) }

    embeddedServer(CIO, port = port, host = host) {
        configureSts(graph)
    }.start(wait = true)
}

fun Application.configureSts(graph: StsAppGraph) {
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
        allowMethod(HttpMethod.Options)
    }

    // Security headers are applied by the IDK's per-response category-aware helper
    // (com.sphereon.oauth2.server.authorization.impl.http.withSecurityHeaders, threaded through
    // AbstractOAuth2HttpAdapter.withSecurityHeadersOnSuccess). Browser HTML pages that contain
    // inline <style> / <script> opt-in to a per-request CSP nonce there. Adding a second,
    // service-wide append() here resulted in duplicate `Content-Security-Policy` headers — the
    // browser intersected the two policies, the strict service-wide one stripped the IDK's
    // nonce permission, and the AS error page rendered unstyled. The IDK helper covers every
    // route that goes through the universal HTTP adapter dispatch (i.e. all OAuth2 endpoints);
    // /health and /ready are plain text and don't need browser-side defenses.

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respondText(
                text = """{"error": "internal_server_error", "error_description": "${cause.message}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    // Install IDK's Ktor plugin for request-scoped DI
    install(KotlinInjectPlugin) {
        appGraph = graph
        tenantResolver = com.sphereon.ktor.server.inject.resolver.FixedTenantResolver("default")
    }

    routing {
        // Health and readiness (outside IDK adapter dispatch)
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }
        get("/ready") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        // IDK's OAuth2HttpAdapter handles all OIDC Provider endpoints.
        // The adapter is discovered via @ContributesBinding and dispatched
        // through the universal HTTP adapter system.
        installUniversalHttpAdapters()
    }
}

private suspend fun bootstrapClients(graph: StsAppGraph) {
    val registry = graph.clientRegistry

    val clientId = System.getenv("STS_CLIENT_ID") ?: "portal"
    val clientSecret = System.getenv("STS_CLIENT_SECRET") ?: "dev-client-secret"
    val redirectUri = System.getenv("STS_REDIRECT_URI")
        ?: "http://localhost:3000/api/auth/callback/sts"
    val walletRedirectUri = System.getenv("STS_WALLET_REDIRECT_URI")
        ?: "http://localhost:3000/api/auth/callback/sts-wallet"

    val exists = registry.clientExists(clientId)
    if (exists.isOk && exists.getOrElse { false }) {
        println("[STS] Client '$clientId' already registered")
        return
    }

    val result = registry.registerClient(
        ClientRegistration(
            clientId = clientId,
            clientSecret = clientSecret,
            clientName = "Portal",
            clientType = ClientType.CONFIDENTIAL,
            grantTypes = listOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN),
            responseTypes = listOf(ResponseType.CODE),
            redirectUris = listOf(redirectUri, walletRedirectUri),
            allowedScopes = listOf("openid", "profile", "email"),
            tokenEndpointAuthMethod = ClientAuthenticationMethod.CLIENT_SECRET_POST,
            requirePkce = true
        )
    )

    if (result.isOk) {
        println("[STS] Registered client '$clientId' with redirect URI: $redirectUri")
    } else {
        println("[STS] Failed to register client '$clientId': ${result}")
    }
}
