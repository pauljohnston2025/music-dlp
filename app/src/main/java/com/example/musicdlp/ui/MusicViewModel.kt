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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicDLPApplication
    private val repository = YoutubeDLRepository(application)
    private val songDao = app.database.songDao()

    val exoPlayer = ExoPlayer.Builder(application).build()

    private val _songsToSwipe = MutableStateFlow<List<Song>>(emptyList())
    val songsToSwipe: StateFlow<List<Song>> = _songsToSwipe

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

    private val downloadSemaphore = Semaphore(2)

    val likedSongs = songDao.getLikedSongs()
    val dislikedSongs = songDao.getDislikedSongs()

    override fun onCleared() {
        exoPlayer.release()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(application)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update yt-dlp: ${e.message}"
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun playPreview(song: Song) {
        viewModelScope.launch {
            _currentlyPlayingId.value = song.id
            _isSongLoading.value = true
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            
            try {
                val streamUrl = repository.getStreamUrl(song.youtubeUrl)
                if (streamUrl != null) {
                    exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                    exoPlayer.prepare()
                    exoPlayer.play()
                } else {
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
        }
    }

    fun resumeSwiping() {
        val currentSong = _songsToSwipe.value.firstOrNull()
        if (currentSong != null && _currentlyPlayingId.value != currentSong.id) {
            playPreview(currentSong)
        }
    }

    fun loadPlaylist(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _isPlaylistLoading.value = true
            try {
                val allSongs = songDao.getAllSongs()
                val seenIds = allSongs.map { it.id }.toSet()
                val seenTitles = allSongs.map { it.title.lowercase().trim() }.toSet()
                
                val playlistSongs = repository.getPlaylistSongs(url)
                val filteredSongs = playlistSongs.filter { 
                    it.id !in seenIds && it.title.lowercase().trim() !in seenTitles 
                }
                _songsToSwipe.value = filteredSongs
                
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

    fun likeSong(song: Song) {
        viewModelScope.launch {
            val updatedSong = song.copy(isLiked = true)
            songDao.insertSong(updatedSong)
            _songsToSwipe.value = _songsToSwipe.value.filter { it.id != song.id }
            
            val nextSong = _songsToSwipe.value.firstOrNull()
            if (nextSong != null) playPreview(nextSong) else exoPlayer.stop()
            
            // Queue download
            launch(Dispatchers.IO) {
                downloadSemaphore.withPermit {
                    val downloadDir = File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "MusicDLP")
                    if (!downloadDir.exists()) downloadDir.mkdirs()
                    
                    repository.downloadSong(updatedSong, downloadDir) { progress ->
                        _downloadProgress.value = _downloadProgress.value + (song.id to progress)
                    }
                    _downloadProgress.value = _downloadProgress.value - song.id
                }
            }
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
