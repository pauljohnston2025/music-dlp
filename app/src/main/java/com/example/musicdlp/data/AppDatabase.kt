package com.example.musicdlp.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Song::class],
    version = 2,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
}
