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
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.aakira.napier.Napier
import kotlinx.coroutines.*
import java.io.File

private const val CUSTOM_ACTION_LIKE = "com.example.musicdlp.COMMAND_LIKE"
private const val CUSTOM_ACTION_DISLIKE = "com.example.musicdlp.COMMAND_DISLIKE"
private const val CUSTOM_ACTION_CYCLE_MODE = "com.example.musicdlp.COMMAND_CYCLE_MODE"

@UnstableApi
class CustomMediaSourceFactory(
    context: Context,
    private var app : MusicDLPApplication,
    private val repository : YoutubeDLRepository
) : MediaSource.Factory by DefaultMediaSourceFactory(context) {

    private val defaultFactory = DefaultMediaSourceFactory(context)

    override fun createMediaSource(item: MediaItem): MediaSource {
        // Check if the URI is already resolved
        if (item.localConfiguration?.uri != null) {
            return defaultFactory.createMediaSource(item)
        }

        // Defer stream resolution until ExoPlayer prepares/plays this item
        val mediaId = item.mediaId
        val resolvedItem = runBlocking(Dispatchers.IO) {
            val dbSong = app.database.songDao().getSongById(mediaId)
                ?: app.currentQueue.firstOrNull { it.id == mediaId }
            var resolved = item
            if (dbSong != null) {
                val repository = YoutubeDLRepository(app)

                resolved = dbSong.toMediaItem()

                val playableUriStr: String? = if (dbSong != null) {
                    val safeArtist = dbSong.artist.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                    val safeTitle = dbSong.title.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                    val finalFileName = "$safeArtist - $safeTitle.mp3"
                    val publicMusicDir =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val downloadDir = File(publicMusicDir, "MusicDLP")
                    val targetFile = File(downloadDir, finalFileName)

                    if (targetFile.exists()) {
                        targetFile.absolutePath
                    } else if (dbSong.youtubeUrl.startsWith("content://") || dbSong.youtubeUrl.startsWith(
                            "file://"
                        ) || dbSong.youtubeUrl.startsWith("/")
                    ) {
                        dbSong.youtubeUrl
                    } else {
                        repository.getStreamUrl(dbSong.youtubeUrl)
                    }
                } else if (item.requestMetadata.mediaUri != null && item.requestMetadata.mediaUri.toString()
                        .startsWith("http")
                ) {
                    repository.getStreamUrl(item.requestMetadata.mediaUri.toString())
                } else null

                if (!playableUriStr.isNullOrBlank()) {
                    val parsedUri = if (playableUriStr.startsWith("/")) {
                        Uri.fromFile(File(playableUriStr))
                    } else {
                        Uri.parse(playableUriStr)
                    }

                    val updatedMetadata = item.mediaMetadata.buildUpon()
                        .setTitle(
                            dbSong?.title ?: item.mediaMetadata.title ?: "MusicDLP"
                        )
                        .setArtist(
                            dbSong?.artist ?: item.mediaMetadata.artist ?: "MusicDLP"
                        )
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()


                        resolved = item.buildUpon()
                            .setUri(parsedUri)
                            .setMediaMetadata(updatedMetadata)
                            .build()
                }

                resolved
            }

            resolved
        }

        return defaultFactory.createMediaSource(resolvedItem)
    }
}

@OptIn(UnstableApi::class)
class MusicLibraryService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var forwardingPlayer: ForwardingPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val customCommandLike = SessionCommand(CUSTOM_ACTION_LIKE, Bundle.EMPTY)
    private val customCommandDislike = SessionCommand(CUSTOM_ACTION_DISLIKE, Bundle.EMPTY)
    private val customCommandCycleMode = SessionCommand(CUSTOM_ACTION_CYCLE_MODE, Bundle.EMPTY)

    private val playerListeners = mutableSetOf<Player.Listener>()
    private val searchResultsCache = mutableMapOf<String, List<MediaItem>>()

    private val queueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.musicdlp.ACTION_QUEUE_CHANGED") {
                val app = application as? MusicDLPApplication ?: return
                val queue = app.currentQueue
                val queueCount = queue.size
                val session = mediaSession ?: return

                session.notifyChildrenChanged("NOW_PLAYING", queueCount, null)
            }
        }
    }


    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val mediaSourceFactory = CustomMediaSourceFactory(this, application as MusicDLPApplication, YoutubeDLRepository(application as MusicDLPApplication))

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
            override fun addListener(listener: Player.Listener) {
                super.addListener(listener)
                playerListeners.add(listener)
            }

            override fun removeListener(listener: Player.Listener) {
                super.removeListener(listener)
                playerListeners.remove(listener)
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
                return extras?.getBoolean("canGoPrevious", false) ?: false
            }

