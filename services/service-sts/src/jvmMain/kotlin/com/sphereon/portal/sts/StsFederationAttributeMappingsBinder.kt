package com.sphereon.portal.sts

/**
 * A single federation extraction mapping entry.
 *
 * Maps an upstream IdP attribute (e.g., "schac_home_organization") to a canonical attribute name
 * (e.g., "institution_id"). These mappings translate upstream provider-specific attribute keys
 * to the shared canonical vocabulary defined in [StsCanonicalAttributeRule].
 *
 * @property source Upstream IdP attribute key
 * @property target Canonical attribute name
 * @property required Whether the upstream attribute is required (fail if absent)
 */
data class FederationExtractionMapping(
    val source: String,
    val target: String,
    val required: Boolean = false,
)
