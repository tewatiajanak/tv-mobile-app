package com.videobridge.core.data.di

import com.videobridge.core.data.DefaultHealthRepository
import com.videobridge.core.data.HealthRepository
import com.videobridge.core.data.auth.AndroidDeviceInfoProvider
import com.videobridge.core.data.auth.AuthRepository
import com.videobridge.core.data.auth.DefaultAuthRepository
import com.videobridge.core.data.auth.DeviceInfoProvider
import com.videobridge.core.data.auth.SessionManager
import com.videobridge.core.data.devices.DefaultDevicesRepository
import com.videobridge.core.data.devices.DefaultPairingRepository
import com.videobridge.core.data.devices.DevicesRepository
import com.videobridge.core.data.devices.PairingRepository
import com.videobridge.core.data.downloads.Downloads
import com.videobridge.core.data.downloads.DownloadsManager
import com.videobridge.core.data.downloads.InstalledVideoApps
import com.videobridge.core.data.downloads.VideoApps
import com.videobridge.core.data.videos.DefaultVideosRepository
import com.videobridge.core.data.videos.VideosRepository
import com.videobridge.core.network.AuthTokenSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {
    @Binds
    fun bindHealthRepository(impl: DefaultHealthRepository): HealthRepository

    @Binds
    fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository

    @Binds
    fun bindDeviceInfoProvider(impl: AndroidDeviceInfoProvider): DeviceInfoProvider

    @Binds
    fun bindAuthTokenSource(impl: SessionManager): AuthTokenSource

    @Binds
    fun bindPairingRepository(impl: DefaultPairingRepository): PairingRepository

    @Binds
    fun bindDevicesRepository(impl: DefaultDevicesRepository): DevicesRepository

    @Binds
    fun bindVideosRepository(impl: DefaultVideosRepository): VideosRepository

    @Binds
    fun bindDownloads(impl: DownloadsManager): Downloads

    @Binds
    fun bindInstalledVideoApps(impl: VideoApps): InstalledVideoApps
}
