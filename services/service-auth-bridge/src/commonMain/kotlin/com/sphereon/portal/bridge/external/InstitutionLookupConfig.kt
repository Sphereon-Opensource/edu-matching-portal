package com.sphereon.portal.bridge.external

/**
 * Configuration for institution-level reverse lookup.
 *
 * During reconciliation, a second identity match is created using the configured
 * canonical attribute (e.g. federated_subject) hashed with Key B. This enables the External
 * API to look up identities by institution identifier (GDPR Art. 15, downstream provisioning).
 *
 * @property attributeName Canonical attribute name to use as lookup key (e.g. "federated_subject", "email").
 *   When null, no institution match is created.
 * @property identifierType IdentifierType value for the match record (default: "SUBJECT_ID").
 */
data class InstitutionLookupConfig(
    val attributeName: String?,
    val identifierType: String = "SUBJECT_ID",
)
