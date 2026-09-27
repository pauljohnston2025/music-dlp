package com.example.musicdlp.service

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.example.musicdlp.MusicDLPApplication
import com.example.musicdlp.R
import com.example.musicdlp.data.AlternateVersion
import com.example.musicdlp.data.Song
import com.example.musicdlp.data.YoutubeDLRepository
import com.example.musicdlp.data.toMediaItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

import com.example.musicdlp.data.SwipingMode

private const val CUSTOM_ACTION_LIKE = "com.example.musicdlp.COMMAND_LIKE"
private const val CUSTOM_ACTION_DISLIKE = "com.example.musicdlp.COMMAND_DISLIKE"
private const val CUSTOM_ACTION_CYCLE_MODE = "com.example.musicdlp.COMMAND_CYCLE_MODE"

@OptIn(UnstableApi::class)
class MusicLibraryService : MediaLibraryService() {

    companion object {
        const val ACTION_PLAY_CONTEXT = "com.example.musicdlp.ACTION_PLAY_CONTEXT"
        const val ACTION_QUEUE_CHANGED = "com.example.musicdlp.ACTION_QUEUE_CHANGED"
        const val ACTION_SET_QUEUE = "com.example.musicdlp.ACTION_SET_QUEUE"
        const val ACTION_LIKE = "com.example.musicdlp.ACTION_LIKE"
        const val ACTION_DISLIKE = "com.example.musicdlp.ACTION_DISLIKE"
        const val ACTION_SET_MODE = "com.example.musicdlp.ACTION_SET_MODE"
        const val ACTION_CYCLE_MODE = "com.example.musicdlp.ACTION_CYCLE_MODE"
        const val ACTION_JUMP_TO_SONG = "com.example.musicdlp.ACTION_JUMP_TO_SONG"
        const val ACTION_UPDATE_SONG = "com.example.musicdlp.ACTION_UPDATE_SONG"
        const val ACTION_CLEAR_QUEUE = "com.example.musicdlp.ACTION_CLEAR_QUEUE"
        const val ACTION_REQUEST_QUEUE_STATE = "com.example.musicdlp.ACTION_REQUEST_QUEUE_STATE"
        const val ACTION_PLAY_PAUSE = "com.example.musicdlp.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.musicdlp.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.example.musicdlp.ACTION_PREVIOUS"

        const val EXTRA_QUEUE_JSON = "extra_queue_json"
        const val EXTRA_SONG_JSON = "extra_song_json"
        const val EXTRA_SONG_ID = "extra_song_id"
        const val EXTRA_START_INDEX = "extra_start_index"
        const val EXTRA_CURRENT_INDEX = "extra_current_index"
        const val EXTRA_SWIPING_MODE = "extra_swiping_mode"
        const val EXTRA_ADVANCE = "extra_advance"
        const val EXTRA_IS_BUFFERING = "extra_is_buffering"
        const val EXTRA_SEEK_TO_MS = "extra_seek_to_ms"
    }

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var forwardingPlayer: ForwardingPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Master Queue State managed exclusively by Service
    private var activeQueue = mutableListOf<Song>()
    private val queueLock = Any()
    private var virtualCurrentIndex: Int? = null

    private var currentMode: SwipingMode = SwipingMode.ONLY_NEW
    private var isProcessingQueue: Boolean = false
    private val processMutex = Mutex()
    private val downloadSemaphore = Semaphore(2)
    private val json = Json { ignoreUnknownKeys = true }

    private val customCommandLike = SessionCommand(CUSTOM_ACTION_LIKE, Bundle.EMPTY)
    private val customCommandDislike = SessionCommand(CUSTOM_ACTION_DISLIKE, Bundle.EMPTY)
    private val customCommandCycleMode = SessionCommand(CUSTOM_ACTION_CYCLE_MODE, Bundle.EMPTY)

    private val searchResultsCache = mutableMapOf<String, List<MediaItem>>()
    private val bufferedStreamUrls = mutableMapOf<String, String>()

