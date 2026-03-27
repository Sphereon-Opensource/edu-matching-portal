package com.sphereon.portal.sts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StsCanonicalProjectionTest {

    private val extractionMappings = listOf(
        FederationExtractionMapping(source = "sub", target = "federated_subject", required = true),
        FederationExtractionMapping(source = "eduid", target = "eduid_id1"),
        FederationExtractionMapping(source = "schac_home_organization", target = "institution_id"),
        FederationExtractionMapping(source = "given_name", target = "given_name"),
        FederationExtractionMapping(source = "family_name", target = "family_name"),
        FederationExtractionMapping(source = "email", target = "email"),
    )

    private val canonicalRules = listOf(
        StsCanonicalAttributeRule("given_name", "OIDC_WINS", required = true, persist = true, project = true),
        StsCanonicalAttributeRule("family_name", "OIDC_WINS", required = true, persist = true, project = true),
        StsCanonicalAttributeRule("email", "OIDC_WINS", required = true, persist = true, project = true),
        StsCanonicalAttributeRule("federated_subject", "OIDC_ONLY", required = true, persist = true, project = true),
        StsCanonicalAttributeRule("eduid_id1", "OIDC_WINS", required = false, persist = true, project = false),
        StsCanonicalAttributeRule("institution_id", "OIDC_ONLY", required = false, persist = true, project = true),
        StsCanonicalAttributeRule("eduperson_affiliation", "OIDC_ONLY", required = false, persist = false, project = true),
    )

    @Test
    fun projectsFederatedClaimsToCanonicalOutput() {
        val rawClaims = mapOf<String, Any>(
            "sub" to "user-123",
            "eduid" to "eduid-456",
            "schac_home_organization" to "kw1c.nl",
            "given_name" to "Alice",
            "family_name" to "Smith",
            "email" to "alice@kw1c.nl",
            "extra_upstream_claim" to "should-not-appear",
        )

        val result = StsAuthProvidersModule.applyCanonicalProjection(
            rawClaims, extractionMappings, canonicalRules
        )

        // project=true claims should be present
        assertEquals("Alice", result["given_name"])
        assertEquals("Smith", result["family_name"])
        assertEquals("alice@kw1c.nl", result["email"])
        assertEquals("user-123", result["federated_subject"])
        assertEquals("kw1c.nl", result["institution_id"])

        // project=false claims should NOT be present
        assertFalse(result.containsKey("eduid_id1"))

        // Upstream claims not in extraction mappings should NOT be present
        assertFalse(result.containsKey("extra_upstream_claim"))
    }

    @Test
    fun missingOptionalClaimsAreOmitted() {
        val rawClaims = mapOf<String, Any>(
            "sub" to "user-123",
            "given_name" to "Alice",
            "family_name" to "Smith",
            "email" to "alice@example.com",
        )

        val result = StsAuthProvidersModule.applyCanonicalProjection(
            rawClaims, extractionMappings, canonicalRules
        )

        assertTrue(result.containsKey("given_name"))
        assertTrue(result.containsKey("federated_subject"))
        assertFalse(result.containsKey("institution_id"))
        assertFalse(result.containsKey("eduid_id1"))
    }

    @Test
    fun extractionRenamingWorksCorrectly() {
        val rawClaims = mapOf<String, Any>(
            "sub" to "user-123",
            "schac_home_organization" to "kw1c.nl",
            "given_name" to "Bob",
            "family_name" to "Jones",
            "email" to "bob@example.com",
        )

        val result = StsAuthProvidersModule.applyCanonicalProjection(
            rawClaims, extractionMappings, canonicalRules
        )

        // schac_home_organization → institution_id (extraction rename)
        assertEquals("kw1c.nl", result["institution_id"])
        assertFalse(result.containsKey("schac_home_organization"))
    }

    @Test
    fun failsClosedOnMissingRequiredAttributes() {
        // Missing given_name, family_name, email -- all required
        val rawClaims = mapOf<String, Any>(
            "sub" to "user-123",
        )

        val ex = assertFailsWith<IllegalStateException> {
            StsAuthProvidersModule.applyCanonicalProjection(
                rawClaims, extractionMappings, canonicalRules
            )
        }
        assertTrue(ex.message!!.contains("given_name"))
        assertTrue(ex.message!!.contains("family_name"))
        assertTrue(ex.message!!.contains("email"))
    }

    @Test
    fun succeedsWhenAllRequiredAttributesPresent() {
        val rawClaims = mapOf<String, Any>(
            "sub" to "user-123",
            "given_name" to "Alice",
            "family_name" to "Smith",
            "email" to "alice@example.com",
        )

        // Should not throw -- all required attributes are present
        val result = StsAuthProvidersModule.applyCanonicalProjection(
            rawClaims, extractionMappings, canonicalRules
        )
        assertEquals("Alice", result["given_name"])
    }
}
