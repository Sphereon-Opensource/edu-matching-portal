package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.PropertyResolver
import com.sphereon.identity.reconciliation.model.AttributeTupleMaterial
import com.sphereon.identity.reconciliation.model.CredentialAttributeTupleMaterial
import com.sphereon.identity.reconciliation.model.HolderKeyMaterial
import com.sphereon.identity.reconciliation.model.ProviderSubjectMaterial
import com.sphereon.identity.reconciliation.model.ReconciliationMaterial
import com.sphereon.identity.reconciliation.model.ReconciliationMaterialProfile

/**
 * Reads material profiles from indexed config at `identity.reconciliation.material-profiles.*`.
 */
object AuthBridgeMaterialProfileConfigBinder {

    private const val PREFIX = "identity.reconciliation.material-profiles"

    fun bind(configService: PropertyResolver): Map<String, ReconciliationMaterialProfile> {
        val profileNames = configService.getPropertyAsString("$PREFIX.names", null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return emptyMap()

        return profileNames.mapNotNull { name ->
            val profilePrefix = "$PREFIX.$name"
            val version = configService.getPropertyAsString("$profilePrefix.version", "1") ?: "1"
            val materials = readMaterials(configService, "$profilePrefix.materials")
            if (materials.isEmpty()) return@mapNotNull null
            name to ReconciliationMaterialProfile(
                id = name,
                version = version,
                materials = materials,
            )
        }.toMap()
    }

    private fun readMaterials(configService: PropertyResolver, prefix: String): List<ReconciliationMaterial> {
        val materials = mutableListOf<ReconciliationMaterial>()
        var index = 0
        while (true) {
            val itemPrefix = "$prefix[$index]"
            val type = configService.getPropertyAsString("$itemPrefix.type", null)?.trim() ?: break
            val material = when (type) {
                "holder_key_fp" -> HolderKeyMaterial(
                    hmacDomain = configService.getPropertyAsString("$itemPrefix.hmac-domain", "holder") ?: "holder",
                )
                "oidc_subject" -> ProviderSubjectMaterial(
                    providerId = configService.getPropertyAsString("$itemPrefix.provider-id", null)
                        ?: error("oidc_subject material requires provider-id at $itemPrefix"),
                    hmacDomain = configService.getPropertyAsString("$itemPrefix.hmac-domain", "institution") ?: "institution",
                )
                "claim_tuple" -> AttributeTupleMaterial(
                    attributePaths = readStringList(configService, "$itemPrefix.attribute-paths"),
                    normalizationProfile = configService.getPropertyAsString("$itemPrefix.normalization-profile", "lowercase-trim") ?: "lowercase-trim",
                    saltRef = configService.getPropertyAsString("$itemPrefix.salt-ref", null)
                        ?: error("claim_tuple material requires salt-ref at $itemPrefix"),
                    hmacDomain = configService.getPropertyAsString("$itemPrefix.hmac-domain", "material") ?: "material",
                    minRequiredAttributes = configService.getPropertyAsString("$itemPrefix.min-required-attributes", "1")?.toIntOrNull() ?: 1,
                )
                "credential_claim_tuple" -> CredentialAttributeTupleMaterial(
                    credentialQueryId = configService.getPropertyAsString("$itemPrefix.credential-query-id", null),
                    credentialId = configService.getPropertyAsString("$itemPrefix.credential-id", null),
                    attributePaths = readStringList(configService, "$itemPrefix.attribute-paths"),
                    normalizationProfile = configService.getPropertyAsString("$itemPrefix.normalization-profile", "lowercase-trim") ?: "lowercase-trim",
                    saltRef = configService.getPropertyAsString("$itemPrefix.salt-ref", null)
                        ?: error("credential_claim_tuple material requires salt-ref at $itemPrefix"),
                    hmacDomain = configService.getPropertyAsString("$itemPrefix.hmac-domain", "material") ?: "material",
                    minRequiredAttributes = configService.getPropertyAsString("$itemPrefix.min-required-attributes", "1")?.toIntOrNull() ?: 1,
                )
                else -> {
                    index++
                    continue
                }
            }
            materials += material
            index++
        }
        return materials
    }

    private fun readStringList(configService: PropertyResolver, key: String): List<String> {
        // Try comma-separated first
        val csv = configService.getPropertyAsString(key, null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (!csv.isNullOrEmpty()) return csv

        // Try indexed
        val items = mutableListOf<String>()
        var index = 0
        while (true) {
            val value = configService.getPropertyAsString("$key[$index]", null)?.trim() ?: break
            if (value.isNotEmpty()) items += value
            index++
        }
        return items
    }
}
