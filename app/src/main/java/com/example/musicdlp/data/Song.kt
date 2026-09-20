package com.example.musicdlp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [Index(value = ["title"], unique = true)]
)
data class Song(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val youtubeUrl: String,
    val isLiked: Boolean,
    val isDisliked: Boolean,
    val filePath: String? = null
)
