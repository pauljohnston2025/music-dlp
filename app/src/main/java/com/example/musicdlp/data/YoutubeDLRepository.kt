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
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*

@Serializable
data class GeminiModelList(
    val models: List<GeminiModelInfo>? = null
)

@Serializable
data class GeminiModelInfo(
    val name: String? = null,
    val version: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val supportedGenerationMethods: List<String>? = null
)

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

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    suspend fun listGeminiModels(): List<String> {
        if (geminiApiKey.isBlank()) return emptyList()
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$geminiApiKey"
            val response: GeminiModelList = client.get(url).body()
            val modelNames = response.models?.mapNotNull { it.name } ?: emptyList()
            Napier.i("Available Gemini Models: $modelNames", tag = "DEBUG_METADATA")
            return modelNames
        } catch (e: Exception) {
            Napier.e("Failed to list Gemini models: ${e.message}", tag = "DEBUG_METADATA")
            return emptyList()
        }
    }

    private suspend fun guessWithGemini(rawTitle: String): Pair<String, String>? {
        if (geminiApiKey.isBlank()) return null
        
        // List models first to debug what's available for this API key
        val availableModels = listGeminiModels()
        
        // Priority order for models we want to use
        val preferredModels = listOf("gemini-1.5-flash", "gemini-pro", "gemini-1.5-pro", "gemini-1.0-pro")
        
        val modelsToTry = if (availableModels.isNotEmpty()) {
            // Filter only models that support generation and are in our preferred list or have gemini in name
            availableModels.map { it.removePrefix("models/") }
                .filter { name -> 
                    preferredModels.contains(name) || name.contains("gemini", ignoreCase = true)
                }
                .sortedBy { name ->
                    val index = preferredModels.indexOf(name)
                    if (index != -1) index else Int.MAX_VALUE
                }
        } else {
            preferredModels
        }

        for (modelName in modelsToTry) {
            try {
                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = geminiApiKey
                )
                val prompt = """
                    Extract the artist name and song title from this YouTube video title: "$rawTitle".
                    Return a JSON object: {"artist": "ARTIST_NAME", "title": "SONG_TITLE"}
                    IMPORTANT: The "title" field must ONLY contain the song name, NOT the artist.
                """.trimIndent()
                
                val response = generativeModel.generateContent(prompt)
                var text = response.text ?: continue
                
                // Extract JSON block if present
                if (text.contains("{")) {
                    text = text.substringAfter("{").substringBeforeLast("}")
                    text = "{$text}"
                }

                val element = json.decodeFromString<JsonElement>(text).jsonObject
                val artist = element["artist"]?.jsonPrimitive?.content?.trim()
                val title = element["title"]?.jsonPrimitive?.content?.trim()
                
                if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                    Napier.i("Gemini guess ($modelName): Artist='$artist', Title='$title'", tag = "DEBUG_METADATA")
                    return artist to title
                }
            } catch (e: Exception) {
                Napier.w("Gemini model '$modelName' failed: ${e.message}", tag = "DEBUG_METADATA")
                // Continue to next model if it's a 404 or support error
            }
        }
        return null
    }

    fun cleanTitleAndArtistRegex(rawTitle: String, uploader: String?): Pair<String, String> {
        Napier.d("cleanTitleAndArtistRegex input: $rawTitle, uploader: $uploader", tag = "DEBUG_METADATA")
        
        // 1. Split by common separators (dash, colon, pipe)
        val parts = rawTitle.split(Regex("""\s*[-–—:|]\s*"""), limit = 2)
        val noiseKeywords = "official|lyric|video|hd|hq|4k|audio|remastered|visualizer|live|concert|full audio|high quality"
        val noisePattern = Regex("""[\(\[\{].*?($noiseKeywords).*?[\)\]\}]""", RegexOption.IGNORE_CASE)

        var artist = uploader ?: "Unknown"
        var title = rawTitle

        if (parts.size == 2) {
            val p0 = parts[0].trim()
            val p1 = parts[1].trim()
            
            // Check which side has the noise tags
            val p0HasNoise = noisePattern.containsMatchIn(p0)
            val p1HasNoise = noisePattern.containsMatchIn(p1)

            if (p0HasNoise && !p1HasNoise) {
                // Pattern: Title [Official] - Artist
                title = p0
                artist = p1
            } else if (!p0HasNoise && p1HasNoise) {
                // Pattern: Artist - Title [Official]
                artist = p0
                title = p1
            } else {
                // Default heuristic based on uploader match or length
                val upLower = uploader?.lowercase() ?: ""
                if (p0.lowercase().contains(upLower) || upLower.contains(p0.lowercase()) || p0.length in 1..45) {
                    artist = p0
                    title = p1
                } else {
                    title = p0
                }
            }
        }

        // 2. Cleanup both
        fun finalCleanup(text: String): String {
            return text
                .replace(noisePattern, "")
                .replace(Regex("""(?i)\b(feat\.?|ft\.?)\b.*"""), "")
                .replace(Regex("""(?i)\s+(official|video|music|audio|lyric|hd|hq|live|concert).*$"""), "")
                .trim()
                .removeSurrounding("\"").removeSurrounding("'")
                .removeSurrounding("“", "”").removeSurrounding("‘", "’")
                .trim()
                .removePrefix("-").removeSuffix("-")
                .trim()
        }

        artist = finalCleanup(artist)
        title = finalCleanup(title)

        // 3. If title still starts with artist name, remove it
        val artistLower = artist.lowercase()
        if (title.lowercase().startsWith(artistLower)) {
            val potentialTitle = title.substring(artist.length).trim()
            if (potentialTitle.startsWith("-") || potentialTitle.startsWith(":") || potentialTitle.startsWith("|")) {
                title = potentialTitle.substring(1).trim()
            }
        }

        // 4. Final fallback artist check
        if (artist.isBlank() || artist.equals("official", true) || artist.equals("video", true)) {
            artist = uploader?.replace(Regex("(?i)vevo|official|channel|music|\\s+-\\s+topic"), "")?.trim() ?: "Unknown"
        }

        Napier.i("Regex Result: Artist='$artist', Title='$title'", tag = "DEBUG_METADATA")
        return artist to title.ifBlank { rawTitle }
    }

    suspend fun cleanTitleAndArtist(rawTitle: String, uploader: String?, isrc: String? = null, youtubeUrl: String? = null): Pair<String, String> {
        Napier.d("cleanTitleAndArtist START - rawTitle: $rawTitle", tag = "DEBUG_METADATA")
        
        fun postClean(text: String) = text.trim().removePrefix("-").removeSuffix("-").trim()
        
        fun ensureTitleOnly(artist: String, title: String): String {
            val a = artist.lowercase().trim()
            var t = title.trim()
            if (t.lowercase().startsWith(a)) {
                t = t.substring(a.length).trim().removePrefix("-").removePrefix(":").removePrefix("|").trim()
            }
            return t
        }

        // 1. Try Gemini first if API key is available
        val geminiGuess = guessWithGemini(rawTitle)
        if (geminiGuess != null) {
            val a = postClean(geminiGuess.first)
            val t = ensureTitleOnly(a, postClean(geminiGuess.second))
            return a to t
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
                    val a = postClean(ytArtist)
                    val t = ensureTitleOnly(a, postClean(ytTrack))
                    return a to t
                }
            } catch (e: Exception) {
                Napier.w("yt-dlp metadata fetch failed: ${e.message}", tag = "DEBUG_METADATA")
            }
        }

        // 3. Fallback to Regex
        val regexResult = cleanTitleAndArtistRegex(rawTitle, uploader)
        val a = postClean(regexResult.first)
        val t = ensureTitleOnly(a, postClean(regexResult.second))
        val finalResult = a to t
        
        Napier.i("Final result: ${finalResult.first} - ${finalResult.second}", tag = "DEBUG_METADATA")
        return finalResult
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
                            id = entry.id ?: "url_${"https://www.youtube.com/watch?v=${entry.id}".hashCode()}",
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
        request.addOption("-f", "bestaudio")
        request.addOption("-g")
        request.addOption("--user-agent", userAgent)
        
        Napier.d("Fetching stream URL for: $youtubeUrl", tag = "DEBUG_METADATA")
        return@withContext try {
            val response = YoutubeDL.getInstance().execute(request)
            val url = response.out.trim().lines().firstOrNull()
            if (url != null && url.startsWith("http")) {
                Napier.d("Stream URL fetched: $url", tag = "DEBUG_METADATA")
                url
            } else {
                Napier.w("Invalid stream URL response: ${response.out}", tag = "DEBUG_METADATA")
                null
            }
        } catch (e: Exception) {
            Napier.e("Failed to fetch stream URL: ${e.message}", tag = "DEBUG_METADATA")
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
        request.addOption("-f", "bestaudio")
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
