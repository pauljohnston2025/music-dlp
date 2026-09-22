package com.example.musicdlp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AlternateVersion(
    val youtubeUrl: String,
    val rawTitle: String? = null,
    val thumbnailUrl: String? = null
)

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

    fun containsYoutubeUrlOrId(targetUrlOrId: String, json: Json = Json { ignoreUnknownKeys = true }): Boolean {
        if (targetUrlOrId.isBlank()) return false
        if (id == targetUrlOrId || youtubeUrl.contains(targetUrlOrId) || targetUrlOrId.contains(id)) return true
        val alternates = getAlternateVersionsList(json)
        return alternates.any { it.youtubeUrl.contains(targetUrlOrId) || targetUrlOrId.contains(it.youtubeUrl) }
    }
}
