package com.sphereon.portal.blobstore.catalog

import com.sphereon.core.api.conf.PropertyResolver
import com.sphereon.data.store.blob.BlobStoreCapabilities
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.impl.BlobStoreConfigBinder
import com.sphereon.data.store.blob.impl.BlobStoreManager
import com.sphereon.portal.blobstore.config.CamelStoreUiHints
import com.sphereon.portal.blobstore.config.HasCamelUiHints
import com.sphereon.data.store.blob.BlobStoreScopeBinding
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

private val logger = KotlinLogging.logger {}

// ── Response DTOs ───────────────────────────────────────────────────────────

@Serializable
data class BlobStoreCatalogResponse(
    val defaultStoreId: String?,
    val stores: List<BlobStoreCatalogItem>,
)

@Serializable
data class BlobStoreCatalogItem(
    val storeId: String,
    val kind: String,
    val label: String,
    val description: String? = null,
    val order: Int = 0,
    val readOnly: Boolean = false,
    val capabilities: BlobStoreCapabilities,
)

// ── Portal Catalog Config ───────────────────────────────────────────────────

/**
 * Portal-layer override config for the store catalog.
 * Parsed from `portal.blob.catalog.*` properties / env vars.
 */
data class PortalBlobCatalogConfig(
    val defaultStoreId: String? = null,
    val exposedStores: Set<String> = emptySet(),
    val stores: Map<String, StoreOverride> = emptyMap(),
) {
    data class StoreOverride(
        val label: String? = null,
        val description: String? = null,
        val order: Int? = null,
        val readOnly: Boolean? = null,
    )

    companion object {
        fun fromEnvironment(): PortalBlobCatalogConfig = fromSources(System.getenv(), null)

        fun fromPropertyResolver(resolver: PropertyResolver): PortalBlobCatalogConfig =
            fromSources(System.getenv(), resolver)

        private fun fromSources(
            env: Map<String, String>,
            resolver: PropertyResolver?,
        ): PortalBlobCatalogConfig {
            // PropertyResolver.getPropertyAsString handles key normalization internally
            // (camelCase → dot.separated.lowercase), so we can use the original key names.
            val defaultStoreId = env["PORTAL_BLOB_CATALOG_DEFAULT_STORE_ID"]
                ?: resolver?.getPropertyAsString("portal.blob.catalog.defaultStoreId")

            val exposedRaw = env["PORTAL_BLOB_CATALOG_EXPOSED_STORES"]
                ?: resolver?.getPropertyAsString("portal.blob.catalog.exposedStores")
            val exposed = exposedRaw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

            val storeOverrides = mutableMapOf<String, StoreOverride>()

            // Parse per-store overrides from env: PORTAL_BLOB_CATALOG_STORE_{ID}_{FIELD}
            env.forEach { (key, value) ->
                val prefix = "PORTAL_BLOB_CATALOG_STORE_"
                if (key.startsWith(prefix)) {
                    val remainder = key.removePrefix(prefix)
                    val parts = remainder.split('_', limit = 2)
                    if (parts.size == 2) {
                        val storeId = parts[0].lowercase()
                        val field = parts[1].uppercase()
                        applyOverride(storeOverrides, storeId, field, value)
                    }
                }
            }

            // Parse per-store overrides from properties: portal.blob.catalog.store.{id}.{field}
            // Keys are returned in normalized form (dot-separated lowercase) after prefix stripping.
            if (resolver != null) {
                val storeProps = resolver.getSubPropertiesAsString(
                    setOf("portal.blob.catalog.store"),
                    stripPrefix = true,
                    redact = false,
                )
                storeProps.forEach { (key, value) ->
                    // After stripping "portal.blob.catalog.store.", key is "{id}.{field}"
                    val dotIdx = key.indexOf('.')
                    if (dotIdx > 0) {
                        val storeId = key.substring(0, dotIdx).lowercase()
                        val field = key.substring(dotIdx + 1).uppercase()
                        // Only set from props if not already set by env
                        val existing = storeOverrides[storeId]
                        if (existing == null) {
                            applyOverride(storeOverrides, storeId, field, value)
                        } else {
                            applyOverrideIfAbsent(storeOverrides, storeId, field, value, existing)
                        }
                    }
                }
            }

            return PortalBlobCatalogConfig(
                defaultStoreId = defaultStoreId,
                exposedStores = exposed,
                stores = storeOverrides,
            )
        }

        private fun applyOverride(
            overrides: MutableMap<String, StoreOverride>,
            storeId: String,
            field: String,
            value: String,
        ) {
            val existing = overrides.getOrPut(storeId) { StoreOverride() }
            overrides[storeId] = when (field) {
                "LABEL" -> existing.copy(label = value)
                "DESCRIPTION" -> existing.copy(description = value)
                "ORDER" -> existing.copy(order = value.toIntOrNull())
                "READ_ONLY", "READONLY" -> existing.copy(readOnly = value.toBooleanStrictOrNull())
                else -> existing
            }
        }

        /** Apply override only if the field is not already set (env takes priority). */
        private fun applyOverrideIfAbsent(
            overrides: MutableMap<String, StoreOverride>,
            storeId: String,
            field: String,
            value: String,
            existing: StoreOverride,
        ) {
            overrides[storeId] = when (field) {
                "LABEL" -> if (existing.label == null) existing.copy(label = value) else existing
                "DESCRIPTION" -> if (existing.description == null) existing.copy(description = value) else existing
                "ORDER" -> if (existing.order == null) existing.copy(order = value.toIntOrNull()) else existing
                "READ_ONLY", "READONLY" -> if (existing.readOnly == null) existing.copy(readOnly = value.toBooleanStrictOrNull()) else existing
                else -> existing
            }
        }
    }
}

