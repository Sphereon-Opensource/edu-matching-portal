package com.sphereon.portal.bridge.persistence

import com.sphereon.identity.reconciliation.model.ReconciliationAttributeMapping
import com.sphereon.identity.reconciliation.model.ReconciliationProvider
import com.sphereon.identity.reconciliation.impl.store.InMemoryReconciliationProviderStore
import com.sphereon.identity.reconciliation.store.ReconciliationProviderStore
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(
    AppScope::class,
    binding = binding<ReconciliationProviderStore>(),
    replaces = [InMemoryReconciliationProviderStore::class]
)
class PostgresReconciliationProviderStore(
    private val database: AuthBridgeDatabase,
) : ReconciliationProviderStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findById(providerId: String): ReconciliationProvider? {
        return database.authBridgeQueries
            .findProviderById(providerId)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun findAll(): List<ReconciliationProvider> {
        return database.authBridgeQueries
            .findAllProviders()
            .executeAsList()
            .map { it.toDomain() }
    }

    override suspend fun save(provider: ReconciliationProvider): ReconciliationProvider {
        database.authBridgeQueries.upsertProvider(
            id = provider.id,
            name = provider.name,
            oidcClientId = provider.oidcClientId,
            identifierAttrName = provider.identifierAttributeName,
            enabled = if (provider.enabled) 1 else 0,
            attributeMappings = if (provider.attributeMappings.isNotEmpty()) {
                json.encodeToString(kotlinx.serialization.serializer<List<ReconciliationAttributeMapping>>(), provider.attributeMappings)
            } else null,
            userinfoAttrMappings = if (provider.userInfoAttributeMappings.isNotEmpty()) {
                json.encodeToString(kotlinx.serialization.serializer<List<ReconciliationAttributeMapping>>(), provider.userInfoAttributeMappings)
            } else null,
            assuranceAcr = provider.assuranceAcr,
            assuranceAmr = provider.assuranceAmr?.let { json.encodeToString(kotlinx.serialization.serializer<List<String>>(), it) },
        )
        return provider
    }

    override suspend fun delete(providerId: String): Boolean {
        database.authBridgeQueries.deleteProvider(providerId)
        return true
    }

    private fun com.sphereon.portal.bridge.db.Reconciliation_provider.toDomain(): ReconciliationProvider {
        return ReconciliationProvider(
            id = id,
            name = name,
            oidcClientId = oidc_client_id,
            identifierAttributeName = identifier_attr_name,
            enabled = enabled.toInt() != 0,
            attributeMappings = attribute_mappings?.let {
                json.decodeFromString<List<ReconciliationAttributeMapping>>(it)
            } ?: emptyList(),
            userInfoAttributeMappings = userinfo_attr_mappings?.let {
                json.decodeFromString<List<ReconciliationAttributeMapping>>(it)
            } ?: emptyList(),
            assuranceAcr = assurance_acr,
            assuranceAmr = assurance_amr?.let { json.decodeFromString<List<String>>(it) },
        )
    }
}
