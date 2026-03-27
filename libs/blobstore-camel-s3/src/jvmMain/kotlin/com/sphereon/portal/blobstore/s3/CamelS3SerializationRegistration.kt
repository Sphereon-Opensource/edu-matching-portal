package com.sphereon.portal.blobstore.s3

import com.sphereon.core.api.json.SerializerRegistration
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreJsonSupport
import com.sphereon.portal.blobstore.config.AwsAccessKeyAuthConfig
import com.sphereon.portal.blobstore.config.AwsAuthConfig
import com.sphereon.portal.blobstore.config.AwsIrsaAuthConfig
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
class CamelS3SerializationRegistration : SerializerRegistration {
    override fun onEnterScope(scope: Scope) {
        BlobStoreJsonSupport.register("camel-s3-blob-store") {
            polymorphic(BlobStoreConfigBase::class) {
                subclass(CamelS3BlobStoreConfig::class)
            }
            polymorphic(AwsAuthConfig::class) {
                subclass(AwsIrsaAuthConfig::class)
                subclass(AwsAccessKeyAuthConfig::class)
            }
        }
    }
}

@ContributesTo(AppScope::class)
interface CamelS3SerializationRegistrationScopedModule {
    @Provides
    @IntoSet
    @ForScope(AppScope::class)
    fun provideS3SerializationScoped(impl: CamelS3SerializationRegistration): Scoped = impl
}
