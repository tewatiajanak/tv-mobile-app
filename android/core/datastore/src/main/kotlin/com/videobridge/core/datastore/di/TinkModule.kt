package com.videobridge.core.datastore.di

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.videobridge.core.datastore.AppSettingsDataStore
import com.videobridge.core.datastore.DeviceSettings
import com.videobridge.core.datastore.DownloadRecords
import com.videobridge.core.datastore.EncryptedTokenStore
import com.videobridge.core.datastore.TokenStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TinkModule {
    /** The data key is kept in private prefs, itself encrypted by a Keystore-held master key. */
    @Provides
    @Singleton
    fun provideAead(@ApplicationContext context: Context): Aead {
        AeadConfig.register()
        return AndroidKeysetManager
            .Builder()
            .withSharedPref(context, "vb_token_keyset", "vb_token_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://vb_token_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface TokenStoreModule {
    @Binds
    fun bindTokenStore(impl: EncryptedTokenStore): TokenStore

    @Binds
    fun bindDeviceSettings(impl: AppSettingsDataStore): DeviceSettings

    @Binds
    fun bindDownloadRecords(impl: AppSettingsDataStore): DownloadRecords
}
