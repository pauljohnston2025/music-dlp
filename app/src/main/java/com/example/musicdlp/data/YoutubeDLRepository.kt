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
import java.util.UUID

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

data class CleanMetadataResult(
    val artist: String,
    val title: String,
    val source: String,
    val score: Int = 0,
    val recoveredRawTitle: String? = null
)

data class GeminiGuessResult(
    val artist: String,
    val title: String,
    val modelUsed: String,
    val attemptsCount: Int
)

@Serializable
data class PlaylistSearchResult(
    val id: String,
    val title: String,
    val uploader: String,
    val songCount: Int? = null,
    val thumbnailUrl: String,
    val playlistUrl: String
)

@Serializable
data class YtDlpPlaylist(
    val entries: List<YtDlpEntry>? = null
)

@Serializable
data class YtDlpEntry(
    val id: String? = null,
    val url: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    val creator: String? = null,
    val channel: String? = null,
    val thumbnail: String? = null,
    val duration: Double? = null,
    val isrc: String? = null,
    val artist: String? = null,
    val track: String? = null,
    @SerialName("_type") val type: String? = null,
    @SerialName("playlist_count") val playlistCount: Int? = null,
    @SerialName("entry_count") val entryCount: Int? = null
)

class YoutubeDLRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("musicdlp_prefs", Context.MODE_PRIVATE)

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("gemini_api_key", value).apply()
        }

    var lastStreamError: String? = null
        private set

    var lastWorkingModel: String?
        get() = prefs.getString("last_working_gemini_model", null)
        private set(value) {
            if (value == null) {
                prefs.edit().remove("last_working_gemini_model").apply()
            } else {
                prefs.edit().putString("last_working_gemini_model", value).apply()
            }
        }

    private val quotaExhaustedModels = mutableMapOf<String, Long>()
    private val invalidModels = mutableSetOf<String>()

    private var cachedModelInfos: List<GeminiModelInfo>? = null
    private var lastModelFetchTime: Long = 0L

    private val PREFERRED_MODELS_ORDER = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash",
        "gemini-flash-latest",
        "gemini-flash-lite-latest",
        "gemini-2.5-flash-lite",
        "gemini-3.1-pro-preview",
        "gemini-pro-latest",
        "gemini-2.5-pro",
        "gemini-1.5-flash",
        "gemini-1.5-pro"
    )

    private val UNSUPPORTED_KEYWORDS = listOf(
        "-tts", "-image", "embedding", "veo", "lyria", "aqa",
        "computer-use", "robotics", "transcribe", "live", "customtools",
        "nano-banana", "antigravity", "deep-research"
    )

    fun clearModelCache() {
        quotaExhaustedModels.clear()
        invalidModels.clear()
        cachedModelInfos = null
        lastModelFetchTime = 0L
    }

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

    suspend fun getAvailableCandidateModels(): List<String> {
        val now = System.currentTimeMillis()
        quotaExhaustedModels.entries.removeIf { it.value < now }

        var modelInfos = cachedModelInfos
        if (modelInfos == null || (now - lastModelFetchTime) > 10 * 60 * 1000L) {
            if (geminiApiKey.isNotBlank()) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$geminiApiKey"
                    val response: GeminiModelList = client.get(url).body()
                    modelInfos = response.models ?: emptyList()
                    cachedModelInfos = modelInfos
                    lastModelFetchTime = now
                } catch (e: Exception) {
                    Napier.e("Failed to list Gemini models: ${e.message}", tag = "DEBUG_METADATA")
                }
            }
        }

        val rawNames = modelInfos?.mapNotNull { info ->
            val name = info.name?.removePrefix("models/") ?: return@mapNotNull null
            val methods = info.supportedGenerationMethods
            if (methods != null && !methods.contains("generateContent")) {
                return@mapNotNull null
            }
            name
        } ?: emptyList()

        val candidateBase = if (rawNames.isNotEmpty()) rawNames else PREFERRED_MODELS_ORDER

        val filtered = candidateBase.filter { name ->
            !invalidModels.contains(name) &&
            !quotaExhaustedModels.containsKey(name) &&
            UNSUPPORTED_KEYWORDS.none { kw -> name.contains(kw, ignoreCase = true) } &&
            (name.contains("gemini", ignoreCase = true) || name.contains("gemma", ignoreCase = true))
        }.toMutableList()

        val lastWorked = lastWorkingModel
        if (!lastWorked.isNullOrBlank() &&
            !invalidModels.contains(lastWorked) &&
            !quotaExhaustedModels.containsKey(lastWorked) &&
            !filtered.contains(lastWorked)
        ) {
            filtered.add(0, lastWorked)
        }

        return filtered.sortedWith(Comparator { m1, m2 ->
            if (m1 == lastWorked) return@Comparator -1
            if (m2 == lastWorked) return@Comparator 1

            val idx1 = PREFERRED_MODELS_ORDER.indexOf(m1).let { if (it == -1) Int.MAX_VALUE else it }
            val idx2 = PREFERRED_MODELS_ORDER.indexOf(m2).let { if (it == -1) Int.MAX_VALUE else it }

            if (idx1 != idx2) idx1.compareTo(idx2)
            else m1.compareTo(m2)
        })
    }

    private fun isQuotaExhaustedError(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("quota") ||
               lower.contains("rate-limit") ||
               lower.contains("rate_limit") ||
               lower.contains("resource_exhausted") ||
               lower.contains("429") ||
               lower.contains("limit: 0") ||
               lower.contains("exceeded your current quota")
    }

    private fun isInvalidModelError(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("404") ||
               lower.contains("not_found") ||
               lower.contains("no longer available") ||
               lower.contains("400") ||
               lower.contains("invalid_argument") ||
               lower.contains("modalities")
    }

    private suspend fun guessWithGemini(rawTitle: String): GeminiGuessResult? {
        if (geminiApiKey.isBlank()) return null

        val candidates = getAvailableCandidateModels()
        if (candidates.isEmpty()) {
            Napier.w("No valid Gemini candidate models available", tag = "DEBUG_METADATA")
            return null
        }

        var attemptsCount = 0

        for (modelName in candidates) {
            attemptsCount++
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

                if (text.contains("{")) {
                    text = text.substringAfter("{").substringBeforeLast("}")
                    text = "{$text}"
                }

                val element = json.decodeFromString<JsonElement>(text).jsonObject
                val artist = element["artist"]?.jsonPrimitive?.content?.trim()
                val title = element["title"]?.jsonPrimitive?.content?.trim()

                if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                    Napier.i("Gemini guess ($modelName, attempt $attemptsCount): Artist='$artist', Title='$title'", tag = "DEBUG_METADATA")
                    lastWorkingModel = modelName
                    return GeminiGuessResult(artist, title, modelName, attemptsCount)
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                Napier.w("Gemini model '$modelName' failed on attempt $attemptsCount: $msg", tag = "DEBUG_METADATA")

                if (isQuotaExhaustedError(msg)) {
                    quotaExhaustedModels[modelName] = System.currentTimeMillis() + 60 * 60 * 1000L
                    Napier.w("Marked Gemini model '$modelName' as quota exhausted", tag = "DEBUG_METADATA")
                } else if (isInvalidModelError(msg)) {
                    invalidModels.add(modelName)
                    Napier.w("Marked Gemini model '$modelName' as invalid/deprecated", tag = "DEBUG_METADATA")
                }

                if (modelName == lastWorkingModel) {
                    lastWorkingModel = null
                }
            }
        }
        return null
    }

    fun extractYoutubeIdAndCleanName(rawName: String): Pair<String?, String> {
        val ytIdRegex = Regex("""[\s\-_\[(]+([a-zA-Z0-9_\-]{10,12})[\])]?$""")
        val match = ytIdRegex.find(rawName.trim())
        return if (match != null) {
            val ytId = match.groupValues[1]
            val cleanedName = rawName.substring(0, match.range.first).trim()
            Pair(ytId, cleanedName.ifBlank { rawName })
        } else {
            Pair(null, rawName)
        }
    }

    fun cleanTitleAndArtistRegex(rawTitle: String, uploader: String?): Pair<String, String> {
        val (extractedId, titleWithoutId) = extractYoutubeIdAndCleanName(rawTitle)
        val workingTitle = titleWithoutId.ifBlank { rawTitle }
        Napier.d("cleanTitleAndArtistRegex input: $rawTitle (workingTitle: $workingTitle, extractedId: $extractedId), uploader: $uploader", tag = "DEBUG_METADATA")
        
        // 1. Split by common separators (dash, colon, pipe)
        val parts = workingTitle.split(Regex("""\s*[-–—:|]\s*"""), limit = 2)
        val noiseKeywords = "official|lyric|video|hd|hq|4k|audio|remastered|visualizer|live|concert|full audio|high quality"
        val noisePattern = Regex("""[\(\[\{].*?($noiseKeywords).*?[\)\]\}]""", RegexOption.IGNORE_CASE)

        var artist = uploader ?: "Unknown"
        var title = workingTitle

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

    private var lastMusicBrainzRequestTime = 0L

    suspend fun verifyWithMusicBrainz(artist: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val waitTime = 1000L - (now - lastMusicBrainzRequestTime)
        if (waitTime > 0) delay(waitTime)
        
        lastMusicBrainzRequestTime = System.currentTimeMillis()
        
        try {
            val query = URLEncoder.encode("artist:\"$artist\" AND recording:\"$title\"", "UTF-8")
            val url = "https://musicbrainz.org/ws/2/recording?query=$query&fmt=json"
            val response: String = client.get(url) {
                header("User-Agent", "MusicDLP/1.0 ( musicdlp@example.com )")
            }.body()
            
            val jsonResponse = json.parseToJsonElement(response).jsonObject
            val count = jsonResponse["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            return@withContext count > 0
        } catch (e: Exception) {
            Napier.w("MusicBrainz verification failed: ${e.message}", tag = "DEBUG_METADATA")
            false
        }
    }

    private fun scoreMetadata(artist: String, title: String): Int {
        var score = 100
        val noiseWords = listOf("official", "video", "lyrics", "hd", "hq", "audio", "remastered", "4k")
        
        noiseWords.forEach { word ->
            if (artist.contains(word, ignoreCase = true)) score -= 20
            if (title.contains(word, ignoreCase = true)) score -= 20
        }
        
        if (artist == "Unknown" || artist.isBlank()) score -= 50
        if (title.isBlank()) score -= 50
        
        if (artist.length > 50) score -= 30
        if (title.length > 80) score -= 30
        
        return score.coerceAtLeast(0)
    }

    suspend fun cleanTitleAndArtist(rawTitle: String, uploader: String?, isrc: String? = null, youtubeUrl: String? = null): CleanMetadataResult {
        Napier.d("cleanTitleAndArtist START - rawTitle: $rawTitle, uploader: $uploader, url: $youtubeUrl", tag = "DEBUG_METADATA")

        var effectiveRawTitle = rawTitle.trim()
        var effectiveUploader = uploader?.trim()
        var recoveredRawTitle: String? = null

        fun isPlaceholderTitle(t: String) = t.isBlank() ||
                t.equals("Loading...", ignoreCase = true) ||
                t.equals("Loading", ignoreCase = true) ||
                t.equals("Please wait", ignoreCase = true) ||
                t.equals("Preview", ignoreCase = true) ||
                t.equals("Unknown", ignoreCase = true) ||
                t.equals("Unknown Title", ignoreCase = true)

        fun isPlaceholderUploader(u: String?) = u.isNullOrBlank() ||
                u.equals("Please wait", ignoreCase = true) ||
                u.equals("Loading...", ignoreCase = true) ||
                u.equals("Unknown", ignoreCase = true) ||
                u.equals("Unknown Artist", ignoreCase = true)

        if ((isPlaceholderTitle(effectiveRawTitle) || isPlaceholderUploader(effectiveUploader)) && !youtubeUrl.isNullOrBlank() && youtubeUrl.startsWith("http")) {
            Napier.i("Placeholder/invalid metadata detected ($effectiveRawTitle / $effectiveUploader), fetching from YouTube...", tag = "DEBUG_METADATA")
            try {
                val fetched = getPlaylistSongs(youtubeUrl).firstOrNull()
                if (fetched != null) {
                    if (isPlaceholderTitle(effectiveRawTitle) && !fetched.rawTitle.isNullOrBlank() && !isPlaceholderTitle(fetched.rawTitle)) {
                        effectiveRawTitle = fetched.rawTitle
                        recoveredRawTitle = fetched.rawTitle
                    } else if (isPlaceholderTitle(effectiveRawTitle) && fetched.title.isNotBlank() && !isPlaceholderTitle(fetched.title)) {
                        effectiveRawTitle = fetched.title
                        recoveredRawTitle = fetched.title
                    }
                    if (isPlaceholderUploader(effectiveUploader) && fetched.artist.isNotBlank() && !isPlaceholderUploader(fetched.artist)) {
                        effectiveUploader = fetched.artist
                    }
                    Napier.i("Recovered metadata from YouTube: rawTitle='$effectiveRawTitle', uploader='$effectiveUploader'", tag = "DEBUG_METADATA")
                }
            } catch (e: Exception) {
                Napier.w("Failed to recover metadata from YouTube for $youtubeUrl: ${e.message}", tag = "DEBUG_METADATA")
            }
        }
        
        fun postClean(text: String) = text.trim().removePrefix("-").removeSuffix("-").trim()
        
        fun ensureTitleOnly(artist: String, title: String): String {
            val a = artist.lowercase().trim()
            var t = title.trim()
            if (t.lowercase().startsWith(a)) {
                t = t.substring(a.length).trim().removePrefix("-").removePrefix(":").removePrefix("|").trim()
            }
            return t
        }

        // 1. Try Regex first
        val regexResult = cleanTitleAndArtistRegex(effectiveRawTitle, effectiveUploader)
        val regA = postClean(regexResult.first)
        val regT = ensureTitleOnly(regA, postClean(regexResult.second))
        val regScore = scoreMetadata(regA, regT)

        if (regScore >= 80) {
            // Verify with MusicBrainz if score is high enough to be worth it but not certain
            if (verifyWithMusicBrainz(regA, regT)) {
                return CleanMetadataResult(regA, regT, "regex + musicbrainz (verified)", regScore + 20, recoveredRawTitle)
            }
        }

        // 2. Try Gemini if score is low or MB failed
        if (geminiApiKey.isNotBlank()) {
            val geminiGuess = guessWithGemini(effectiveRawTitle)
            if (geminiGuess != null) {
                val a = postClean(geminiGuess.artist)
                val t = ensureTitleOnly(a, postClean(geminiGuess.title))
                val source = "gemini: ${geminiGuess.modelUsed}"
                return CleanMetadataResult(a, t, source, 100, recoveredRawTitle)
            }
        }

        // 3. Fallback to Regex result
        return CleanMetadataResult(regA, regT, "regex (guess)", regScore, recoveredRawTitle)
    }

    suspend fun searchPlaylists(query: String): List<PlaylistSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || cleanQuery.startsWith("http://") || cleanQuery.startsWith("https://")) {
            return@withContext emptyList()
        }

        val searchUrl = "https://www.youtube.com/results?search_query=${URLEncoder.encode(cleanQuery, "UTF-8")}&sp=EgIQAw%3D%3D"
        Napier.d("Searching playlists with query: $cleanQuery (URL: $searchUrl)", tag = "DEBUG_METADATA")

        fun parsePlaylistJson(jsonString: String): List<PlaylistSearchResult> {
            if (!jsonString.startsWith("{")) return emptyList()
            val jsonNode = json.decodeFromString<JsonElement>(jsonString).jsonObject
            val rawEntries = if (jsonNode.containsKey("entries")) {
                json.decodeFromString<YtDlpPlaylist>(jsonString).entries ?: emptyList()
            } else {
                listOf(json.decodeFromString<YtDlpEntry>(jsonString))
            }

            val results = mutableListOf<PlaylistSearchResult>()
            for (entry in rawEntries) {
                val rawTitle = entry.title ?: continue
                val rawId = entry.id ?: entry.url ?: continue
                if (rawTitle.isBlank() || rawId.isBlank()) continue

                val uploader = entry.channel ?: entry.uploader ?: entry.creator ?: "YouTube"
                val count = entry.playlistCount ?: entry.entryCount
                val playlistId = when {
                    rawId.contains("list=") -> rawId.substringAfter("list=").substringBefore("&").substringBefore("?")
                    rawId.startsWith("/playlist?list=") -> rawId.substringAfter("/playlist?list=")
                    else -> rawId
                }
                val thumbnail = if (!entry.thumbnail.isNullOrBlank()) {
                    entry.thumbnail
                } else if (playlistId.isNotBlank() && !playlistId.startsWith("http")) {
                    "https://i.ytimg.com/vi/$playlistId/hqdefault.jpg"
                } else {
                    ""
                }
                val playlistUrl = if (playlistId.startsWith("http")) playlistId else "https://www.youtube.com/playlist?list=$playlistId"

                results.add(
                    PlaylistSearchResult(
                        id = playlistId,
                        title = rawTitle,
                        uploader = uploader,
                        songCount = count,
                        thumbnailUrl = thumbnail,
                        playlistUrl = playlistUrl
                    )
                )
            }
            return results
        }

        try {
            val request = YoutubeDLRequest(searchUrl)
            request.addOption("--flat-playlist")
            request.addOption("--dump-single-json")
            val response = YoutubeDL.getInstance().execute(request)
            var results = parsePlaylistJson(response.out.trim())

            if (results.isEmpty()) {
                Napier.d("Web search returned 0 playlists, trying ytsearch10 fallback...", tag = "DEBUG_METADATA")
                val fallbackReq = YoutubeDLRequest("ytsearch10:$cleanQuery playlist")
                fallbackReq.addOption("--flat-playlist")
                fallbackReq.addOption("--dump-single-json")
                val fallbackResp = YoutubeDL.getInstance().execute(fallbackReq)
                results = parsePlaylistJson(fallbackResp.out.trim())
            }

            Napier.i("Playlist search returned ${results.size} playlists", tag = "DEBUG_METADATA")
            results
        } catch (e: Exception) {
            Napier.e("Playlist search failed: ${e.message}", tag = "DEBUG_METADATA")
            emptyList()
        }
    }

    suspend fun searchSongsOrPlaylists(query: String): List<Song> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val isUrl = cleanQuery.startsWith("http://") || cleanQuery.startsWith("https://") ||
                cleanQuery.contains("youtube.com") || cleanQuery.contains("youtu.be")

        val searchUrl = if (isUrl) {
            cleanQuery
        } else {
            "ytmusicsearch20:$cleanQuery"
        }

        Napier.d("Searching with query: $cleanQuery (URL: $searchUrl)", tag = "DEBUG_METADATA")
        val request = YoutubeDLRequest(searchUrl)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")

        try {
            val response = YoutubeDL.getInstance().execute(request)
            var jsonString = response.out.trim()

            // Fallback to ytsearch if ytmusicsearch returned empty/non-JSON response
            if (!isUrl && (jsonString.isBlank() || !jsonString.startsWith("{"))) {
                Napier.d("ytmusicsearch returned empty/non-JSON, falling back to ytsearch20", tag = "DEBUG_METADATA")
                val fallbackUrl = "ytsearch20:$cleanQuery music"
                val fallbackReq = YoutubeDLRequest(fallbackUrl)
                fallbackReq.addOption("--flat-playlist")
                fallbackReq.addOption("--dump-single-json")
                try {
                    jsonString = YoutubeDL.getInstance().execute(fallbackReq).out.trim()
                } catch (e: Exception) {
                    Napier.w("Fallback ytsearch failed: ${e.message}", tag = "DEBUG_METADATA")
                }
            }

            val songs = mutableListOf<Song>()
            val nonMusicKeywords = setOf(
                "gameplay", "walkthrough", "playthrough", "full episode",
                "unboxing", "tutorial", "reaction", "review", "vlog",
                "news", "interview", "documentary", "speedrun", "trailer", "asmr",
                "modding", "let's play", "lets play", "guide", "comparison"
            )

            if (jsonString.startsWith("{")) {
                val jsonNode = json.decodeFromString<JsonElement>(jsonString).jsonObject

                val rawEntries = if (jsonNode.containsKey("entries")) {
                    json.decodeFromString<YtDlpPlaylist>(jsonString).entries ?: emptyList()
                } else {
                    listOf(json.decodeFromString<YtDlpEntry>(jsonString))
                }

                for ((index, entry) in rawEntries.withIndex()) {
                    val rawTitle = entry.title ?: ""
                    val titleLower = rawTitle.lowercase()

                    // Filter out non-music keyword titles unless explicitly searched
                    val containsNonMusicKeyword = nonMusicKeywords.any { kw ->
                        titleLower.contains(kw) && !cleanQuery.lowercase().contains(kw)
                    }
                    if (containsNonMusicKeyword) {
                        continue
                    }

                    // Filter out long videos > 20 mins (1200s) unless explicitly requested
                    val duration = entry.duration ?: 0.0
                    if (duration > 1200.0 && !cleanQuery.lowercase().contains("album") &&
                        !cleanQuery.lowercase().contains("mix") && !cleanQuery.lowercase().contains("podcast")) {
                        continue
                    }

                    val titleToUse = entry.track?.ifBlank { null } ?: rawTitle
                    val artistToUse = entry.artist?.ifBlank { null }
                        ?: entry.creator?.ifBlank { null }
                        ?: entry.uploader?.replace(Regex("(?i)vevo|official|channel|music|- topic|topic"), "")?.trim()
                        ?: "Unknown"

                    val thumbnail = if (!entry.thumbnail.isNullOrBlank()) {
                        entry.thumbnail
                    } else if (!entry.id.isNullOrBlank()) {
                        "https://i.ytimg.com/vi/${entry.id}/hqdefault.jpg"
                    } else {
                        ""
                    }
                    val rawId = entry.id
                    val isPlaylistId = rawId?.startsWith("PL") == true || rawId?.startsWith("RD") == true || rawId?.startsWith("OLAK") == true
                    val videoOrPlaylistUrl = if (isPlaylistId) {
                        "https://www.youtube.com/playlist?list=$rawId"
                    } else if (!rawId.isNullOrBlank()) {
                        "https://www.youtube.com/watch?v=$rawId"
                    } else {
                        ""
                    }
                    val songId = if (!rawId.isNullOrBlank() && !isPlaylistId) {
                        rawId
                    } else {
                        "search_${cleanQuery.hashCode()}_$index"
                    }

                    if (videoOrPlaylistUrl.isNotBlank() || songId.isNotBlank()) {
                        songs.add(
                            Song(
                                id = songId,
                                title = titleToUse.ifBlank { "Unknown Title" },
                                artist = artistToUse.ifBlank { "Unknown" },
                                thumbnailUrl = thumbnail,
                                youtubeUrl = if (videoOrPlaylistUrl.isNotBlank()) videoOrPlaylistUrl else "https://www.youtube.com/watch?v=$songId",
                                isLiked = false,
                                isDisliked = false,
                                isrc = entry.isrc,
                                rawTitle = rawTitle.ifBlank { "Unknown Title" },
                                isMetadataCleaned = false
                            )
                        )
                    }
                }
            } else {
                Napier.w("Search response does not look like JSON: ${jsonString.take(100)}", tag = "DEBUG_METADATA")
                jsonString.lines().forEach { line ->
                    if (line.contains("watch?v=")) {
                        val videoId = line.substringAfter("watch?v=").substringBefore("&").trim()
                        if (videoId.isNotBlank()) {
                            songs.add(
                                Song(
                                    id = videoId,
                                    title = "Unknown Title",
                                    artist = "Unknown",
                                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                    youtubeUrl = "https://www.youtube.com/watch?v=$videoId",
                                    rawTitle = "Unknown Title",
                                    isMetadataCleaned = false
                                )
                            )
                        }
                    }
                }
            }

            // If query is not a URL and no playlists were in the top results, query for playlists specifically
            val hasPlaylists = songs.any { it.youtubeUrl.contains("list=") }
            if (!isUrl && !hasPlaylists) {
                try {
                    val playlistSearchUrl = "ytsearch10:$cleanQuery playlist"
                    val plReq = YoutubeDLRequest(playlistSearchUrl)
                    plReq.addOption("--flat-playlist")
                    plReq.addOption("--dump-single-json")
                    val plJson = YoutubeDL.getInstance().execute(plReq).out.trim()
                    if (plJson.startsWith("{")) {
                        val plNode = json.decodeFromString<JsonElement>(plJson).jsonObject
                        val plEntries = if (plNode.containsKey("entries")) {
                            json.decodeFromString<YtDlpPlaylist>(plJson).entries ?: emptyList()
                        } else {
                            listOf(json.decodeFromString<YtDlpEntry>(plJson))
                        }
                        for ((index, entry) in plEntries.withIndex()) {
                            val rawTitle = entry.title ?: ""
                            val rawId = entry.id
                            val url = entry.url ?: ""
                            val playlistId = when {
                                !rawId.isNullOrBlank() && (rawId.startsWith("PL") || rawId.startsWith("RD") || rawId.startsWith("OLAK")) -> rawId
                                url.contains("list=") -> url.substringAfter("list=").substringBefore("&").substringBefore("?")
                                else -> null
                            }
                            if (!playlistId.isNullOrBlank()) {
                                val plUrl = "https://www.youtube.com/playlist?list=$playlistId"
                                if (songs.none { it.youtubeUrl == plUrl }) {
                                    songs.add(
                                        0,
                                        Song(
                                            id = playlistId,
                                            title = rawTitle.ifBlank { "Playlist" },
                                            artist = entry.uploader ?: "YouTube Playlist",
                                            thumbnailUrl = entry.thumbnail ?: "https://i.ytimg.com/vi/$playlistId/hqdefault.jpg",
                                            youtubeUrl = plUrl,
                                            rawTitle = rawTitle
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Napier.w("Playlist search fallback exception: ${e.message}", tag = "DEBUG_METADATA")
                }
            }

            Napier.i("Search returned ${songs.size} results", tag = "DEBUG_METADATA")
            songs
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
                for ((index, entry) in rawEntries.withIndex()) {
                    val rawTitle = entry.title ?: ""
                    // Do NOT run heavy MusicBrainz lookup during playlist load; use raw/uploader info initially so load is instantaneous.
                    // MusicBrainz lookup / cleaning will happen lazily when playing/buffering.
                    val artist = entry.uploader?.replace(Regex("(?i)vevo|official|channel|music"), "")?.trim() ?: "Unknown"

                    val rawId = entry.id?.takeIf { it.isNotBlank() && it != "null" }
                        ?: entry.url?.let { u ->
                            if (u.contains("watch?v=")) u.substringAfter("watch?v=").substringBefore("&").substringBefore("?").trim()
                            else if (u.contains("youtu.be/")) u.substringAfter("youtu.be/").substringBefore("?").substringBefore("&").trim()
                            else if (u.length in 10..12 && !u.contains("/")) u.trim()
                            else null
                        }?.takeIf { it.isNotBlank() && it != "null" }

                    val videoId = rawId ?: UUID.randomUUID().toString()
                    val videoUrl = if (rawId != null) "https://www.youtube.com/watch?v=$rawId" else (entry.url ?: url)

                    val thumbnail = if (!entry.thumbnail.isNullOrBlank()) {
                        entry.thumbnail
                    } else if (rawId != null) {
                        "https://i.ytimg.com/vi/$rawId/hqdefault.jpg"
                    } else {
                        ""
                    }

                    songs.add(
                        Song(
                            id = videoId,
                            title = rawTitle.ifBlank { "Unknown Title" },
                            artist = artist.ifBlank { "Unknown" },
                            thumbnailUrl = thumbnail,
                            youtubeUrl = videoUrl,
                            isLiked = false,
                            isDisliked = false,
                            isrc = entry.isrc,
                            rawTitle = rawTitle.ifBlank { "Unknown Title" }
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

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    var rateLimitCooldownUntilMs: Long = 0L
        private set

    fun isRateLimited(): Boolean {
        return System.currentTimeMillis() < rateLimitCooldownUntilMs
    }

    fun setRateLimitedCooldown(seconds: Long = 20) {
        rateLimitCooldownUntilMs = System.currentTimeMillis() + (seconds * 1000L)
    }

    suspend fun getStreamUrl(youtubeUrl: String): String? = withContext(Dispatchers.IO) {
        if (youtubeUrl.startsWith("content://") || youtubeUrl.startsWith("file://") || youtubeUrl.startsWith("/")) {
            lastStreamError = null
            return@withContext youtubeUrl
        }
        if (isRateLimited()) {
            val remainingSec = ((rateLimitCooldownUntilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
            val err = "YouTube rate limited. Waiting ${remainingSec}s before retrying."
            lastStreamError = err
            Napier.w(err, tag = "DEBUG_METADATA")
            return@withContext null
        }
        val request = YoutubeDLRequest(youtubeUrl)
        request.addOption("-f", "bestaudio/best")
        request.addOption("-g")
        request.addOption("--user-agent", USER_AGENT)
        
        Napier.d("Fetching stream URL for: $youtubeUrl", tag = "DEBUG_METADATA")
        return@withContext try {
            val response = YoutubeDL.getInstance().execute(request)
            val url = response.out.trim().lines().firstOrNull()
            if (url != null && url.startsWith("http")) {
                lastStreamError = null
                Napier.d("Stream URL fetched: $url", tag = "DEBUG_METADATA")
                url
            } else {
                val err = "Invalid stream URL response: ${response.out.take(150)}"
                lastStreamError = err
                Napier.w(err, tag = "DEBUG_METADATA")
                null
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown yt-dlp error"
            Napier.e("Failed to fetch stream URL: $msg", tag = "DEBUG_METADATA")
            if (msg.contains("429") || msg.contains("Too Many Requests", ignoreCase = true) || msg.contains("bot", ignoreCase = true) || msg.contains("Sign in", ignoreCase = true)) {
                setRateLimitedCooldown(25)
                lastStreamError = "YouTube Bot Check / Rate Limited: Sign in required or wait 25s."
                Napier.w("Set rate limit / bot check cooldown for 25s", tag = "DEBUG_METADATA")
            } else {
                lastStreamError = msg
            }
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
        request.addOption("-f", "bestaudio/best")
        request.addOption("-x")
        request.addOption("--audio-format", "mp3")
        request.addOption("--add-metadata")
        request.addOption("--embed-thumbnail")
        request.addOption("--convert-thumbnails", "jpg")

        val cleanTitle = song.title.replace("\"", "\\\"").replace("\n", " ").trim()
        val cleanArtist = song.artist.replace("\"", "\\\"").replace("\n", " ").trim()
        val cleanAlbum = "MusicDLP"

        var tempThumbFile: File? = null
        val effectiveThumbUrl = if (song.thumbnailUrl.isNotBlank()) {
            song.thumbnailUrl
        } else if (song.youtubeUrl.contains("watch?v=")) {
            val vId = song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
            "https://i.ytimg.com/vi/$vId/hqdefault.jpg"
        } else null

        if (!effectiveThumbUrl.isNullOrBlank()) {
            try {
                val tempThumb = File(downloadDir, "${song.id}_thumb.jpg")
                val conn = URL(effectiveThumbUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.inputStream.use { input ->
                    tempThumb.outputStream().use { output -> input.copyTo(output) }
                }
                if (tempThumb.exists() && tempThumb.length() > 0) {
                    tempThumbFile = tempThumb
                }
            } catch (e: Exception) {
                Napier.w("Thumbnail download for embedding failed: ${e.message}", tag = "DEBUG_METADATA")
            }
        }

        if (tempThumbFile != null) {
            request.addOption(
                "--postprocessor-args",
                "ffmpeg:-i \"${tempThumbFile.absolutePath}\" -map 0:a -map 1:v -c:a copy -c:v mjpeg -metadata:s:v title=\"Album cover\" -metadata:s:v comment=\"Cover (front)\" -metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" -id3v2_version 3"
            )
        } else {
            request.addOption(
                "--postprocessor-args",
                "ffmpeg:-metadata title=\"$cleanTitle\" -metadata artist=\"$cleanArtist\" -metadata album=\"$cleanAlbum\" -id3v2_version 3"
            )
        }
        request.addOption("--user-agent", USER_AGENT)

        try {
            YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                onProgress(progress / 100f)
            }
        } finally {
            tempThumbFile?.delete()
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
