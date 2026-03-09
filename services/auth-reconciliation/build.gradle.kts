plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin)
}

kotlin {
    kotlin.applyDefaultHierarchyTemplate()
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // IDK Auth Bridge
                api("com.sphereon.idk:lib-openid-oid4vp-auth-bridge-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-openid-oid4vp-auth-bridge-impl:${libs.versions.sphereon.idk.get()}")

                // IDK OAuth2 Server Authorization
                api("com.sphereon.idk:lib-oauth2-server-authorization-public:${libs.versions.sphereon.idk.get()}")
                implementation("com.sphereon.idk:lib-oauth2-server-authorization-impl:${libs.versions.sphereon.idk.get()}")

                // IDK Core
                implementation(libs.sphereon.core.api.public)
                implementation(libs.sphereon.core.api.default)

                // DI
                implementation(libs.bundles.kotlin.inject)

                // Coroutines
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.sphereon.core.logger.console)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit5)
                implementation(libs.mockk)
            }
        }
    }
}

dependencies {
    add("kspJvm", libs.kotlin.inject.compiler.ksp)
    add("kspJvm", libs.anvil.compiler.ksp)
    add("kspJvm", libs.amz.kotlin.inject.contribute.code.generators)
}