    private val serviceCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SET_QUEUE -> {
                    val queueJson = intent.getStringExtra(EXTRA_QUEUE_JSON) ?: return
                    val targetSongId = intent.getStringExtra(EXTRA_SONG_ID)
                    val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
                    val modeStr = intent.getStringExtra(EXTRA_SWIPING_MODE)
                    val seekToMs = intent.getLongExtra(EXTRA_SEEK_TO_MS, 0L)

                    if (!modeStr.isNullOrBlank()) {
                        try { currentMode = SwipingMode.valueOf(modeStr) } catch (e: Exception) {}
                    }

                    try {
                        val parsedQueue = json.decodeFromString<List<Song>>(queueJson)
                        setQueueAndPlay(parsedQueue, targetSongId, startIndex, seekToMs)
                    } catch (e: Exception) {
                        Napier.e("Failed to parse queue JSON in ACTION_SET_QUEUE: ${e.message}", e)
                    }
                }
                ACTION_LIKE -> {
                    val songJson = intent.getStringExtra(EXTRA_SONG_JSON)
                    val advance = intent.getBooleanExtra(EXTRA_ADVANCE, false)
                    val songToLike = if (!songJson.isNullOrBlank()) {
                        try { json.decodeFromString<Song>(songJson) } catch (e: Exception) { null }
                    } else null

                    likeSong(songToLike, advance)
                }
                ACTION_DISLIKE -> {
                    val songJson = intent.getStringExtra(EXTRA_SONG_JSON)
                    val advance = intent.getBooleanExtra(EXTRA_ADVANCE, false)
                    val songToDislike = if (!songJson.isNullOrBlank()) {
                        try { json.decodeFromString<Song>(songJson) } catch (e: Exception) { null }
                    } else null

                    dislikeSong(songToDislike, advance)
                }
                ACTION_SET_MODE -> {
                    val modeStr = intent.getStringExtra("mode") ?: intent.getStringExtra(EXTRA_SWIPING_MODE) ?: SwipingMode.ONLY_NEW.name
                    currentMode = try {
                        SwipingMode.valueOf(modeStr)
                    } catch (e: Exception) {
                        SwipingMode.ONLY_NEW
                    }
                    updateNotificationLayout(exoPlayer.currentMediaItem)
                    broadcastQueueChanged()
                }
                ACTION_CYCLE_MODE -> {
                    currentMode = when (currentMode) {
                        SwipingMode.ONLY_NEW -> SwipingMode.NEW_AND_LIKED
                        SwipingMode.NEW_AND_LIKED -> SwipingMode.PLAY_ALL_RECATEGORISE
                        SwipingMode.PLAY_ALL_RECATEGORISE -> SwipingMode.ONLY_NEW
                    }
                    updateNotificationLayout(exoPlayer.currentMediaItem)
                    broadcastQueueChanged()
                }
                ACTION_JUMP_TO_SONG -> {
                    val songJson = intent.getStringExtra(EXTRA_SONG_JSON)
                    val targetSongId = intent.getStringExtra(EXTRA_SONG_ID)
                    val targetSong = if (!songJson.isNullOrBlank()) {
                        try { json.decodeFromString<Song>(songJson) } catch (e: Exception) { null }
                    } else null

                    jumpToSong(targetSong, targetSongId)
                }
                ACTION_UPDATE_SONG -> {
                    val songJson = intent.getStringExtra(EXTRA_SONG_JSON) ?: return
                    try {
                        val updatedSong = json.decodeFromString<Song>(songJson)
                        updateSongInQueue(updatedSong)
                    } catch (e: Exception) {
                        Napier.e("Failed to update song: ${e.message}", e)
                    }
                }
                ACTION_CLEAR_QUEUE -> {
                    clearQueue()
                }
                ACTION_REQUEST_QUEUE_STATE -> {
                    broadcastQueueChanged()
                }
                ACTION_NEXT -> forwardingPlayer.seekToNextMediaItem()
                ACTION_PREVIOUS -> forwardingPlayer.seekToPreviousMediaItem()
                ACTION_PLAY_PAUSE -> {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(YoutubeDLRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val upstreamFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val resolvingDataSourceFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            val uriStr = uri.toString()

            val isAlreadyPlayable = (uriStr.startsWith("http") && !uriStr.contains("youtube.com") && !uriStr.contains("dummy")) ||
                    uriStr.startsWith("file://") ||
                    uriStr.startsWith("content://")

            if (isAlreadyPlayable) {
                return@Factory dataSpec
            }

            val mediaId = when {
                uri.host == "dummy.local" -> uri.lastPathSegment ?: ""
                uriStr.contains("v=") -> uri.getQueryParameter("v") ?: ""
                uriStr.contains("youtu.be/") -> uri.lastPathSegment ?: ""
                else -> uri.lastPathSegment ?: ""
            }

            if (mediaId.isBlank() || mediaId == "watch" || mediaId.startsWith("MODE_") || mediaId == "no_more_songs") {
                return@Factory dataSpec
            }

            runBlocking(Dispatchers.IO) {
                val app = application as MusicDLPApplication
                val repository = YoutubeDLRepository(app)
                val currentList = synchronized(queueLock) { activeQueue.toList() }
                val dbSong = app.database.songDao().getSongById(mediaId)
                    ?: currentList.firstOrNull { it.id == mediaId }

                val title = dbSong?.title ?: ""
                val artist = dbSong?.artist ?: ""
                val youtubeUrl = dbSong?.youtubeUrl ?: uriStr

                val playableUri = resolvePlayableUri(app, repository, mediaId, youtubeUrl, title, artist)

                if (!playableUri.isNullOrBlank()) {
                    val parsedUri = if (playableUri.startsWith("/")) Uri.fromFile(File(playableUri)) else Uri.parse(playableUri)
                    dataSpec.buildUpon()
                        .setUri(parsedUri)
                        .build()
                } else {
                    dataSpec
                }
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(resolvingDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val currentMediaId = exoPlayer.currentMediaItem?.mediaId
                if (!currentMediaId.isNullOrBlank()) {
                    bufferedStreamUrls.remove(currentMediaId)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    val mediaId = mediaItem.mediaId
                    if (mediaId != "ROOT" && mediaId != "no_more_songs") {
                        broadcastQueueChanged()
                        triggerProcessQueue()
                    }
                }
                updateNotificationLayout(mediaItem)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateNotificationLayout(exoPlayer.currentMediaItem)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                    updateNotificationLayout(player.currentMediaItem)
                    broadcastQueueChanged()
                }
            }
        })

        forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                builder.add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                builder.add(COMMAND_SEEK_TO_PREVIOUS).add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                builder.add(COMMAND_GET_TIMELINE) // shows the "queue" button in android auto
                return builder.build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_GET_TIMELINE -> true
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> hasNextMediaItem()
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> hasPreviousMediaItem()
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                virtualCurrentIndex = null
                super.seekTo(mediaItemIndex, positionMs)
            }

            override fun hasNextMediaItem(): Boolean {
                val currentList = synchronized(queueLock) { activeQueue.toList() }
                val startIdx = virtualCurrentIndex ?: currentMediaItemIndex
                return getNextSongIndex(currentList, startIdx, currentMode) != -1
            }

            override fun hasPreviousMediaItem(): Boolean {
                val currentList = synchronized(queueLock) { activeQueue.toList() }
                val startIdx = virtualCurrentIndex ?: currentMediaItemIndex
                return getPreviousSongIndex(currentList, startIdx, currentMode) != -1
            }

            override fun seekToNext() {
                seekToNextMediaItem()
            }

            override fun seekToNextMediaItem() {
                val currentList = synchronized(queueLock) { activeQueue.toList() }
                val startIdx = virtualCurrentIndex ?: currentMediaItemIndex
                val nextIdx = getNextSongIndex(currentList, startIdx, currentMode)
                if (nextIdx != -1 && nextIdx < mediaItemCount) {
                    virtualCurrentIndex = null
                    seekTo(nextIdx, 0L)
                } else if (nextIdx == -1) {
                    virtualCurrentIndex = currentList.size
                    exoPlayer.pause()
                    broadcastQueueChanged(overrideCurrentIndex = currentList.size)
                }
            }

            override fun seekToPrevious() {
                seekToPreviousMediaItem()
            }

            override fun seekToPreviousMediaItem() {
                val currentList = synchronized(queueLock) { activeQueue.toList() }
                val startIdx = virtualCurrentIndex ?: currentMediaItemIndex
                val prevIdx = getPreviousSongIndex(currentList, startIdx, currentMode)
                if (prevIdx != -1 && prevIdx < mediaItemCount) {
                    virtualCurrentIndex = null
                    seekTo(prevIdx, 0L)
                }
            }
        }

        mediaSession = MediaLibrarySession.Builder(this, forwardingPlayer, LibrarySessionCallback())
            .setCustomLayout(buildCustomLayout(null))
            .setPeriodicPositionUpdateEnabled(false) // stops the android auto "queue" COMMAND_GET_TIMELINE  from bouncing around
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("musicdlp_playback_channel")
                .setChannelName(R.string.app_name)
                .build()
        )

        val filter = IntentFilter().apply {
            addAction(ACTION_SET_QUEUE)
            addAction(ACTION_LIKE)
            addAction(ACTION_DISLIKE)
            addAction(ACTION_SET_MODE)
            addAction(ACTION_CYCLE_MODE)
            addAction(ACTION_JUMP_TO_SONG)
            addAction(ACTION_UPDATE_SONG)
            addAction(ACTION_CLEAR_QUEUE)
            addAction(ACTION_REQUEST_QUEUE_STATE)
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
            addAction(CUSTOM_ACTION_LIKE)
            addAction(CUSTOM_ACTION_DISLIKE)
            addAction(CUSTOM_ACTION_CYCLE_MODE)
        }
        ContextCompat.registerReceiver(this, serviceCommandReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun broadcastQueueChanged(notifyMediaBrowser: Boolean = true, overrideCurrentIndex: Int? = null) {
        val currentList = synchronized(queueLock) { activeQueue.toList() }
        val queueJson = json.encodeToString(currentList)
        val currentIndex = overrideCurrentIndex ?: virtualCurrentIndex ?: (if (exoPlayer.mediaItemCount > 0) exoPlayer.currentMediaItemIndex else 0)
        val currentSongId = exoPlayer.currentMediaItem?.mediaId ?: ""

        val intent = Intent(ACTION_QUEUE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_QUEUE_JSON, queueJson)
            putExtra(EXTRA_CURRENT_INDEX, currentIndex)
            putExtra(EXTRA_SONG_ID, currentSongId)
            putExtra(EXTRA_SWIPING_MODE, currentMode.name)
            putExtra(EXTRA_IS_BUFFERING, isProcessingQueue)
        }
        sendBroadcast(intent)
        if (notifyMediaBrowser) {
            notifyMediaBrowserChildrenChanged()
        }
    }

    private fun notifyMediaBrowserChildrenChanged() {
        val session = mediaSession ?: return

        // Notify ALL folder IDs across the entire tree hierarchy
        val targetFolders = listOf(
            "ROOT",
            "NOW_PLAYING",
            "NOW_PLAYING_LIKED",
            "NOW_PLAYING_DISLIKED",
            "NOW_PLAYING_NEW",
            "NOW_PLAYING_ALL",
            "LIKED",
            "DISLIKED",
            "SETTINGS"
        )

        // Send notifications to default session and all active connected controllers
        for (folderId in targetFolders) {
            session.notifyChildrenChanged(folderId, 0, null)
        }

        for (controller in session.connectedControllers) {
            for (folderId in targetFolders) {
                session.notifyChildrenChanged(controller, folderId, 0, null)
            }
        }
    }

    private fun getFilteredQueueForMode(queue: List<Song>, mode: SwipingMode): List<Song> {
        return when (mode) {
            SwipingMode.ONLY_NEW -> queue.filter { !it.isLiked && !it.isDisliked }
            SwipingMode.NEW_AND_LIKED -> queue.filter { !it.isDisliked }
            SwipingMode.PLAY_ALL_RECATEGORISE -> queue
        }
    }

    private fun setQueueAndPlay(
        queue: List<Song>,
        targetSongId: String?,
        startIndex: Int,
        seekToMs: Long
    ) {
        synchronized(queueLock) {
            activeQueue = queue.toMutableList()
        }

        val playableQueue = getFilteredQueueForMode(queue, currentMode).ifEmpty { queue }

        val targetIndex = if (!targetSongId.isNullOrBlank()) {
            playableQueue.indexOfFirst { it.id == targetSongId }.let { if (it != -1) it else 0 }
        } else {
            startIndex.coerceIn(0, (playableQueue.size - 1).coerceAtLeast(0))
        }

        val mediaItems = playableQueue.map { it.toMediaItem(swipingMode = currentMode.name) }

        exoPlayer.setMediaItems(mediaItems, targetIndex, seekToMs)
        exoPlayer.prepare()
        exoPlayer.play()

        broadcastQueueChanged()
        triggerProcessQueue()
    }

    private fun jumpToSong(song: Song?, songId: String?) {
        val targetId = song?.id ?: songId ?: return
        val currentList = synchronized(queueLock) { activeQueue.toList() }

        var index = currentList.indexOfFirst {
            it.id == targetId || it.containsYoutubeUrlOrId(targetId) ||
                    (song != null && it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true))
        }

        if (index == -1 && song != null) {
            synchronized(queueLock) {
                activeQueue.add(0, song)
            }
            index = 0
            val mediaItems = activeQueue.map { it.toMediaItem(swipingMode = currentMode.name) }
            exoPlayer.setMediaItems(mediaItems, 0, 0L)
        } else if (index != -1) {
            exoPlayer.seekTo(index, 0L)
        }

        exoPlayer.prepare()
        exoPlayer.play()

        broadcastQueueChanged()
        triggerProcessQueue()
    }

    private fun updateSongInQueue(updatedSong: Song) {
        synchronized(queueLock) {
            activeQueue = activeQueue.map { if (it.id == updatedSong.id) updatedSong else it }.toMutableList()
        }

        val idxInPlayer = activeQueue.indexOfFirst { it.id == updatedSong.id }
        if (idxInPlayer >= 0 && idxInPlayer < exoPlayer.mediaItemCount) {
            val currentPos = if (exoPlayer.currentMediaItemIndex == idxInPlayer) exoPlayer.currentPosition else 0L
            val mediaItem = updatedSong.toMediaItem(swipingMode = currentMode.name)
            exoPlayer.replaceMediaItem(idxInPlayer, mediaItem)
            if (exoPlayer.currentMediaItemIndex == idxInPlayer) {
                exoPlayer.seekTo(idxInPlayer, currentPos)
            }
        }

        broadcastQueueChanged()
        triggerProcessQueue()
    }

    private fun clearQueue() {
        synchronized(queueLock) {
            activeQueue.clear()
        }
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        broadcastQueueChanged()
    }

    fun likeSong(targetSong: Song?, advance: Boolean = false) {
        serviceScope.launch(Dispatchers.Main) {
            val currentList = synchronized(queueLock) { activeQueue.toList() }
            val songToLike = targetSong
                ?: currentList.getOrNull(exoPlayer.currentMediaItemIndex)
                ?: currentList.firstOrNull { it.id == exoPlayer.currentMediaItem?.mediaId }
                ?: return@launch

            withContext(Dispatchers.IO) {
                val app = application as MusicDLPApplication
                val songDao = app.database.songDao()
                val allSongs = songDao.getAllSongs()

                val hasValidTitleAndArtist = !songToLike.title.isBlank() &&
                        !songToLike.title.equals("Unknown Title", ignoreCase = true) &&
                        !songToLike.title.equals("Loading...", ignoreCase = true) &&
                        !songToLike.artist.isBlank() &&
                        !songToLike.artist.equals("Unknown", ignoreCase = true)

                val matchingDisliked = allSongs.filter {
                    it.isDisliked && ((it.id.isNotBlank() && it.id == songToLike.id) ||
                            (hasValidTitleAndArtist && it.title.equals(songToLike.title, ignoreCase = true) && it.artist.equals(songToLike.artist, ignoreCase = true)))
                }
                for (disliked in matchingDisliked) {
                    songDao.deleteSongById(disliked.id)
                }

                val existingLiked = if (hasValidTitleAndArtist) {
                    allSongs.firstOrNull {
                        it.isLiked && it.title.equals(songToLike.title, ignoreCase = true) && it.artist.equals(songToLike.artist, ignoreCase = true)
                    }
                } else {
                    allSongs.firstOrNull { it.isLiked && it.id.isNotBlank() && it.id == songToLike.id }
                }

                if (existingLiked != null) {
                    val updated = existingLiked.addAlternateVersion(
                        AlternateVersion(
                            youtubeUrl = songToLike.youtubeUrl,
                            rawTitle = songToLike.rawTitle ?: songToLike.title,
                            thumbnailUrl = songToLike.thumbnailUrl
                        )
                    )
                    songDao.insertSong(updated)
                } else {
                    val newLiked = songToLike.copy(isLiked = true, isDisliked = false, likedAt = System.currentTimeMillis())
                    songDao.insertSong(newLiked)
                    saveLikedSong(newLiked)
                }

                val updatedSong = songToLike.copy(isLiked = true, isDisliked = false)
                synchronized(queueLock) {
                    activeQueue = activeQueue.map { if (it.id == songToLike.id) updatedSong else it }.toMutableList()
                }
            }

            updateNotificationLayout(exoPlayer.currentMediaItem)
            broadcastQueueChanged()
            if (advance) {
                forwardingPlayer.seekToNextMediaItem()
            }
            triggerProcessQueue()
        }
    }

    fun dislikeSong(targetSong: Song?, advance: Boolean = false) {
        serviceScope.launch(Dispatchers.Main) {
            val currentList = synchronized(queueLock) { activeQueue.toList() }
            val songToDislike = targetSong
                ?: currentList.getOrNull(exoPlayer.currentMediaItemIndex)
                ?: currentList.firstOrNull { it.id == exoPlayer.currentMediaItem?.mediaId }
                ?: return@launch

            withContext(Dispatchers.IO) {
                val app = application as MusicDLPApplication
                val songDao = app.database.songDao()
                val allSongs = songDao.getAllSongs()

                val hasValidTitleAndArtist = !songToDislike.title.isBlank() &&
                        !songToDislike.title.equals("Unknown Title", ignoreCase = true) &&
                        !songToDislike.title.equals("Loading...", ignoreCase = true) &&
                        !songToDislike.artist.isBlank() &&
                        !songToDislike.artist.equals("Unknown", ignoreCase = true)

                val matchingLiked = allSongs.filter {
                    it.isLiked && ((it.id.isNotBlank() && it.id == songToDislike.id) ||
                            (hasValidTitleAndArtist && it.title.equals(songToDislike.title, ignoreCase = true) && it.artist.equals(songToDislike.artist, ignoreCase = true)))
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

                val existingDisliked = if (hasValidTitleAndArtist) {
                    allSongs.firstOrNull {
                        it.isDisliked && it.title.equals(songToDislike.title, ignoreCase = true) && it.artist.equals(songToDislike.artist, ignoreCase = true)
                    }
                } else {
                    allSongs.firstOrNull { it.isDisliked && it.id.isNotBlank() && it.id == songToDislike.id }
                }

                if (existingDisliked != null) {
                    val updated = existingDisliked.addAlternateVersion(
                        AlternateVersion(
                            youtubeUrl = songToDislike.youtubeUrl,
                            rawTitle = songToDislike.rawTitle ?: songToDislike.title,
                            thumbnailUrl = songToDislike.thumbnailUrl
                        )
                    )
                    songDao.insertSong(updated)
                } else {
                    val newDisliked = songToDislike.copy(isLiked = false, isDisliked = true, dislikedAt = System.currentTimeMillis())
                    songDao.insertSong(newDisliked)
                }

                val updatedSong = songToDislike.copy(isLiked = false, isDisliked = true)
                synchronized(queueLock) {
                    activeQueue = activeQueue.map { if (it.id == songToDislike.id) updatedSong else it }.toMutableList()
                }
            }

            updateNotificationLayout(exoPlayer.currentMediaItem)
            broadcastQueueChanged()
            if (advance) {
                forwardingPlayer.seekToNextMediaItem()
            }
            triggerProcessQueue()
        }
    }

    private fun saveLikedSong(song: Song) {
        serviceScope.launch(Dispatchers.IO) {
            downloadSemaphore.withPermit {
                try {
                    val app = application as MusicDLPApplication
                    val repository = YoutubeDLRepository(app)

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
                        repository.downloadSong(song.copy(isLiked = true), tempDir) { progress -> }
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
                    resolver.openOutputStream(uri)?.use { outputStream -> fileToInsert.inputStream().use { input -> input.copyTo(outputStream) } }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        details.clear()
                        details.put(MediaStore.Audio.Media.IS_PENDING, 0)
                        resolver.update(uri, details, null, null)
                    }
                    if (fileToInsert.parentFile?.name == "downloads" || fileToInsert.parentFile?.absolutePath == app.cacheDir.absolutePath) fileToInsert.delete()
                } catch (e: Exception) {
                    Napier.e("Failed to download song ${song.title}: ${e.message}", e)
                }
            }
        }
    }

    private fun triggerProcessQueue() {
        serviceScope.launch(Dispatchers.IO) {
            processQueue()
        }
    }

    // Single source of truth for cleaning title/artist metadata over the playing queue
    private suspend fun processQueue() {
        if (!processMutex.tryLock()) return
        try {
            isProcessingQueue = true
            withContext(Dispatchers.Main) { broadcastQueueChanged() }

            while (true) {
                val currentList = synchronized(queueLock) { activeQueue.toList() }
                if (currentList.isEmpty()) break

                val currentIndex = withContext(Dispatchers.Main) {
                    if (exoPlayer.mediaItemCount > 0) exoPlayer.currentMediaItemIndex else 0
                }.coerceAtLeast(0)

                // Find the FIRST uncleaned song anywhere in the queue (not just window)
                val songToProcess = currentList.firstOrNull { it.isMetadataCleaned != true }
                if (songToProcess == null) break // All songs are cleaned!

                val targetIndex = currentList.indexOfFirst { it.id == songToProcess.id }

                try {
                    val app = application as MusicDLPApplication
                    val songDao = app.database.songDao()
                    val repository = YoutubeDLRepository(app)

                    // should probably be selecting by id or name, rather than processing every possible song manually
                    // a problem for a future date when perf becomes a problem (or we cant fit every song into memory)
                    // might need to persist a row per song or something, or persist alternates to their own table so
                    // we can just select onto them
                    val allSongs = songDao.getAllSongs()

                    val cleanResult = repository.cleanTitleAndArtist(
                        songToProcess.title,
                        songToProcess.artist,
                        songToProcess.isrc,
                        songToProcess.youtubeUrl
                    )
                    val finalRawTitle = cleanResult.recoveredRawTitle ?: songToProcess.rawTitle ?: cleanResult.title

                    // 1. Strict ID / URL match first
                    val existingByIdOrUrl = if (songToProcess.id.isNotBlank() || !songToProcess.youtubeUrl.isNullOrBlank()) {
                        allSongs.firstOrNull {
                            (songToProcess.id.isNotBlank() && it.containsYoutubeUrlOrId(songToProcess.id)) ||
                                    (!songToProcess.youtubeUrl.isNullOrBlank() && it.containsYoutubeUrlOrId(songToProcess.youtubeUrl))
                        }
                    } else null

                    // 2. Strict Title + Artist match (ignore "Unknown" fallback false-positives)
                    val hasValidArtist = cleanResult.artist.isNotBlank() && !cleanResult.artist.equals("Unknown", ignoreCase = true)

                    val matchingExistingLiked = if (existingByIdOrUrl == null && hasValidArtist && cleanResult.title.isNotBlank()) {
                        allSongs.firstOrNull {
                            it.isLiked &&
                                    it.title.equals(cleanResult.title, ignoreCase = true) &&
                                    it.artist.equals(cleanResult.artist, ignoreCase = true)
                        }
                    } else null

                    val matchingExistingDisliked = if (existingByIdOrUrl == null && matchingExistingLiked == null && hasValidArtist && cleanResult.title.isNotBlank()) {
                        allSongs.firstOrNull {
                            it.isDisliked &&
                                    it.title.equals(cleanResult.title, ignoreCase = true) &&
                                    it.artist.equals(cleanResult.artist, ignoreCase = true)
                        }
                    } else null

                    // 3. Resolve flags safely
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

                    // Update activeQueue state in thread-safe block
                    synchronized(queueLock) {
                        activeQueue = activeQueue.map { if (it.id == songToProcess.id) cleanedSong else it }.toMutableList()
                    }

                    withContext(Dispatchers.Main) {
                        val idxInPlayer = activeQueue.indexOfFirst { it.id == cleanedSong.id }
                        val isCurrentlyPlaying = exoPlayer.currentMediaItemIndex == idxInPlayer
                        if (isCurrentlyPlaying && idxInPlayer >= 0 && idxInPlayer < exoPlayer.mediaItemCount) {
                            val currentPos = exoPlayer.currentPosition
                            val isPlaying = exoPlayer.isPlaying
                            val mediaItem = cleanedSong.toMediaItem(swipingMode = currentMode.name)
                            exoPlayer.replaceMediaItem(idxInPlayer, mediaItem)
                            exoPlayer.seekTo(idxInPlayer, currentPos)
                            if (isPlaying) exoPlayer.play()
                        }
                        // we must update when name changes, otherwise we will not get the
                        // category (liked/disliked/new) switch (it can make the ui a bit jumpy though)
                        broadcastQueueChanged(notifyMediaBrowser = true)
                    }

                    // PRE-BUFFERING GUARD: Only pre-buffer audio if within 5 tracks ahead
                    val distanceToCurrent = targetIndex - currentIndex
                    if (distanceToCurrent in 1..5) {
                        prebufferNextItem(repository, cleanedSong.toMediaItem())
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Napier.e("Failed to process song ${songToProcess.id}:${e.message}", e)
                }

                delay(100)
            }
        } finally {
            isProcessingQueue = false
            withContext(Dispatchers.Main) {
                // update the ui one final time just incase
                broadcastQueueChanged(notifyMediaBrowser = true)
            }
            processMutex.unlock()
        }
    }

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY_CONTEXT -> {
                val targetSongId = intent.getStringExtra(EXTRA_SONG_ID)
                val currentList = synchronized(queueLock) { activeQueue.toList() }
                val targetIndex = currentList.indexOfFirst { it.id == targetSongId }.coerceAtLeast(0)
                if (currentList.isNotEmpty()) {
                    exoPlayer.seekTo(targetIndex, 0L)
                    exoPlayer.prepare()
                    exoPlayer.play()
                    broadcastQueueChanged()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun resolvePlayableUri(
        app: MusicDLPApplication,
        repository: YoutubeDLRepository,
        songId: String,
        youtubeUrl: String,
        title: String,
        artist: String
    ): String? {
        val safeArtist = artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        if (safeTitle.isNotBlank()) {
            val finalFileName = "$safeArtist - $safeTitle.mp3"
            val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val downloadDir = File(publicMusicDir, "MusicDLP")
            val targetFile = File(downloadDir, finalFileName)
            if (targetFile.exists()) {
                Napier.d("Found downloaded file for $artist - $title: ${targetFile.absolutePath}", tag = "DEBUG_METADATA")
                return targetFile.absolutePath
            }
        }

        if (youtubeUrl.startsWith("content://") || youtubeUrl.startsWith("file://") || youtubeUrl.startsWith("/")) {
            return youtubeUrl
        }

        bufferedStreamUrls[songId]?.let {
            Napier.d("Using cached stream URL for $songId", tag = "DEBUG_METADATA")
            return it
        }

        if (youtubeUrl.startsWith("http://") || youtubeUrl.startsWith("https://")) {
            Napier.d("Fetching stream URL for $title ($youtubeUrl)", tag = "DEBUG_METADATA")
            val url = repository.getStreamUrl(youtubeUrl)
            if (url != null) {
                bufferedStreamUrls[songId] = url
                return url
            }
        }

        return null
    }

    private fun prebufferNextItem(repository: YoutubeDLRepository, item: MediaItem) {
        serviceScope.launch(Dispatchers.IO) {
            val app = application as MusicDLPApplication
            val mediaId = item.mediaId
            if (bufferedStreamUrls.containsKey(mediaId)) return@launch

            val currentList = synchronized(queueLock) { activeQueue.toList() }
            val dbSong = app.database.songDao().getSongById(mediaId)
                ?: currentList.firstOrNull { it.id == mediaId }

            val title = dbSong?.title ?: item.mediaMetadata.title?.toString() ?: ""
            val artist = dbSong?.artist ?: item.mediaMetadata.artist?.toString() ?: ""
            val youtubeUrl = dbSong?.youtubeUrl ?: item.requestMetadata.mediaUri?.toString() ?: item.localConfiguration?.uri?.toString() ?: ""

            val safeArtist = artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
            val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
            if (safeTitle.isNotBlank()) {
                val targetFile = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "MusicDLP"), "$safeArtist - $safeTitle.mp3")
                if (targetFile.exists()) return@launch
            }
            if (youtubeUrl.startsWith("content://") || youtubeUrl.startsWith("file://") || youtubeUrl.startsWith("/")) {
                return@launch
            }

            if (youtubeUrl.startsWith("http://") || youtubeUrl.startsWith("https://")) {
                try {
                    Napier.d("Pre-buffering stream URL for next song: $title", tag = "DEBUG_METADATA")
                    val streamUrl = repository.getStreamUrl(youtubeUrl)
                    if (streamUrl != null) {
                        bufferedStreamUrls[mediaId] = streamUrl
                    }
                } catch (e: Exception) {
                    Napier.w("Pre-buffering failed for $title: ${e.message}", tag = "DEBUG_METADATA")
                }
            }
        }
    }

    private fun buildCustomLayout(mediaItem: MediaItem?): ImmutableList<CommandButton> {
        val extras = mediaItem?.mediaMetadata?.extras
        val isLiked = extras?.getBoolean("isLiked", false) ?: false
        val isDisliked = extras?.getBoolean("isDisliked", false) ?: false

        val likeIcon = if (isLiked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up_outlined
        val dislikeIcon = if (isDisliked) R.drawable.ic_thumb_down_filled else R.drawable.ic_thumb_down_outlined

        val (modeIcon, modeName) = when (currentMode) {
            SwipingMode.NEW_AND_LIKED -> R.drawable.baseline_library_music_24 to "Mode: New & Liked"
            SwipingMode.PLAY_ALL_RECATEGORISE -> R.drawable.baseline_all_inclusive_24 to "Mode: Play All"
            else -> R.drawable.baseline_fiber_new_24 to "Mode: Only New"
        }

        @Suppress("DEPRECATION")
        val likeBtn = CommandButton.Builder()
            .setDisplayName(if (isLiked) "Liked" else "Like")
            .setIconResId(likeIcon)
            .setSessionCommand(customCommandLike)
            .setEnabled(true)
            .build()

        @Suppress("DEPRECATION")
        val dislikeBtn = CommandButton.Builder()
            .setDisplayName(if (isDisliked) "Disliked" else "Dislike")
            .setIconResId(dislikeIcon)
            .setSessionCommand(customCommandDislike)
            .setEnabled(true)
            .build()

        @Suppress("DEPRECATION")
        val cycleBtn = CommandButton.Builder()
            .setDisplayName(modeName)
            .setIconResId(modeIcon)
            .setSessionCommand(customCommandCycleMode)
            .setEnabled(true)
            .build()

        return ImmutableList.of(likeBtn, dislikeBtn, cycleBtn)
    }

    private fun updateNotificationLayout(mediaItem: MediaItem?) {
        val session = mediaSession ?: return
        session.setCustomLayout(buildCustomLayout(mediaItem))

        val updatedCommands = forwardingPlayer.availableCommands
        // Copy the connectedControllers list to avoid ConcurrentModificationException
        val controllers = ArrayList(session.connectedControllers)

        for (controller in controllers) {
            try {
                session.setAvailableCommands(
                    controller,
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(customCommandLike)
                        .add(customCommandDislike)
                        .add(customCommandCycleMode)
                        .build(),
                    updatedCommands
                )
            } catch (e: Exception) {
                // Catches DeadObjectException / RemoteException if a client process died unexpectedly
                Napier.w("Failed to set available commands for ${controller.packageName}:${e.message}", tag = "DEBUG_METADATA")
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        try { unregisterReceiver(serviceCommandReceiver) } catch (e: Exception) {}

        serviceScope.cancel()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }

        searchResultsCache.clear()
        bufferedStreamUrls.clear()

        super.onDestroy()
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(customCommandLike)
                .add(customCommandDislike)
                .add(customCommandCycleMode)
                .build()

            val playerCommands = session.player.availableCommands
            val layout = buildCustomLayout(session.player.currentMediaItem)

            @Suppress("DEPRECATION")
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(layout)
                .build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            Napier.d("Controller disconnected: ${controller.packageName}", tag = "DEBUG_METADATA")
            super.onDisconnected(session, controller)
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val action = customCommand.customAction
            if (action == CUSTOM_ACTION_LIKE || action == ACTION_LIKE) {
                likeSong(null, advance = false)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            } else if (action == CUSTOM_ACTION_DISLIKE || action == ACTION_DISLIKE) {
                dislikeSong(null, advance = false)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            } else if (action == CUSTOM_ACTION_CYCLE_MODE || action == ACTION_CYCLE_MODE) {
                currentMode = when (currentMode) {
                    SwipingMode.ONLY_NEW -> SwipingMode.NEW_AND_LIKED
                    SwipingMode.NEW_AND_LIKED -> SwipingMode.PLAY_ALL_RECATEGORISE
                    SwipingMode.PLAY_ALL_RECATEGORISE -> SwipingMode.ONLY_NEW
                }
                updateNotificationLayout(session.player.currentMediaItem)
                broadcastQueueChanged()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        @Suppress("DEPRECATION")
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootExtras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putBoolean("CONTENT_STYLE_SUPPORTED", true)
                putInt("CONTENT_STYLE_BROWSABLE_HINT", 1)
                putInt("CONTENT_STYLE_PLAYABLE_HINT", 1)
            }

            if (params?.extras != null) {
                rootExtras.putAll(params.extras)
            }

            val libraryParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .build()

            val rootItem = MediaItem.Builder()
                .setMediaId("ROOT")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .setTitle("MusicDLP")
                        .setExtras(rootExtras)
                        .build()
                )
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, libraryParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val settableFuture = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val app = application as MusicDLPApplication
                    val currentList = synchronized(queueLock) { activeQueue.toList() }
                    val children = when (parentId.uppercase()) {
                        "ROOT", "/", "MEDIA_ROOT" -> {
                            listOf(
                                createBrowsableItem("NOW_PLAYING", "Now Playing"),
                                createBrowsableItem("LIKED", "Liked"),
                                createBrowsableItem("DISLIKED", "Disliked"),
                                createBrowsableItem("SETTINGS", "Settings")
                            )
                        }
                        "NOW_PLAYING" -> {
                            val likedList = currentList.filter { it.isLiked }
                            val dislikedList = currentList.filter { it.isDisliked }
                            val newList = currentList.filter { !it.isLiked && !it.isDisliked }

                            listOf(
                                createBrowsableItem("NOW_PLAYING_LIKED", "Now Playing (Liked)", "${likedList.size} songs"),
                                createBrowsableItem("NOW_PLAYING_DISLIKED", "Now Playing (Disliked)", "${dislikedList.size} songs"),
                                createBrowsableItem("NOW_PLAYING_NEW", "Now Playing (New)", "${newList.size} songs"),
                                createBrowsableItem("NOW_PLAYING_ALL", "Now Playing (All)", "${currentList.size} songs")
                            )
                        }
                        "NOW_PLAYING_LIKED" -> {
                            val liked = currentList.filter { it.isLiked }
                            liked.map { it.toMediaItem(parentId = "NOW_PLAYING_LIKED") }
                        }
                        "NOW_PLAYING_DISLIKED" -> {
                            val disliked = currentList.filter { it.isDisliked }
                            disliked.map { it.toMediaItem(parentId = "NOW_PLAYING_DISLIKED") }
                        }
                        "NOW_PLAYING_NEW" -> {
                            val newSongs = currentList.filter { !it.isLiked && !it.isDisliked }
                            newSongs.map { it.toMediaItem(parentId = "NOW_PLAYING_NEW") }
                        }
                        "NOW_PLAYING_ALL" -> {
                            currentList.map { it.toMediaItem(parentId = "NOW_PLAYING_ALL", swipingMode = currentMode.name) }
                        }
                        "SETTINGS" -> {
                            listOf(
                                createPlayableSettingItem("MODE_ONLY_NEW", "Mode: ${SwipingMode.ONLY_NEW.displayName}", isSelected = currentMode == SwipingMode.ONLY_NEW),
                                createPlayableSettingItem("MODE_NEW_AND_LIKED", "Mode: ${SwipingMode.NEW_AND_LIKED.displayName}", isSelected = currentMode == SwipingMode.NEW_AND_LIKED),
                                createPlayableSettingItem("MODE_PLAY_ALL", "Mode: ${SwipingMode.PLAY_ALL_RECATEGORISE.displayName}", isSelected = currentMode == SwipingMode.PLAY_ALL_RECATEGORISE)
                            )
                        }
                        "LIKED" -> {
                            val liked = app.database.songDao().getLikedSongsList()
                            liked.map { it.toMediaItem(parentId = "LIKED") }
                        }
                        "DISLIKED" -> {
                            val disliked = app.database.songDao().getDislikedSongsList()
                            disliked.map { it.toMediaItem(parentId = "DISLIKED") }
                        }
                        else -> emptyList()
                    }

                    val paginatedChildren = if (pageSize > 0 && page >= 0) {
                        val fromIndex = (page * pageSize).coerceAtMost(children.size)
                        val toIndex = (fromIndex + pageSize).coerceAtMost(children.size)
                        children.subList(fromIndex, toIndex)
                    } else {
                        children
                    }

                    val returnParams = params ?: LibraryParams.Builder().setExtras(
                        Bundle().apply {
                            putInt("CONTENT_STYLE_BROWSABLE_HINT", 1)
                            putInt("CONTENT_STYLE_PLAYABLE_HINT", 1)
                        }
                    ).build()

                    settableFuture.set(LibraryResult.ofItemList(ImmutableList.copyOf(paginatedChildren), returnParams))
                } catch (t: Throwable) {
                    Napier.e("onGetChildren failed for parentId=$parentId: ${t.message}", t, tag = "DEBUG_METADATA")
                    settableFuture.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return settableFuture
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Napier.d("onSetMediaItems called for ${mediaItems.size} items", tag = "DEBUG_METADATA")
            val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val app = application as MusicDLPApplication
                    val clickedItem = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()

                    if (clickedItem == null) {
                        settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                        return@launch
                    }

                    val mediaId = clickedItem.mediaId
                    val parentId = clickedItem.requestMetadata.extras?.getString("parentId")?.uppercase() ?: ""

                    if (mediaId.startsWith("MODE_")) {
                        val modeStr = when (mediaId) {
                            "MODE_NEW_AND_LIKED" -> "NEW_AND_LIKED"
                            "MODE_PLAY_ALL" -> "PLAY_ALL_RECATEGORISE"
                            else -> "ONLY_NEW"
                        }
                        currentMode = SwipingMode.valueOf(modeStr)
                        updateNotificationLayout(exoPlayer.currentMediaItem)
                        notifyMediaBrowserChildrenChanged()
                        broadcastQueueChanged()
                        settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                        return@launch
                    }

                    val currentList = synchronized(queueLock) { activeQueue.toList() }

                    val listToPlay = when (parentId) {
                        "LIKED" -> app.database.songDao().getLikedSongsList()
                        "DISLIKED" -> app.database.songDao().getDislikedSongsList()
                        "NOW_PLAYING_LIKED" -> currentList.filter { it.isLiked }.ifEmpty { app.database.songDao().getLikedSongsList() }
                        "NOW_PLAYING_DISLIKED" -> currentList.filter { it.isDisliked }.ifEmpty { app.database.songDao().getDislikedSongsList() }
                        "NOW_PLAYING_NEW" -> currentList.filter { !it.isLiked && !it.isDisliked }
                        "NOW_PLAYING_ALL" -> currentList
                        else -> currentList.ifEmpty {
                            app.database.songDao().getSongById(mediaId)?.let { listOf(it) } ?: emptyList()
                        }
                    }

                    val modeToUse = if (parentId == "NOW_PLAYING_NEW") "ONLY_NEW" else "PLAY_ALL_RECATEGORISE"
                    currentMode = SwipingMode.valueOf(modeToUse)

                    val targetIndex = listToPlay.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
                    val resultItems = listToPlay.map { it.toMediaItem(swipingMode = currentMode.name) }

                    withContext(Dispatchers.Main) {
                        setQueueAndPlay(listToPlay, mediaId, targetIndex, 0L)
                        settableFuture.set(MediaSession.MediaItemsWithStartPosition(resultItems, targetIndex, 0L))
                    }
                } catch (t: Throwable) {
                    Napier.e("onSetMediaItems failed: ${t.message}", t, tag = "DEBUG_METADATA")
                    settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                }
            }
            return settableFuture
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Napier.d("onGetItem called for mediaId: $mediaId", tag = "DEBUG_METADATA")
            val settableFuture = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val app = application as MusicDLPApplication

                    val browsableItem = when (mediaId.uppercase()) {
                        "ROOT", "/", "MEDIA_ROOT" -> createBrowsableItem("ROOT", "MusicDLP")
                        "NOW_PLAYING" -> createBrowsableItem("NOW_PLAYING", "Now Playing")
                        "NOW_PLAYING_LIKED" -> createBrowsableItem("NOW_PLAYING_LIKED", "Liked Songs")
                        "NOW_PLAYING_DISLIKED" -> createBrowsableItem("NOW_PLAYING_DISLIKED", "Disliked Songs")
                        "NOW_PLAYING_NEW" -> createBrowsableItem("NOW_PLAYING_NEW", "New Songs")
                        "NOW_PLAYING_ALL" -> createBrowsableItem("NOW_PLAYING_NEW", "All Songs")
                        "LIKED" -> createBrowsableItem("LIKED", "Liked Songs")
                        "DISLIKED" -> createBrowsableItem("DISLIKED", "Disliked Songs")
                        "SETTINGS" -> createBrowsableItem("SETTINGS", "Settings / Playback Mode")
                        "MODE_ONLY_NEW" -> createPlayableSettingItem("MODE_ONLY_NEW", "Mode: Only Categorise New")
                        "MODE_NEW_AND_LIKED" -> createPlayableSettingItem("MODE_NEW_AND_LIKED", "Mode: New and Liked")
                        "MODE_PLAY_ALL" -> createPlayableSettingItem("MODE_PLAY_ALL", "Mode: Play All / Recateogise")
                        else -> null
                    }

                    if (browsableItem != null) {
                        settableFuture.set(LibraryResult.ofItem(browsableItem, null))
                        return@launch
                    }

                    val currentList = synchronized(queueLock) { activeQueue.toList() }
                    val dbSong = app.database.songDao().getSongById(mediaId)
                        ?: currentList.firstOrNull { it.id == mediaId }

                    if (dbSong != null) {
                        val item = dbSong.toMediaItem(swipingMode = currentMode.name)
                        settableFuture.set(LibraryResult.ofItem(item, null))
                    } else {
                        settableFuture.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                    }
                } catch (t: Throwable) {
                    Napier.e("onGetItem failed for mediaId=$mediaId: ${t.message}", t, tag = "DEBUG_METADATA")
                    settableFuture.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
                }
            }
            return settableFuture
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val app = application as MusicDLPApplication
                    val songDao = app.database.songDao()
                    val repo = YoutubeDLRepository(app)

                    val allDbSongs = songDao.getAllSongs()

                    val likedMatches = allDbSongs.filter {
                        it.isLiked && (it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) || (it.rawTitle?.contains(query, ignoreCase = true) == true))
                    }

                    val dislikedMatches = allDbSongs.filter {
                        it.isDisliked && (it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) || (it.rawTitle?.contains(query, ignoreCase = true) == true))
                    }

                    val onlineResults = repo.searchSongsOrPlaylists(query)

                    val likedOrDislikedIds = (likedMatches + dislikedMatches).map { it.id }.toSet()
                    val filteredOnline = onlineResults.filter { it.id !in likedOrDislikedIds }

                    val combinedResults = likedMatches + dislikedMatches + filteredOnline

                    if (combinedResults.isNotEmpty()) {
                        val mediaItems = combinedResults.map { it.toMediaItem(swipingMode = currentMode.name) }
                        searchResultsCache[query] = mediaItems
                        session.notifySearchResultChanged(browser, query, mediaItems.size, params)

                        withContext(Dispatchers.Main) {
                            setQueueAndPlay(combinedResults, combinedResults.first().id, 0, 0L)
                        }
                    }
                } catch (e: Exception) {
                    Napier.e("onSearch failed: ${e.message}", e, tag = "DEBUG_METADATA")
                }
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val results = searchResultsCache[query] ?: emptyList()
            val returnParams = params ?: LibraryParams.Builder().setExtras(
                Bundle().apply {
                    putInt("CONTENT_STYLE_BROWSABLE_HINT", 1)
                    putInt("CONTENT_STYLE_PLAYABLE_HINT", 1)
                }
            ).build()
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(results), returnParams))
        }

        @Suppress("DEPRECATION")
        private fun createBrowsableItem(id: String, title: String, subtitle: String? = null): MediaItem {
            val itemExtras = Bundle().apply {
                putInt("CONTENT_STYLE_BROWSABLE_HINT", 1)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setExtras(itemExtras)
                        .build()
                )
                .build()
        }

        @Suppress("DEPRECATION")
        private fun createPlayableSettingItem(id: String, title: String, isSelected: Boolean = false): MediaItem {
            val displayTitle = "$title"
            val displaySubtitle = if (isSelected) "● ACTIVE" else "Tap to select"

            val itemExtras = Bundle().apply {
                putInt("CONTENT_STYLE_PLAYABLE_HINT", 1)
                putBoolean("isSelected", isSelected)
                putBoolean("android.media.extra.SELECTED", isSelected)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setUri(Uri.parse("http://dummy_setting/$id"))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                        .setTitle(displayTitle)
                        .setSubtitle(displaySubtitle)
                        .setExtras(itemExtras)
                        .build()
                )
                .build()
        }
    }
}
