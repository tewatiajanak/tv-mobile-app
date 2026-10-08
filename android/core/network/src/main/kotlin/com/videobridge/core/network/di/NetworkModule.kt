package com.videobridge.core.network.di

import com.videobridge.core.network.AuthApi
import com.videobridge.core.network.AuthTokenSource
import com.videobridge.core.network.DevicesApi
import com.videobridge.core.network.HealthApi
import com.videobridge.core.network.NetworkConfig
import com.videobridge.core.network.PairingApi
import com.videobridge.core.network.RefreshApi
import com.videobridge.core.network.VideosApi
import com.videobridge.core.network.createJson
import com.videobridge.core.network.createOkHttpClient
import com.videobridge.core.network.createRetrofit
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

/** [NetworkConfig] is provided by each app module, [AuthTokenSource] by core:data. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = createJson()

    @Provides
    @Singleton
    fun provideRefreshApi(config: NetworkConfig, json: Json): RefreshApi =
        createRetrofit(config.apiBaseUrl, createOkHttpClient(config), json).create(RefreshApi::class.java)

    @Provides
    @Singleton
    fun provideOkHttpClient(config: NetworkConfig, tokens: AuthTokenSource, refreshApi: RefreshApi): OkHttpClient =
        createOkHttpClient(config, tokens to refreshApi)

    @Provides
    @Singleton
    fun provideRetrofit(config: NetworkConfig, client: OkHttpClient, json: Json): Retrofit = createRetrofit(config.apiBaseUrl, client, json)

    @Provides
    @Singleton
    fun provideHealthApi(retrofit: Retrofit): HealthApi = retrofit.create(HealthApi::class.java)

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun providePairingApi(retrofit: Retrofit): PairingApi = retrofit.create(PairingApi::class.java)

    @Provides
    @Singleton
    fun provideDevicesApi(retrofit: Retrofit): DevicesApi = retrofit.create(DevicesApi::class.java)

    @Provides
    @Singleton
    fun provideVideosApi(retrofit: Retrofit): VideosApi = retrofit.create(VideosApi::class.java)
}