//            override fun seekToNext() {
//                val intent = Intent("com.example.musicdlp.ACTION_NEXT").apply { setPackage(packageName) }
//                sendBroadcast(intent)
//            }

//            override fun seekToNextMediaItem() {
//                seekToNext()
//            }
//
//            override fun seekToPrevious() {
//                val intent = Intent("com.example.musicdlp.ACTION_PREVIOUS").apply { setPackage(packageName) }
//                sendBroadcast(intent)
//            }
//
//            override fun seekToPreviousMediaItem() {
//                seekToPrevious()
//            }
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
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Napier.d(
                "Connection request from ${controller.packageName} (isPackageNameVerified=${controller.isPackageNameVerified}) (isTrusted=${controller.isTrusted})",
                tag = "DEBUG_METADATA"
            )

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
            Napier.d("onGetLibraryRoot called by ${browser.packageName}", tag = "DEBUG_METADATA")

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
            Napier.d("onGetChildren called for parentId: $parentId by ${browser.packageName}", tag = "DEBUG_METADATA")
            val settableFuture = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val app = application as MusicDLPApplication
                    val children = when (parentId.uppercase()) {
                        "ROOT", "/", "MEDIA_ROOT" -> {
                            listOf(
                                createBrowsableItem("NOW_PLAYING", "Now Playing"),
                                createBrowsableItem("LIKED", "Liked Songs"),
                                createBrowsableItem("DISLIKED", "Disliked Songs"),
                                createBrowsableItem("SETTINGS", "Settings / Playback Mode")
                            )
                        }
                        "SETTINGS" -> {
                            listOf(
                                createPlayableSettingItem("MODE_ONLY_NEW", "Mode: Only Categorise New"),
                                createPlayableSettingItem("MODE_NEW_AND_LIKED", "Mode: New and Liked"),
                                createPlayableSettingItem("MODE_PLAY_ALL", "Mode: Play All / Recategorise")
                            )
                        }
                        "NOW_PLAYING" -> {
                            val queue = app.currentQueue
                            if (queue.isNotEmpty()) {
                                queue.map { it.toMediaItem() }
                            } else {
                                val liked = app.database.songDao().getLikedSongsList()
                                liked.map { it.toMediaItem() }
                            }
                        }
                        "LIKED" -> {
                            val liked = app.database.songDao().getLikedSongsList()
                            liked.map { it.toMediaItem() }
                        }
                        "DISLIKED" -> {
                            val disliked = app.database.songDao().getDislikedSongsList()
                            disliked.map { it.toMediaItem() }
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
                    val resolvedItems = mutableListOf<MediaItem>()

                    for (item in mediaItems) {
                        val mediaId = item.mediaId
                        if (mediaId.startsWith("MODE_")) {
                            val modeStr = when (mediaId) {
                                "MODE_NEW_AND_LIKED" -> "NEW_AND_LIKED"
                                "MODE_PLAY_ALL" -> "PLAY_ALL_RECATEGORISE"
                                else -> "ONLY_NEW"
                            }
                            val intent = Intent("com.example.musicdlp.ACTION_SET_MODE").apply {
                                setPackage(packageName)
                                putExtra("mode", modeStr)
                            }
                            sendBroadcast(intent)
                            continue
                        }

                        resolvedItems.add(item)
                    }

                    settableFuture.set(MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs))
                } catch (t: Throwable) {
                    Napier.e("onSetMediaItems failed: ${t.message}", t, tag = "DEBUG_METADATA")
                    settableFuture.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
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

                    val dbSong = app.database.songDao().getSongById(mediaId) ?: app.currentQueue.firstOrNull { it.id == mediaId }
                    if (dbSong != null) {
                           var item = dbSong.toMediaItem()
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
            Napier.d("onSearch called for query: $query", tag = "DEBUG_METADATA")
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
            Napier.d("onGetSearchResult called for query: $query", tag = "DEBUG_METADATA")
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

@Suppress("DEPRECATION")
fun Song.toMediaItem(): MediaItem {
    val artworkUri = if (thumbnailUrl.isNotBlank()) {
        Uri.parse(thumbnailUrl)
    } else if (youtubeUrl.contains("watch?v=")) {
        val id = youtubeUrl.substringAfter("watch?v=").substringBefore("&")
        Uri.parse("https://i.ytimg.com/vi/$id/hqdefault.jpg")
    } else null

    val itemExtras = Bundle().apply {
        putBoolean("isLiked", isLiked)
        putBoolean("isDisliked", isDisliked)
    }

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.parse(if (youtubeUrl.isBlank()) "http://dummy" else youtubeUrl))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.ifBlank { "Unknown Title" })
                .setArtist(artist.ifBlank { "MusicDLP" })
                .setArtworkUri(artworkUri)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                .setExtras(itemExtras)
                .build()
        )
        .build()
}
