plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
    id("app.cash.sqldelight")
}
metro {
}

kotlin {
    kotlin.applyDefaultHierarchyTemplate()
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // IDK Auth Bridge (OID4VP sessions, VP verification, identity resolution)
                api(libs.sphereon.auth.bridge.public)
                implementation(libs.sphereon.auth.bridge.impl)

                // IDK Identity Matching (HMAC-based holder key hash lookup)
                api("com.sphereon.idk:lib-identity-matching-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-identity-matching-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Identity Resolution (pluggable resolver framework)
                api("com.sphereon.idk:lib-identity-resolution-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-identity-resolution-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Identity Reconciliation (OIDC-based identity linking for unknown keys)
                api("com.sphereon.idk:lib-identity-reconciliation-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-identity-reconciliation-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Identity Verification (IDV definitions, stores, graph models)
                api("com.sphereon.idk:lib-idv-public:${libs.versions.sphereon.idk.get()}")

                // IDK OAuth2 Client (OIDC RP for reconciliation provider)
                api("com.sphereon.idk:lib-oauth2-client-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-client-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Claims Mapper (config-driven claim transformation)
                api("com.sphereon.idk:lib-credential-claims-mapper-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-credential-claims-mapper-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Core
                implementation(libs.sphereon.core.api.public)
                implementation(libs.sphereon.core.api.default)

                // IDK Core Events (EventHub DI binding)
                implementation("com.sphereon.idk:lib-core-events-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-core-events-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Crypto (needed for VP verification, signing operations)
                api("com.sphereon.idk:lib-crypto-core-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-crypto-core-impl:${libs.versions.sphereon.idk.get()}")

                // IDK HTTP Client (needed by OID4VP verifier for callbacks)
                implementation("com.sphereon.idk:lib-data-link-http-client-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-data-link-http-client-impl:${libs.versions.sphereon.idk.get()}")

                // IDK KV Store (needed by OID4VP session storage)
                implementation("com.sphereon.idk:lib-data-store-kv-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-data-store-kv-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-data-store-kv-impl-memory:${libs.versions.sphereon.idk.get()}")

                // IDK OAuth2 JWT Validation (OIDC discovery for reconciliation + external API token validation)
                implementation("com.sphereon.idk:lib-oauth2-jwt-validation-api:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-jwt-validation-impl:${libs.versions.sphereon.idk.get()}")

                // IDK OAuth2 Resource Server (VerifyJwtCommand needed by JWT validation DI)
                api("com.sphereon.idk:lib-oauth2-server-resource-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-server-resource-impl:${libs.versions.sphereon.idk.get()}")

                // IDK OAuth2 Common
                api("com.sphereon.idk:lib-oauth2-common-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-common-impl:${libs.versions.sphereon.idk.get()}")

                // IDK SD-JWT (VP verification)
                implementation("com.sphereon.idk:lib-sdjwt-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-sdjwt-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Identity Matching impl (contributes bindings for matching commands)
                implementation("com.sphereon.idk:lib-identity-matching-impl:${libs.versions.sphereon.idk.get()}")

                // KMS providers for crypto operations
                implementation("com.sphereon.idk:lib-crypto-kms-provider-software:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-crypto-kms-provider-azure:${libs.versions.sphereon.idk.get()}")

                // Kottage KV persistence (required by IDK's OID4VP session store at runtime)
                implementation("com.sphereon.idk:lib-data-store-kv-impl-kottage:${libs.versions.sphereon.idk.get()}")

                // DID resolution (replaces NoOpVerificationMethodKeyResolver with real DID resolver)
                implementation("com.sphereon.idk:lib-did-resolver-impl:${libs.versions.sphereon.idk.get()}")
                // DID methods: did:jwk and did:web (for CNF kid resolution and issuer verification)
                implementation("com.sphereon.idk:lib-did-methods-jwk:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-did-methods-web:${libs.versions.sphereon.idk.get()}")

                // OID4VP Verifier (needed by AuthBridgeKeyInitializer for client metadata JWKS)
                implementation("com.sphereon.idk:lib-openid-oid4vp-verifier-impl:${libs.versions.sphereon.idk.get()}")

                // OID4VP Universal API (needed for Metro to discover @ContributesIntoSet HttpAdapter)
                implementation("com.sphereon.idk:lib-openid-oid4vp-universal-impl:${libs.versions.sphereon.idk.get()}")

                // DI
                implementation(libs.bundles.app.platform.di)

                // Coroutines
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val jvmMain by getting {
            dependencies {
                // Ktor server
                implementation(sphereonlib.io.ktor.server.core)
                implementation(sphereonlib.io.ktor.server.cio)
                implementation(sphereonlib.io.ktor.server.content.negotiation)
                implementation(sphereonlib.io.ktor.serialization.kotlinx.json)
                implementation(sphereonlib.io.ktor.server.cors)
                implementation(sphereonlib.io.ktor.server.status.pages)
                implementation(sphereonlib.io.ktor.server.call.logging)

                // Ktor auth (required by IDK HTTP adapters)
                implementation(sphereonlib.io.ktor.server.auth)

                // IDK Ktor plugin
                implementation("com.sphereon.idk:ktor-server-kotlin-inject:${libs.versions.sphereon.idk.get()}")

                // PostgreSQL + SQLDelight
                implementation(sphereonlib.app.cash.sqldelight.jdbc.driver)
                implementation(sphereonlib.org.postgresql.postgresql)
                implementation(sphereonlib.com.zaxxer.hikaricp)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
                implementation(libs.mockk)
                implementation(sphereonlib.org.testcontainers.testcontainers)
                implementation(sphereonlib.org.testcontainers.postgresql)
                implementation(sphereonlib.org.testcontainers.junit.jupiter)
                implementation(sphereonlib.io.ktor.server.test.host)
                implementation(sphereonlib.io.ktor.client.content.negotiation)
            }
        }
    }
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("service-auth-bridge")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.sphereon.portal.bridge.AuthBridgeMainKt" }

    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    from(provider { mainCompilation.output.classesDirs })
    from(provider { mainCompilation.output.resourcesDir })
    from(configurations.named("jvmRuntimeClasspath").map { config ->
        config.filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    dependsOn("jvmMainClasses")
}

tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Run the Auth Bridge service"
    dependsOn("compileKotlinJvm")
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = files(
        provider { mainCompilation.output.allOutputs },
        configurations.named("jvmRuntimeClasspath")
    )
    mainClass.set("com.sphereon.portal.bridge.AuthBridgeMainKt")
    workingDir = project.projectDir
}

sqldelight {
    databases {
        create("AuthBridgeDatabase") {
            packageName.set("com.sphereon.portal.bridge.db")
            dialect("app.cash.sqldelight:postgresql-dialect:2.2.1")
            srcDirs("src/jvmMain/sqldelight")
            verifyMigrations.set(false)
        }
    }
}

tasks.configureEach {
    if (name.contains("verify", ignoreCase = true) && name.contains("Migration", ignoreCase = true)) {
        enabled = false
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}
