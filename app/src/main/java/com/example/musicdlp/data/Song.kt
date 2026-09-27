package com.example.musicdlp.data

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class SwipingMode(val displayName: String) {
    ONLY_NEW("Only New"),
    NEW_AND_LIKED("New and Liked"),
    PLAY_ALL_RECATEGORISE("Play All")
}

@Serializable
data class AlternateVersion(
    val youtubeUrl: String,
    val rawTitle: String? = null,
    val thumbnailUrl: String? = null
)

@Serializable
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val youtubeUrl: String,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val isrc: String? = null,
    val rawTitle: String? = null,
    val isMetadataCleaned: Boolean? = false,
    val likedAt: Long? = null,
    val dislikedAt: Long? = null,
    val metadataSource: String? = null,
    // this really needs to be its own table with relations, or use the same table but have a "parent" link
    // its really slow deserialising the string every time and makes lookups by id even harder because we can't just select where id or songname matches
    // ie would be really nice to do containsYoutubeUrlOrId straight against the db, no hydration
    val alternateYoutubeUrls: String? = null
) {
    fun getAlternateVersionsList(json: Json = Json { ignoreUnknownKeys = true }): List<AlternateVersion> {
        if (alternateYoutubeUrls.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<AlternateVersion>>(alternateYoutubeUrls)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addAlternateVersion(version: AlternateVersion, json: Json = Json { ignoreUnknownKeys = true }): Song {
        val currentList = getAlternateVersionsList(json).toMutableList()
        if (version.youtubeUrl != youtubeUrl && currentList.none { it.youtubeUrl == version.youtubeUrl }) {
            currentList.add(version)
        }
        val encoded = json.encodeToString(currentList)
        return copy(alternateYoutubeUrls = encoded)
    }

    // this really needs to be its own table with relations, or use the same table but have a "parent" link
    // its really slow deserialising the string every time and makes lookups by id even harder because we can't just select where id or songname matches
    // ie would be really nice to do containsYoutubeUrlOrId straight against the db, no hydration
    fun containsYoutubeUrlOrId(targetUrlOrId: String, json: Json = Json { ignoreUnknownKeys = true }): Boolean {
        if (targetUrlOrId.isBlank()) return false
        val cleanTarget = targetUrlOrId.trim()
        if (cleanTarget.length < 5 || cleanTarget.contains("watch?v=null")) return false

        if (id.isNotBlank() && !id.startsWith("url_") && !id.startsWith("search_") && id == cleanTarget) return true
        if (youtubeUrl.isNotBlank() && !youtubeUrl.contains("watch?v=null") && (youtubeUrl.contains(cleanTarget) || cleanTarget.contains(youtubeUrl))) return true

        val alternates = getAlternateVersionsList(json)
        return alternates.any {
            it.youtubeUrl.isNotBlank() && !it.youtubeUrl.contains("watch?v=null") &&
                    (it.youtubeUrl.contains(cleanTarget) || cleanTarget.contains(it.youtubeUrl))
        }
    }
}

fun Song.toMediaItem(
    playableUri: String? = null,
    parentId: String? = null,
    canGoPrevious: Boolean = false,
    canGoNext: Boolean = false,
    swipingMode: String = "ONLY_NEW"
): MediaItem {
    val artworkUri = if (thumbnailUrl.isNotBlank()) {
        Uri.parse(thumbnailUrl)
    } else if (youtubeUrl.contains("watch?v=")) {
        val id = youtubeUrl.substringAfter("watch?v=").substringBefore("&")
        Uri.parse("https://i.ytimg.com/vi/$id/hqdefault.jpg")
    } else null

    val safeId = id.ifBlank { "unknown_${youtubeUrl.hashCode()}" }

    val uriToUse: Uri = when {
        !playableUri.isNullOrBlank() -> if (playableUri.startsWith("/")) Uri.fromFile(File(playableUri)) else Uri.parse(playableUri)
        youtubeUrl.startsWith("content://") || youtubeUrl.startsWith("file://") || youtubeUrl.startsWith("/") -> {
            if (youtubeUrl.startsWith("/")) Uri.fromFile(File(youtubeUrl)) else Uri.parse(youtubeUrl)
        }
        youtubeUrl.startsWith("http://") || youtubeUrl.startsWith("https://") -> Uri.parse(youtubeUrl)
        else -> Uri.parse("http://dummy/$safeId")
    }

    val itemExtras = Bundle().apply {
        putBoolean("isLiked", isLiked)
        putBoolean("isDisliked", isDisliked)
        putBoolean("canGoPrevious", canGoPrevious)
        putBoolean("canGoNext", canGoNext)
        putString("swipingMode", swipingMode)
        putString("songId", id)
        putString("rawTitle", rawTitle)
        putString("artist", artist)
        putString("title", title)
        putString("youtubeUrl", youtubeUrl)
        if (!parentId.isNullOrBlank()) {
            putString("parentId", parentId)
        }
    }

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uriToUse)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.ifBlank { "Unknown Title" })
                .setArtist(artist.ifBlank { "MusicDLP" })
                .setArtworkUri(artworkUri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                .setExtras(itemExtras)
                .build()
        )
        .build()
}
