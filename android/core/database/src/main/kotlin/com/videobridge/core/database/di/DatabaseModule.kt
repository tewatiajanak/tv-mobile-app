package com.videobridge.core.database.di

import android.content.Context
import androidx.room.Room
import com.videobridge.core.database.KeyValueDao
import com.videobridge.core.database.VideoBridgeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VideoBridgeDatabase =
        Room.databaseBuilder(context, VideoBridgeDatabase::class.java, VideoBridgeDatabase.NAME).build()

    @Provides
    fun provideKeyValueDao(database: VideoBridgeDatabase): KeyValueDao = database.keyValueDao()
}
