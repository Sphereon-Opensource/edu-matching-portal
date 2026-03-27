package com.sphereon.portal.bridge

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.identity.reconciliation.impl.config.ReconciliationProviderConfigBinder
import com.sphereon.identity.reconciliation.store.OidcClientConfigStore
import com.sphereon.identity.reconciliation.store.ReconciliationProviderStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo

/**
 * Populates the [ReconciliationProviderStore] from config at startup.
 *
 * The store is AppScope-scoped (singleton), so populating it once
 * makes providers available to all session-scoped commands.
 */
object AuthBridgeReconciliationInitializer {

    @ContributesTo(AppScope::class)
    interface Graph {
        val reconciliationProviderStore: ReconciliationProviderStore
        val oidcClientConfigStore: OidcClientConfigStore
        val appConfigService: AppConfigService
    }

    suspend fun initialize(graph: AuthBridgeAppGraph) {
        val appGraph = graph as? Graph ?: run {
            println("WARNING: ReconciliationProviderStore not available in AppGraph, skipping provider bootstrap")
            return
        }

        val configService = appGraph.appConfigService
        val providerStore = appGraph.reconciliationProviderStore
        val oidcClientConfigStore = appGraph.oidcClientConfigStore
        val binder = ReconciliationProviderConfigBinder(configService)

        val oidcClients = binder.bindOidcClients()
        for (client in oidcClients) {
            oidcClientConfigStore.save(client)
            println("Registered OIDC client config: id=${client.id}, discoveryUrl=${client.discoveryUrl}")
        }

        val providers = binder.bindProviders()

        if (providers.isEmpty()) {
            println("WARNING: No reconciliation providers configured. IDV flow will fail.")
            return
        }

        for (provider in providers) {
            if (provider.enabled) {
                providerStore.save(provider)
                println("Registered reconciliation provider: id=${provider.id}, name=${provider.name}, oidcClientId=${provider.oidcClientId}")
            }
        }

        println("Loaded ${oidcClients.size} OIDC client(s), ${providers.count { it.enabled }} reconciliation provider(s)")
    }
}
