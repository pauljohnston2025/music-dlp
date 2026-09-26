package com.example.musicdlp.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import com.example.musicdlp.MusicDLPApplication
import com.example.musicdlp.data.AlternateVersion
import com.example.musicdlp.data.Song
import com.example.musicdlp.data.YoutubeDLRepository
import com.example.musicdlp.data.toMediaItem
import com.example.musicdlp.service.MusicLibraryService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.yausername.youtubedl_android.YoutubeDL
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicDLPApplication
    private val repository = YoutubeDLRepository(application)
    private val songDao = app.database.songDao()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private suspend fun getMediaController(): Player? {
        val current = controller
        if (current != null) return current
        val future = controllerFuture ?: return null
        return withContext(Dispatchers.Main) {
            if (future.isDone) {
                try { future.get() } catch (e: Exception) { null }
            } else {
                suspendCancellableCoroutine { continuation ->
                    future.addListener({
                        try {
                            if (continuation.isActive) continuation.resume(future.get())
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }, MoreExecutors.directExecutor())
                }
            }
        }
    }

    val dbLikedSongsFlow = songDao.getLikedSongs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dbDislikedSongsFlow = songDao.getDislikedSongs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var retryAttempts = 0

    // Master list driving current session queue
    private val _activePlayingList = MutableStateFlow(emptyList<Song>())
    val activePlayingList: StateFlow<List<Song>> = _activePlayingList

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    enum class SwipingMode(val displayName: String) {
        ONLY_NEW("Only Categorise New"),
        NEW_AND_LIKED("New and Liked"),
        PLAY_ALL_RECATEGORISE("Play All / RecATEGORISE")
    }

    private val _swipingMode = MutableStateFlow(SwipingMode.ONLY_NEW)
    val swipingMode: StateFlow<SwipingMode> = _swipingMode

    // --- Derived Flows directly linked to DB and Queue ---
    val playlistLikedSongs: StateFlow<List<Song>> = combine(_activePlayingList, dbLikedSongsFlow) { queue, dbLiked ->
        queue.filter { song ->
            song.isLiked || dbLiked.any { db -> db.id == song.id || (db.title.equals(song.title, ignoreCase = true) && db.artist.equals(song.artist, ignoreCase = true)) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlistDislikedSongs: StateFlow<List<Song>> = combine(_activePlayingList, dbDislikedSongsFlow) { queue, dbDisliked ->
        queue.filter { song ->
            song.isDisliked || dbDisliked.any { db -> db.id == song.id || (db.title.equals(song.title, ignoreCase = true) && db.artist.equals(song.artist, ignoreCase = true)) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlistNewSongs: StateFlow<List<Song>> = combine(_activePlayingList, playlistLikedSongs, playlistDislikedSongs) { queue, liked, disliked ->
        val likedIds = liked.map { it.id }.toSet()
        val dislikedIds = disliked.map { it.id }.toSet()
        queue.filter { song -> song.id !in likedIds && song.id !in dislikedIds }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        val sessionToken = SessionToken(application, ComponentName(application, MusicLibraryService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            setupControllerListener()
        }, MoreExecutors.directExecutor())
    }

    private fun updateAppQueue() {
        app.playlistLikedSongs = playlistLikedSongs.value
        app.playlistDislikedSongs = playlistDislikedSongs.value
        app.playlistNewSongs = playlistNewSongs.value
        app.currentQueue = _activePlayingList.value

        val intent = Intent("com.example.musicdlp.ACTION_QUEUE_CHANGED").apply { setPackage(app.packageName) }
        app.sendBroadcast(intent)
    }

    private fun setupControllerListener() {
        controller?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId ?: return
                if (mediaId == "no_more_songs" || mediaId == "ROOT") return

                viewModelScope.launch(Dispatchers.IO) {
                    val song = songDao.getSongById(mediaId) ?: _activePlayingList.value.firstOrNull { it.id == mediaId }

                    if (song != null) {
                        val songWithState = updatePlayingSongWithDbState(song)
                        withContext(Dispatchers.Main) {
                            if (app.currentQueue.isNotEmpty() && _activePlayingList.value != app.currentQueue) {
                                _activePlayingList.value = app.currentQueue
                            }

                            val currentList = _activePlayingList.value
                            val newIdx = currentList.indexOfFirst { it.id == mediaId }
                            if (newIdx != -1) {
                                _currentIndex.value = newIdx
                            }

                            if (_currentlyPlayingId.value != songWithState.id) {
                                _currentlyPlayingId.value = songWithState.id
                                _currentPlayingSong.value = songWithState
                            }
                            updateAppQueue()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Napier.e("Player error: ${error.message}", tag = "DEBUG_METADATA")
                val currentSong = _currentPlayingSong.value
                if (currentSong != null && retryAttempts < 3) {
                    retryAttempts++
                    _errorMessage.value = "Playback error. Retrying stream... ($retryAttempts/3)"
                    _isSongLoading.value = true
                    viewModelScope.launch {
                        delay(800)
                        val currentPos = exoPlayer?.currentPosition ?: 0L
                        playPreview(currentSong, forceRefreshSource = true, seekToMs = currentPos)
                    }
                } else {
                    retryAttempts = 0
                    _isSongLoading.value = false
                    _errorMessage.value = "Playback error: ${error.message}"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    retryAttempts = 0
                    _isSongLoading.value = false
                } else if (playbackState == Player.STATE_ENDED) {
                    playNextInContext()
                }
            }
        })
    }

    val exoPlayer: Player?
        get() = controller

    private fun getNextSongIndex(queue: List<Song>, currentIndex: Int, mode: SwipingMode): Int {
        if (queue.isEmpty()) return -1
        val startSearchFrom = (currentIndex + 1).coerceAtLeast(0)

        for (i in startSearchFrom until queue.size) {
            val song = queue[i]
            val isMatch = when (mode) {
                SwipingMode.ONLY_NEW -> !song.isLiked && !song.isDisliked
                SwipingMode.NEW_AND_LIKED -> !song.isDisliked
                SwipingMode.PLAY_ALL_RECATEGORISE -> true
            }
            if (isMatch) return i
        }
        return -1
    }

    private fun getPreviousSongIndex(queue: List<Song>, currentIndex: Int, mode: SwipingMode): Int {
        if (queue.isEmpty() || currentIndex <= 0) return -1
        val startSearchFrom = (currentIndex - 1).coerceAtMost(queue.size - 1)

        for (i in startSearchFrom downTo 0) {
            val song = queue[i]
            val isMatch = when (mode) {
                SwipingMode.ONLY_NEW -> !song.isLiked && !song.isDisliked
                SwipingMode.NEW_AND_LIKED -> !song.isDisliked
                SwipingMode.PLAY_ALL_RECATEGORISE -> true
            }
            if (isMatch) return i
        }
        return -1
    }

    private suspend fun prescanAndCategorize(rawSongs: List<Song>) = withContext(Dispatchers.IO) {
        val allDbSongs = songDao.getAllSongs()
        val categorizedRaw = rawSongs.map { song ->
            val match = allDbSongs.firstOrNull { dbSong ->
                dbSong.id == song.id ||
                        dbSong.containsYoutubeUrlOrId(song.id) ||
                        dbSong.containsYoutubeUrlOrId(song.youtubeUrl) ||
                        (dbSong.title.equals(song.title, ignoreCase = true) &&
                                (dbSong.artist.equals(song.artist, ignoreCase = true) || song.artist == "Unknown" || dbSong.artist == "Unknown"))
            }
            if (match != null) {
                song.copy(isLiked = match.isLiked, isDisliked = match.isDisliked)
            } else {
                song
            }
        }

        _activePlayingList.value = categorizedRaw
        _currentIndex.value = 0
        updateAppQueue()

        val firstSong = categorizedRaw.firstOrNull()
        if (firstSong != null) {
            withContext(Dispatchers.Main) {
                playSong(firstSong, categorizedRaw)
            }
        }
    }

    fun cycleSwipingMode() {
        val nextMode = when (_swipingMode.value) {
            SwipingMode.ONLY_NEW -> SwipingMode.NEW_AND_LIKED
            SwipingMode.NEW_AND_LIKED -> SwipingMode.PLAY_ALL_RECATEGORISE
            SwipingMode.PLAY_ALL_RECATEGORISE -> SwipingMode.ONLY_NEW
        }
        setSwipingMode(nextMode)
    }

    fun setSwipingMode(mode: SwipingMode) {
        _swipingMode.value = mode
        val intent = Intent("com.example.musicdlp.ACTION_SET_MODE").apply {
            putExtra("mode", mode.name)
            setPackage(app.packageName)
        }
        app.sendBroadcast(intent)

        val current = _currentPlayingSong.value
        if (current != null) {
            viewModelScope.launch {
                val p = getMediaController()
                if (p != null) {
                    val currentIndex = p.currentMediaItemIndex
                    if (currentIndex >= 0 && currentIndex < p.mediaItemCount) {
                        val currentPos = p.currentPosition
                        val updatedMediaItem = current.toMediaItem(
                            canGoPrevious = _currentIndex.value > 0,
                            canGoNext = _currentIndex.value < _activePlayingList.value.lastIndex,
                            swipingMode = mode.name
                        )
                        p.replaceMediaItem(currentIndex, updatedMediaItem)
                        p.seekTo(currentIndex, currentPos)
                    }
                }
            }
        }
    }

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

    val canGoPreviousInContext: StateFlow<Boolean> = combine(_currentPlayingSong, _activePlayingList, _currentIndex) { current, list, idx ->
        if (current == null) false else idx > 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val canGoNextInContext: StateFlow<Boolean> = combine(_currentPlayingSong, _activePlayingList, _currentIndex) { current, list, idx ->
        if (current == null) false else idx < list.lastIndex
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.musicdlp.ACTION_PLAY_PAUSE" -> togglePlayPause()
                "com.example.musicdlp.ACTION_NEXT" -> playNextInContext()
                "com.example.musicdlp.ACTION_PREVIOUS" -> playPreviousInContext()
                "com.example.musicdlp.ACTION_LIKE" -> {
                    _currentPlayingSong.value?.let { likeSong(it) }
                }
                "com.example.musicdlp.ACTION_DISLIKE" -> {
                    _currentPlayingSong.value?.let { dislikeSong(it, true) }
                }
                "com.example.musicdlp.ACTION_CYCLE_MODE" -> cycleSwipingMode()
                "com.example.musicdlp.ACTION_SET_MODE" -> {
                    val modeStr = intent.getStringExtra("mode") ?: ""
                    val mode = try { SwipingMode.valueOf(modeStr) } catch (e: Exception) { SwipingMode.ONLY_NEW }
                    _swipingMode.value = mode
                }
                "com.example.musicdlp.ACTION_SEARCH_VOICE" -> {
                    val query = intent.getStringExtra("query") ?: ""
                    handleVoiceSearch(query)
                }
                "com.example.musicdlp.ACTION_PLAY_MEDIA_ID" -> {
                    val mediaId = intent.getStringExtra("mediaId") ?: ""
                    handlePlayMediaId(mediaId)
                }
                "com.example.musicdlp.ACTION_QUEUE_CHANGED" -> {
                    val app = getApplication() as MusicDLPApplication
                    val updatedQueue = app.currentQueue
                    val newIndex = intent.getIntExtra(MusicLibraryService.EXTRA_CURRENT_INDEX, 0)
                    _activePlayingList.value = updatedQueue
                    _currentIndex.value = newIndex
                }
            }
        }
    }

    private fun handleVoiceSearch(query: String) {
        setSwipingMode(SwipingMode.NEW_AND_LIKED)
        if (query.isBlank()) return
        val likedMatches = dbLikedSongsFlow.value.filter { song ->
            song.title.contains(query, ignoreCase = true) || song.artist.contains(query, ignoreCase = true)
        }
        if (likedMatches.isNotEmpty()) {
            val matched = likedMatches.first()
            playSongInContext(matched, likedMatches)
        } else {
            searchPlaylists(query)
        }
    }

    private fun handlePlayMediaId(mediaId: String) {
        if (mediaId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val song = songDao.getSongById(mediaId) ?: _activePlayingList.value.firstOrNull { it.id == mediaId }
            if (song != null) {
                withContext(Dispatchers.Main) {
                    playLikedSong(song) { playPreview(song) }
                }
            }
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            val p = getMediaController() ?: return@launch
            if (p.isPlaying) {
                p.pause()
            } else {
                if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED || p.mediaItemCount == 0) {
                    _currentPlayingSong.value?.let { playPreview(it, forceRefreshSource = true) }
                } else {
                    p.play()
                }
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
        val trimmedTitle = newTitle.trim()
        val trimmedArtist = newArtist.trim()

        viewModelScope.launch(Dispatchers.IO) {
            val allSongs = songDao.getAllSongs()
            val matchingLiked = allSongs.firstOrNull {
                it.isLiked &&
                        it.title.equals(trimmedTitle, ignoreCase = true) &&
                        (it.artist.equals(trimmedArtist, ignoreCase = true) || trimmedArtist == "Unknown" || it.artist == "Unknown")
            }
            val matchingDisliked = if (matchingLiked == null) {
                allSongs.firstOrNull {
                    it.isDisliked &&
                            it.title.equals(trimmedTitle, ignoreCase = true) &&
                            (it.artist.equals(trimmedArtist, ignoreCase = true) || trimmedArtist == "Unknown" || it.artist == "Unknown")
                }
            } else null

            val isLiked = matchingLiked != null || song.isLiked
            val isDisliked = !isLiked && (matchingDisliked != null || song.isDisliked)

            if (matchingLiked != null) {
                val updatedLiked = matchingLiked.addAlternateVersion(
                    AlternateVersion(
                        youtubeUrl = song.youtubeUrl,
                        rawTitle = song.rawTitle ?: song.title,
                        thumbnailUrl = song.thumbnailUrl
                    )
                )
                songDao.insertSong(updatedLiked)
            } else if (matchingDisliked != null) {
                val updatedDisliked = matchingDisliked.addAlternateVersion(
                    AlternateVersion(
                        youtubeUrl = song.youtubeUrl,
                        rawTitle = song.rawTitle ?: song.title,
                        thumbnailUrl = song.thumbnailUrl
                    )
                )
                songDao.insertSong(updatedDisliked)
            }

            val updatedSong = song.copy(
                title = trimmedTitle,
                artist = trimmedArtist,
                metadataSource = "manual edit",
                isMetadataCleaned = true,
                isLiked = isLiked,
                isDisliked = isDisliked
            )

            withContext(Dispatchers.Main) {
                _activePlayingList.value = _activePlayingList.value.map { if (it.id == song.id) updatedSong else it }

                val current = _currentPlayingSong.value
                if (current != null && (current.id == song.id || current.title.equals(song.title, ignoreCase = true))) {
                    _currentPlayingSong.value = updatedSong
                    val p = getMediaController()
                    if (p != null) {
                        val currentIndex = p.currentMediaItemIndex
                        if (currentIndex >= 0 && currentIndex < p.mediaItemCount) {
                            val currentPos = p.currentPosition
                            p.replaceMediaItem(
                                currentIndex,
                                updatedSong.toMediaItem(
                                    canGoPrevious = _currentIndex.value > 0,
                                    canGoNext = _currentIndex.value < _activePlayingList.value.lastIndex,
                                    swipingMode = _swipingMode.value.name
                                )
                            )
                            p.seekTo(currentIndex, currentPos)
                        }
                    }
                }
                updateAppQueue()
                _promptForSongName.value = null
            }
        }
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
                val workingSongs = _activePlayingList.value
                val needsProcessing = workingSongs.take(5).filter { it.isMetadataCleaned != true }

                if (needsProcessing.isEmpty() && pendingBufferQueue.isEmpty()) break

                if (needsProcessing.isEmpty() && pendingBufferQueue.isNotEmpty()) {
                    val nextFromQueue = queueMutex.withLock {
                        if (pendingBufferQueue.isNotEmpty()) pendingBufferQueue.removeAt(0) else null
                    }
                    if (nextFromQueue != null) {
                        _activePlayingList.value = workingSongs + nextFromQueue
                        updateAppQueue()
                        continue
                    }
                }

                val songToProcess = needsProcessing.firstOrNull() ?: break

                _isBuffering.value = true
                try {
                    val allSongs = songDao.getAllSongs()
                    val existingByIdOrUrl = allSongs.firstOrNull { it.containsYoutubeUrlOrId(songToProcess.id) || it.containsYoutubeUrlOrId(songToProcess.youtubeUrl) }

                    val cleanResult = repository.cleanTitleAndArtist(songToProcess.title, songToProcess.artist, songToProcess.isrc, songToProcess.youtubeUrl)
                    val finalRawTitle = cleanResult.recoveredRawTitle ?: songToProcess.rawTitle ?: cleanResult.title

                    val matchingExistingLiked = allSongs.firstOrNull {
                        it.isLiked &&
                                it.title.equals(cleanResult.title, ignoreCase = true) &&
                                (it.artist.equals(cleanResult.artist, ignoreCase = true) || cleanResult.artist == "Unknown" || it.artist == "Unknown")
                    }

                    val matchingExistingDisliked = if (matchingExistingLiked == null) {
                        allSongs.firstOrNull {
                            it.isDisliked &&
                                    it.title.equals(cleanResult.title, ignoreCase = true) &&
                                    (it.artist.equals(cleanResult.artist, ignoreCase = true) || cleanResult.artist == "Unknown" || it.artist == "Unknown")
                        }
                    } else null

                    val isLiked = existingByIdOrUrl?.isLiked == true || matchingExistingLiked != null
                    val isDisliked = !isLiked && (existingByIdOrUrl?.isDisliked == true || matchingExistingDisliked != null)

                    val cleanedSong = songToProcess.copy(
                        artist = cleanResult.artist,
                        title = cleanResult.title,
                        rawTitle = if (songToProcess.rawTitle.isNullOrBlank() || songToProcess.rawTitle == "Loading...") finalRawTitle else songToProcess.rawTitle,
                        metadataSource = cleanResult.source,
                        isMetadataCleaned = true,
                        isLiked = isLiked,
                        isDisliked = isDisliked
                    )

                    _activePlayingList.value = _activePlayingList.value.map { if (it.id == songToProcess.id) cleanedSong else it }
                    updateAppQueue()

                    if (_currentlyPlayingId.value == cleanedSong.id || _currentPlayingSong.value?.id == cleanedSong.id) {
                        _currentPlayingSong.value = cleanedSong
                        withContext(Dispatchers.Main) {
                            val p = getMediaController()
                            if (p != null) {
                                val currentIndex = p.currentMediaItemIndex
                                if (currentIndex >= 0 && currentIndex < p.mediaItemCount && p.getMediaItemAt(currentIndex).mediaId == cleanedSong.id) {
                                    val currentPos = p.currentPosition
                                    p.replaceMediaItem(
                                        currentIndex,
                                        cleanedSong.toMediaItem(
                                            canGoPrevious = _currentIndex.value > 0,
                                            canGoNext = _currentIndex.value < _activePlayingList.value.lastIndex,
                                            swipingMode = _swipingMode.value.name
                                        )
                                    )
                                    p.seekTo(currentIndex, currentPos)
                                }
                            }
                        }
                    } else if (_currentlyPlayingId.value == null && _activePlayingList.value.firstOrNull()?.id == cleanedSong.id) {
                        playPreview(cleanedSong)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Napier.e("Failed to process song ${songToProcess.id}: ${e.message}", tag = "DEBUG_METADATA")
                }

                delay(100)
            }
        } finally {
            _isBuffering.value = false
            processMutex.unlock()
        }
    }

    fun skipSong(song: Song) {
        viewModelScope.launch {
            advanceList()
        }
    }

    fun jumpToSongInSwipeList(targetSong: Song) {
        viewModelScope.launch {
            controller?.pause()
            val currentList = _activePlayingList.value
            val index = currentList.indexOfFirst { song ->
                song.id == targetSong.id ||
                        song.containsYoutubeUrlOrId(targetSong.id) ||
                        song.containsYoutubeUrlOrId(targetSong.youtubeUrl) ||
                        (song.title.equals(targetSong.title, ignoreCase = true) && song.artist.equals(targetSong.artist, ignoreCase = true))
            }

            if (index != -1) {
                _currentIndex.value = index
                playPreview(currentList[index])
            } else {
                _activePlayingList.value = listOf(targetSong) + currentList
                _currentIndex.value = 0
                updateAppQueue()
                playPreview(targetSong)
            }
            processQueue()
        }
    }

    private fun showNoMoreSongsPlaceholder() {
        viewModelScope.launch {
            _currentlyPlayingId.value = "no_more_songs"
            val placeholderSong = Song(
                id = "no_more_songs",
                title = "No more songs to swipe",
                artist = "MusicDLP",
                thumbnailUrl = "",
                youtubeUrl = ""
            )
            _currentPlayingSong.value = placeholderSong
            val p = getMediaController()
            if (p != null) {
                p.stop()
                p.clearMediaItems()
            }
        }
    }

    private fun advanceList() {
        controller?.pause()
        playNextInContext()
        viewModelScope.launch { processQueue() }
    }

    fun likeSong(song: Song, advance: Boolean = false) {
        viewModelScope.launch {
            val allSongs = songDao.getAllSongs()

            val matchingDisliked = allSongs.filter {
                it.isDisliked &&
                        (it.id == song.id || (it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true)))
            }
            for (disliked in matchingDisliked) {
                songDao.deleteSongById(disliked.id)
            }

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
                val newLiked = song.copy(isLiked = true, isDisliked = false, likedAt = System.currentTimeMillis())
                saveLikedSong(newLiked)
                newLiked
            }

            val updatedSong = song.copy(isLiked = true, isDisliked = false)
            _activePlayingList.value = _activePlayingList.value.map { if (it.id == song.id) updatedSong else it }

            val current = _currentPlayingSong.value
            if (current != null && (current.id == song.id || (current.title.equals(song.title, ignoreCase = true) && current.artist.equals(song.artist, ignoreCase = true)))) {
                val updatedCurrent = current.copy(isLiked = true, isDisliked = false)
                _currentPlayingSong.value = updatedCurrent
                val p = getMediaController()
                if (p != null) {
                    p.setMediaItem(updatedCurrent.toMediaItem())
                }
            }
            updateAppQueue()

            if (advance && _currentPlayingSong.value?.id == song.id) {
                advanceList()
            }
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

                    val downloadedPath: String = if (song.youtubeUrl.startsWith("content://") || song.youtubeUrl.startsWith("file://") || song.youtubeUrl.startsWith("/")) {
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

    fun dislikeSong(song: Song, advance: Boolean = false) {
        viewModelScope.launch {
            val allSongs = songDao.getAllSongs()

            val matchingLiked = allSongs.filter {
                it.isLiked &&
                        (it.id == song.id || (it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true)))
            }
            for (liked in matchingLiked) {
                val safeArtist = liked.artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                val safeTitle = liked.title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                val finalFileName = "$safeArtist - $safeTitle.mp3"
                val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val downloadDir = File(publicMusicDir, "MusicDLP")
                val targetFile = File(downloadDir, finalFileName)
                if (targetFile.exists()) targetFile.delete()

                songDao.deleteSongById(liked.id)
            }

            val existingDisliked = allSongs.firstOrNull {
                it.isDisliked &&
                        it.title.equals(song.title, ignoreCase = true) &&
                        (it.artist.equals(song.artist, ignoreCase = true) || song.artist == "Unknown" || it.artist == "Unknown")
            }

            val dislikedToRecord = if (existingDisliked != null) {
                val updated = existingDisliked.addAlternateVersion(
                    AlternateVersion(
                        youtubeUrl = song.youtubeUrl,
                        rawTitle = song.rawTitle ?: song.title,
                        thumbnailUrl = song.thumbnailUrl
                    )
                )
                songDao.insertSong(updated)
                updated
            } else {
                val newDisliked = song.copy(isLiked = false, isDisliked = true, dislikedAt = System.currentTimeMillis())
                songDao.insertSong(newDisliked)
                newDisliked
            }

            val updatedSong = song.copy(isLiked = false, isDisliked = true)
            _activePlayingList.value = _activePlayingList.value.map { if (it.id == song.id) updatedSong else it }

            val current = _currentPlayingSong.value
            if (current != null && (current.id == song.id || (current.title.equals(song.title, ignoreCase = true) && current.artist.equals(song.artist, ignoreCase = true)))) {
                val updatedCurrent = current.copy(isLiked = false, isDisliked = true)
                _currentPlayingSong.value = updatedCurrent
                val p = getMediaController()
                if (p != null) {
                    p.setMediaItem(updatedCurrent.toMediaItem())
                }
            }
            updateAppQueue()

            if (advance && _currentPlayingSong.value?.id == song.id) {
                advanceList()
            }
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

    private fun updatePlayingSongWithDbState(song: Song): Song {
        val likedList = dbLikedSongsFlow.value
        val dislikedList = dbDislikedSongsFlow.value

        val isLikedInDb = likedList.any { dbSong ->
            dbSong.id == song.id ||
                    dbSong.containsYoutubeUrlOrId(song.id) ||
                    dbSong.containsYoutubeUrlOrId(song.youtubeUrl) ||
                    (dbSong.title.equals(song.title, ignoreCase = true) &&
                            (dbSong.artist.equals(song.artist, ignoreCase = true) || song.artist == "Unknown" || dbSong.artist == "Unknown"))
        }

        val isDislikedInDb = !isLikedInDb && dislikedList.any { dbSong ->
            dbSong.id == song.id ||
                    dbSong.containsYoutubeUrlOrId(song.id) ||
                    dbSong.containsYoutubeUrlOrId(song.youtubeUrl) ||
                    (dbSong.title.equals(song.title, ignoreCase = true) &&
                            (dbSong.artist.equals(song.artist, ignoreCase = true) || song.artist == "Unknown" || dbSong.artist == "Unknown"))
        }

        return song.copy(
            isLiked = isLikedInDb || song.isLiked,
            isDisliked = isDislikedInDb || (song.isDisliked && !isLikedInDb)
        )
    }

    fun playSong(song: Song, contextList: List<Song> = emptyList(), seekToMs: Long = 0L) {
        viewModelScope.launch {
            var workingSong = song
            if (song.isMetadataCleaned != true) {
                try {
                    val cleanResult = repository.cleanTitleAndArtist(song.title, song.artist, song.isrc, song.youtubeUrl)
                    val finalRawTitle = cleanResult.recoveredRawTitle ?: song.rawTitle ?: cleanResult.title
                    workingSong = song.copy(
                        artist = cleanResult.artist,
                        title = cleanResult.title,
                        rawTitle = if (song.rawTitle.isNullOrBlank() || song.rawTitle == "Loading...") finalRawTitle else song.rawTitle,
                        metadataSource = cleanResult.source,
                        isMetadataCleaned = true
                    )
                    _activePlayingList.value = _activePlayingList.value.map { if (it.id == song.id) workingSong else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {}
            }

            val songWithState = updatePlayingSongWithDbState(workingSong)
            _currentlyPlayingId.value = songWithState.id
            _currentPlayingSong.value = songWithState

            val p = getMediaController() ?: return@launch
            val fullQueue = if (contextList.isNotEmpty()) contextList else _activePlayingList.value
            val actualQueue = if (fullQueue.none { it.id == songWithState.id }) listOf(songWithState) + fullQueue else fullQueue

            val startIndex = actualQueue.indexOfFirst { it.id == songWithState.id }.coerceAtLeast(0)
            _currentIndex.value = startIndex

            val canPrev = getPreviousSongIndex(actualQueue, startIndex, _swipingMode.value) != -1
            val canNext = getNextSongIndex(actualQueue, startIndex, _swipingMode.value) != -1

            val mediaItems = actualQueue.map {
                it.toMediaItem(
                    canGoPrevious = canPrev,
                    canGoNext = canNext,
                    swipingMode = _swipingMode.value.name
                )
            }

            p.setMediaItems(mediaItems, startIndex, seekToMs)
            p.prepare()
            p.play()
        }
    }

    fun playSongInContext(song: Song, contextList: List<Song>, isFromLikedOrDisliked: Boolean = false) {
        viewModelScope.launch {
            app.currentQueue = contextList
            _activePlayingList.value = contextList
            setSwipingMode(SwipingMode.PLAY_ALL_RECATEGORISE)
            playSong(song, contextList)
        }
    }

    fun playNextInContext() {
        val fullQueue = _activePlayingList.value
        val currentSong = _currentPlayingSong.value ?: fullQueue.firstOrNull()
        val currentIndex = if (currentSong != null) fullQueue.indexOfFirst { it.id == currentSong.id } else _currentIndex.value

        val nextIdx = getNextSongIndex(fullQueue, currentIndex, _swipingMode.value)
        if (nextIdx != -1) {
            val nextSong = fullQueue[nextIdx]
            _currentIndex.value = nextIdx
            playSong(nextSong, fullQueue)
        } else {
            showNoMoreSongsPlaceholder()
        }
    }

    fun playPreviousInContext() {
        val fullQueue = _activePlayingList.value
        val currentSong = _currentPlayingSong.value ?: fullQueue.firstOrNull()
        val currentIndex = if (currentSong != null) fullQueue.indexOfFirst { it.id == currentSong.id } else _currentIndex.value

        val prevIdx = getPreviousSongIndex(fullQueue, currentIndex, _swipingMode.value)
        if (prevIdx != -1) {
            val prevSong = fullQueue[prevIdx]
            _currentIndex.value = prevIdx
            playSong(prevSong, fullQueue)
        } else if (currentIndex > 0) {
            val prevSong = fullQueue[currentIndex - 1]
            _currentIndex.value = currentIndex - 1
            playSong(prevSong, fullQueue)
        }
    }

    fun playLikedSong(song: Song, onNotDownloaded: () -> Unit = {}) {
        playSong(song)
    }

    private var previewJob: Job? = null

    fun playPreviewByUrl(youtubeUrl: String, title: String = "Preview") {
        val tempSong = Song(
            id = "preview_${youtubeUrl.hashCode()}",
            title = title,
            artist = "",
            thumbnailUrl = "",
            youtubeUrl = youtubeUrl,
            rawTitle = if (title != "Preview" && title != "Loading...") title else null,
            isMetadataCleaned = false
        )
        playPreview(tempSong)
    }

    fun playPreview(song: Song, forceRefreshSource: Boolean = false, seekToMs: Long = 0L) {
        playSong(song, seekToMs = seekToMs)
    }

    private fun resetPlaybackAndQueue() {
        previewJob?.cancel()
        viewModelScope.launch {
            getMediaController()?.stop()
            getMediaController()?.clearMediaItems()
        }
        _currentlyPlayingId.value = null
        _currentPlayingSong.value = null
        _activePlayingList.value = emptyList()
        updateAppQueue()
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

                prescanAndCategorize(results)

                queueMutex.withLock {
                    pendingBufferQueue.addAll(results)
                }

                processQueue()
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
            resetPlaybackAndQueue()
            queueMutex.withLock { pendingBufferQueue.clear() }
            try {
                val playlistSongs = repository.getPlaylistSongs(url)
                _playlistTotal.value = playlistSongs.size
                processedCount = 0

                prescanAndCategorize(playlistSongs)

                queueMutex.withLock {
                    pendingBufferQueue.addAll(playlistSongs)
                }

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
            resetPlaybackAndQueue()
            queueMutex.withLock { pendingBufferQueue.clear() }

            withContext(Dispatchers.IO) {
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
                        return@withContext
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
                        return@withContext
                    }

                    val localSongs = audioFiles.map { file ->
                        val fileName = file.name ?: "Unknown"
                        val nameWithoutExt = fileName.substringBeforeLast(".")
                        val (extractedYtId, cleanedTitle) = repository.extractYoutubeIdAndCleanName(nameWithoutExt)
                        val uriString = file.uri.toString()
                        val deterministicId = if (!extractedYtId.isNullOrBlank()) extractedYtId else "local_${fileName.lowercase().trim().hashCode()}_${cleanedTitle.lowercase().trim()}"
                        val thumbnail = if (!extractedYtId.isNullOrBlank()) "https://i.ytimg.com/vi/$extractedYtId/hqdefault.jpg" else ""

                        Song(
                            id = deterministicId,
                            title = cleanedTitle,
                            artist = "Unknown",
                            thumbnailUrl = thumbnail,
                            youtubeUrl = uriString,
                            rawTitle = cleanedTitle,
                            isMetadataCleaned = false
                        )
                    }

                    _playlistTotal.value = localSongs.size
                    processedCount = 0

                    prescanAndCategorize(localSongs)

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
            addAction("com.example.musicdlp.ACTION_NEXT")
            addAction("com.example.musicdlp.ACTION_PREVIOUS")
            addAction("com.example.musicdlp.ACTION_LIKE")
            addAction("com.example.musicdlp.ACTION_DISLIKE")
            addAction("com.example.musicdlp.ACTION_CYCLE_MODE")
            addAction("com.example.musicdlp.ACTION_SET_MODE")
            addAction("com.example.musicdlp.ACTION_SEARCH_VOICE")
            addAction("com.example.musicdlp.ACTION_PLAY_MEDIA_ID")
            addAction("com.example.musicdlp.ACTION_QUEUE_CHANGED")
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
        controller?.release()
    }
}