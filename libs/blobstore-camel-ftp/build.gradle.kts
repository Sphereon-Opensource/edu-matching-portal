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
                implementation(project(":blobstore-camel-core"))
                implementation("com.sphereon.idk:lib-data-store-blob-public:${libs.versions.sphereon.idk.get()}")
                implementation(libs.sphereon.core.api.public)
                implementation(libs.bundles.app.platform.di)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("org.apache.camel:camel-ftp:4.8.0")
            }
        }
    }
}
