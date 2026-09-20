package com.example.musicdlp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.musicdlp.data.Song
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SwipingScreen(viewModel: MusicViewModel) {
    val songs by viewModel.songsToSwipe.collectAsState()
    val isPlaylistLoading by viewModel.isPlaylistLoading.collectAsState()
    var playlistUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextField(
            value = playlistUrl,
            onValueChange = { playlistUrl = it },
            label = { Text("Playlist URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { viewModel.loadPlaylist(playlistUrl) },
            enabled = !isPlaylistLoading,
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
        ) {
            if (isPlaylistLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Load Playlist")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isPlaylistLoading) {
                CircularProgressIndicator()
            } else if (songs.isNotEmpty()) {
                val currentSong = songs.first()
                key(currentSong.id) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TinderCard(
                            song = currentSong,
                            viewModel = viewModel,
                            onSwipedLeft = { viewModel.dislikeSong(currentSong) },
                            onSwipedRight = { viewModel.likeSong(currentSong) }
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.dislikeSong(currentSong) },
                                modifier = Modifier
                                    .size(64.dp)
                                    .border(2.dp, Color.Red, CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dislike", tint = Color.Red, modifier = Modifier.size(32.dp))
                            }
                            IconButton(
                                onClick = { viewModel.likeSong(currentSong) },
                                modifier = Modifier
                                    .size(64.dp)
                                    .border(2.dp, Color(0xFF4CAF50), CircleShape)
                            ) {
                                Icon(Icons.Default.Favorite, contentDescription = "Like", tint = Color(0xFF4CAF50), modifier = Modifier.size(32.dp))
                            }
                        }
                    }
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
    val isSongLoading by viewModel.isSongLoading.collectAsState()
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
        Box(modifier = Modifier.fillMaxSize()) {
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

            // Swiping Hints
            if (offsetX.value > 50) {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                        .graphicsLayer { rotationZ = -15f }
                        .border(4.dp, Color(0xFF4CAF50), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .alpha((offsetX.value / 400f).coerceIn(0f, 1f))
                ) {
                    Text(
                        text = "LIKE",
                        color = Color(0xFF4CAF50),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (offsetX.value < -50) {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopEnd)
                        .graphicsLayer { rotationZ = 15f }
                        .border(4.dp, Color.Red, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .alpha((-offsetX.value / 400f).coerceIn(0f, 1f))
                ) {
                    Text(
                        text = "NOPE",
                        color = Color.Red,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            if (isSongLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
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
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search Liked") },
            modifier = Modifier.fillMaxWidth()
        )
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(likedSongs.filter { it.title.contains(searchQuery, ignoreCase = true) }) { song ->
                val isDownloading = downloadProgress.containsKey(song.id)
                val progress = downloadProgress[song.id] ?: 0f
                val isPlaying = currentlyPlayingId == song.id

                Column {
                    ListItem(
                        headlineContent = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(song.title)
                                if (isPlaying) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Default.Favorite, contentDescription = "Playing", tint = Color.Red, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        supportingContent = { Text(song.artist) },
                        leadingContent = {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                        },
                        trailingContent = {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        modifier = Modifier.clickable { viewModel.playPreview(song) }
                    )
                    
                    if (isPlaying) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            PlayerControls(viewModel)
                        }
                    }
                    HorizontalDivider()
                }
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
