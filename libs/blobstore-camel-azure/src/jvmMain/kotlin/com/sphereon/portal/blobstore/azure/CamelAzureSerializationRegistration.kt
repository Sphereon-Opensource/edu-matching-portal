package com.sphereon.portal.blobstore.azure

import com.sphereon.core.api.json.SerializerRegistration
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreJsonSupport
import com.sphereon.portal.blobstore.config.AzureAccountKeyAuthConfig
import com.sphereon.portal.blobstore.config.AzureAuthConfig
import com.sphereon.portal.blobstore.config.AzureConnectionStringAuthConfig
import com.sphereon.portal.blobstore.config.AzureManagedIdentityAuthConfig
import com.sphereon.portal.blobstore.config.AzureSasTokenAuthConfig
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ForScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import software.amazon.app.platform.scope.Scope
import software.amazon.app.platform.scope.Scoped

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<SerializerRegistration>())
class CamelAzureSerializationRegistration : SerializerRegistration {
    override fun onEnterScope(scope: Scope) {
        BlobStoreJsonSupport.register("camel-azure-blob-store") {
            polymorphic(BlobStoreConfigBase::class) {
                subclass(CamelAzureBlobStoreConfig::class)
            }
            polymorphic(AzureAuthConfig::class) {
                subclass(AzureManagedIdentityAuthConfig::class)
                subclass(AzureAccountKeyAuthConfig::class)
                subclass(AzureSasTokenAuthConfig::class)
                subclass(AzureConnectionStringAuthConfig::class)
            }
        }
    }
}

@ContributesTo(AppScope::class)
interface CamelAzureSerializationRegistrationScopedModule {
    @Provides
    @IntoSet
    @ForScope(AppScope::class)
    fun provideAzureSerializationScoped(impl: CamelAzureSerializationRegistration): Scoped = impl
}
