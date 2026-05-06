package com.sphereon.portal.sts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * Fetches additional identity claims from the eduID resource APIs using an access token.
 *
 * Endpoints:
 * - `/myconext/api/eduid/eppn` — returns institutional eppn(s) linked to the eduID
 * - `/myconext/api/eduid/links` — returns all linked institutional accounts
 *
 * These are NOT standard OIDC endpoints — they are eduID-specific resource APIs
 * that require the access token obtained with the `eduid.nl/eppn` or `eduid.nl/links` scope.
 */
object StsEduIdResourceApiFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    private val baseUrl = System.getenv("EDUID_API_BASE_URL")
        ?: "https://login.test.eduid.nl"

    suspend fun fetchAdditionalClaims(accessToken: String): Map<String, Any> {
        println("[eduID-API] Fetching additional claims from $baseUrl (token: ${accessToken.take(20)}...)")
        val claims = mutableMapOf<String, Any>()

        // Test: introspect the access token ourselves against SURFconext
        try {
            val introspectUrl = "https://connect.test.surfconext.nl/oidc/introspect"
            val clientId = System.getenv("FEDERATION_CLIENT_ID") ?: "kw1c-surf.demo.sphereon.com"
            val clientSecret = System.getenv("FEDERATION_CLIENT_SECRET") ?: ""
            val body = "token=$accessToken"

            val connection = URI(introspectUrl).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("Accept", "application/json")
                // Basic auth with client credentials
                val credentials = java.util.Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
                connection.setRequestProperty("Authorization", "Basic $credentials")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                val responseBody = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    val err = connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                    "[HTTP $responseCode] $err"
                }
                println("[eduID-API] Introspection response (HTTP $responseCode): $responseBody")
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            println("[eduID-API] Introspection failed: ${e.message}")
        }

        // Fetch eppn(s)
        try {
            val eppnResponse = httpGet("$baseUrl/myconext/api/eduid/eppn", accessToken)
            if (eppnResponse != null) {
                println("[eduID-API] /eppn response: $eppnResponse")
                claims["eduid_eppn_response"] = eppnResponse
                // Parse and extract structured data
                try {
                    val parsed = json.parseToJsonElement(eppnResponse)
                    if (parsed is JsonArray) {
                        val eppns = parsed.mapNotNull { entry ->
                            val obj = entry.jsonObject
                            val eppn = obj["eppn"]?.jsonPrimitive?.content
                            val schacHome = obj["schac_home_organization"]?.jsonPrimitive?.content
                                ?: obj["schachome"]?.jsonPrimitive?.content
                            if (eppn != null && schacHome != null) "$eppn@$schacHome" else eppn
                        }
                        if (eppns.isNotEmpty()) {
                            claims["eduid_institutional_eppns"] = eppns.joinToString(",")
                        }
                    }
                } catch (e: Exception) {
                    println("[eduID-API] Failed to parse eppn response: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("[eduID-API] /eppn call failed: ${e.message}")
        }

        // Fetch linked accounts
        try {
            val linksResponse = httpGet("$baseUrl/myconext/api/eduid/links", accessToken)
            if (linksResponse != null) {
                println("[eduID-API] /links response: $linksResponse")
                claims["eduid_links_response"] = linksResponse
            }
        } catch (e: Exception) {
            println("[eduID-API] /links call failed: ${e.message}")
        }

        return claims
    }

    private fun httpGet(url: String, accessToken: String): String? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                } catch (_: Exception) { "could not read error" }
                println("[eduID-API] HTTP $responseCode from $url: $errorBody")
                return null
            }

            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
