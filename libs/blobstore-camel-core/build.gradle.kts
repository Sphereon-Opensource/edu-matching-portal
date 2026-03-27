plugins {
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform)
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization)
}

kotlin {
    kotlin.applyDefaultHierarchyTemplate()
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("com.sphereon.idk:lib-data-store-blob-public:${libs.versions.sphereon.idk.get()}")
                api(libs.sphereon.core.api.public)
                api(libs.kotlinx.coroutines.core)
                implementation(libs.bundles.app.platform.di)
            }
        }
        val jvmMain by getting {
            dependencies {
                api("org.apache.camel:camel-core:4.8.0")
            }
        }
    }
}
