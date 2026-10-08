package com.videobridge.tv.di

import com.videobridge.core.network.NetworkConfig
import com.videobridge.tv.BuildConfig
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
        platform = "android-tv",
        logRequests = BuildConfig.FLAVOR == "dev",
    )
}
