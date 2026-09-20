package com.example.musicdlp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.musicdlp.data.Song
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SwipingScreen(viewModel: MusicViewModel) {
    val songs by viewModel.songsToSwipe.collectAsState()
    var playlistUrl by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextField(
            value = playlistUrl,
            onValueChange = { playlistUrl = it },
            label = { Text("Playlist URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { viewModel.loadPlaylist(playlistUrl) },
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
        ) {
            Text("Load Playlist")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (songs.isNotEmpty()) {
                val currentSong = songs.first()
                key(currentSong.id) {
                    TinderCard(
                        song = currentSong,
                        viewModel = viewModel,
                        onSwipedLeft = { viewModel.dislikeSong(currentSong) },
                        onSwipedRight = { viewModel.likeSong(currentSong) }
                    )
                }
            } else {
                Text("No more songs to swipe!")
            }
        }
    }
}

@Composable
fun TinderCard(
    song: Song,
    viewModel: MusicViewModel,
    onSwipedLeft: () -> Unit,
    onSwipedRight: () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer {
                rotationZ = offsetX.value / 20
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            offsetX.snapTo(offsetX.value + dragAmount.x)
                        }
                    },
                    onDragEnd = {
                        if (offsetX.value > 400) {
                            coroutineScope.launch {
                                offsetX.animateTo(1000f)
                                onSwipedRight()
                            }
                        } else if (offsetX.value < -400) {
                            coroutineScope.launch {
                                offsetX.animateTo(-1000f)
                                onSwipedLeft()
                            }
                        } else {
                            coroutineScope.launch {
                                offsetX.animateTo(0f)
                            }
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            Text(text = song.title, style = MaterialTheme.typography.titleLarge)
            Text(text = song.artist, style = MaterialTheme.typography.bodyMedium)
            
            PlayerControls(viewModel)
        }
    }
}

@Composable
fun PlayerControls(viewModel: MusicViewModel) {
    val player = viewModel.exoPlayer
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    // Update progress periodically
    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0) {
                progress = player.currentPosition.toFloat() / player.duration.toFloat()
            }
            isPlaying = player.isPlaying
            kotlinx.coroutines.delay(500)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Slider(
            value = progress,
            onValueChange = {
                val seekPos = (it * player.duration).toLong()
                player.seekTo(seekPos)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = { if (isPlaying) player.pause() else player.play() }) {
                Text(if (isPlaying) "Pause" else "Play")
            }
        }
    }
}

@Composable
fun LikedSongsScreen(viewModel: MusicViewModel) {
    val likedSongs by viewModel.likedSongs.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search Liked") },
            modifier = Modifier.fillMaxWidth()
        )
        
        LazyColumn {
            items(likedSongs.filter { it.title.contains(searchQuery, ignoreCase = true) }) { song ->
                ListItem(
                    headlineContent = { Text(song.title) },
                    supportingContent = { Text(song.artist) },
                    leadingContent = {
                        AsyncImage(
                            model = song.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                    },
                    modifier = Modifier.clickable { viewModel.playPreview(song) }
                )
            }
        }
    }
}

@Composable
fun DislikedSongsScreen(viewModel: MusicViewModel) {
    val dislikedSongs by viewModel.dislikedSongs.collectAsState(initial = emptyList())
    
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(dislikedSongs) { song ->
            ListItem(
                headlineContent = { Text(song.title) },
                supportingContent = { Text(song.artist) }
            )
        }
    }
}
