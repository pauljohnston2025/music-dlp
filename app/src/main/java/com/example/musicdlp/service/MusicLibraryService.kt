package com.example.musicdlp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DefaultDataSource
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
import com.example.musicdlp.data.Song
import com.example.musicdlp.data.YoutubeDLRepository
import com.example.musicdlp.data.toMediaItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val CUSTOM_ACTION_LIKE = "com.example.musicdlp.COMMAND_LIKE"
private const val CUSTOM_ACTION_DISLIKE = "com.example.musicdlp.COMMAND_DISLIKE"
private const val CUSTOM_ACTION_CYCLE_MODE = "com.example.musicdlp.COMMAND_CYCLE_MODE"

@OptIn(UnstableApi::class)
class MusicLibraryService : MediaLibraryService() {

    companion object {
        const val ACTION_PLAY_CONTEXT = "com.example.musicdlp.ACTION_PLAY_CONTEXT"
        const val EXTRA_SONG_ID = "extra_song_id"
        const val EXTRA_QUEUE = "extra_queue"
        const val EXTRA_CURRENT_INDEX = "extra_current_index"
    }

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var forwardingPlayer: ForwardingPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val customCommandLike = SessionCommand(CUSTOM_ACTION_LIKE, Bundle.EMPTY)
    private val customCommandDislike = SessionCommand(CUSTOM_ACTION_DISLIKE, Bundle.EMPTY)
    private val customCommandCycleMode = SessionCommand(CUSTOM_ACTION_CYCLE_MODE, Bundle.EMPTY)

    private val playerListeners = mutableSetOf<Player.Listener>()
    private val searchResultsCache = mutableMapOf<String, List<MediaItem>>()

    // Service-level cache of resolved YouTube audio stream URLs
    private val bufferedStreamUrls = mutableMapOf<String, String>()

