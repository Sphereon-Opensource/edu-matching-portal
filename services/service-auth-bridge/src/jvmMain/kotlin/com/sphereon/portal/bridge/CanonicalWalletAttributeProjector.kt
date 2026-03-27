package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.di.session.SessionScope
import com.sphereon.identity.reconciliation.api.attributesToProject
import com.sphereon.identity.reconciliation.model.CanonicalAttributeBag
import com.sphereon.identity.reconciliation.model.CanonicalAttributeRule
import com.sphereon.identity.reconciliation.model.AttributeProvenanceSummary
import com.sphereon.openid.oid4vp.auth.claims.WalletAttributeProjector
import com.sphereon.openid.oid4vp.universal.VerifiedCredential
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * Canonical [WalletAttributeProjector] implementation.
 *
 * Supports two modes configured via `oid4vp.auth-bridge.wallet-claim-mapping-mode`:
 *
 * - **explicit** (default): Applies wallet extraction mappings from [WalletAttributeMappings],
 *   then canonical attribute rules, then projection.
 *
 * - **auto**: Auto-maps all wallet claims to canonical attributes. Normalizes claim names
 *   (hyphens → underscores), filters out excluded claims (configurable via
 *   `oid4vp.auth-bridge.wallet-claim-exclusions`), and projects `project=true` attributes.
 *   DCQL query only defines what to REQUEST from the wallet — mapping is fully automatic.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<WalletAttributeProjector>())
class CanonicalWalletAttributeProjector(
    private val walletAttributeMappings: WalletAttributeMappings,
    private val canonicalAttributeRules: List<CanonicalAttributeRule>,
    appConfigService: AppConfigService,
) : WalletAttributeProjector {

    private val mappingMode: String = appConfigService
        .getPropertyAsString("oid4vp.auth-bridge.wallet-claim-mapping-mode", "explicit")
        ?: "explicit"

    private val exclusions: Set<String> = appConfigService
        .getPropertyAsString("oid4vp.auth-bridge.wallet-claim-exclusions", DEFAULT_EXCLUSIONS)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()

    override suspend fun projectWalletAttributes(
        credentials: List<VerifiedCredential>,
    ): Map<String, JsonElement> {
        // 1. Flatten all credential claims
        val rawClaims = buildMap<String, JsonElement> {
            for (credential in credentials) {
                putAll(credential.claims)
            }
        }

        // 2. Apply mapping based on mode
        val canonicalAttributes = when (mappingMode) {
            "auto" -> autoMapClaims(rawClaims)
            else -> explicitMapClaims(rawClaims)
        }

        // 3. Build canonical attribute bag
        val bag = CanonicalAttributeBag(
            attributes = canonicalAttributes,
            provenance = AttributeProvenanceSummary(
                providerIds = setOf("wallet"),
            ),
        )

        // 4. Project only project=true attributes
        return attributesToProject(bag, canonicalAttributeRules)
    }

    /**
     * Explicit mode: apply wallet extraction mappings (wallet key -> canonical name).
     */
    private fun explicitMapClaims(rawClaims: Map<String, JsonElement>): Map<String, JsonElement> {
        return buildMap {
            for ((key, value) in rawClaims) {
                val canonicalKey = walletAttributeMappings.resolve(key) ?: key
                put(canonicalKey, value)
            }
        }
    }

    /**
     * Auto mode: map all wallet claims to canonical attributes automatically.
     * - Filter out excluded claims (iss, vct, cnf, etc.)
     * - Normalize claim names (hyphens → underscores)
     * - Match against known canonical attribute rules
     */
    private fun autoMapClaims(rawClaims: Map<String, JsonElement>): Map<String, JsonElement> {
        val knownCanonicalNames = canonicalAttributeRules.map { it.canonicalName }.toSet()

        // Build reverse alias map: wallet source name -> canonical name
        val reverseAliases = buildMap<String, String> {
            for (rule in canonicalAttributeRules) {
                for ((_, alias) in rule.sourceAliases) {
                    put(alias, rule.canonicalName)
                }
            }
        }

        return buildMap {
            for ((key, value) in rawClaims) {
                if (key in exclusions) continue

                // Try reverse alias first, then normalize, then use as-is
                val canonicalKey = reverseAliases[key]
                    ?: key.replace('-', '_').let { normalized ->
                        if (normalized in knownCanonicalNames) normalized else key
                    }
                put(canonicalKey, value)
            }
        }
    }

    companion object {
        private const val DEFAULT_EXCLUSIONS = "iss,vct,cnf,iat,exp,nbf"
    }
}
