rootProject.name = "Portal"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Helper function to include a project with its name as the path while pointing to the actual directory
fun includeProject(name: String, path: String) {
    include(":$name")
    project(":$name").projectDir = file(path)
}

pluginManagement {
    if (file("idk/gradle-build-support/settings.gradle.kts").exists()) {
        includeBuild("idk/gradle-build-support/plugins/toml-catalog")
        includeBuild("idk/gradle-build-support")
    }

    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroupAndSubgroups("software.amazon") }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots/")
            mavenContent { snapshotsOnly() }
            content {
                includeGroupAndSubgroups("com.sphereon")
                includeGroupAndSubgroups("software.amazon")
            }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases/")
            mavenContent { releasesOnly() }
            content {
                includeGroupAndSubgroups("com.sphereon")
                includeGroupAndSubgroups("software.amazon")
            }
        }
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
            content {
                includeGroupAndSubgroups("org.jetbrains.compose")
                includeGroupAndSubgroups("org.jetbrains.kotlin")
                includeGroupAndSubgroups("org.jetbrains.kotlinx")
                includeGroupAndSubgroups("org.jetbrains.skiko")
            }
        }

        // Keep maven local at the end!!!!
        mavenLocal {
            content {
                includeGroupAndSubgroups("com.sphereon")
            }
        }
    }

    plugins {
        id("app.cash.sqldelight") version "2.2.1"
        id("com.sphereon.gradle.toml-catalog") version settings.extra["gbsVersion"] as String
    }
}

// ===========================================
// Composite Build Configuration for gradle-build-support and IDK
// ===========================================

fun isGradleBuildSupportAvailable(): Boolean {
    System.getenv("USE_LOCAL_GRADLE_BUILD_SUPPORT")?.let { value ->
        if (value.equals("true", ignoreCase = true) || value == "1") return true
        if (value.equals("false", ignoreCase = true) || value == "0") return false
    }
    val gbsDir = file("idk/gradle-build-support")
    val gbsSettingsFile = file("idk/gradle-build-support/settings.gradle.kts")
    return gbsDir.exists() && gbsDir.isDirectory && gbsSettingsFile.exists()
}

fun isIdkAvailable(): Boolean {
    System.getenv("USE_LOCAL_IDK")?.let { value ->
        if (value.equals("true", ignoreCase = true) || value == "1") return true
        if (value.equals("false", ignoreCase = true) || value == "0") return false
    }
    val idkDir = file("idk")
    val idkSettingsFile = file("idk/settings.gradle.kts")
    return idkDir.exists() && idkDir.isDirectory && idkSettingsFile.exists()
}

fun extractGbsModulesFromSettings(settingsFile: File): List<Pair<String, String>> {
    if (!settingsFile.exists()) return emptyList()
    return settingsFile.readLines()
        .filter { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")
        }
        .mapNotNull { line ->
            val regex = Regex("""include\s*\(\s*"(:[^"]+)"\s*\)""")
            regex.find(line)?.let { match ->
                val projectPath = match.groupValues[1]
                val artifactName = projectPath.substringAfterLast(":")
                Pair(artifactName, projectPath)
            }
        }
}

fun extractModuleNamesFromSettings(settingsFile: File): List<String> {
    if (!settingsFile.exists()) return emptyList()
    return settingsFile.readLines()
        .filter { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")
        }
        .mapNotNull { line ->
            val regex = Regex("""includeProject\s*\(\s*"([^"]+)"\s*,\s*"[^"]+"\s*\)""")
            regex.find(line)?.groupValues?.get(1)
        }
}

val gbsVersion: String by settings
val useGbsCompositeBuild = isGradleBuildSupportAvailable()
val useIdkCompositeBuild = isIdkAvailable()

// IMPORTANT: Include gradle-build-support FIRST, before IDK
if (useGbsCompositeBuild) {
    println("==> Gradle Build Support Composite Build: ENABLED (using local idk/gradle-build-support/ sources)")

    val gbsSettingsFile = file("idk/gradle-build-support/settings.gradle.kts")
    val gbsModules = extractGbsModulesFromSettings(gbsSettingsFile)

    includeBuild("idk/gradle-build-support") {
        name = "gradle-build-support"
        dependencySubstitution {
            gbsModules.forEach { (artifactName, projectPath) ->
                substitute(module("com.sphereon.gradle:$artifactName")).using(project(projectPath))
            }
        }
    }
} else {
    println("==> Gradle Build Support Composite Build: DISABLED (using Maven dependencies)")
}

if (useIdkCompositeBuild) {
    println("==> IDK Composite Build: ENABLED (using local idk/ sources)")

    includeBuild("idk") {
        name = "Identity-Development-Kit"

        val idkSettingsFile = file("idk/settings.gradle.kts")
        val idkModules = extractModuleNamesFromSettings(idkSettingsFile)
            .filterNot { it == "lib-all" }

        dependencySubstitution {
            idkModules.forEach { moduleName ->
                substitute(module("com.sphereon.idk:$moduleName")).using(project(":$moduleName"))
            }
        }
    }
} else {
    println("==> IDK Composite Build: DISABLED (using Maven dependencies)")
}

plugins {
    id("com.gradle.develocity") version ("4.0.2")
    id("org.gradle.toolchains.foojay-resolver-convention") version ("0.8.0")
}

dependencyResolutionManagement {
    versionCatalogs {
        val gbsTomlDir = file("idk/gradle-build-support/versions")
        val plugBomToml = gbsTomlDir.resolve("gradle-plugin-bom/build/tomlCatalog/sphereonGradlePluginBom.toml")
        val libBomToml = gbsTomlDir.resolve("library-bom/build/tomlCatalog/sphereonLibraryBom.versioned.toml")

        create("sphereonplug") {
            if (useGbsCompositeBuild && plugBomToml.exists()) {
                from(files(plugBomToml))
            } else {
                from("com.sphereon.gradle:gradle-plugin-bom:$gbsVersion@toml" as String)
            }
        }
        create("sphereonlib") {
            if (useGbsCompositeBuild && libBomToml.exists()) {
                from(files(libBomToml))
            } else {
                from("com.sphereon.gradle:library-bom:$gbsVersion@toml" as String)
            }
        }
    }
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = uri("https://aws.oss.sonatype.org/content/repositories/snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroupAndSubgroups("software.amazon") }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-snapshots")
            mavenContent { snapshotsOnly() }
            content {
                includeGroupAndSubgroups("com.sphereon")
                includeGroupAndSubgroups("software.amazon")
            }
        }
        maven {
            url = uri("https://nexus.sphereon.com/repository/sphereon-opensource-releases")
            mavenContent { releasesOnly() }
            content {
                includeGroupAndSubgroups("com.sphereon")
                includeGroupAndSubgroups("software.amazon")
            }
        }
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
            content {
                includeGroupAndSubgroups("org.jetbrains.compose")
                includeGroupAndSubgroups("org.jetbrains.kotlin")
                includeGroupAndSubgroups("org.jetbrains.kotlinx")
                includeGroupAndSubgroups("org.jetbrains.skiko")
            }
        }

        // Keep maven local at the end!!!!
        mavenLocal {
            content {
                includeGroupAndSubgroups("com.sphereon")
            }
        }
    }
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
    }
}

// =====================
// Portal Services
// =====================
includeProject("auth-reconciliation", "services/auth-reconciliation")
