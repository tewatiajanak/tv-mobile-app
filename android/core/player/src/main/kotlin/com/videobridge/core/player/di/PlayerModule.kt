package com.videobridge.core.player.di

import com.videobridge.core.player.DefaultPlayerFactory
import com.videobridge.core.player.PlayerFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface PlayerModule {
    @Binds
    fun bindPlayerFactory(impl: DefaultPlayerFactory): PlayerFactory
}
