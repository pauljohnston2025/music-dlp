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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
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
                    DefaultDataSource.Factory(
                        application,
                        DefaultHttpDataSource.Factory()
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    )
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

    private val pendingBufferQueue = mutableListOf<Song>()
    
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

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
        val updatedSong = song.copy(title = newTitle.trim(), artist = newArtist.trim(), isMetadataCleaned = true)
        _songsToSwipe.value = _songsToSwipe.value.map { if (it.id == song.id) updatedSong else it }
        _promptForSongName.value = null
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

    fun isSongDownloaded(song: Song): Boolean {
        val safeArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        val finalFileName = "$safeArtist - $safeTitle.mp3"
        val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val downloadDir = File(publicMusicDir, "MusicDLP")
        return File(downloadDir, finalFileName).exists()
    }

    fun clearDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            _isPlaylistLoading.value = true
            try {
                songDao.clearAllSongs()
                _errorMessage.value = "Database cleared"
            } catch (e: Exception) {
                _errorMessage.value = "Failed to clear database: ${e.message}"
            } finally {
                _isPlaylistLoading.value = false
            }
        }
    }

    private suspend fun processQueue() {
        if (_isBuffering.value) return
        _isBuffering.value = true
        try {
            while (pendingBufferQueue.isNotEmpty() && _songsToSwipe.value.size < 5) {
                val song = pendingBufferQueue.removeAt(0)
                
                var workingSong = song
                if (song.isMetadataCleaned != true) {
                    try {
                        val (cleanArtist, cleanTitle) = repository.cleanTitleAndArtist(song.title, song.artist, song.isrc, song.youtubeUrl)
                        workingSong = song.copy(artist = cleanArtist, title = cleanTitle, isMetadataCleaned = true)
                    } catch (e: Exception) {}
                }

                val allSongs = songDao.getAllSongs()
                if (allSongs.any { it.title.lowercase().trim() == workingSong.title.lowercase().trim() }) continue

                try {
                    val url = repository.getStreamUrl(workingSong.youtubeUrl)
                    if (url != null) bufferedStreamUrls[workingSong.id] = url
                } catch (e: Exception) {}

                _songsToSwipe.value = _songsToSwipe.value + workingSong
                if (_currentlyPlayingId.value == null) playPreview(workingSong)
            }
        } finally {
            _isBuffering.value = false
        }
    }

    fun skipSong(song: Song) {
        viewModelScope.launch {
            songDao.insertSong(song.copy(isDisliked = true, dislikedAt = System.currentTimeMillis()))
            advanceList()
        }
    }

    private fun advanceList() {
        val currentList = _songsToSwipe.value
        if (currentList.isNotEmpty()) {
            _songsToSwipe.value = currentList.drop(1)
            processedCount++
            _playlistIndex.value = processedCount + 1
        }
        val nextSong = _songsToSwipe.value.firstOrNull()
        if (nextSong != null) {
            playPreview(nextSong)
        } else {
            exoPlayer.stop()
            _currentlyPlayingId.value = null
        }
        viewModelScope.launch { processQueue() }
    }

    fun likeSong(song: Song) {
        viewModelScope.launch {
            saveLikedSong(song.copy(likedAt = System.currentTimeMillis()))
            advanceList()
        }
    }

    private suspend fun saveLikedSong(song: Song) {
        songDao.insertSong(song.copy(isLiked = true))
        
        // Queue download
        viewModelScope.launch(Dispatchers.IO) {
            downloadSemaphore.withPermit {
                try {
                    val safeArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                    val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                    val finalFileName = "$safeArtist - $safeTitle.mp3"
                    
                    val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val downloadDir = File(publicMusicDir, "MusicDLP")
                    if (!downloadDir.exists()) downloadDir.mkdirs()
                    val finalFile = File(downloadDir, finalFileName)
                    
                    if (finalFile.exists()) {
                        Napier.d("File already exists: $finalFileName", tag = "DEBUG_METADATA")
                        return@withPermit
                    }

                    val downloadedPath: String = if (song.youtubeUrl.startsWith("/")) {
                        val linkFile = File(app.cacheDir, finalFileName)
                        try { Os.link(song.youtubeUrl, linkFile.absolutePath) } catch (e: Exception) { File(song.youtubeUrl).copyTo(linkFile, overwrite = true) }
                        linkFile.absolutePath
                    } else {
                        val tempDir = File(app.cacheDir, "downloads")
                        if (!tempDir.exists()) tempDir.mkdirs()
                        repository.downloadSong(song.copy(isLiked = true), tempDir) { progress ->
                            _downloadProgress.value = _downloadProgress.value + (song.id to progress)
                        }
                    }

                    val fileToInsert = File(downloadedPath)
                    val resolver = app.contentResolver
                    val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
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
                    val uri = resolver.insert(audioCollection, details) ?: throw IOException("Failed to create MediaStore entry")
                    resolver.openOutputStream(uri)?.let { outputStream -> fileToInsert.inputStream().use { input -> input.copyTo(outputStream) } }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        details.clear()
                        details.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        resolver.update(uri, details, null, null)
                    }
                    if (fileToInsert.parentFile?.name == "downloads" || fileToInsert.parentFile?.absolutePath == app.cacheDir.absolutePath) fileToInsert.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                    _errorMessage.value = "Failed to save ${song.title}: ${e.message}"
                } finally {
                    _downloadProgress.value = _downloadProgress.value - song.id
                }
            }
        }
    }

    fun dislikeSong(song: Song) {
        viewModelScope.launch {
            songDao.insertSong(song.copy(isDisliked = true, dislikedAt = System.currentTimeMillis()))
            advanceList()
        }
    }

    fun retryDownload(song: Song) {
        viewModelScope.launch {
            saveLikedSong(song)
        }
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
            _currentlyPlayingId.value = song.id
            _isSongLoading.value = true
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            try {
                var streamUrl = bufferedStreamUrls[song.id]
                if (streamUrl == null) {
                    streamUrl = repository.getStreamUrl(song.youtubeUrl)
                    if (streamUrl != null) bufferedStreamUrls[song.id] = streamUrl
                }
                if (streamUrl != null) {
                    exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                    exoPlayer.prepare()
                    exoPlayer.play()
                } else {
                    _errorMessage.value = "Could not fetch stream URL for ${song.title}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Playback error: ${e.message}"
            } finally {
                _isSongLoading.value = false
            }
        }
    }

    fun resumeSwiping() {
        val currentSong = _songsToSwipe.value.firstOrNull()
        if (currentSong != null && _currentlyPlayingId.value != currentSong.id) playPreview(currentSong)
    }

    fun searchPlaylists(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isPlaylistLoading.value = true
            bufferedStreamUrls.clear()
            _songsToSwipe.value = emptyList()
            pendingBufferQueue.clear()
            try {
                val results = repository.searchSongsOrPlaylists(query)
                _playlistTotal.value = results.size
                processedCount = 0
                for (url in results) {
                    try {
                        val songs = repository.getPlaylistSongs(url)
                        pendingBufferQueue.addAll(songs)
                        processQueue()
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
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
            _songsToSwipe.value = emptyList()
            pendingBufferQueue.clear()
            try {
                val playlistSongs = repository.getPlaylistSongs(url)
                _playlistTotal.value = playlistSongs.size
                processedCount = 0
                pendingBufferQueue.addAll(playlistSongs)
                processQueue()
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
            bufferedStreamUrls.clear()
            _songsToSwipe.value = emptyList()
            pendingBufferQueue.clear()
            try {
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
                val docFile = DocumentFile.fromTreeUri(context, uri) ?: return@launch
                val audioFiles = docFile.listFiles().filter { it.isFile && (it.name?.endsWith(".mp3", true) == true) }
                val localSongs = audioFiles.map { file ->
                    val cachedFile = File(app.cacheDir, file.name ?: "local.mp3")
                    context.contentResolver.openInputStream(file.uri)?.use { input -> cachedFile.outputStream().use { input.copyTo(it) } }
                    Song(id = "local_${file.uri.toString().hashCode()}", title = file.name ?: "Unknown", artist = "Unknown", thumbnailUrl = "", youtubeUrl = cachedFile.absolutePath)
                }
                _playlistTotal.value = localSongs.size
                processedCount = 0
                pendingBufferQueue.addAll(localSongs)
                processQueue()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to read local folder: ${e.message}"
            } finally {
                _isPlaylistLoading.value = false
            }
        }
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
            } catch (e: Exception) {
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
            try { YoutubeDL.getInstance().updateYoutubeDL(application) } catch (e: Exception) {}
        }
    }
}
