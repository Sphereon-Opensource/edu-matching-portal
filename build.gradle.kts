allprojects {
    group = "com.sphereon.portal"
    version = "0.1.0-SNAPSHOT"

    plugins.withType<MavenPublishPlugin> {
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "sphereon-portal"
                    val snapshotsUrl = "https://nexus.sphereon.com/repository/sphereon-portal-snapshots/"
                    val releasesUrl = "https://nexus.sphereon.com/repository/sphereon-portal-releases/"
                    url = uri(if (version.toString().contains("SNAPSHOT")) snapshotsUrl else releasesUrl)
                    credentials {
                        username = System.getenv("NEXUS_USERNAME")
                        password = System.getenv("NEXUS_PASSWORD")
                    }
                }
            }
        }
    }
}

plugins {
    alias(sphereonplug.plugins.com.sphereon.gradle.plugin.conventions) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.multiplatform) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.jvm) apply false
    alias(sphereonplug.plugins.org.jetbrains.kotlin.plugin.serialization) apply false
    alias(sphereonplug.plugins.com.google.devtools.ksp.com.google.devtools.ksp.gradle.plugin) apply false
    alias(sphereonplug.plugins.software.amazon.app.platform) apply false
    alias(sphereonplug.plugins.io.kotest.io.kotest.gradle.plugin) apply false
}

subprojects {
    apply(plugin = "com.sphereon.gradle.plugin.conventions")
}
