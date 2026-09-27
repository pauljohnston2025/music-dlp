package com.example.musicdlp.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.musicdlp.MusicDLPApplication
import com.example.musicdlp.data.AlternateVersion
import com.example.musicdlp.data.SharedQueueHolder
import com.example.musicdlp.data.Song
import com.example.musicdlp.data.SwipingMode
import com.example.musicdlp.data.YoutubeDLRepository
import com.example.musicdlp.service.MusicLibraryService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.yausername.youtubedl_android.YoutubeDL
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.resume

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicDLPApplication
    private val repository = YoutubeDLRepository(application)
    private val songDao = app.database.songDao()
    private val json = Json { ignoreUnknownKeys = true }

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

    // Master list driving current session queue (received from MusicLibraryService)
    private val _activePlayingList = MutableStateFlow<List<Song>>(emptyList())
    val activePlayingList: StateFlow<List<Song>> = _activePlayingList

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _swipingMode = MutableStateFlow(SwipingMode.ONLY_NEW)
    val swipingMode: StateFlow<SwipingMode> = _swipingMode

    private fun isValidTitleAndArtist(song: Song): Boolean {
        return song.title.isNotBlank() &&
                !song.title.equals("Unknown Title", ignoreCase = true) &&
                !song.title.equals("Loading...", ignoreCase = true) &&
                song.artist.isNotBlank() &&
                !song.artist.equals("Unknown", ignoreCase = true)
    }

    // Derived Flows directly linked to DB and active queue
    val playlistLikedSongs: StateFlow<List<Song>> = combine(_activePlayingList, dbLikedSongsFlow) { queue, dbLiked ->
        queue.filter { song ->
            song.isLiked || dbLiked.any { db ->
                (db.id.isNotBlank() && db.id == song.id) ||
                        (isValidTitleAndArtist(song) && isValidTitleAndArtist(db) &&
                                db.title.equals(song.title, ignoreCase = true) &&
                                db.artist.equals(song.artist, ignoreCase = true))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlistDislikedSongs: StateFlow<List<Song>> = combine(_activePlayingList, dbDislikedSongsFlow) { queue, dbDisliked ->
        queue.filter { song ->
            song.isDisliked || dbDisliked.any { db ->
                (db.id.isNotBlank() && db.id == song.id) ||
                        (isValidTitleAndArtist(song) && isValidTitleAndArtist(db) &&
                                db.title.equals(song.title, ignoreCase = true) &&
                                db.artist.equals(song.artist, ignoreCase = true))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlistNewSongs: StateFlow<List<Song>> = combine(_activePlayingList, playlistLikedSongs, playlistDislikedSongs) { queue, liked, disliked ->
        val likedIds = liked.map { it.id }.toSet()
        val dislikedIds = disliked.map { it.id }.toSet()
        queue.filter { song -> song.id !in likedIds && song.id !in dislikedIds }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _playlistTotal = MutableStateFlow(0)
    val playlistTotal: StateFlow<Int> = _playlistTotal

    private val _playlistIndex = MutableStateFlow(0)
    val playlistIndex: StateFlow<Int> = _playlistIndex

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

    val likedSongs = songDao.getLikedSongs()
    val dislikedSongs = songDao.getDislikedSongs()
    val newSongs = songDao.getNewSongs()

    val exoPlayer: Player?
        get() = controller

    // Single receiver for state updates broadcast from MusicLibraryService
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MusicLibraryService.ACTION_QUEUE_CHANGED -> {
                    val queueJson = intent.getStringExtra(MusicLibraryService.EXTRA_QUEUE_JSON)
                    val newIndex = intent.getIntExtra(MusicLibraryService.EXTRA_CURRENT_INDEX, 0)
                    val modeStr = intent.getStringExtra(MusicLibraryService.EXTRA_SWIPING_MODE)
                    val isBuffering = intent.getBooleanExtra(MusicLibraryService.EXTRA_IS_BUFFERING, false)
                    val extraErr = intent.getStringExtra("extra_error")

                    val sharedQueue = SharedQueueHolder.getQueue()
                    if (sharedQueue.isNotEmpty()) {
                        _activePlayingList.value = sharedQueue
                    } else if (!queueJson.isNullOrBlank()) {
                        try {
                            val updatedQueue = json.decodeFromString<List<Song>>(queueJson)
                            _activePlayingList.value = updatedQueue
                        } catch (e: Exception) {
                            Napier.e("Failed to parse queue JSON in ACTION_QUEUE_CHANGED: ${e.message}", e)
                        }
                    }
                    _currentIndex.value = newIndex
                    if (!modeStr.isNullOrBlank()) {
                        try { _swipingMode.value = SwipingMode.valueOf(modeStr) } catch (e: Exception) {}
                    }
                    _isBuffering.value = isBuffering
                    if (!extraErr.isNullOrBlank()) {
                        _errorMessage.value = extraErr
                    }

                    val currentList = _activePlayingList.value
                    if (newIndex in currentList.indices) {
                        val currentSong = currentList[newIndex]
                        _currentlyPlayingId.value = currentSong.id
                        _currentPlayingSong.value = currentSong
                    } else {
                        _currentlyPlayingId.value = null
                        _currentPlayingSong.value = null
                    }
                }
            }
        }
    }

    init {
        val sessionToken = SessionToken(application, ComponentName(application, MusicLibraryService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            setupControllerListener()
        }, MoreExecutors.directExecutor())

        val filter = IntentFilter().apply {
            addAction(MusicLibraryService.ACTION_QUEUE_CHANGED)
        }
        ContextCompat.registerReceiver(application, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val prefs = application.getSharedPreferences("musicdlp_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        _geminiApiKey.value = savedKey
        repository.geminiApiKey = savedKey

        viewModelScope.launch(Dispatchers.IO) {
            try { YoutubeDL.getInstance().updateYoutubeDL(application) } catch (e: Exception) {}
        }

        // Request initial state from service
        val requestIntent = Intent(MusicLibraryService.ACTION_REQUEST_QUEUE_STATE).apply {
            setPackage(app.packageName)
        }
        app.sendBroadcast(requestIntent)
    }

    private fun setupControllerListener() {
        controller?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId ?: return
                if (mediaId == "no_more_songs" || mediaId == "ROOT") return

                val currentList = _activePlayingList.value
                val newIdx = currentList.indexOfFirst { it.id == mediaId }
                if (newIdx != -1) {
                    _currentIndex.value = newIdx
                    _currentlyPlayingId.value = mediaId
                    _currentPlayingSong.value = currentList[newIdx]
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Napier.e("Player error: ${error.message}", tag = "DEBUG_METADATA")
                _isSongLoading.value = false
                val streamErr = repository.lastStreamError
                val causeMsg = error.cause?.message
                val detailedMsg = when {
                    !streamErr.isNullOrBlank() -> streamErr
                    !causeMsg.isNullOrBlank() && causeMsg.contains("UnrecognizedInputFormatException") ->
                        "Failed to parse audio stream. Check YouTube link or bot verification."
                    !causeMsg.isNullOrBlank() -> causeMsg
                    else -> "Playback error: ${error.message}"
                }
                _errorMessage.value = detailedMsg
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

    fun cycleSwipingMode() {
        val intent = Intent(MusicLibraryService.ACTION_CYCLE_MODE).apply {
            setPackage(app.packageName)
        }
        app.sendBroadcast(intent)
    }

    fun setSwipingMode(mode: SwipingMode) {
        _swipingMode.value = mode
        val intent = Intent(MusicLibraryService.ACTION_SET_MODE).apply {
            setPackage(app.packageName)
            putExtra("mode", mode.name)
        }
        app.sendBroadcast(intent)
    }

    fun likeSong(song: Song, advance: Boolean = false) {
        val intent = Intent(MusicLibraryService.ACTION_LIKE).apply {
            setPackage(app.packageName)
            putExtra(MusicLibraryService.EXTRA_SONG_JSON, json.encodeToString(song))
            putExtra(MusicLibraryService.EXTRA_ADVANCE, advance)
        }
        app.sendBroadcast(intent)
    }

    fun dislikeSong(song: Song, advance: Boolean = false) {
        val intent = Intent(MusicLibraryService.ACTION_DISLIKE).apply {
            setPackage(app.packageName)
            putExtra(MusicLibraryService.EXTRA_SONG_JSON, json.encodeToString(song))
            putExtra(MusicLibraryService.EXTRA_ADVANCE, advance)
        }
        app.sendBroadcast(intent)
    }

    fun playSong(song: Song, contextList: List<Song> = emptyList(), seekToMs: Long = 0L) {
        val fullQueue = if (contextList.isNotEmpty()) contextList else _activePlayingList.value
        val actualQueue = if (fullQueue.none { it.id == song.id }) listOf(song) + fullQueue else fullQueue

        val intent = Intent(MusicLibraryService.ACTION_SET_QUEUE).apply {
            setPackage(app.packageName)
            putExtra(MusicLibraryService.EXTRA_QUEUE_JSON, json.encodeToString(actualQueue))
            putExtra(MusicLibraryService.EXTRA_SONG_ID, song.id)
            putExtra(MusicLibraryService.EXTRA_SEEK_TO_MS, seekToMs)
        }
        app.sendBroadcast(intent)
    }

    fun playSongInContext(song: Song, contextList: List<Song>, isFromLikedOrDisliked: Boolean = false) {
        val mode = if (isFromLikedOrDisliked) SwipingMode.PLAY_ALL_RECATEGORISE else SwipingMode.ONLY_NEW
        val intent = Intent(MusicLibraryService.ACTION_SET_QUEUE).apply {
            setPackage(app.packageName)
            putExtra(MusicLibraryService.EXTRA_QUEUE_JSON, json.encodeToString(contextList))
            putExtra(MusicLibraryService.EXTRA_SONG_ID, song.id)
            putExtra(MusicLibraryService.EXTRA_SWIPING_MODE, mode.name)
        }
        app.sendBroadcast(intent)
    }

    fun jumpToSongInSwipeList(targetSong: Song) {
        val intent = Intent(MusicLibraryService.ACTION_JUMP_TO_SONG).apply {
            setPackage(app.packageName)
            putExtra(MusicLibraryService.EXTRA_SONG_JSON, json.encodeToString(targetSong))
        }
        app.sendBroadcast(intent)
    }

    fun playNextInContext() {
        viewModelScope.launch {
            val p = getMediaController()
            if (p != null) {
                p.seekToNextMediaItem()
            } else {
                val intent = Intent(MusicLibraryService.ACTION_NEXT).apply {
                    setPackage(app.packageName)
                }
                app.sendBroadcast(intent)
            }
        }
    }

    fun playPreviousInContext() {
        viewModelScope.launch {
            val p = getMediaController()
            if (p != null) {
                p.seekToPreviousMediaItem()
            } else {
                val intent = Intent(MusicLibraryService.ACTION_PREVIOUS).apply {
                    setPackage(app.packageName)
                }
                app.sendBroadcast(intent)
            }
        }
    }

    fun playLikedSong(song: Song, onNotDownloaded: () -> Unit = {}) {
        playSong(song)
    }

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

    fun skipSong(song: Song) {
        playNextInContext()
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

    fun searchPlaylists(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isPlaylistLoading.value = true
            try {
                val allDbSongs = songDao.getAllSongs()

                val likedMatches = allDbSongs.filter {
                    it.isLiked && (it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) || (it.rawTitle?.contains(query, ignoreCase = true) == true))
                }

                val dislikedMatches = allDbSongs.filter {
                    it.isDisliked && (it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) || (it.rawTitle?.contains(query, ignoreCase = true) == true))
                }

                val onlineResults = repository.searchSongsOrPlaylists(query)

                val likedOrDislikedIds = (likedMatches + dislikedMatches).map { it.id }.toSet()
                val filteredOnline = onlineResults.filter { it.id !in likedOrDislikedIds }

                val combinedResults = likedMatches + dislikedMatches + filteredOnline
                _playlistTotal.value = combinedResults.size

                val intent = Intent(MusicLibraryService.ACTION_SET_QUEUE).apply {
                    setPackage(app.packageName)
                    putExtra(MusicLibraryService.EXTRA_QUEUE_JSON, json.encodeToString(combinedResults))
                    putExtra(MusicLibraryService.EXTRA_START_INDEX, 0)
                }
                app.sendBroadcast(intent)
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
            try {
                val playlistSongs = repository.getPlaylistSongs(url)
                _playlistTotal.value = playlistSongs.size

                val intent = Intent(MusicLibraryService.ACTION_SET_QUEUE).apply {
                    setPackage(app.packageName)
                    putExtra(MusicLibraryService.EXTRA_QUEUE_JSON, json.encodeToString(playlistSongs))
                    putExtra(MusicLibraryService.EXTRA_START_INDEX, 0)
                }
                app.sendBroadcast(intent)
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
                    if (audioFiles.isEmpty()) {
                        _errorMessage.value = "No audio files found in selected folder."
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

                    val intent = Intent(MusicLibraryService.ACTION_SET_QUEUE).apply {
                        setPackage(app.packageName)
                        putExtra(MusicLibraryService.EXTRA_QUEUE_JSON, json.encodeToString(localSongs))
                        putExtra(MusicLibraryService.EXTRA_START_INDEX, 0)
                    }
                    app.sendBroadcast(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    _errorMessage.value = "Failed to scan local folder: ${e.message}"
                } finally {
                    _isPlaylistLoading.value = false
                }
            }
        }
    }

    fun updateSongNameAndArtist(song: Song, newTitle: String, newArtist: String) {
        val trimmedTitle = newTitle.trim()
        val trimmedArtist = newArtist.trim()

        viewModelScope.launch(Dispatchers.IO) {
            val allSongs = songDao.getAllSongs()
            val hasValidTrimmed = trimmedTitle.isNotBlank() &&
                    !trimmedTitle.equals("Unknown Title", ignoreCase = true) &&
                    !trimmedTitle.equals("Loading...", ignoreCase = true) &&
                    trimmedArtist.isNotBlank() &&
                    !trimmedArtist.equals("Unknown", ignoreCase = true)

            if (!hasValidTrimmed) return@launch

            // 1. Check if another song in DB matches this new title & artist
            val matchingOtherSong = allSongs.firstOrNull {
                it.id != song.id &&
                        it.title.equals(trimmedTitle, ignoreCase = true) &&
                        it.artist.equals(trimmedArtist, ignoreCase = true)
            }

            val finalSongToBroadcast: Song

            if (matchingOtherSong != null) {
                // Merge `song` as an alternate version on `matchingOtherSong`
                val alternate = AlternateVersion(
                    youtubeUrl = song.youtubeUrl,
                    rawTitle = song.rawTitle ?: song.title,
                    thumbnailUrl = song.thumbnailUrl
                )
                val updatedOther = matchingOtherSong.addAlternateVersion(alternate).copy(
                    updatedAt = System.currentTimeMillis()
                )
                songDao.insertSong(updatedOther)

                // If `song` existed as its own standalone row in DB, delete it
                if (allSongs.any { it.id == song.id }) {
                    songDao.deleteSongById(song.id)
                }

                // Clean up song from any other parent song's alternate list
                allSongs.filter { it.id != matchingOtherSong.id && it.containsYoutubeUrlOrId(song.youtubeUrl) }.forEach { parent ->
                    val cleanList = parent.getAlternateVersionsList().filter { it.youtubeUrl != song.youtubeUrl }
                    songDao.insertSong(parent.copy(alternateYoutubeUrls = json.encodeToString(cleanList), updatedAt = System.currentTimeMillis()))
                }

                finalSongToBroadcast = updatedOther
            } else {
                // No existing song matches this name.
                // If song was previously an alternate on a parent song, pull it out as its own thing!
                allSongs.filter { it.id != song.id && it.containsYoutubeUrlOrId(song.youtubeUrl) }.forEach { parent ->
                    val cleanList = parent.getAlternateVersionsList().filter { it.youtubeUrl != song.youtubeUrl }
                    songDao.insertSong(parent.copy(alternateYoutubeUrls = json.encodeToString(cleanList), updatedAt = System.currentTimeMillis()))
                }

                val updatedSong = song.copy(
                    title = trimmedTitle,
                    artist = trimmedArtist,
                    metadataSource = "manual edit",
                    isMetadataCleaned = true,
                    updatedAt = System.currentTimeMillis()
                )

                songDao.insertSong(updatedSong)
                finalSongToBroadcast = updatedSong
            }

            val intent = Intent(MusicLibraryService.ACTION_UPDATE_SONG).apply {
                setPackage(app.packageName)
                putExtra(MusicLibraryService.EXTRA_SONG_JSON, json.encodeToString(finalSongToBroadcast))
            }
            app.sendBroadcast(intent)

            withContext(Dispatchers.Main) {
                _promptForSongName.value = null
            }
        }
    }

    suspend fun isAlternateVersionInDb(song: Song): Boolean = withContext(Dispatchers.IO) {
        val allSongs = songDao.getAllSongs()
        val primaryMatch = allSongs.firstOrNull { it.id == song.id }
        if (primaryMatch != null) return@withContext false
        return@withContext allSongs.any { db ->
            db.id != song.id && (
                db.containsAlternateId(song.youtubeUrl)
            )
        }
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

    private val prefs = application.getSharedPreferences("musicdlp_prefs", Context.MODE_PRIVATE)

    private val _hasUserSwiped = MutableStateFlow(prefs.getBoolean("has_user_swiped", false))
    val hasUserSwiped: StateFlow<Boolean> = _hasUserSwiped

    fun setHasUserSwiped() {
        if (!_hasUserSwiped.value) {
            _hasUserSwiped.value = true
            prefs.edit().putBoolean("has_user_swiped", true).apply()
        }
    }

    fun retryDownload(song: Song) {
        likeSong(song, advance = false)
    }

    fun replaceLikedSongWithAlternateVersion(
        targetSong: Song,
        selectedVersion: AlternateVersion
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isSongLoading.value = true
            _errorMessage.value = "Downloading alternate version for ${targetSong.title}..."
            try {
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
                    alternateYoutubeUrls = json.encodeToString(currentAlternates)
                )

                songDao.insertSong(updatedSong)
                likeSong(updatedSong, advance = false)
                _errorMessage.value = "Replaced ${targetSong.title} with alternate version!"
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Failed to replace alternate version: ${e.message}"
            } finally {
                _isSongLoading.value = false
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

    override fun onCleared() {
        try { app.unregisterReceiver(notificationReceiver) } catch (e: Exception) {}
        controller?.release()
    }
}
