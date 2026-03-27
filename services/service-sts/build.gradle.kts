plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.metro)
}
metro {
}

kotlin {
    kotlin.applyDefaultHierarchyTemplate()
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // IDK OAuth2 Server Authorization
                api(libs.sphereon.oauth2.server.authorization.public)
                implementation(libs.sphereon.oauth2.server.authorization.impl)

                // IDK OAuth2 Client (upstream OIDC RP for federation)
                api("com.sphereon.idk:lib-oauth2-client-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-client-impl:${libs.versions.sphereon.idk.get()}")

                // IDK OAuth2 Common
                api("com.sphereon.idk:lib-oauth2-common-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-common-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Credential Claims Mapper (for ClaimTransformation sealed interface)
                api("com.sphereon.idk:lib-credential-claims-mapper-public:${libs.versions.sphereon.idk.get()}")

                // IDK Core
                implementation(libs.sphereon.core.api.public)
                implementation(libs.sphereon.core.api.default)

                // IDK Crypto (needed for JWT signing — ID tokens, access tokens)
                api("com.sphereon.idk:lib-crypto-core-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-crypto-core-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Software KMS Provider (in-memory key management for signing)
                implementation("com.sphereon.idk:lib-crypto-kms-provider-software:${libs.versions.sphereon.idk.get()}")

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
                implementation(sphereonlib.io.ktor.server.auth)
                implementation(sphereonlib.io.ktor.server.auth.jwt)

                // IDK Ktor plugin
                implementation("com.sphereon.idk:ktor-server-kotlin-inject:${libs.versions.sphereon.idk.get()}")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.mockk)
            }
        }
    }
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("service-sts")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.sphereon.portal.sts.StsMainKt" }

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
    description = "Run the STS service"
    dependsOn("compileKotlinJvm")
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = files(
        provider { mainCompilation.output.allOutputs },
        configurations.named("jvmRuntimeClasspath")
    )
    mainClass.set("com.sphereon.portal.sts.StsMainKt")
    workingDir = project.projectDir
}
