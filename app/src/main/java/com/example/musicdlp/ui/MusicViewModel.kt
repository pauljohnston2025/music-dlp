package com.example.musicdlp.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.musicdlp.MusicDLPApplication
import com.example.musicdlp.data.Song
import com.example.musicdlp.data.YoutubeDLRepository
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import io.github.aakira.napier.Napier

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicDLPApplication
    private val repository = YoutubeDLRepository(application)
    private val songDao = app.database.songDao()

    val exoPlayer = ExoPlayer.Builder(application)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(application)
                .setDataSourceFactory(
                    DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                )
        )
        .build().apply {
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Napier.e("ExoPlayer error: ${error.message}", tag = "DEBUG_METADATA")
                _errorMessage.value = "Playback error: ${error.message}"
            }
        })
    }

    private val _songsToSwipe = MutableStateFlow<List<Song>>(emptyList())
    val songsToSwipe: StateFlow<List<Song>> = _songsToSwipe

    private val _playlistTotal = MutableStateFlow(0)
    val playlistTotal: StateFlow<Int> = _playlistTotal

    private val _playlistIndex = MutableStateFlow(0)
    val playlistIndex: StateFlow<Int> = _playlistIndex

    private var processedCount = 0

    private val _isPlaylistLoading = MutableStateFlow(false)
    val isPlaylistLoading: StateFlow<Boolean> = _isPlaylistLoading

    private val _isSongLoading = MutableStateFlow(false)
    val isSongLoading: StateFlow<Boolean> = _isSongLoading

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    private val _currentlyPlayingId = MutableStateFlow<String?>(null)
    val currentlyPlayingId: StateFlow<String?> = _currentlyPlayingId

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _promptForSongName = MutableStateFlow<Song?>(null)
    val promptForSongName: StateFlow<Song?> = _promptForSongName

    private val _suggestedTitle = MutableStateFlow("")
    val suggestedTitle: StateFlow<String> = _suggestedTitle

    private val _suggestedArtist = MutableStateFlow("")
    val suggestedArtist: StateFlow<String> = _suggestedArtist

    fun setSongNamePrompt(song: Song?) {
        _promptForSongName.value = song
        if (song != null) {
            val parts = song.title.split(Regex("""\s*[-–—]\s*"""), limit = 2)
            if (parts.size >= 2) {
                _suggestedArtist.value = parts[0].trim()
                _suggestedTitle.value = parts[1].trim()
            } else {
                _suggestedArtist.value = song.artist
                _suggestedTitle.value = song.title
            }
        } else {
            _suggestedTitle.value = ""
            _suggestedArtist.value = ""
        }
    }

    fun updateSongNameAndArtist(song: Song, newTitle: String, newArtist: String) {
        song.title = newTitle.trim()
        song.artist = newArtist.trim()
        _promptForSongName.value = null
        _songsToSwipe.value = _songsToSwipe.value.toList()
    }

    private val _geminiApiKey = MutableStateFlow("")
    val geminiApiKey: StateFlow<String> = _geminiApiKey

    private val _similarPlaylists = MutableStateFlow<List<String>>(emptyList())
    val similarPlaylists: StateFlow<List<String>> = _similarPlaylists

    private val downloadSemaphore = Semaphore(2)

    val likedSongs = songDao.getLikedSongs()
    val dislikedSongs = songDao.getDislikedSongs()

    private val bufferedStreamUrls = mutableMapOf<String, String>()

    override fun onCleared() {
        exoPlayer.release()
    }

    fun updateYtDlp() {
        viewModelScope.launch(Dispatchers.IO) {
            _isPlaylistLoading.value = true
            try {
                val currentVersion = YoutubeDL.getInstance().version(app)
                Napier.d("Current yt-dlp version: $currentVersion", tag = "DEBUG_METADATA")
                val result = YoutubeDL.getInstance().updateYoutubeDL(app)
                val newVersion = YoutubeDL.getInstance().version(app)
                _errorMessage.value = "yt-dlp update: $result. Version: $newVersion"
                Napier.i("yt-dlp update status: $result, New version: $newVersion", tag = "DEBUG_METADATA")
            } catch (e: Exception) {
                Napier.e("Failed to update yt-dlp: ${e.message}", tag = "DEBUG_METADATA")
                _errorMessage.value = "Failed to update yt-dlp: ${e.message}"
            } finally {
                _isPlaylistLoading.value = false
            }
        }
    }

    init {
        val prefs = application.getSharedPreferences("musicdlp_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        _geminiApiKey.value = savedKey
        repository.geminiApiKey = savedKey

        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(application)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update yt-dlp: ${e.message}"
            }
        }
    }

    fun setGeminiApiKey(key: String) {
        _geminiApiKey.value = key
        repository.geminiApiKey = key
        val prefs = app.getSharedPreferences("musicdlp_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("gemini_api_key", key).apply()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private suspend fun bufferNextSongs(songs: List<Song>, startIndex: Int) {
        val toBuffer = songs.drop(startIndex).take(5)
        for (song in toBuffer) {
            // Lazily clean title/artist via YouTube metadata or MusicBrainz if not already cleaned
            if (song.artist == "Unknown" || !song.artist.contains(" ")) {
                try {
                    Napier.d("Lazily cleaning metadata for: ${song.title}", tag = "DEBUG_METADATA")
                    val (cleanArtist, cleanTitle) = repository.cleanTitleAndArtist(song.title, song.artist, song.isrc, song.youtubeUrl)
                    song.artist = cleanArtist
                    song.title = cleanTitle
                    
                    // Trigger UI update to reflect cleaned names in the swipe list
                    _songsToSwipe.value = _songsToSwipe.value.toList()

                    // Check if this newly cleaned title already exists in liked/disliked database or previously buffered songs to avoid duplicates
                    val allSongs = songDao.getAllSongs()
                    val seenTitles = allSongs.map { it.title.lowercase().trim() }.toSet()
                    if (song.title.lowercase().trim() in seenTitles) {
                        Napier.i("Removing duplicate song after cleaning: ${song.title}", tag = "DEBUG_METADATA")
                        // Remove duplicate song from swipe list
                        _songsToSwipe.value = _songsToSwipe.value.filter { it.id != song.id }
                        continue
                    }
                } catch (e: Exception) {
                    Napier.w("Metadata cleaning failed for: ${song.title}, prompting user", tag = "DEBUG_METADATA")
                    // Prompt user for name/artist if MusicBrainz lookup fails
                    _promptForSongName.value = song
                }
            }

            if (!bufferedStreamUrls.containsKey(song.id)) {
                try {
                    val url = repository.getStreamUrl(song.youtubeUrl)
                    if (url != null) {
                        bufferedStreamUrls[song.id] = url
                    }
                } catch (e: Exception) {
                    // ignore buffer failure
                }
            }
        }
    }

    fun retryDownload(song: Song) {
        likeSong(song)
    }

    fun playLikedSong(song: Song, onNotDownloaded: () -> Unit) {
        viewModelScope.launch {
            val safeArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
            val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
            val finalFileName = "$safeArtist - $safeTitle.mp3"

            val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val downloadDir = File(publicMusicDir, "MusicDLP")
            val targetFile = File(downloadDir, finalFileName)

            if (targetFile.exists()) {
                _currentlyPlayingId.value = song.id
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.setMediaItem(MediaItem.fromUri(targetFile.absolutePath))
                exoPlayer.prepare()
                exoPlayer.play()
            } else {
                onNotDownloaded()
            }
        }
    }

    fun playPreview(song: Song) {
        viewModelScope.launch {
            // Lazily clean title/artist via YouTube metadata or MusicBrainz when playing the current song if needed
            if (song.artist == "Unknown" || !song.artist.contains(" ")) {
                try {
                    Napier.d("Cleaning metadata during playPreview for: ${song.title}", tag = "DEBUG_METADATA")
                    val (cleanArtist, cleanTitle) = repository.cleanTitleAndArtist(song.title, song.artist, song.isrc, song.youtubeUrl)
                    song.artist = cleanArtist
                    song.title = cleanTitle
                } catch (e: Exception) {
                    Napier.w("Metadata cleaning failed during playPreview for: ${song.title}", tag = "DEBUG_METADATA")
                    // Ignore MusicBrainz/YouTube metadata failure
                }
            }

            _currentlyPlayingId.value = song.id
            _isSongLoading.value = true
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            
            try {
                var streamUrl = bufferedStreamUrls[song.id]
                if (streamUrl == null) {
                    streamUrl = repository.getStreamUrl(song.youtubeUrl)
                    if (streamUrl != null) {
                        bufferedStreamUrls[song.id] = streamUrl
                    }
                }
                
                if (streamUrl != null) {
                    Napier.d("Preparing ExoPlayer with URL for ${song.title}: $streamUrl", tag = "DEBUG_METADATA")
                    exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                    exoPlayer.prepare()
                    exoPlayer.play()
                } else {
                    Napier.w("No stream URL for ${song.title}", tag = "DEBUG_METADATA")
                    _errorMessage.value = "Could not fetch stream URL for ${song.title}"
                }
            } catch (e: Exception) {
                if (e.message?.contains("429") == true) {
                    _errorMessage.value = "Rate limited by YouTube. Please wait a bit."
                } else {
                    _errorMessage.value = "Playback error: ${e.message}"
                }
            } finally {
                _isSongLoading.value = false
            }

            // Buffer next 5
            val currentList = _songsToSwipe.value
            val idx = currentList.indexOfFirst { it.id == song.id }
            if (idx >= 0) {
                bufferNextSongs(currentList, idx + 1)
            }
        }
    }

    fun resumeSwiping() {
        val currentSong = _songsToSwipe.value.firstOrNull()
        if (currentSong != null && _currentlyPlayingId.value != currentSong.id) {
            playPreview(currentSong)
        }
    }

    fun searchPlaylists(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isPlaylistLoading.value = true
            bufferedStreamUrls.clear()
            try {
                Napier.d("Starting search for: $query", tag = "DEBUG_METADATA")
                val results = repository.searchSongsOrPlaylists(query)
                _similarPlaylists.value = results
                
                if (results.isNotEmpty()) {
                    val allSongs = mutableListOf<Song>()
                    val seenIds = songDao.getAllSongs().map { it.id }.toSet()
                    
                    for (url in results) {
                        try {
                            val songs = repository.getPlaylistSongs(url)
                            allSongs.addAll(songs.filter { it.id !in seenIds })
                        } catch (e: Exception) {
                            Napier.w("Failed to load song(s) from $url during search: ${e.message}", tag = "DEBUG_METADATA")
                        }
                        // Limit to first few to avoid long hangs if many playlists found
                        if (allSongs.size > 20) break
                    }
                    
                    val filteredSongs = allSongs.distinctBy { it.id }
                    _songsToSwipe.value = filteredSongs
                    _playlistTotal.value = filteredSongs.size
                    processedCount = 0
                    _playlistIndex.value = if (filteredSongs.isNotEmpty()) 1 else 0
                    
                    if (filteredSongs.isNotEmpty()) {
                        playPreview(filteredSongs.first())
                    } else {
                        _errorMessage.value = "All search results are already in your library"
                    }
                } else {
                    _errorMessage.value = "No results found for \"$query\""
                }
            } catch (e: Exception) {
                Napier.e("Search failed in ViewModel: ${e.message}", tag = "DEBUG_METADATA")
                _errorMessage.value = "Search failed: ${e.message}"
            } finally {
                _isPlaylistLoading.value = false
            }
        }
    }

    fun loadPlaylist(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _isPlaylistLoading.value = true
            bufferedStreamUrls.clear()
            try {
                val allSongs = songDao.getAllSongs()
                val seenIds = allSongs.map { it.id }.toSet()
                val seenTitles = allSongs.map { it.title.lowercase().trim() }.toSet()
                
                val playlistSongs = repository.getPlaylistSongs(url)
                val filteredSongs = playlistSongs.filter { 
                    it.id !in seenIds && it.title.lowercase().trim() !in seenTitles 
                }
                _songsToSwipe.value = filteredSongs
                _playlistTotal.value = filteredSongs.size
                processedCount = 0
                _playlistIndex.value = if (filteredSongs.isNotEmpty()) 1 else 0
                
                if (filteredSongs.isNotEmpty()) {
                    playPreview(filteredSongs.first())
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load playlist: ${e.message}"
            } finally {
                _isPlaylistLoading.value = false
            }
        }
    }

    fun queryLocalStorageUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isPlaylistLoading.value = true
            try {
                // Take persistable URI permission if needed
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Ignore if not supported/already taken
                }

                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                if (docFile == null || !docFile.isDirectory) {
                    _errorMessage.value = "Selected URI is not a valid directory"
                    return@launch
                }

                val audioFiles = docFile.listFiles().filter { file ->
                    val name = file.name ?: ""
                    file.isFile && (name.endsWith(".mp3", true) || name.endsWith(".m4a", true) || name.endsWith(".wav", true) || name.endsWith(".flac", true))
                }

                val localSongs = audioFiles.map { file ->
                    val nameWithoutExt = file.name?.substringBeforeLast('.') ?: "Unknown"
                    val parts = nameWithoutExt.split(" - ", limit = 2)
                    val artist = if (parts.size == 2) parts[0].trim() else "Unknown"
                    val title = if (parts.size == 2) parts[1].trim() else nameWithoutExt
                    
                    // Resolve file URI path or copy to cache file so yt-dlp / ExoPlayer can read file path directly without content:// scheme issues
                    val cachedFile = File(getApplication<Application>().cacheDir, file.name ?: "local.mp3")
                    try {
                        getApplication<Application>().contentResolver.openInputStream(file.uri)?.use { input ->
                            cachedFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    Song(
                        id = "local_${file.uri.toString().hashCode()}",
                        title = title,
                        artist = artist,
                        thumbnailUrl = "",
                        youtubeUrl = cachedFile.absolutePath,
                        isLiked = false,
                        isDisliked = false
                    )
                }

                _songsToSwipe.value = localSongs
                _playlistTotal.value = localSongs.size
                processedCount = 0
                _playlistIndex.value = if (localSongs.isNotEmpty()) 1 else 0
                if (localSongs.isNotEmpty()) {
                    val first = localSongs.first()
                    _currentlyPlayingId.value = first.id
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(first.youtubeUrl)))
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to read local folder: ${e.message}"
            } finally {
                _isPlaylistLoading.value = false
            }
        }
    }

    fun likeSong(song: Song) {
        viewModelScope.launch {
            val updatedSong = song.copy(isLiked = true)
            songDao.insertSong(updatedSong)
            
            val currentList = _songsToSwipe.value
            val nextList = currentList.drop(1)
            _songsToSwipe.value = nextList
            processedCount++
            _playlistIndex.value = if (nextList.isNotEmpty()) processedCount + 1 else 0
            
            val nextSong = nextList.firstOrNull()
            if (nextSong != null) playPreview(nextSong) else exoPlayer.stop()
            
            // Queue download or hard link
            launch(Dispatchers.IO) {
                downloadSemaphore.withPermit {
                    try {
                        val safeArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                        val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                        val finalFileName = "$safeArtist - $safeTitle.mp3"

                        val downloadedPath: String = if (song.youtubeUrl.startsWith("/")) {
                            // Local file -> hard link or copy to cache/temp then insert
                            val linkFile = File(getApplication<Application>().cacheDir, finalFileName)
                            if (linkFile.exists()) linkFile.delete()
                            try {
                                Os.link(song.youtubeUrl, linkFile.absolutePath)
                            } catch (e: Exception) {
                                File(song.youtubeUrl).copyTo(linkFile, overwrite = true)
                            }
                            linkFile.absolutePath
                        } else {
                            val tempDir = File(getApplication<Application>().cacheDir, "downloads")
                            if (!tempDir.exists()) tempDir.mkdirs()
                            val path = repository.downloadSong(updatedSong, tempDir) { progress ->
                                _downloadProgress.value = _downloadProgress.value + (song.id to progress)
                            }
                            path
                        }

                        // Insert via MediaStore on Android 10+ or write to public Music directory
                        val fileToInsert = File(downloadedPath)
                        if (!fileToInsert.exists()) {
                            throw FileNotFoundException("Downloaded file not found at $downloadedPath")
                        }

                        val appCtx = getApplication<Application>()
                        val resolver = appCtx.contentResolver
                        val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        } else {
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        }

                        val details = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, finalFileName)
                            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                            put(MediaStore.Audio.Media.ARTIST, song.artist)
                            put(MediaStore.Audio.Media.TITLE, song.title)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MusicDLP")
                                put(MediaStore.Audio.Media.IS_PENDING, 1)
                            }
                        }

                        val uri = resolver.insert(audioCollection, details)
                            ?: throw IOException("Failed to create MediaStore entry for $finalFileName")

                        resolver.openOutputStream(uri)?.let { outputStream ->
                            fileToInsert.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                            outputStream.close()
                        } ?: throw IOException("Failed to open output stream for MediaStore URI")

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            details.clear()
                            details.put(MediaStore.Audio.Media.IS_PENDING, 0)
                            resolver.update(uri, details, null, null)
                        }

                        // Clean up temp file if any
                        val cacheDownloadsDir = File(appCtx.cacheDir, "downloads").absolutePath
                        if (fileToInsert.parentFile?.absolutePath == cacheDownloadsDir || fileToInsert.parentFile?.absolutePath == appCtx.cacheDir.absolutePath) {
                            fileToInsert.delete()
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        _errorMessage.value = "Failed to save ${song.title}: ${e.message}"
                    } finally {
                        _downloadProgress.value = _downloadProgress.value - song.id
                    }
                }
            }
        }
    }

    fun dislikeSong(song: Song) {
        viewModelScope.launch {
            val updatedSong = song.copy(isDisliked = true)
            songDao.insertSong(updatedSong)
            
            val currentList = _songsToSwipe.value
            val nextList = currentList.drop(1)
            _songsToSwipe.value = nextList
            processedCount++
            _playlistIndex.value = if (nextList.isNotEmpty()) processedCount + 1 else 0
            
            val nextSong = nextList.firstOrNull()
            if (nextSong != null) playPreview(nextSong) else exoPlayer.stop()
        }
    }
}
