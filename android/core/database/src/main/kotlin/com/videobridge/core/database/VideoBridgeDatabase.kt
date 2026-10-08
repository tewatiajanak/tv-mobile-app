package com.videobridge.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/** The local source of truth for UI. Keep exportSchema on: migration tests read schemas/. */
@Database(entities = [KeyValueEntity::class], version = 1, exportSchema = true)
abstract class VideoBridgeDatabase : RoomDatabase() {
    abstract fun keyValueDao(): KeyValueDao

    companion object {
        const val NAME = "videobridge.db"
    }
}
