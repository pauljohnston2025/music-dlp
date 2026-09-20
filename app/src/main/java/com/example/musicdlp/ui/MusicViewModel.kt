package com.example.musicdlp.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicdlp.MusicDLPApplication
import com.example.musicdlp.data.Song
import com.example.musicdlp.data.YoutubeDLRepository
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicDLPApplication
    private val repository = YoutubeDLRepository(application)
    private val songDao = app.database.songDao()

    val exoPlayer = ExoPlayer.Builder(application).build()

    private val _songsToSwipe = MutableStateFlow<List<Song>>(emptyList())
    val songsToSwipe: StateFlow<List<Song>> = _songsToSwipe

    val likedSongs = songDao.getLikedSongs()
    val dislikedSongs = songDao.getDislikedSongs()

    override fun onCleared() {
        super.onCleared()
        exoPlayer.release()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(application)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun playPreview(song: Song) {
        viewModelScope.launch {
            val streamUrl = repository.getStreamUrl(song.youtubeUrl)
            if (streamUrl != null) {
                exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }

    fun loadPlaylist(url: String) {
        viewModelScope.launch {
            val allSongs = songDao.getAllSongs()
            val seenIds = allSongs.map { it.id }.toSet()
            val playlistSongs = repository.getPlaylistSongs(url)
            val filteredSongs = playlistSongs.filter { it.id !in seenIds }
            _songsToSwipe.value = filteredSongs
            if (filteredSongs.isNotEmpty()) {
                playPreview(filteredSongs.first())
            }
        }
    }

    fun likeSong(song: Song) {
        viewModelScope.launch {
            val updatedSong = song.copy(isLiked = true)
            songDao.insertSong(updatedSong)
            _songsToSwipe.value = _songsToSwipe.value.filter { it.id != song.id }
            
            val nextSong = _songsToSwipe.value.firstOrNull()
            if (nextSong != null) playPreview(nextSong) else exoPlayer.stop()
            
            // Download the song
            val downloadDir = File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "MusicDLP")
            if (!downloadDir.exists()) downloadDir.mkdirs()
            
            repository.downloadSong(updatedSong, downloadDir)
        }
    }

    fun dislikeSong(song: Song) {
        viewModelScope.launch {
            val updatedSong = song.copy(isDisliked = true)
            songDao.insertSong(updatedSong)
            _songsToSwipe.value = _songsToSwipe.value.filter { it.id != song.id }
            
            val nextSong = _songsToSwipe.value.firstOrNull()
            if (nextSong != null) playPreview(nextSong) else exoPlayer.stop()
        }
    }
}
