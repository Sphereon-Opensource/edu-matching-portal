package com.sphereon.portal.blobstore.gcs

import com.sphereon.core.api.json.SerializerRegistration
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreJsonSupport
import com.sphereon.portal.blobstore.config.GcsApplicationDefaultAuthConfig
import com.sphereon.portal.blobstore.config.GcsAuthConfig
import com.sphereon.portal.blobstore.config.GcsServiceAccountKeyAuthConfig
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
class CamelGcsSerializationRegistration : SerializerRegistration {
    override fun onEnterScope(scope: Scope) {
        BlobStoreJsonSupport.register("camel-gcs-blob-store") {
            polymorphic(BlobStoreConfigBase::class) {
                subclass(CamelGcsBlobStoreConfig::class)
            }
            polymorphic(GcsAuthConfig::class) {
                subclass(GcsApplicationDefaultAuthConfig::class)
                subclass(GcsServiceAccountKeyAuthConfig::class)
            }
        }
    }
}

@ContributesTo(AppScope::class)
interface CamelGcsSerializationRegistrationScopedModule {
    @Provides
    @IntoSet
    @ForScope(AppScope::class)
    fun provideGcsSerializationScoped(impl: CamelGcsSerializationRegistration): Scoped = impl
}