    private val queueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.musicdlp.ACTION_QUEUE_CHANGED") {
                val app = application as? MusicDLPApplication ?: return
                val queue = app.currentQueue
                val session = mediaSession ?: return
                session.notifyChildrenChanged("NOW_PLAYING", queue.size, null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val upstreamFactory = DefaultDataSource.Factory(this)

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
                val dbSong = app.database.songDao().getSongById(mediaId)
                    ?: app.currentQueue.firstOrNull { it.id == mediaId }

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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    val mediaId = mediaItem.mediaId
                    if (mediaId != "ROOT" && mediaId != "no_more_songs") {
                        val currentIndex = exoPlayer.currentMediaItemIndex

                        // Broadcast the index change so the ViewModel / TinderCard can stay in sync
                        val intent = Intent("com.example.musicdlp.ACTION_QUEUE_CHANGED").apply {
                            setPackage(packageName)
                            putExtra(EXTRA_CURRENT_INDEX, currentIndex)
                            putExtra(EXTRA_SONG_ID, mediaItem.mediaId)
                        }
                        sendBroadcast(intent)

                        // Pre-buffering logic...
                        val nextIndex = currentIndex + 1
                        if (nextIndex < exoPlayer.mediaItemCount) {
                            val app = application as MusicDLPApplication
                            val repository = YoutubeDLRepository(app)
                            val nextItem = exoPlayer.getMediaItemAt(nextIndex)
                            prebufferNextItem(repository, nextItem)
                        }
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
                }
            }
        })

        forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            // FIX 1: Delegate listeners properly to super/exoPlayer so MediaSession receives timeline updates
            override fun addListener(listener: Player.Listener) {
                super.addListener(listener)
            }

            override fun removeListener(listener: Player.Listener) {
                super.removeListener(listener)
            }

            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                builder.add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                builder.add(COMMAND_SEEK_TO_PREVIOUS).add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                return builder.build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> hasPreviousMediaItem()
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean {
                return true
            }

            override fun hasPreviousMediaItem(): Boolean {
                val extras = currentMediaItem?.mediaMetadata?.extras
                return extras?.getBoolean("canGoPrevious", false) ?: super.hasPreviousMediaItem()
            }

            // FIX 2: Explicitly override seek functions for media controller integration
            override fun seekToNext() {
                if (super.hasNextMediaItem()) {
                    super.seekToNext()
                } else {
                    seekToNextMediaItem()
                }
            }

            override fun seekToNextMediaItem() {
                if (super.hasNextMediaItem()) {
                    super.seekToNextMediaItem()
                }
            }
        }

        mediaSession = MediaLibrarySession.Builder(this, forwardingPlayer, LibrarySessionCallback())
            .setCustomLayout(buildCustomLayout(null))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("musicdlp_playback_channel")
                .setChannelName(R.string.app_name)
                .build()
        )

        val filter = IntentFilter("com.example.musicdlp.ACTION_QUEUE_CHANGED")
        ContextCompat.registerReceiver(this, queueReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY_CONTEXT -> {
                val targetSongId = intent.getStringExtra(EXTRA_SONG_ID)
                val app = application as? MusicDLPApplication ?: return START_STICKY

                val queue = app.currentQueue
                val targetIndex = queue.indexOfFirst { it.id == targetSongId }.coerceAtLeast(0)

                playQueueIndex(targetIndex)
            }
        }
        return START_STICKY
    }

    private fun playQueueIndex(index: Int) {
        val app = application as? MusicDLPApplication ?: return
        val queue = app.currentQueue
        if (queue.isEmpty()) return

        val safeIndex = index.coerceIn(0, queue.size - 1)
        val mediaItems = queue.map { it.toMediaItem() }

        exoPlayer.setMediaItems(mediaItems, safeIndex, C.TIME_UNSET)
        exoPlayer.prepare()
        exoPlayer.play()

        val intent = Intent("com.example.musicdlp.ACTION_QUEUE_CHANGED").apply {
            setPackage(packageName)
            putExtra(EXTRA_CURRENT_INDEX, exoPlayer.currentMediaItemIndex)
        }
        sendBroadcast(intent)
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

            val dbSong = app.database.songDao().getSongById(mediaId)
                ?: app.currentQueue.firstOrNull { it.id == mediaId }

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
        val swipingMode = extras?.getString("swipingMode") ?: "ONLY_NEW"

        val likeIcon = if (isLiked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up_outlined
        val dislikeIcon = if (isDisliked) R.drawable.ic_thumb_down_filled else R.drawable.ic_thumb_down_outlined

        val (modeIcon, modeName) = when (swipingMode) {
            "NEW_AND_LIKED" -> R.drawable.baseline_library_music_24 to "Mode: New & Liked"
            "PLAY_ALL_RECATEGORISE" -> R.drawable.baseline_all_inclusive_24 to "Mode: Play All"
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
        for (controller in session.connectedControllers) {
            session.setAvailableCommands(
                controller,
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(customCommandLike)
                    .add(customCommandDislike)
                    .add(customCommandCycleMode)
                    .build(),
                updatedCommands
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        try { unregisterReceiver(queueReceiver) } catch (e: Exception) {}

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
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(customCommandLike)
                .add(customCommandDislike)
                .add(customCommandCycleMode)
                .build()

            val playerCommands = session.player.availableCommands
            val layout = buildCustomLayout(session.player.currentMediaItem)

            @Suppress("DEPRECATION")
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(layout)
                .build()
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CUSTOM_ACTION_LIKE) {
                val intent = Intent("com.example.musicdlp.ACTION_LIKE").apply { setPackage(packageName) }
                sendBroadcast(intent)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            } else if (customCommand.customAction == CUSTOM_ACTION_DISLIKE) {
                val intent = Intent("com.example.musicdlp.ACTION_DISLIKE").apply { setPackage(packageName) }
                sendBroadcast(intent)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            } else if (customCommand.customAction == CUSTOM_ACTION_CYCLE_MODE) {
                val intent = Intent("com.example.musicdlp.ACTION_CYCLE_MODE").apply { setPackage(packageName) }
                sendBroadcast(intent)
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
                    val children = when (parentId.uppercase()) {
                        "ROOT", "/", "MEDIA_ROOT" -> {
                            listOf(
                                createBrowsableItem("NOW_PLAYING", "Now Playing / Swipe List"),
                                createBrowsableItem("LIKED", "Liked Songs"),
                                createBrowsableItem("DISLIKED", "Disliked Songs"),
                                createBrowsableItem("SETTINGS", "Settings / Playback Mode")
                            )
                        }
                        "NOW_PLAYING" -> {
                            val queue = app.currentQueue
                            val likedList = queue.filter { it.isLiked }
                            val dislikedList = queue.filter { it.isDisliked }
                            val newList = queue.filter { !it.isLiked && !it.isDisliked }

                            listOf(
                                createBrowsableItem("NOW_PLAYING_LIKED", "Liked (${likedList.size})"),
                                createBrowsableItem("NOW_PLAYING_DISLIKED", "Disliked (${dislikedList.size})"),
                                createBrowsableItem("NOW_PLAYING_NEW", "New (${newList.size})"),
                                createBrowsableItem("NOW_PLAYING_ALL", "All Songs (${queue.size})")
                            )
                        }
                        "NOW_PLAYING_LIKED" -> {
                            val queue = app.currentQueue
                            val liked = queue.filter { it.isLiked }
                            val listToUse = if (liked.isNotEmpty()) liked else app.playlistLikedSongs
                            listToUse.map { it.toMediaItem(parentId = "NOW_PLAYING_LIKED") }
                        }
                        "NOW_PLAYING_DISLIKED" -> {
                            val queue = app.currentQueue
                            val disliked = queue.filter { it.isDisliked }
                            val listToUse = if (disliked.isNotEmpty()) disliked else app.playlistDislikedSongs
                            listToUse.map { it.toMediaItem(parentId = "NOW_PLAYING_DISLIKED") }
                        }
                        "NOW_PLAYING_NEW" -> {
                            val queue = app.currentQueue
                            val newSongs = queue.filter { !it.isLiked && !it.isDisliked }
                            newSongs.map { it.toMediaItem(parentId = "NOW_PLAYING_NEW") }
                        }
                        "SETTINGS" -> {
                            listOf(
                                createPlayableSettingItem("MODE_ONLY_NEW", "Mode: Only Categorise New"),
                                createPlayableSettingItem("MODE_NEW_AND_LIKED", "Mode: New and Liked"),
                                createPlayableSettingItem("MODE_PLAY_ALL", "Mode: Play All / Recategorise")
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

                    val returnParams = params ?: LibraryParams.Builder().setExtras(
                        Bundle().apply {
                            putInt("CONTENT_STYLE_BROWSABLE_HINT", 1)
                            putInt("CONTENT_STYLE_PLAYABLE_HINT", 1)
                        }
                    ).build()

                    settableFuture.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), returnParams))
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
                        sendBroadcast(Intent("com.example.musicdlp.ACTION_SET_MODE").apply {
                            setPackage(packageName)
                            putExtra("mode", modeStr)
                        })
                        settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                        return@launch
                    }

                    val queue = app.currentQueue

                    val result = when (parentId) {
                        "LIKED" -> {
                            val list = app.database.songDao().getLikedSongsList()
                            playSongInContext(mediaId, list, "PLAY_ALL_RECATEGORISE")
                        }
                        "DISLIKED" -> {
                            val list = app.database.songDao().getDislikedSongsList()
                            playSongInContext(mediaId, list, "PLAY_ALL_RECATEGORISE")
                        }
                        "NOW_PLAYING_LIKED" -> {
                            val list = queue.filter { it.isLiked }.ifEmpty { app.playlistLikedSongs }
                            playSongInContext(mediaId, list, "PLAY_ALL_RECATEGORISE")
                        }
                        "NOW_PLAYING_DISLIKED" -> {
                            val list = queue.filter { it.isDisliked }.ifEmpty { app.playlistDislikedSongs }
                            playSongInContext(mediaId, list, "PLAY_ALL_RECATEGORISE")
                        }
                        "NOW_PLAYING_NEW" -> {
                            val list = queue.filter { !it.isLiked && !it.isDisliked }
                            playSongInContext(mediaId, list, "ONLY_NEW")
                        }
                        else -> {
                            val fallbackList = queue.ifEmpty {
                                app.database.songDao().getSongById(mediaId)?.let { listOf(it) } ?: emptyList()
                            }
                            playSongInContext(mediaId, fallbackList, "ONLY_NEW")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        settableFuture.set(result)
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
                        "LIKED" -> createBrowsableItem("LIKED", "Liked Songs")
                        "DISLIKED" -> createBrowsableItem("DISLIKED", "Disliked Songs")
                        "SETTINGS" -> createBrowsableItem("SETTINGS", "Settings / Playback Mode")
                        "MODE_ONLY_NEW" -> createPlayableSettingItem("MODE_ONLY_NEW", "Mode: Only Categorise New")
                        "MODE_NEW_AND_LIKED" -> createPlayableSettingItem("MODE_NEW_AND_LIKED", "Mode: New and Liked")
                        "MODE_PLAY_ALL" -> createPlayableSettingItem("MODE_PLAY_ALL", "Mode: Play All / Recategorise")
                        else -> null
                    }

                    if (browsableItem != null) {
                        settableFuture.set(LibraryResult.ofItem(browsableItem, null))
                        return@launch
                    }

                    val dbSong = app.database.songDao().getSongById(mediaId)
                        ?: app.currentQueue.firstOrNull { it.id == mediaId }

                    if (dbSong != null) {
                        val item = dbSong.toMediaItem()
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
                    val dbSongs = app.database.songDao().getAllSongs()
                    var matches = dbSongs.filter {
                        it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
                    }.map { it.toMediaItem() }

                    if (matches.isEmpty()) {
                        val repo = YoutubeDLRepository(app)
                        val searchResults = repo.searchSongsOrPlaylists(query)
                        matches = searchResults.map { it.toMediaItem() }
                    }

                    searchResultsCache[query] = matches
                    session.notifySearchResultChanged(browser, query, matches.size, params)

                    val intent = Intent("com.example.musicdlp.ACTION_SEARCH_VOICE").apply {
                        setPackage(packageName)
                        putExtra("query", query)
                    }
                    sendBroadcast(intent)
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
        private fun createBrowsableItem(id: String, title: String): MediaItem {
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
                        .setExtras(itemExtras)
                        .build()
                )
                .build()
        }

        fun playSongInContext(
            targetMediaId: String,
            contextList: List<Song>,
            swipingMode: String
        ): MediaSession.MediaItemsWithStartPosition {
            val targetIndex = contextList.indexOfFirst { it.id == targetMediaId }.coerceAtLeast(0)

            val intent = Intent("com.example.musicdlp.ACTION_SET_MODE").apply {
                setPackage(packageName)
                putExtra("mode", swipingMode)
            }
            sendBroadcast(intent)

            (application as MusicDLPApplication).currentQueue = contextList

            val mediaItems = contextList.map { it.toMediaItem() }

            return MediaSession.MediaItemsWithStartPosition(
                mediaItems,
                targetIndex,
                0L
            )
        }

        @Suppress("DEPRECATION")
        private fun createPlayableSettingItem(id: String, title: String): MediaItem {
            val itemExtras = Bundle().apply {
                putInt("CONTENT_STYLE_PLAYABLE_HINT", 1)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setUri(Uri.parse("http://dummy_setting"))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                        .setTitle(title)
                        .setExtras(itemExtras)
                        .build()
                )
                .build()
        }
    }
}