package com.videobridge.phone.di

import com.videobridge.core.network.NetworkConfig
import com.videobridge.phone.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideNetworkConfig(): NetworkConfig = NetworkConfig(
        apiBaseUrl = BuildConfig.API_BASE_URL,
        wsUrl = BuildConfig.WS_URL,
        appVersion = BuildConfig.VERSION_NAME,
        platform = "android-phone",
        logRequests = BuildConfig.FLAVOR == "dev",
    )
}
