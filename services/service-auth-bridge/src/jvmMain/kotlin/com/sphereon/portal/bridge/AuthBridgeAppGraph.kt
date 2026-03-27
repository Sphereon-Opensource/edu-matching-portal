package com.sphereon.portal.bridge

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.identity.matching.store.IdentityLinkBindingStore
import com.sphereon.identity.matching.store.IdentityMatchStore
import com.sphereon.openid.oid4vp.auth.store.Oid4vpAuthSessionStore
import com.sphereon.portal.bridge.db.AuthBridgeDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/**
 * AppGraph for the Auth Bridge service.
 *
 * Merges all DI contributions for:
 * - OID4VP Auth Bridge (IDK)
 * - Identity Matching / Resolution / Reconciliation (IDK)
 * - Claims Mapping (IDK)
 * - Software KMS Provider (IDK)
 * - Config property sources
 * - PostgreSQL persistence (AuthBridgeDatabase)
 */
@DependencyGraph(AppScope::class)
abstract class AuthBridgeAppGraph : AbstractAppGraph() {
    // Exposed for external API initialization (not DI-managed)
    abstract val authBridgeDatabase: AuthBridgeDatabase
    abstract val identityMatchStore: IdentityMatchStore
    abstract val identityLinkBindingStore: IdentityLinkBindingStore
    abstract val oid4vpAuthSessionStore: Oid4vpAuthSessionStore

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): AuthBridgeAppGraph
    }
}

fun initAuthBridgeAppGraph(
    application: Any = Unit,
    appId: String = "portal-auth-bridge",
    profile: String = "development",
    version: String = "dev"
): AuthBridgeAppGraph {
    val graph = createGraphFactory<AuthBridgeAppGraph.Factory>().create(
        application = application,
        appId = appId,
        profile = profile,
        version = version,
        rootScopeProvider = DefaultRootScopeProvider(),
    )
    graph.initRootScopeProvider()
    return graph
}
