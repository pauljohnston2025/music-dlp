package com.example.musicdlp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey
    val id: String,
    var title: String,
    var artist: String,
    val thumbnailUrl: String,
    val youtubeUrl: String,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val isrc: String? = null,
    val rawTitle: String? = null
)