// ── Catalog Service ─────────────────────────────────────────────────────────

@Inject
@SingleIn(AppScope::class)
class BlobStoreCatalogService(
    private val configBinder: BlobStoreConfigBinder,
    private val blobStoreManager: BlobStoreManager,
    private val portalCatalogConfig: PortalBlobCatalogConfig,
) {
    fun getCatalog(
        configs: Array<BlobStoreConfigBase>,
        tenantId: String?,
    ): BlobStoreCatalogResponse {
        val enabledConfigs = configs.filter { it.enabled }
        val exposed = resolveExposedStoreIds(enabledConfigs)

        val items = enabledConfigs
            .filter { it.id in exposed }
            .filter { isVisibleForTenant(it, tenantId) }
            .mapNotNull { config ->
                val ui = getUiHints(config)
                val override = portalCatalogConfig.stores[config.id]

                // Try to resolve store for capabilities; skip broken stores
                val store = try {
                    blobStoreManager.resolve(config)
                } catch (e: Exception) {
                    logger.warn(e) { "Skipping store '${config.id}' — failed to resolve: ${e.message}" }
                    return@mapNotNull null
                }

                BlobStoreCatalogItem(
                    storeId = config.id,
                    kind = config.backendId,
                    label = override?.label ?: ui?.label ?: config.id,
                    description = override?.description ?: ui?.description,
                    order = override?.order ?: ui?.order ?: Int.MAX_VALUE,
                    readOnly = override?.readOnly ?: ui?.readOnly ?: deriveReadOnly(store.capabilities),
                    capabilities = store.capabilities,
                )
            }
            .sortedWith(compareBy<BlobStoreCatalogItem> { it.order }.thenBy { it.label.lowercase() })

        return BlobStoreCatalogResponse(
            defaultStoreId = portalCatalogConfig.defaultStoreId ?: items.firstOrNull()?.storeId,
            stores = items,
        )
    }

    private fun resolveExposedStoreIds(configs: List<BlobStoreConfigBase>): Set<String> {
        val explicit = portalCatalogConfig.exposedStores
        if (explicit.isNotEmpty()) return explicit
        return configs
            .filter { getUiHints(it)?.exposeInPortal != false }
            .map { it.id }
            .toSet()
    }

    /**
     * Derive read-only from capabilities when no explicit setting is provided.
     * Defaults to read-only (upload disabled). Stores must opt-in to uploads
     * via `ui.readOnly=false` or portal catalog overrides.
     */
    private fun deriveReadOnly(@Suppress("UNUSED_PARAMETER") capabilities: BlobStoreCapabilities): Boolean =
        true

    private fun getUiHints(config: BlobStoreConfigBase): CamelStoreUiHints? =
        (config as? HasCamelUiHints)?.ui

    /**
     * Tenant visibility: APP-scoped stores are visible to all tenants.
     * TENANT-scoped stores are visible when no tenantId filter is given (admin view)
     * or when the store's scope matches. The actual tenant data isolation is handled
     * by the IDK's path-prefixing in DefaultBlobService, not here.
     */
    private fun isVisibleForTenant(config: BlobStoreConfigBase, tenantId: String?): Boolean {
        if (tenantId == null) return true // no filter = show all
        if (config.scopeBinding == BlobStoreScopeBinding.APP) return true
        // TENANT-scoped stores are visible to any authenticated tenant
        return true
    }
}
