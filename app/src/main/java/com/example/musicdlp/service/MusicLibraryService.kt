package com.example.musicdlp.service

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.musicdlp.MusicDLPApplication
import com.example.musicdlp.R
import com.example.musicdlp.data.Song
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private const val CUSTOM_ACTION_LIKE = "com.example.musicdlp.COMMAND_LIKE"
private const val CUSTOM_ACTION_DISLIKE = "com.example.musicdlp.COMMAND_DISLIKE"

@OptIn(UnstableApi::class)
class MusicLibraryService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var forwardingPlayer: ForwardingPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val customCommandLike = SessionCommand(CUSTOM_ACTION_LIKE, Bundle.EMPTY)
    private val customCommandDislike = SessionCommand(CUSTOM_ACTION_DISLIKE, Bundle.EMPTY)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this).build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateNotificationLayout(mediaItem)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateNotificationLayout(exoPlayer.currentMediaItem)
            }
        })

        forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                if (hasNextMediaItem()) {
                    builder.add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                } else {
                    builder.remove(COMMAND_SEEK_TO_NEXT).remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (hasPreviousMediaItem()) {
                    builder.add(COMMAND_SEEK_TO_PREVIOUS).add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                } else {
                    builder.remove(COMMAND_SEEK_TO_PREVIOUS).remove(
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                    )
                }
                return builder.build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> hasNextMediaItem()
                    COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> hasPreviousMediaItem()
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean {
                val extras = currentMediaItem?.mediaMetadata?.extras
                return extras?.getBoolean("canGoNext", false) ?: false
            }

            override fun hasPreviousMediaItem(): Boolean {
                val extras = currentMediaItem?.mediaMetadata?.extras
                return extras?.getBoolean("canGoPrevious", false) ?: false
            }

            override fun seekToNext() {
                if (hasNextMediaItem()) {
                    val intent = Intent("com.example.musicdlp.ACTION_NEXT").apply { setPackage(packageName) }
                    sendBroadcast(intent)
                }
            }

            override fun seekToNextMediaItem() {
                seekToNext()
            }

            override fun seekToPrevious() {
                if (hasPreviousMediaItem()) {
                    val intent = Intent("com.example.musicdlp.ACTION_PREVIOUS").apply { setPackage(packageName) }
                    sendBroadcast(intent)
                }
            }

            override fun seekToPreviousMediaItem() {
                seekToPrevious()
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
    }

    private fun buildCustomLayout(mediaItem: MediaItem?): ImmutableList<CommandButton> {
        val extras = mediaItem?.mediaMetadata?.extras
        val isLiked = extras?.getBoolean("isLiked", false) ?: false
        val isDisliked = extras?.getBoolean("isDisliked", false) ?: false

        val likeIcon = if (isLiked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up_outlined
        val dislikeIcon = if (isDisliked) R.drawable.ic_thumb_down_filled else R.drawable.ic_thumb_down_outlined

        @Suppress("DEPRECATION")
        val likeBtn = CommandButton.Builder()
            .setDisplayName("Like")
            .setIconResId(likeIcon)
            .setSessionCommand(customCommandLike)
            .build()

        @Suppress("DEPRECATION")
        val dislikeBtn = CommandButton.Builder()
            .setDisplayName("Dislike")
            .setIconResId(dislikeIcon)
            .setSessionCommand(customCommandDislike)
            .build()

        return ImmutableList.of(likeBtn, dislikeBtn)
    }

    private fun updateNotificationLayout(mediaItem: MediaItem?) {
        mediaSession?.setCustomLayout(buildCustomLayout(mediaItem))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
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
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(customCommandLike)
                .add(customCommandDislike)
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
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("ROOT")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("MusicDLP")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return when (parentId) {
                "ROOT" -> {
                    val children = listOf(
                        createBrowsableItem("LIKED", "Liked Songs"),
                        createBrowsableItem("DISLIKED", "Disliked Songs")
                    )
                    Futures.immediateFuture(LibraryResult.ofItemList(children, params))
                }
                "LIKED" -> {
                    val future = serviceScope.async(Dispatchers.IO) {
                        val app = application as MusicDLPApplication
                        val liked = app.database.songDao().getLikedSongs().first()
                        val items = liked.map { it.toMediaItem() }
                        LibraryResult.ofItemList(items, params)
                    }
                    Futures.immediateFuture(runBlocking { future.await() })
                }
                "DISLIKED" -> {
                    val future = serviceScope.async(Dispatchers.IO) {
                        val app = application as MusicDLPApplication
                        val disliked = app.database.songDao().getDislikedSongs().first()
                        val items = disliked.map { it.toMediaItem() }
                        LibraryResult.ofItemList(items, params)
                    }
                    Futures.immediateFuture(runBlocking { future.await() })
                }
                else -> Futures.immediateFuture(LibraryResult.ofItemList(listOf(), params))
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val intent = Intent("com.example.musicdlp.ACTION_SEARCH_VOICE").apply {
                setPackage(packageName)
                putExtra("query", query)
            }
            sendBroadcast(intent)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        private fun createBrowsableItem(id: String, title: String): MediaItem {
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle(title)
                        .build()
                )
                .build()
        }
    }
}

fun Song.toMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(Uri.parse(thumbnailUrl))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()
}
