package com.example.musicdlp.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import io.github.aakira.napier.Napier

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

@Serializable
data class YtDlpPlaylist(
    val entries: List<YtDlpEntry>? = null
)

@Serializable
data class YtDlpEntry(
    val id: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val creator: String? = null,
    val channel: String? = null,
    val thumbnail: String? = null,
    val duration: Double? = null,
    val isrc: String? = null,
    val artist: String? = null,
    val track: String? = null
)

class YoutubeDLRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    var geminiApiKey: String = ""

    private suspend fun guessWithGemini(rawTitle: String): Pair<String, String>? {
        if (geminiApiKey.isBlank()) return null
        try {
            val generativeModel = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = geminiApiKey,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                }
            )
            val prompt = """
                Extract the artist name and song title from this YouTube video title: "$rawTitle".
                The title often contains noise like "Official Video", "feat.", or the artist's name repeated.
                Return only a JSON object with "artist" and "title" keys.
                Ensure "title" only contains the song name, not the artist.
                Example Input: "Daft Punk - One More Time (Official Music Video)"
                Example Output: {"artist": "Daft Punk", "title": "One More Time"}
            """.trimIndent()
            
            val response = generativeModel.generateContent(prompt)
            val text = response.text ?: return null
            val element = json.decodeFromString<JsonElement>(text).jsonObject
            val artist = element["artist"]?.jsonPrimitive?.content
            val title = element["title"]?.jsonPrimitive?.content
            if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                Napier.i("Gemini guess: $artist - $title", tag = "DEBUG_METADATA")
                return artist to title
            }
        } catch (e: Exception) {
            Napier.w("Gemini guess failed: ${e.message}", tag = "DEBUG_METADATA")
        }
        return null
    }

    fun cleanTitleAndArtistRegex(rawTitle: String, uploader: String?): Pair<String, String> {
        var clean = rawTitle
            .replace(Regex("(?i)\\(official\\s*(music\\s*)?video\\)|\\[official\\s*(music\\s*)?video\\]"), "")
            .replace(Regex("(?i)\\(official\\s*audio\\)|\\[official\\s*audio\\]"), "")
            .replace(Regex("(?i)\\(lyrics?\\s*(video)?\\)|\\[lyrics?\\s*(video)?\\]"), "")
            .replace(Regex("(?i)\\(visualizer\\)|\\[visualizer\\]"), "")
            .replace(Regex("(?i)\\(audio\\)|\\[audio\\]"), "")
            .replace(Regex("(?i)ft\\.?|feat\\.?"), "-")
            .trim()

        clean = clean.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()

        val parts = clean.split(Regex("""\s*[-–—:|]\s*"""), limit = 2)
        var artist = uploader ?: "Unknown"
        var title = clean

        if (parts.size == 2) {
            val p0 = parts[0].trim()
            val p1 = parts[1].trim()
            // If first part is reasonably short, assume it's the artist
            if (p0.length in 1..40 && !p0.contains("http", ignoreCase = true)) {
                artist = p0
                title = p1
            } else {
                // Otherwise assume p0 is the title and p1 might be extra info
                title = p0
            }
        }

        artist = artist.replace(Regex("(?i)vevo|official|channel|music"), "").trim()
        if (artist.isBlank()) {
            artist = uploader?.replace(Regex("(?i)vevo|official|channel|music"), "")?.trim() ?: "Unknown"
        }

        return artist.ifBlank { "Unknown" } to title.ifBlank { rawTitle }
    }

    suspend fun cleanTitleAndArtist(rawTitle: String, uploader: String?, isrc: String? = null, youtubeUrl: String? = null): Pair<String, String> {
        Napier.d("cleanTitleAndArtist START - rawTitle: $rawTitle", tag = "DEBUG_METADATA")
        
        // 1. Try Gemini first if API key is available
        val geminiGuess = guessWithGemini(rawTitle)
        if (geminiGuess != null) {
            return geminiGuess
        }

        // 2. Try fetching high-quality metadata from YouTube
        if (!youtubeUrl.isNullOrBlank() && !youtubeUrl.startsWith("content://") && !youtubeUrl.startsWith("/")) {
            try {
                val request = YoutubeDLRequest(youtubeUrl)
                request.addOption("--dump-json")
                val response = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request)
                }
                val entry = json.decodeFromString<YtDlpEntry>(response.out)
                val ytArtist = (entry.artist ?: entry.creator ?: entry.channel ?: entry.uploader)
                    ?.replace(Regex("(?i)vevo|official|channel|music"), "")?.trim()
                val ytTrack = entry.track
                if (!ytArtist.isNullOrBlank() && !ytTrack.isNullOrBlank()) {
                    return ytArtist to ytTrack.trim()
                }
            } catch (e: Exception) {
                Napier.w("yt-dlp metadata fetch failed: ${e.message}", tag = "DEBUG_METADATA")
            }
        }

        // 3. Fallback to Regex
        val regexResult = cleanTitleAndArtistRegex(rawTitle, uploader)
        Napier.i("Regex result: ${regexResult.first} - ${regexResult.second}", tag = "DEBUG_METADATA")
        return regexResult
    }

    suspend fun searchSongsOrPlaylists(query: String): List<String> = withContext(Dispatchers.IO) {
        val searchUrl = "ytsearch15:$query"
        Napier.d("Searching with query: $query (URL: $searchUrl)", tag = "DEBUG_METADATA")
        val request = YoutubeDLRequest(searchUrl)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        try {
            val response = YoutubeDL.getInstance().execute(request)
            val jsonString = response.out
            val trimmed = jsonString.trim()
            val urls = mutableListOf<String>()
            
            Napier.d("Search response length: ${jsonString.length}", tag = "DEBUG_METADATA")

            if (trimmed.startsWith("{")) {
                val jsonNode = json.decodeFromString<JsonElement>(trimmed).jsonObject
                
                // If it's a search result with entries
                if (jsonNode.containsKey("entries")) {
                    val playlist = json.decodeFromString<YtDlpPlaylist>(trimmed)
                    playlist.entries?.forEach { entry ->
                        val id = entry.id
                        if (!id.isNullOrBlank()) {
                            if (id.startsWith("PL") || id.startsWith("RD") || id.startsWith("OLAK")) {
                                urls.add("https://www.youtube.com/playlist?list=$id")
                            } else {
                                urls.add("https://www.youtube.com/watch?v=$id")
                            }
                        }
                    }
                } else {
                    // Single result
                    val id = jsonNode["id"]?.jsonPrimitive?.content
                    if (!id.isNullOrBlank()) {
                        urls.add("https://www.youtube.com/watch?v=$id")
                    }
                }
            } else {
                Napier.w("Search response does not look like JSON: ${trimmed.take(100)}", tag = "DEBUG_METADATA")
                trimmed.lines().forEach { line ->
                    if (line.contains("watch?v=")) {
                        val videoId = line.substringAfter("watch?v=").substringBefore("&").trim()
                        if (videoId.isNotBlank()) {
                            urls.add("https://www.youtube.com/watch?v=$videoId")
                        }
                    }
                }
            }
            Napier.i("Search returned ${urls.size} results", tag = "DEBUG_METADATA")
            urls
        } catch (e: Exception) {
            Napier.e("Search failed: ${e.message}", tag = "DEBUG_METADATA")
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getPlaylistSongs(url: String): List<Song> = withContext(Dispatchers.IO) {
        Napier.d("Fetching songs from URL: $url", tag = "DEBUG_METADATA")
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        
        return@withContext try {
            val response = YoutubeDL.getInstance().execute(request)
            val jsonString = response.out
            val trimmed = jsonString.trim()
            if (trimmed.startsWith("{")) {
                val jsonNode = json.decodeFromString<JsonElement>(trimmed).jsonObject
                val rawEntries = if (jsonNode.containsKey("entries")) {
                    json.decodeFromString<YtDlpPlaylist>(trimmed).entries ?: emptyList()
                } else {
                    listOf(json.decodeFromString<YtDlpEntry>(trimmed))
                }
                
                Napier.d("Found ${rawEntries.size} raw entries", tag = "DEBUG_METADATA")
                val songs = mutableListOf<Song>()
                for (entry in rawEntries) {
                    val rawTitle = entry.title ?: ""
                    if (rawTitle.contains("live", ignoreCase = true) || rawTitle.contains("concert", ignoreCase = true) || rawTitle.contains("festival", ignoreCase = true)) {
                        continue
                    }
                    
                    // Do NOT run heavy MusicBrainz lookup during playlist load; use raw/uploader info initially so load is instantaneous.
                    // MusicBrainz lookup / cleaning will happen lazily when playing/buffering.
                    val artist = entry.uploader?.replace(Regex("(?i)vevo|official|channel|music"), "")?.trim() ?: "Unknown"
                    val thumbnail = if (!entry.thumbnail.isNullOrBlank()) {
                        entry.thumbnail
                    } else if (!entry.id.isNullOrBlank()) {
                        "https://i.ytimg.com/vi/${entry.id}/hqdefault.jpg"
                    } else {
                        ""
                    }
                    
                    songs.add(
                        Song(
                            id = entry.id ?: System.currentTimeMillis().toString(),
                            title = rawTitle,
                            artist = artist.ifBlank { "Unknown" },
                            thumbnailUrl = thumbnail,
                            youtubeUrl = "https://www.youtube.com/watch?v=${entry.id}",
                            isLiked = false,
                            isDisliked = false,
                            isrc = entry.isrc,
                            rawTitle = rawTitle
                        )
                    )
                }
                Napier.i("Loaded ${songs.size} songs from URL", tag = "DEBUG_METADATA")
                songs
            } else {
                Napier.w("Playlist response is not JSON", tag = "DEBUG_METADATA")
                emptyList()
            }
        } catch (e: Exception) {
            Napier.e("Failed to get songs from URL: ${e.message}", tag = "DEBUG_METADATA")
            e.printStackTrace()
            emptyList()
        }
    }

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    suspend fun getStreamUrl(youtubeUrl: String): String? = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(youtubeUrl)
        // Prefer m4a for better ExoPlayer compatibility, fallback to any best audio
        request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
        request.addOption("-g")
        request.addOption("--no-playlist")
        request.addOption("--user-agent", userAgent)
        request.addOption("--force-ipv4") // Sometimes helps with signature issues
        
        Napier.d("Fetching stream URL for: $youtubeUrl", tag = "DEBUG_METADATA")
        return@withContext try {
            val response = YoutubeDL.getInstance().execute(request)
            var url = response.out.trim().lines().firstOrNull()
            
            // If m4a preference failed or returned nothing, retry with a broader filter
            if (url.isNullOrBlank()) {
                Napier.w("m4a stream fetch failed, retrying with broad bestaudio", tag = "DEBUG_METADATA")
                val retryRequest = YoutubeDLRequest(youtubeUrl)
                retryRequest.addOption("-f", "bestaudio")
                retryRequest.addOption("-g")
                retryRequest.addOption("--no-playlist")
                retryRequest.addOption("--user-agent", userAgent)
                val retryResponse = YoutubeDL.getInstance().execute(retryRequest)
                url = retryResponse.out.trim().lines().firstOrNull()
            }

            if (url != null) {
                Napier.d("Stream URL fetched: $url", tag = "DEBUG_METADATA")
            } else {
                Napier.w("No stream URL in response. Output: ${response.out}", tag = "DEBUG_METADATA")
            }
            url
        } catch (e: Exception) {
            Napier.e("Failed to fetch stream URL: ${e.message}", tag = "DEBUG_METADATA")
            e.printStackTrace()
            null
        }
    }

    suspend fun downloadSong(
        song: Song, 
        downloadDir: File, 
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(song.youtubeUrl)
        request.addOption("-o", File(downloadDir, "%(id)s.%(ext)s").absolutePath)
        request.addOption("-x")
        request.addOption("--audio-format", "mp3")
        request.addOption("--user-agent", userAgent)
        
        YoutubeDL.getInstance().execute(request) { progress, _, _ ->
            onProgress(progress / 100f)
        }
        val downloaded = downloadDir.listFiles()?.firstOrNull { 
            it.name.startsWith(song.id) && (it.extension == "mp3" || it.extension == "m4a" || it.extension == "webm")
        }
        if (downloaded != null && downloaded.exists()) {
            return@withContext downloaded.absolutePath
        }
        throw IllegalStateException("Downloaded file not found for song: ${song.title}")
    }
}
