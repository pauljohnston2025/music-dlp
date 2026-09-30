package com.example.musicdlp.data

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

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
    @ColumnInfo(defaultValue = "NULL")
    val isMetadataCleaned: Boolean? = false,
    @ColumnInfo(defaultValue = "NULL")
    val likedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val dislikedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val metadataSource: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val alternateYoutubeUrls: String? = null,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL")
    val durationSeconds: Double? = null
) {
    fun getFormattedDuration(): String? {
        val sec = durationSeconds ?: return null
        if (sec <= 0) return null
        val totalSec = sec.toLong()
        val mins = totalSec / 60
        val secs = totalSec % 60
        val hours = mins / 60
        val remMins = mins % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, remMins, secs)
        } else {
            String.format(Locale.US, "%d:%02d", remMins, secs)
        }
    }
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
        if (youtubeUrl.isNotBlank() && !youtubeUrl.contains("watch?v=null") && (youtubeUrl.contains(
                cleanTarget
            ) || cleanTarget.contains(youtubeUrl))
        ) return true

        return containsAlternateId(targetUrlOrId, json)
    }

    fun containsAlternateId(targetUrlOrId: String, json: Json = Json { ignoreUnknownKeys = true }): Boolean {
        val cleanTarget = targetUrlOrId.trim()
        if (cleanTarget.length < 5 || cleanTarget.contains("watch?v=null")) return false

        val alternates = getAlternateVersionsList(json)
        return alternates.any {
            it.youtubeUrl.isNotBlank() && !it.youtubeUrl.contains("watch?v=null") &&
                    (it.youtubeUrl.contains(cleanTarget) || cleanTarget.contains(it.youtubeUrl))
        }
    }
}

@UnstableApi
fun Song.toMediaItem(
    playableUri: String? = null,
    parentId: String? = null,
    isCurrentSong: Boolean = false,
    completionPercentage: Double? = null
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
        if (!parentId.isNullOrBlank()) {
            putString("parentId", parentId)
        }
        putBoolean("CONTENT_STYLE_SUPPORTED", true)
        putInt("CONTENT_STYLE_BROWSABLE_HINT", 2)
        putInt("CONTENT_STYLE_PLAYABLE_HINT", 2)
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        // absolutely no idea why the below seems inverted, the below give s green dot next to the
        // artist name thats playing, making it look like its playing
        // if I invert it all the songs get a green dot and the one thats playing is empty (i guess to indicate you can click it?)
        if (isCurrentSong) {
            putInt("android.media.extra.PLAYBACK_STATUS", 0)
            putBoolean("android.media.extra.IS_PLAYING", false) // this seems to not be needed if PLAYBACK_STATUS is supplied (or takes over if it is supplied, but its still inverted?)
            // this seems to do nothing
            putInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            )
        } else {
            putInt("android.media.extra.PLAYBACK_STATUS", 1)
            putBoolean("android.media.extra.IS_PLAYING", true) // this seems to not be needed if PLAYBACK_STATUS is supplied (or takes over if it is supplied, but its still inverted?)
            // this seems to do nothing
            putInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
            )
            putDouble(
                MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                completionPercentage ?: 0.5
            )
        }
    }

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uriToUse)
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(uriToUse)
                .setExtras(itemExtras)
                .build()
        )
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
