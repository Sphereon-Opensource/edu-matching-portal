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
                // Base lib + protocol modules (comment out protocols you don't need)
                implementation(project(":blobstore-camel-core"))
                implementation(project(":blobstore-camel-s3"))
                implementation(project(":blobstore-camel-ftp"))
                implementation(project(":blobstore-camel-azure"))
                implementation(project(":blobstore-camel-gcs"))

                // IDK blob store
                implementation("com.sphereon.idk:lib-data-store-blob-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-data-store-blob-impl-fs:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-data-store-blob-impl-memory:${libs.versions.sphereon.idk.get()}")

                // IDK core
                implementation(libs.sphereon.core.api.default)
                implementation("com.sphereon.idk:lib-core-events-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-core-events-impl:${libs.versions.sphereon.idk.get()}")

                // IDK impl modules (needed for @DependencyGraph DI graph resolution)
                implementation("com.sphereon.idk:lib-crypto-core-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-crypto-core-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-crypto-kms-provider-software:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-data-store-kv-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-data-store-kv-impl-memory:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-did-resolver-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-did-manager-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-did-persistence-memory:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-did-methods-key:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-did-methods-jwk:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-sdjwt-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-common-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-client-impl:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-data-link-http-client-impl:${libs.versions.sphereon.idk.get()}")

                // DI
                implementation(libs.bundles.app.platform.di)
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
                implementation(sphereonlib.io.ktor.server.status.pages)
                implementation(sphereonlib.io.ktor.server.call.logging)

                // IDK Ktor plugin
                implementation("com.sphereon.idk:ktor-server-kotlin-inject:${libs.versions.sphereon.idk.get()}")

                // Logging
                implementation(libs.kotlin.logging.jvm)
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
                implementation(sphereonlib.org.jetbrains.kotlinx.coroutines.test)
            }
        }
    }
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("service-blobstore-camel")
    archiveClassifier.set("all")
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.sphereon.portal.blobstore.BlobstoreCamelMainKt"
        attributes["Implementation-Version"] = project.version.toString()
    }

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
    description = "Run the Blobstore Camel service"
    dependsOn("compileKotlinJvm")
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = files(
        provider { mainCompilation.output.allOutputs },
        configurations.named("jvmRuntimeClasspath"),
    )
    mainClass.set("com.sphereon.portal.blobstore.BlobstoreCamelMainKt")
    workingDir = project.projectDir
    // Forward all environment variables to the JVM process
    environment(System.getenv())
}
