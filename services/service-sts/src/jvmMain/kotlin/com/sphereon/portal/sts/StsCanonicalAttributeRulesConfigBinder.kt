package com.sphereon.portal.sts

import com.sphereon.core.api.conf.PropertyResolver

/**
 * A canonical attribute rule for the STS projection pipeline.
 *
 * Local STS type to avoid pulling in `lib-identity-reconciliation-public`
 * which triggers unwanted DI graph wiring via `@ContributesTo` annotations.
 *
 * merge-mode is validated against the canonical set of allowed values
 * to ensure the STS rejects config that would also be rejected by the auth-bridge.
 */
data class StsCanonicalAttributeRule(
    val canonicalName: String,
    val mergeMode: String,
    val required: Boolean = false,
    val persist: Boolean = false,
    val project: Boolean = false,
    val sourceAliases: Map<String, String> = emptyMap(),
)

/**
 * Binds canonical attribute rules from configuration via [PropertyResolver].
 *
 * Uses indexed list format — same approach as [CanonicalAttributeRulesConfigBinder] in the IDK
 * but produces local [StsCanonicalAttributeRule] objects to avoid DI graph contamination.
 *
 * ```
 * identity.reconciliation.attribute-rules[0].canonical-name=given_name
 * identity.reconciliation.attribute-rules[0].merge-mode=OIDC_WINS
 * identity.reconciliation.attribute-rules[0].source-aliases.surf=sub
 * ```
 */
object StsCanonicalAttributeRulesConfigBinder {

    private const val PREFIX = "identity.reconciliation.attribute-rules"

    private val VALID_MERGE_MODES = setOf(
        "WALLET_ONLY", "OIDC_ONLY", "OIDC_WINS", "WALLET_WINS", "MERGE_ALL",
    )

    /** Merge modes that include OIDC-sourced attributes. */
    private val OIDC_MODES = setOf("OIDC_ONLY", "OIDC_WINS", "WALLET_WINS", "MERGE_ALL")

    fun bind(
        configService: PropertyResolver,
        knownProviderIds: Set<String> = emptySet(),
    ): List<StsCanonicalAttributeRule> {
        val rules = mutableListOf<StsCanonicalAttributeRule>()
        var index = 0
        while (true) {
            val rulePrefix = "$PREFIX[$index]"
            val canonicalName = configService.getPropertyAsString("$rulePrefix.canonical-name", null)
                ?: break

            val mergeMode = configService.getPropertyAsString("$rulePrefix.merge-mode", null)
                ?.replace('-', '_')?.uppercase()
                ?: error("Missing 'merge-mode' for canonical attribute rule: $canonicalName (at index $index)")

            require(mergeMode in VALID_MERGE_MODES) {
                "Invalid merge-mode '$mergeMode' for attribute rule '$canonicalName'. " +
                    "Allowed: ${VALID_MERGE_MODES.joinToString(", ")}"
            }

            val sourceAliases = knownProviderIds.mapNotNull { providerId ->
                configService.getPropertyAsString("$rulePrefix.source-aliases.$providerId", null)
                    ?.let { providerId to it }
            }.toMap()

            rules.add(
                StsCanonicalAttributeRule(
                    canonicalName = canonicalName,
                    mergeMode = mergeMode,
                    required = configService.getPropertyAsString("$rulePrefix.required", "false")?.toBoolean() == true,
                    persist = configService.getPropertyAsString("$rulePrefix.persist", "false")?.toBoolean() == true,
                    project = configService.getPropertyAsString("$rulePrefix.project", "false")?.toBoolean() == true,
                    sourceAliases = sourceAliases,
                )
            )
            index++
        }

        val duplicates = rules.groupBy { it.canonicalName }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate canonical-name(s) in attribute rules: ${duplicates.joinToString(", ")}"
        }

        return rules
    }

    /**
     * Derive federation extraction mappings for a specific provider from canonical rules.
     * Only includes attributes whose merge-mode allows OIDC sources.
     */
    fun deriveExtractionMappings(
        rules: List<StsCanonicalAttributeRule>,
        providerId: String,
    ): List<FederationExtractionMapping> {
        return rules
            .filter { it.mergeMode in OIDC_MODES }
            .map { rule ->
                FederationExtractionMapping(
                    source = rule.sourceAliases[providerId] ?: rule.canonicalName,
                    target = rule.canonicalName,
                    required = rule.required,
                )
            }
    }
}
