package com.sphereon.portal.blobstore

import com.sphereon.data.store.blob.BlobStoreError
import com.sphereon.data.store.blob.BlobStoreJsonSupport
import com.sphereon.portal.blobstore.routes.ErrorResponse
import com.sphereon.portal.blobstore.routes.installBlobRoutes
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private val logger = KotlinLogging.logger {}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val profile = System.getenv("APP_PROFILE") ?: "development"

    logger.info { "Starting service-blobstore-camel (profile=$profile, host=$host, port=$port)" }

    val graph = initBlobstoreCamelAppGraph(
        appId = "portal-blobstore-camel",
        profile = profile,
    )

    val camelContext = graph.camelContext
    camelContext.start()
    logger.info { "CamelContext started" }

    // Ensure CamelContext is stopped on JVM shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutting down CamelContext..." }
        camelContext.stop()
        graph.destroy()
        logger.info { "Shutdown complete" }
    })

    val configBinder = graph.blobStoreConfigBinder
    val storeManager = graph.blobStoreManager
    val catalogService = graph.catalogService

    embeddedServer(CIO, host = host, port = port) {
        install(ContentNegotiation) { json(BlobStoreJsonSupport.serializer) }

        // No CORS — internal service behind the BFF, not browser-accessible
        install(StatusPages) {
            exception<BlobStoreError> { call, cause ->
                val status = when (cause.kind) {
                    BlobStoreError.Kind.NOT_FOUND -> HttpStatusCode.NotFound
                    BlobStoreError.Kind.PERMISSION_DENIED -> HttpStatusCode.Forbidden
                    BlobStoreError.Kind.ALREADY_EXISTS -> HttpStatusCode.Conflict
                    BlobStoreError.Kind.UNSUPPORTED -> HttpStatusCode.BadRequest
                    BlobStoreError.Kind.QUOTA_EXCEEDED -> HttpStatusCode.PayloadTooLarge
                    else -> HttpStatusCode.InternalServerError
                }
                call.respond(status, ErrorResponse(error = cause.kind.name, message = cause.message))
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "BAD_REQUEST", message = cause.message))
            }
            exception<Throwable> { call, cause ->
                logger.error(cause) { "Unhandled error" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = "INTERNAL_ERROR", message = "Internal server error"))
            }
        }

        routing {
            get("/health") { call.respondText("OK", ContentType.Text.Plain) }
            get("/ready") {
                if (camelContext.status.isStarted) {
                    call.respondText("OK", ContentType.Text.Plain)
                } else {
                    call.respond(HttpStatusCode.ServiceUnavailable, "CamelContext not ready")
                }
            }

            installBlobRoutes(
                configBinder = configBinder,
                storeManager = storeManager,
                configs = { configBinder.getBlobStoreConfigs(graph.appConfigService) },
                catalogService = catalogService,
            )
        }
    }.start(wait = true)
}
