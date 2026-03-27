package com.sphereon.portal.sts

import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.di.app.RootScopeProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/**
 * AppGraph for the Portal STS (Security Token Service).
 *
 * Merges all DI contributions for:
 * - OAuth2 Authorization Server (IDK)
 * - OAuth2 Client for upstream federation (IDK)
 * - Config property sources
 */
@DependencyGraph(AppScope::class)
abstract class StsAppGraph : AbstractAppGraph() {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): StsAppGraph
    }
}

fun initStsAppGraph(
    application: Any = Unit,
    appId: String = "portal-sts",
    profile: String = "development",
    version: String = "dev"
): StsAppGraph {
    val graph = createGraphFactory<StsAppGraph.Factory>().create(
        application = application,
        appId = appId,
        profile = profile,
        version = version,
        rootScopeProvider = DefaultRootScopeProvider(),
    )
    graph.initRootScopeProvider()
    return graph
}
