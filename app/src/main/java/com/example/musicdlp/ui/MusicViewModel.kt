package com.example.musicdlp.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.example.musicdlp.MainActivity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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
import com.example.musicdlp.R
import com.example.musicdlp.data.AlternateVersion
import com.example.musicdlp.data.Song
import com.example.musicdlp.data.YoutubeDLRepository
import kotlinx.serialization.json.Json
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val currentSong = _songsToSwipe.value.firstOrNull()
                    if (currentSong != null && _currentlyPlayingId.value == currentSong.id) {
                        Napier.d("Song playback ended automatically. Skipping to next song.", tag = "DEBUG_METADATA")
                        skipSong(currentSong)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _currentPlayingSong.value?.let { showPlaybackNotification(it) }
            }
        })
    }

    private val _songsToSwipe = MutableStateFlow<List<Song>>(emptyList())
    val songsToSwipe: StateFlow<List<Song>> = _songsToSwipe

    private val _playlistLikedSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistLikedSongs: StateFlow<List<Song>> = _playlistLikedSongs

    private val _playlistDislikedSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistDislikedSongs: StateFlow<List<Song>> = _playlistDislikedSongs

    private val _playlistNewSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistNewSongs: StateFlow<List<Song>> = _playlistNewSongs

    private fun addPlaylistLikedSong(song: Song) {
        val current = _playlistLikedSongs.value.toMutableList()
        current.removeAll { it.id == song.id || (it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true)) }
        current.add(song)
        _playlistLikedSongs.value = current
    }

    private fun addPlaylistDislikedSong(song: Song) {
        val current = _playlistDislikedSongs.value.toMutableList()
        current.removeAll { it.id == song.id || (it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true)) }
        current.add(song)
        _playlistDislikedSongs.value = current
    }

    private fun removePlaylistDislikedSong(song: Song) {
        val current = _playlistDislikedSongs.value.toMutableList()
        current.removeAll { it.id == song.id || (it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true)) }
        _playlistDislikedSongs.value = current
    }

    private fun addPlaylistNewSong(song: Song) {
        val current = _playlistNewSongs.value.toMutableList()
        current.removeAll { it.id == song.id || (it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true)) }
        current.add(song)
        _playlistNewSongs.value = current
    }

    private fun removePlaylistNewSong(song: Song) {
        val current = _playlistNewSongs.value.toMutableList()
        current.removeAll { it.id == song.id || (it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true)) }
        _playlistNewSongs.value = current
    }

    private val skippedHistory = mutableListOf<Song>()
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

    private val pendingBufferQueue = mutableListOf<Song>()
    private val queueMutex = Mutex()
    private val processMutex = Mutex()
    
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

    private val _currentPlayingSong = MutableStateFlow<Song?>(null)
    val currentPlayingSong: StateFlow<Song?> = _currentPlayingSong

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.musicdlp.ACTION_PLAY_PAUSE" -> {
                    togglePlayPause()
                }
                "com.example.musicdlp.ACTION_LIKE" -> {
                    val song = _currentPlayingSong.value ?: _songsToSwipe.value.firstOrNull()
                    song?.let { likeSong(it) }
                }
                "com.example.musicdlp.ACTION_DISLIKE" -> {
                    val song = _currentPlayingSong.value ?: _songsToSwipe.value.firstOrNull()
                    song?.let { dislikeSong(it) }
                }
            }
        }
    }

    private fun showPlaybackNotification(song: Song) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "playback_channel",
                "Playback Notification",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows currently playing song"
                setShowBadge(true)
            }
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            app, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = Intent("com.example.musicdlp.ACTION_PLAY_PAUSE").setPackage(app.packageName)
        val playPausePendingIntent = PendingIntent.getBroadcast(
            app, 1, playPauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val likeIntent = Intent("com.example.musicdlp.ACTION_LIKE").setPackage(app.packageName)
        val likePendingIntent = PendingIntent.getBroadcast(
            app, 2, likeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dislikeIntent = Intent("com.example.musicdlp.ACTION_DISLIKE").setPackage(app.packageName)
        val dislikePendingIntent = PendingIntent.getBroadcast(
            app, 3, dislikeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPlaying = exoPlayer.isPlaying
        val playPauseIcon = if (isPlaying) R.drawable.sharp_pause_24 else R.drawable.sharp_play_arrow_24
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val notification = NotificationCompat.Builder(app, "playback_channel")
            .setContentTitle(song.title.ifBlank { "Unknown Title" })
            .setContentText(song.artist.ifBlank { song.rawTitle ?: "MusicDLP" })
            .setSmallIcon(R.drawable.sharp_play_arrow_24)
            .setContentIntent(openPendingIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.sharp_delete_24, "Dislike", dislikePendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.sharp_play_arrow_24, "Like", likePendingIntent)
            .build()

        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(1001, notification)
        } catch (e: Exception) {
            Napier.w("Could not post playback notification: ${e.message}", tag = "DEBUG_METADATA")
        }
    }

    private fun clearPlaybackNotification() {
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.cancel(1001)
        } catch (e: Exception) {}
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playerError != null || exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.mediaItemCount == 0) {
                val current = _currentPlayingSong.value ?: _songsToSwipe.value.firstOrNull()
                current?.let { playPreview(it, forceRefreshSource = true) }
            } else {
                exoPlayer.play()
            }
        }
    }

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
        val updatedSong = song.copy(
            title = newTitle.trim(),
            artist = newArtist.trim(),
            metadataSource = "manual edit",
            isMetadataCleaned = true
        )
        _songsToSwipe.value = _songsToSwipe.value.map { if (it.id == song.id) updatedSong else it }
        _promptForSongName.value = null
    }

    val lastWorkingModel: String?
        get() = repository.lastWorkingModel

    fun clearGeminiModelCache() {
        repository.clearModelCache()
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
        if (!processMutex.tryLock()) return
        try {
            while (true) {
                val song = queueMutex.withLock {
                    if (pendingBufferQueue.isNotEmpty() && _songsToSwipe.value.size < 5) {
                        pendingBufferQueue.removeAt(0)
                    } else null
                } ?: break
                
                // 1. FAST ID & URL CHECK - Skip if youtubeUrl or ID is already known
                val allSongs = songDao.getAllSongs()
                val existingByIdOrUrl = allSongs.firstOrNull { it.containsYoutubeUrlOrId(song.id) || it.containsYoutubeUrlOrId(song.youtubeUrl) }
                if (existingByIdOrUrl != null) {
                    Napier.d("Skipping song already in library (ID/URL match): ${song.id}", tag = "DEBUG_METADATA")
                    if (existingByIdOrUrl.isLiked) addPlaylistLikedSong(existingByIdOrUrl)
                    if (existingByIdOrUrl.isDisliked) addPlaylistDislikedSong(existingByIdOrUrl)
                    processedCount++
                    _playlistIndex.value = processedCount + 1
                    continue
                }

                _isBuffering.value = true
                var workingSong = song
                if (song.isMetadataCleaned != true) {
                    try {
                        val cleanResult = repository.cleanTitleAndArtist(song.title, song.artist, song.isrc, song.youtubeUrl)
                        workingSong = song.copy(
                            artist = cleanResult.artist,
                            title = cleanResult.title,
                            metadataSource = cleanResult.source,
                            isMetadataCleaned = true
                        )
                    } catch (e: Exception) {}
                }

                // 2. CHECK IF NAME MATCHES AN EXISTING SONG IN DB
                val matchingExistingSong = allSongs.firstOrNull {
                    it.title.equals(workingSong.title, ignoreCase = true) &&
                    (it.artist.equals(workingSong.artist, ignoreCase = true) || workingSong.artist == "Unknown" || it.artist == "Unknown")
                }

                if (matchingExistingSong != null) {
                    Napier.d("Found new YouTube ID for existing song in DB: '${workingSong.title}'. Adding as alternate version.", tag = "DEBUG_METADATA")
                    val updatedExistingSong = matchingExistingSong.addAlternateVersion(
                        AlternateVersion(
                            youtubeUrl = workingSong.youtubeUrl,
                            rawTitle = workingSong.rawTitle ?: workingSong.title,
                            thumbnailUrl = workingSong.thumbnailUrl
                        )
                    )
                    songDao.insertSong(updatedExistingSong)
                    if (updatedExistingSong.isLiked) addPlaylistLikedSong(updatedExistingSong)
                    if (updatedExistingSong.isDisliked) addPlaylistDislikedSong(updatedExistingSong)
                    processedCount++
                    _playlistIndex.value = processedCount + 1
                    continue
                }

                // 3. Add to UI immediately so user can see it
                addPlaylistNewSong(workingSong)
                _songsToSwipe.value = _songsToSwipe.value + workingSong
                
                // 4. Pre-fetch stream URL in background
                try {
                    val url = repository.getStreamUrl(workingSong.youtubeUrl)
                    if (url != null) bufferedStreamUrls[workingSong.id] = url
                } catch (e: Exception) {}

                // If nothing is playing, play this one (it's the first)
                if (_currentlyPlayingId.value == null) {
                    delay(800) 
                    playPreview(workingSong)
                }
                
                delay(100) // Yield for UI
            }
        } finally {
            _isBuffering.value = false
            processMutex.unlock()
        }
    }

    fun skipSong(song: Song) {
        viewModelScope.launch {
            skippedHistory.add(song)
            _canGoBack.value = skippedHistory.isNotEmpty()
            advanceList()
        }
    }

    fun goBackToPreviousSong() {
        viewModelScope.launch {
            if (skippedHistory.isNotEmpty()) {
                val previousSong = skippedHistory.removeAt(skippedHistory.lastIndex)
                _canGoBack.value = skippedHistory.isNotEmpty()

                try {
                    val dbSong = songDao.getSongById(previousSong.id)
                    if (dbSong != null && dbSong.isDisliked) {
                        songDao.deleteSongById(previousSong.id)
                    }
                } catch (e: Exception) {}

                removePlaylistDislikedSong(previousSong)

                _songsToSwipe.value = listOf(previousSong) + _songsToSwipe.value
                if (processedCount > 0) processedCount--
                _playlistIndex.value = (processedCount + 1).coerceAtLeast(1)

                playPreview(previousSong)
            }
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
            val allSongs = songDao.getAllSongs()
            val existingLiked = allSongs.firstOrNull {
                it.isLiked &&
                it.title.equals(song.title, ignoreCase = true) &&
                (it.artist.equals(song.artist, ignoreCase = true) || song.artist == "Unknown" || it.artist == "Unknown")
            }

            val likedToRecord = if (existingLiked != null) {
                val updated = existingLiked.addAlternateVersion(
                    AlternateVersion(
                        youtubeUrl = song.youtubeUrl,
                        rawTitle = song.rawTitle ?: song.title,
                        thumbnailUrl = song.thumbnailUrl
                    )
                )
                songDao.insertSong(updated)
                updated
            } else {
                val newLiked = song.copy(isLiked = true, likedAt = System.currentTimeMillis())
                saveLikedSong(newLiked)
                newLiked
            }

            addPlaylistLikedSong(likedToRecord)
            removePlaylistNewSong(song)
            advanceList()
        }
    }

    private suspend fun saveLikedSong(song: Song) {
        songDao.insertSong(song.copy(isLiked = true))
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
                    
                    if (finalFile.exists()) return@withPermit

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

    private val prefs = application.getSharedPreferences("musicdlp_prefs", Context.MODE_PRIVATE)

    private val _hasUserSwiped = MutableStateFlow(prefs.getBoolean("has_user_swiped", false))
    val hasUserSwiped: StateFlow<Boolean> = _hasUserSwiped

    fun setHasUserSwiped() {
        if (!_hasUserSwiped.value) {
            _hasUserSwiped.value = true
            prefs.edit().putBoolean("has_user_swiped", true).apply()
        }
    }

    fun dislikeSong(song: Song) {
        viewModelScope.launch {
            val disliked = song.copy(isDisliked = true, dislikedAt = System.currentTimeMillis())
            songDao.insertSong(disliked)
            addPlaylistDislikedSong(disliked)
            removePlaylistNewSong(song)
            advanceList()
        }
    }

    fun retryDownload(song: Song) {
        viewModelScope.launch { saveLikedSong(song) }
    }

    fun replaceLikedSongWithAlternateVersion(
        targetSong: Song,
        selectedVersion: AlternateVersion
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isSongLoading.value = true
            _errorMessage.value = "Downloading alternate version for ${targetSong.title}..."
            try {
                val safeArtist = targetSong.artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                val safeTitle = targetSong.title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                val finalFileName = "$safeArtist - $safeTitle.mp3"

                val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val downloadDir = File(publicMusicDir, "MusicDLP")
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val targetFile = File(downloadDir, finalFileName)

                if (targetFile.exists()) {
                    targetFile.delete()
                }

                val tempDir = File(app.cacheDir, "downloads")
                if (!tempDir.exists()) tempDir.mkdirs()

                val tempSong = targetSong.copy(youtubeUrl = selectedVersion.youtubeUrl)
                val downloadedPath = repository.downloadSong(tempSong, tempDir) { progress ->
                    _downloadProgress.value = _downloadProgress.value + (targetSong.id to progress)
                }

                val fileToInsert = File(downloadedPath)
                val resolver = app.contentResolver
                val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val details = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, finalFileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.ARTIST, targetSong.artist)
                    put(MediaStore.Audio.Media.TITLE, targetSong.title)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MusicDLP")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(audioCollection, details) ?: throw IOException("Failed to create MediaStore entry")
                resolver.openOutputStream(uri)?.use { outputStream -> fileToInsert.inputStream().use { input -> input.copyTo(outputStream) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    details.clear()
                    details.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(uri, details, null, null)
                }
                if (fileToInsert.parentFile?.name == "downloads" || fileToInsert.parentFile?.absolutePath == app.cacheDir.absolutePath) fileToInsert.delete()

                val currentAlternates = targetSong.getAlternateVersionsList().toMutableList()
                currentAlternates.removeAll { it.youtubeUrl == selectedVersion.youtubeUrl }
                currentAlternates.add(
                    AlternateVersion(
                        youtubeUrl = targetSong.youtubeUrl,
                        rawTitle = targetSong.rawTitle ?: targetSong.title,
                        thumbnailUrl = targetSong.thumbnailUrl
                    )
                )

                val newThumbnail = if (!selectedVersion.thumbnailUrl.isNullOrBlank()) {
                    selectedVersion.thumbnailUrl
                } else if (selectedVersion.youtubeUrl.contains("watch?v=")) {
                    val id = selectedVersion.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                    "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                } else {
                    targetSong.thumbnailUrl
                }

                val updatedSong = targetSong.copy(
                    youtubeUrl = selectedVersion.youtubeUrl,
                    rawTitle = selectedVersion.rawTitle ?: targetSong.rawTitle,
                    thumbnailUrl = newThumbnail,
                    alternateYoutubeUrls = Json.encodeToString(currentAlternates)
                )

                songDao.insertSong(updatedSong)
                _errorMessage.value = "Replaced ${targetSong.title} with alternate version!"
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Failed to replace alternate version: ${e.message}"
            } finally {
                _downloadProgress.value = _downloadProgress.value - targetSong.id
                _isSongLoading.value = false
            }
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
                _currentPlayingSong.value = song
                showPlaybackNotification(song)
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

    private var previewJob: Job? = null

    fun playPreviewByUrl(youtubeUrl: String, title: String = "Preview") {
        val tempSong = Song(
            id = "preview_${youtubeUrl.hashCode()}",
            title = title,
            artist = "",
            thumbnailUrl = "",
            youtubeUrl = youtubeUrl,
            isMetadataCleaned = true
        )
        playPreview(tempSong)
    }

    fun playPreview(song: Song, forceRefreshSource: Boolean = false) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            var workingSong = song
            if (song.isMetadataCleaned != true) {
                try {
                    val cleanResult = repository.cleanTitleAndArtist(song.title, song.artist, song.isrc, song.youtubeUrl)
                    workingSong = song.copy(
                        artist = cleanResult.artist,
                        title = cleanResult.title,
                        metadataSource = cleanResult.source,
                        isMetadataCleaned = true
                    )
                    _songsToSwipe.value = _songsToSwipe.value.map { if (it.id == song.id) workingSong else it }
                } catch (e: Exception) {}
            }
            _currentlyPlayingId.value = workingSong.id
            _currentPlayingSong.value = workingSong
            showPlaybackNotification(workingSong)
            _isSongLoading.value = true
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            try {
                if (forceRefreshSource) {
                    bufferedStreamUrls.remove(workingSong.id)
                }
                var streamUrl = bufferedStreamUrls[workingSong.id]
                if (streamUrl == null) {
                    streamUrl = repository.getStreamUrl(workingSong.youtubeUrl)
                    if (streamUrl != null) bufferedStreamUrls[workingSong.id] = streamUrl
                }
                if (!coroutineContext.isActive) return@launch
                if (streamUrl != null) {
                    _errorMessage.value = null
                    exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                    exoPlayer.prepare()
                    exoPlayer.play()
                } else {
                    _errorMessage.value = "Could not fetch stream URL for ${workingSong.title}"
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

    private fun resetPlaybackAndQueue() {
        previewJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _currentlyPlayingId.value = null
        _currentPlayingSong.value = null
        clearPlaybackNotification()
        bufferedStreamUrls.clear()
        _songsToSwipe.value = emptyList()
        _playlistLikedSongs.value = emptyList()
        _playlistDislikedSongs.value = emptyList()
        _playlistNewSongs.value = emptyList()
    }

    fun searchPlaylists(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isPlaylistLoading.value = true
            resetPlaybackAndQueue()
            queueMutex.withLock { pendingBufferQueue.clear() }
            try {
                val results = repository.searchSongsOrPlaylists(query)
                _playlistTotal.value = results.size
                processedCount = 0
                if (results.isEmpty()) {
                    _isPlaylistLoading.value = false
                } else {
                    for (url in results) {
                        launch(Dispatchers.IO) {
                            try {
                                val songs = repository.getPlaylistSongs(url)
                                queueMutex.withLock { pendingBufferQueue.addAll(songs) }
                                processQueue()
                            } catch (e: Exception) {
                            } finally {
                                _isPlaylistLoading.value = false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Search failed: ${e.message}"
                _isPlaylistLoading.value = false
            }
        }
    }

    fun loadPlaylist(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _isPlaylistLoading.value = true
            resetPlaybackAndQueue()
            queueMutex.withLock { pendingBufferQueue.clear() }
            try {
                val playlistSongs = repository.getPlaylistSongs(url)
                _playlistTotal.value = playlistSongs.size
                processedCount = 0
                queueMutex.withLock { pendingBufferQueue.addAll(playlistSongs) }
                processQueue()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load playlist: ${e.message}"
            } finally {
                _isPlaylistLoading.value = false
            }
        }
    }

    fun queryLocalStorageUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isPlaylistLoading.value = true
            resetPlaybackAndQueue()
            queueMutex.withLock { pendingBufferQueue.clear() }

            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {}

                val rootDoc = DocumentFile.fromTreeUri(context, uri)
                if (rootDoc == null || !rootDoc.exists()) {
                    _errorMessage.value = "Could not access selected folder"
                    _isPlaylistLoading.value = false
                    return@launch
                }

                val supportedExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus", "webm")

                fun findAudioFiles(dir: DocumentFile): List<DocumentFile> {
                    val result = mutableListOf<DocumentFile>()
                    val files = dir.listFiles()
                    for (file in files) {
                        if (file.isDirectory) {
                            result.addAll(findAudioFiles(file))
                        } else if (file.isFile) {
                            val name = file.name ?: ""
                            val ext = name.substringAfterLast(".", "").lowercase()
                            if (supportedExtensions.contains(ext)) {
                                result.add(file)
                            }
                        }
                    }
                    return result
                }

                val audioFiles = findAudioFiles(rootDoc)
                Napier.d("Local folder scan found ${audioFiles.size} audio files", tag = "DEBUG_METADATA")

                if (audioFiles.isEmpty()) {
                    _errorMessage.value = "No audio files (.mp3, .m4a, .flac, etc.) found in selected folder."
                    _isPlaylistLoading.value = false
                    return@launch
                }

                val localSongs = audioFiles.map { file ->
                    val fileName = file.name ?: "Unknown"
                    val nameWithoutExt = fileName.substringBeforeLast(".")
                    val uriString = file.uri.toString()

                    Song(
                        id = "local_${uriString.hashCode()}",
                        title = nameWithoutExt,
                        artist = "Local File",
                        thumbnailUrl = "",
                        youtubeUrl = uriString,
                        rawTitle = fileName,
                        isMetadataCleaned = true
                    )
                }

                _playlistTotal.value = localSongs.size
                processedCount = 0
                queueMutex.withLock { pendingBufferQueue.addAll(localSongs) }
                _isPlaylistLoading.value = false
                processQueue()
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Failed to scan local folder: ${e.message}"
                _isPlaylistLoading.value = false
            }
        }
    }

    fun updateYtDlp() {
        viewModelScope.launch(Dispatchers.IO) {
            _isPlaylistLoading.value = true
            try {
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
        val filter = IntentFilter().apply {
            addAction("com.example.musicdlp.ACTION_PLAY_PAUSE")
            addAction("com.example.musicdlp.ACTION_LIKE")
            addAction("com.example.musicdlp.ACTION_DISLIKE")
        }
        ContextCompat.registerReceiver(application, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val prefs = application.getSharedPreferences("musicdlp_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        _geminiApiKey.value = savedKey
        repository.geminiApiKey = savedKey
        viewModelScope.launch(Dispatchers.IO) {
            try { YoutubeDL.getInstance().updateYoutubeDL(application) } catch (e: Exception) {}
        }
    }

    override fun onCleared() {
        try { app.unregisterReceiver(notificationReceiver) } catch (e: Exception) {}
        clearPlaybackNotification()
        exoPlayer.release()
    }
}
