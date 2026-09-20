package com.example.musicdlp.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    suspend fun getPlaylistSongs(playlistUrl: String): List<Song> = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(playlistUrl)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        
        return@withContext try {
            val response = YoutubeDL.getInstance().execute(request)
            val playlist = json.decodeFromString<YtDlpPlaylist>(response.out)
            playlist.entries?.map { entry ->
                Song(
                    id = entry.id ?: "",
                    title = entry.title ?: "Unknown",
                    artist = entry.uploader ?: "Unknown",
                    thumbnailUrl = entry.thumbnail ?: "",
                    youtubeUrl = "https://www.youtube.com/watch?v=${entry.id}",
                    isLiked = false,
                    isDisliked = false
                )
            } ?: emptyList()
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

    suspend fun downloadSong(song: Song, downloadDir: File): String? = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(song.youtubeUrl)
        val outputFile = File(downloadDir, "${song.id}.mp3")
        request.addOption("-o", outputFile.absolutePath)
        request.addOption("-x")
        request.addOption("--audio-format", "mp3")
        
        try {
            YoutubeDL.getInstance().execute(request) { progress, eta, line ->
                // Handle progress
            }
            return@withContext outputFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}
