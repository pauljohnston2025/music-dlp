package com.example.musicdlp.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

@Serializable
data class YtDlpPlaylist(
    val entries: List<YtDlpEntry>? = null
)

@Serializable
data class YtDlpEntry(
    val id: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val thumbnail: String? = null
)

class YoutubeDLRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun cleanTitle(rawTitle: String, uploader: String?): Pair<String, String> {
        // Remove common suffixes like (Official Video), [Lyrics], etc.
        val cleaned = rawTitle.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
        
        // Try to split by " - "
        val parts = cleaned.split(" - ", limit = 2)
        return if (parts.size == 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            (uploader ?: "Unknown") to cleaned
        }
    }

    suspend fun getPlaylistSongs(url: String): List<Song> = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        
        return@withContext try {
            val response = YoutubeDL.getInstance().execute(request)
            val jsonString = response.out
            if (jsonString.trim().startsWith("{")) {
                val jsonElement = json.parseToJsonElement(jsonString)
                if (jsonElement is kotlinx.serialization.json.JsonObject && jsonElement.containsKey("entries")) {
                    // It's a playlist
                    val playlist = json.decodeFromString<YtDlpPlaylist>(jsonString)
                    playlist.entries?.map { entry ->
                        val (artist, title) = cleanTitle(entry.title ?: "Unknown", entry.uploader)
                        Song(
                            id = entry.id ?: "",
                            title = title,
                            artist = artist,
                            thumbnailUrl = entry.thumbnail ?: "",
                            youtubeUrl = "https://www.youtube.com/watch?v=${entry.id}",
                            isLiked = false,
                            isDisliked = false
                        )
                    } ?: emptyList()
                } else {
                    // It's a single video
                    val entry = json.decodeFromString<YtDlpEntry>(jsonString)
                    val (artist, title) = cleanTitle(entry.title ?: "Unknown", entry.uploader)
                    listOf(
                        Song(
                            id = entry.id ?: "",
                            title = title,
                            artist = artist,
                            thumbnailUrl = entry.thumbnail ?: "",
                            youtubeUrl = "https://www.youtube.com/watch?v=${entry.id}",
                            isLiked = false,
                            isDisliked = false
                        )
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getStreamUrl(youtubeUrl: String): String? = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(youtubeUrl)
        request.addOption("-f", "bestaudio")
        request.addOption("-g")
        
        return@withContext try {
            val response = YoutubeDL.getInstance().execute(request)
            response.out.trim()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun downloadSong(
        song: Song, 
        downloadDir: File, 
        onProgress: (Float) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(song.youtubeUrl)
        val outputFile = File(downloadDir, "${song.id}.mp3")
        request.addOption("-o", outputFile.absolutePath)
        request.addOption("-x")
        request.addOption("--audio-format", "mp3")
        
        try {
            YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                onProgress(progress / 100f)
            }
            return@withContext outputFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}
