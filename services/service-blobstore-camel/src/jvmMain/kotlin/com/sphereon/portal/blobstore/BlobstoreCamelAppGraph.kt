package com.sphereon.portal.blobstore

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.defaults.app.DefaultRootScopeProvider
import com.sphereon.data.store.blob.impl.BlobStoreConfigBinder
import com.sphereon.data.store.blob.impl.BlobStoreManager
import com.sphereon.di.app.AbstractAppGraph
import com.sphereon.di.app.RootScopeProvider
import com.sphereon.portal.blobstore.catalog.BlobStoreCatalogService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import org.apache.camel.CamelContext

@DependencyGraph(AppScope::class)
abstract class BlobstoreCamelAppGraph : AbstractAppGraph() {
    abstract val camelContext: CamelContext
    abstract override val appConfigService: AppConfigService
    abstract override val blobStoreConfigBinder: BlobStoreConfigBinder
    abstract override val blobStoreManager: BlobStoreManager
    abstract val catalogService: BlobStoreCatalogService

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Any,
            @Provides @Named("appId") appId: String,
            @Provides @Named("profile") profile: String,
            @Provides @Named("version") version: String,
            @Provides rootScopeProvider: RootScopeProvider,
        ): BlobstoreCamelAppGraph
    }
}

fun initBlobstoreCamelAppGraph(
    application: Any = Unit,
    appId: String = "portal-blobstore-camel",
    profile: String = "development",
    version: String = BlobstoreCamelAppGraph::class.java.`package`?.implementationVersion ?: "dev",
): BlobstoreCamelAppGraph {
    val graph = createGraphFactory<BlobstoreCamelAppGraph.Factory>().create(
        application = application,
        appId = appId,
        profile = profile,
        version = version,
        rootScopeProvider = DefaultRootScopeProvider(),
    )
    graph.initRootScopeProvider()
    return graph
}
