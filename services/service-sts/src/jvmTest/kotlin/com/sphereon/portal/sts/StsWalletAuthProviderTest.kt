package com.sphereon.portal.sts

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StsWalletAuthProviderTest {

    @Test
    fun `wallet provider reuses auth bridge projected claims directly`() = runBlocking {
        val responseBody = """
            {
              "userId": "user-123",
              "authenticatedAt": 1710000000000,
              "acr": "urn:test:acr",
              "amr": ["vp", "idv"],
              "claims": {
                "sub": "user-123",
                "given_name": "Jane",
                "family_name": "Doe",
                "email": "jane@example.com",
                "institution": "kw1c",
                "roles": ["student", "employee"],
                "address": {
                  "locality": "Tilburg"
                }
              }
            }
        """.trimIndent()
        val provider = StsWalletAuthProvider("http://auth-bridge") { uri ->
            FakeHttpURLConnection(uri.toURL(), 200, responseBody)
        }

        val authenticated = provider.getAuthenticatedUser("session-1")

        assertTrue(authenticated.isOk)
        assertEquals("user-123", authenticated.value?.userId)
        assertEquals("urn:test:acr", authenticated.value?.acr)
        assertEquals(listOf("vp", "idv"), authenticated.value?.amr)

        val userInfo = provider.getUserInfo("user-123")
        assertTrue(userInfo.isOk)
        assertEquals("Jane Doe", userInfo.value.displayName)
        assertEquals("jane@example.com", userInfo.value.email)
        assertEquals("kw1c", userInfo.value.attributes["institution"])
        assertEquals(listOf("student", "employee"), userInfo.value.attributes["roles"])
        val address = assertIs<Map<*, *>>(userInfo.value.attributes["address"])
        assertEquals("Tilburg", address["locality"])
    }

    @Test
    fun `wallet provider returns null when auth bridge session is not ready`() = runBlocking {
        val provider = StsWalletAuthProvider("http://auth-bridge") { uri ->
            FakeHttpURLConnection(uri.toURL(), 409, """{"error":"not_ready"}""")
        }

        val authenticated = provider.getAuthenticatedUser("session-2")

        assertTrue(authenticated.isOk)
        assertNull(authenticated.value)

        val userInfo = provider.getUserInfo("missing-user")
        assertTrue(userInfo.isOk)
        assertEquals("OID4VP User", userInfo.value.displayName)
        assertTrue(userInfo.value.attributes.isEmpty())
    }

    @Test
    fun `wallet provider uses configured auth bridge base url when opening connection`() = runBlocking {
        val capturedUris = mutableListOf<URI>()
        val provider = StsWalletAuthProvider("http://auth-bridge:8090/root") { uri ->
            capturedUris.add(uri)
            FakeHttpURLConnection(uri.toURL(), 200, """{"userId":"user-1","claims":{}}""")
        }

        val result = provider.getAuthenticatedUser("session-3")

        assertTrue(result.isOk)
        assertTrue(capturedUris.isNotEmpty())
        assertEquals("http://auth-bridge:8090/root/auth/oid4vp/sessions/session-3/complete", capturedUris.first().toString())
    }

    private class FakeHttpURLConnection(
        url: URL,
        private val statusCode: Int,
        private val body: String,
    ) : HttpURLConnection(url) {

        private val responseBytes = body.toByteArray(Charsets.UTF_8)

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit

        override fun getResponseCode(): Int = statusCode

        override fun getInputStream(): InputStream {
            assertTrue(statusCode in 200..299)
            return ByteArrayInputStream(responseBytes)
        }

        override fun getErrorStream(): InputStream? {
            return if (statusCode in 200..299) null else ByteArrayInputStream(responseBytes)
        }
    }
}
