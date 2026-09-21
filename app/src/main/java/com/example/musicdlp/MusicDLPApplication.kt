package com.example.musicdlp

import android.app.Application
import com.example.musicdlp.data.AppDatabase
import androidx.room.Room
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.ffmpeg.FFmpeg
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier

class MusicDLPApplication : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()

        Napier.base(DebugAntilog())
        
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "musicdlp-database"
        ).build()

        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            e.printStackTrace()
        }
    }
}
