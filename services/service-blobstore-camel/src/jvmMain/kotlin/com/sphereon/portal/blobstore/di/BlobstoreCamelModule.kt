package com.sphereon.portal.blobstore.di

import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.portal.blobstore.catalog.PortalBlobCatalogConfig
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.apache.camel.CamelContext
import org.apache.camel.impl.DefaultCamelContext

/**
 * Provides portal-specific infrastructure that isn't auto-discoverable.
 * Factories and serialization registrations are contributed via @ContributesBinding
 * on their own classes — no manual @IntoSet wiring needed.
 */
@ContributesTo(AppScope::class)
interface BlobstoreCamelModule {

    @Provides
    @SingleIn(AppScope::class)
    fun provideCamelContext(): CamelContext {
        val context = DefaultCamelContext()
        context.disableJMX()
        return context
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providePortalCatalogConfig(appConfigService: AppConfigService): PortalBlobCatalogConfig =
        PortalBlobCatalogConfig.fromPropertyResolver(appConfigService)
}
