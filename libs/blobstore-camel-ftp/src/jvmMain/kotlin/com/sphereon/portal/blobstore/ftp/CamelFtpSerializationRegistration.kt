package com.sphereon.portal.blobstore.ftp

import com.sphereon.core.api.json.SerializerRegistration
import com.sphereon.data.store.blob.BlobStoreConfigBase
import com.sphereon.data.store.blob.BlobStoreJsonSupport
import com.sphereon.portal.blobstore.config.PasswordAuthConfig
import com.sphereon.portal.blobstore.config.PrivateKeyAuthConfig
import com.sphereon.portal.blobstore.config.SftpLikeAuthConfig
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
class CamelFtpSerializationRegistration : SerializerRegistration {
    override fun onEnterScope(scope: Scope) {
        BlobStoreJsonSupport.register("camel-ftp-blob-stores") {
            polymorphic(BlobStoreConfigBase::class) {
                subclass(CamelSftpBlobStoreConfig::class)
                subclass(CamelFtpBlobStoreConfig::class)
            }
            polymorphic(SftpLikeAuthConfig::class) {
                subclass(PasswordAuthConfig::class)
                subclass(PrivateKeyAuthConfig::class)
            }
        }
    }
}

@ContributesTo(AppScope::class)
interface CamelFtpSerializationRegistrationScopedModule {
    @Provides
    @IntoSet
    @ForScope(AppScope::class)
    fun provideFtpSerializationScoped(impl: CamelFtpSerializationRegistration): Scoped = impl
}
