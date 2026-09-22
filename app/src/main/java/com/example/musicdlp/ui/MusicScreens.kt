package com.example.musicdlp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.musicdlp.data.Song
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SwipingScreen(viewModel: MusicViewModel) {
    val songs by viewModel.songsToSwipe.collectAsState()
    val isPlaylistLoading by viewModel.isPlaylistLoading.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val playlistTotal by viewModel.playlistTotal.collectAsState()
    val playlistIndex by viewModel.playlistIndex.collectAsState()
    val playlistLikedSongs by viewModel.playlistLikedSongs.collectAsState()
    val playlistDislikedSongs by viewModel.playlistDislikedSongs.collectAsState()

    val uniqueLiked = remember(playlistLikedSongs) {
        playlistLikedSongs.distinctBy { (it.title.lowercase().trim()) + "___" + (it.artist.lowercase().trim()) }
    }
    val uniqueDisliked = remember(playlistDislikedSongs) {
        playlistDislikedSongs.distinctBy { (it.title.lowercase().trim()) + "___" + (it.artist.lowercase().trim()) }
    }

    var showLikedPlaylistDialog by remember { mutableStateOf(false) }
    var showDislikedPlaylistDialog by remember { mutableStateOf(false) }

    if (showLikedPlaylistDialog) {
        FilteredSongsDialog(
            title = "Already Liked Songs in Playlist",
            songs = uniqueLiked,
            viewModel = viewModel,
            onDismiss = { showLikedPlaylistDialog = false }
        )
    }

    if (showDislikedPlaylistDialog) {
        FilteredSongsDialog(
            title = "Disliked Songs in Playlist",
            songs = uniqueDisliked,
            viewModel = viewModel,
            onDismiss = { showDislikedPlaylistDialog = false }
        )
    }

    val errorMessage by viewModel.errorMessage.collectAsState()
    val promptSong by viewModel.promptForSongName.collectAsState()

    val suggestedTitle by viewModel.suggestedTitle.collectAsState()
    val suggestedArtist by viewModel.suggestedArtist.collectAsState()

    var customTitle by remember(promptSong) { mutableStateOf(suggestedTitle) }
    var customArtist by remember(promptSong) { mutableStateOf(suggestedArtist) }

    if (promptSong != null) {
        AlertDialog(
            onDismissRequest = { viewModel.setSongNamePrompt(null) },
            title = { Text("MusicBrainz Lookup Failed") },
            text = {
                Column {
                    Text("Lookup failed for \"${promptSong?.artist} - ${promptSong?.title}\". Please verify or enter correct details:")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text("Song Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = customArtist,
                        onValueChange = { customArtist = it },
                        label = { Text("Artist Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = promptSong
                    if (s != null && customTitle.isNotBlank()) {
                        viewModel.updateSongNameAndArtist(s, customTitle, customArtist.ifBlank { "Unknown" })
                    }
                    customTitle = ""
                    customArtist = ""
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.setSongNamePrompt(null)
                    customTitle = ""
                    customArtist = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    var playlistUrl by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Error message box if any, scrollable
        errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        TextField(
            value = playlistUrl,
            onValueChange = { playlistUrl = it },
            label = { Text("Playlist URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { viewModel.loadPlaylist(playlistUrl) },
                enabled = !isPlaylistLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Playlist")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search bar for similar playlists query from youtube
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search songs or playlists on YouTube") },
            trailingIcon = {
                IconButton(onClick = { viewModel.searchPlaylists(searchQuery) }) {
                    Icon(Icons.Default.Search, contentDescription = "Search Playlists")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Local storage directory picker
        val context = LocalContext.current
        val directoryPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                viewModel.queryLocalStorageUri(context, uri)
            }
        }

        Button(
            onClick = { directoryPickerLauncher.launch(null) },
            enabled = !isPlaylistLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Choose Local Music Folder to Scan")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (playlistTotal > 0) {
            Text(
                text = "Counter: $playlistIndex / $playlistTotal",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { showLikedPlaylistDialog = true },
                    enabled = uniqueLiked.isNotEmpty(),
                    label = { Text("${uniqueLiked.size}/$playlistTotal already liked") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = if (uniqueLiked.isNotEmpty()) Color.Red else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                AssistChip(
                    onClick = { showDislikedPlaylistDialog = true },
                    enabled = uniqueDisliked.isNotEmpty(),
                    label = { Text("${uniqueDisliked.size}/$playlistTotal disliked") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(650.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isPlaylistLoading || (isBuffering && songs.isEmpty())) {
                CircularProgressIndicator()
            } else if (songs.isNotEmpty()) {
                val currentSong = songs.first()
                key(currentSong.id) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // NOPE and LIKE buttons above the card
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.dislikeSong(currentSong) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                enabled = currentSong.isMetadataCleaned == true
                            ) {
                                Text("NOPE", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Button(
                                onClick = { viewModel.likeSong(currentSong) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                enabled = currentSong.isMetadataCleaned == true
                            ) {
                                Text("LIKE", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        TinderCard(
                            song = currentSong,
                            viewModel = viewModel,
                            onSwipedLeft = { viewModel.dislikeSong(currentSong) },
                            onSwipedRight = { viewModel.likeSong(currentSong) }
                        )
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
    val currentOnSwipedLeft by rememberUpdatedState(onSwipedLeft)
    val currentOnSwipedRight by rememberUpdatedState(onSwipedRight)
    
    val isSongLoading by viewModel.isSongLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(580.dp)
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
            .graphicsLayer {
                rotationZ = offsetX.value / 20
            }
            .pointerInput(song.id) {
                if (song.isMetadataCleaned == true) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                offsetX.snapTo(offsetX.value + dragAmount.x)
                                offsetY.snapTo(offsetY.value + dragAmount.y)
                            }
                        },
                        onDragEnd = {
                            if (offsetX.value > 400) {
                                coroutineScope.launch {
                                    offsetX.animateTo(1000f)
                                    currentOnSwipedRight()
                                }
                            } else if (offsetX.value < -400) {
                                coroutineScope.launch {
                                    offsetX.animateTo(-1000f)
                                    currentOnSwipedLeft()
                                }
                            } else if (offsetY.value < -400) {
                                coroutineScope.launch {
                                    offsetY.animateTo(-1000f)
                                    viewModel.skipSong(song)
                                }
                            } else {
                                coroutineScope.launch {
                                    offsetX.animateTo(0f)
                                    offsetY.animateTo(0f)
                                }
                            }
                        }
                    )
                }
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val effectiveThumbnail = if (song.thumbnailUrl.isBlank() && song.youtubeUrl.contains("watch?v=")) {
                    val id = song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                    "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                } else {
                    song.thumbnailUrl
                }

                if (effectiveThumbnail.isNotBlank()) {
                    AsyncImage(
                        model = effectiveThumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(0.6f).fillMaxWidth()
                    )
                } else {
                    Box(
                        modifier = Modifier.weight(0.6f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "YouTube: ${song.rawTitle ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.alpha(0.7f).padding(bottom = 2.dp)
                )
                if (!song.metadataSource.isNullOrBlank()) {
                    Text(
                        text = "Cleaned by: ${song.metadataSource}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                
                var editableTitle by remember(song.id) { mutableStateOf(song.title) }
                var editableArtist by remember(song.id) { mutableStateOf(song.artist) }

                // Sync UI fields if background cleaning completes after the card is already shown
                LaunchedEffect(song.title, song.artist, song.isMetadataCleaned) {
                    editableTitle = song.title
                    editableArtist = song.artist
                }

                TextField(
                    value = editableTitle,
                    onValueChange = { 
                        editableTitle = it
                        viewModel.updateSongNameAndArtist(song, it, editableArtist)
                    },
                    label = { Text("Song Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = editableArtist,
                    onValueChange = { 
                        editableArtist = it
                        viewModel.updateSongNameAndArtist(song, editableTitle, it)
                    },
                    label = { Text("Artist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                PlayerControls(viewModel)

                if (errorMessage?.contains("Playback error") == true) {
                    TextButton(onClick = { viewModel.playPreview(song) }) {
                        Text("Retry Preview")
                    }
                }
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

            if (offsetY.value < -50) {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.Center)
                        .border(4.dp, Color.Gray, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .alpha((-offsetY.value / 400f).coerceIn(0f, 1f))
                ) {
                    Text(
                        text = "SKIP",
                        color = Color.Gray,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            if (isSongLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
fun PlayerControls(viewModel: MusicViewModel) {
    val player = viewModel.exoPlayer
    val canGoBack by viewModel.canGoBack.collectAsState()
    val songs by viewModel.songsToSwipe.collectAsState()
    val currentSong = songs.firstOrNull()
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0) {
                progress = player.currentPosition.toFloat() / player.duration.toFloat()
            }
            isPlaying = player.isPlaying
            delay(500)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.goBackToPreviousSong() },
                enabled = canGoBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Song",
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(
                onClick = { if (isPlaying) player.pause() else player.play() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isPlaying) MaterialTheme.colorScheme.primary else Color(0xFF4CAF50),
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(
                onClick = { currentSong?.let { viewModel.skipSong(it) } },
                enabled = currentSong != null,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next Song",
                    modifier = Modifier.size(36.dp)
                )
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

    var songToPrompt by remember { mutableStateOf<Song?>(null) }
    var songForAlternatesDialog by remember { mutableStateOf<Song?>(null) }

    if (songForAlternatesDialog != null) {
        AlternateVersionsDialog(
            song = songForAlternatesDialog!!,
            viewModel = viewModel,
            onDismiss = { songForAlternatesDialog = null }
        )
    }

    if (songToPrompt != null) {
        AlertDialog(
            onDismissRequest = { songToPrompt = null },
            title = { Text("Still Downloading") },
            text = { Text("This song is still downloading. Would you like to start streaming/previewing it right now?") },
            confirmButton = {
                TextButton(onClick = {
                    val s = songToPrompt
                    songToPrompt = null
                    if (s != null) viewModel.playPreview(s)
                }) {
                    Text("Preview Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { songToPrompt = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Liked Songs (${likedSongs.size})",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search Liked (Artist or Song)") },
            modifier = Modifier.fillMaxWidth()
        )
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(likedSongs.filter { 
                it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
            }) { song ->
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
                        supportingContent = {
                            val alternates = song.getAlternateVersionsList()
                            Column {
                                Text(song.artist)
                                if (alternates.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    AssistChip(
                                        onClick = { songForAlternatesDialog = song },
                                        label = { Text("Alternate Versions (${alternates.size})") },
                                        leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            val effectiveThumbnail = if (song.thumbnailUrl.isBlank() && song.youtubeUrl.contains("watch?v=")) {
                                val id = song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                                "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                            } else {
                                song.thumbnailUrl
                            }

                            if (effectiveThumbnail.isNotBlank()) {
                                AsyncImage(
                                    model = effectiveThumbnail,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null)
                                }
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isDownloading) {
                                    CircularProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                if (!viewModel.isSongDownloaded(song)) {
                                    TextButton(onClick = { viewModel.retryDownload(song) }) {
                                        Text("Retry")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.playLikedSong(song) {
                                songToPrompt = song
                            }
                        }
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
fun AlternateVersionsDialog(
    song: Song,
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val alternates = remember(song) { song.getAlternateVersionsList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alternate Versions") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${song.artist} - ${song.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select an alternate version to replace your downloaded copy:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "[Current Downloaded Version]",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = song.rawTitle ?: song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2
                                )
                                Text(
                                    text = song.youtubeUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    items(alternates) { version ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = version.rawTitle ?: "Alternate Version",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2
                                )
                                Text(
                                    text = version.youtubeUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        viewModel.replaceLikedSongWithAlternateVersion(song, version)
                                        onDismiss()
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Replace Download")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun SettingsScreen(viewModel: MusicViewModel) {
    val geminiApiKey by viewModel.geminiApiKey.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = geminiApiKey,
            onValueChange = { viewModel.setGeminiApiKey(it) },
            label = { Text("Gemini API Key") },
            placeholder = { Text("Enter your Gemini API key here...") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Gemini is used to intelligently parse YouTube titles into clean Artist and Track names.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val lastModel = viewModel.lastWorkingModel
        if (!lastModel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Last Working Model: $lastModel",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.clearGeminiModelCache() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset Model Quota & Error Cache")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.updateYtDlp() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Update yt-dlp binary")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.clearDatabase() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear Library Database", color = MaterialTheme.colorScheme.onError)
        }
    }
}

@Composable
fun DislikedSongsScreen(viewModel: MusicViewModel) {
    val dislikedSongs by viewModel.dislikedSongs.collectAsState(initial = emptyList())
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Disliked Songs (${dislikedSongs.size})",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search Disliked (Artist or Song)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(dislikedSongs.filter { 
                it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
            }) { song ->
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
                            val effectiveThumbnail = if (song.thumbnailUrl.isBlank() && song.youtubeUrl.contains("watch?v=")) {
                                val id = song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                                "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                            } else {
                                song.thumbnailUrl
                            }

                            if (effectiveThumbnail.isNotBlank()) {
                                AsyncImage(
                                    model = effectiveThumbnail,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null)
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.playPreview(song)
                        }
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
fun FilteredSongsDialog(
    title: String,
    songs: List<Song>,
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsState()
    var songForAlternatesDialog by remember { mutableStateOf<Song?>(null) }

    if (songForAlternatesDialog != null) {
        AlternateVersionsDialog(
            song = songForAlternatesDialog!!,
            viewModel = viewModel,
            onDismiss = { songForAlternatesDialog = null }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${songs.size} unique ${if (songs.size == 1) "song" else "songs"}:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                    items(songs) { song ->
                        val isPlaying = currentlyPlayingId == song.id
                        val alternates = song.getAlternateVersionsList()

                        Column {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        viewModel.playLikedSong(song) {
                                            viewModel.playPreview(song)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val effectiveThumbnail = if (song.thumbnailUrl.isBlank() && song.youtubeUrl.contains("watch?v=")) {
                                        val id = song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                                        "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                                    } else {
                                        song.thumbnailUrl
                                    }

                                    if (effectiveThumbnail.isNotBlank()) {
                                        AsyncImage(
                                            model = effectiveThumbnail,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp)
                                        )
                                    } else {
                                        Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.MusicNote, contentDescription = null)
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                            if (isPlaying) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(Icons.Default.Favorite, contentDescription = "Playing", tint = Color.Red, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                        Text(text = song.artist, style = MaterialTheme.typography.bodySmall)

                                        if (alternates.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            AssistChip(
                                                onClick = { songForAlternatesDialog = song },
                                                label = { Text("Alternates (${alternates.size})") },
                                                leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                            )
                                        }
                                    }
                                }
                            }

                            if (isPlaying) {
                                Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    PlayerControls(viewModel)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
