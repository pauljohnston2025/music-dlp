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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.ui.text.input.ImeAction
import androidx.media3.common.Player
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    val playlistLikedSongs by viewModel.playlistLikedSongs.collectAsState()
    val playlistDislikedSongs by viewModel.playlistDislikedSongs.collectAsState()
    val playlistNewSongs by viewModel.playlistNewSongs.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val uniqueLiked = remember(playlistLikedSongs) {
        playlistLikedSongs.distinctBy { (it.title.lowercase().trim()) + "___" + (it.artist.lowercase().trim()) }
    }
    val uniqueDisliked = remember(playlistDislikedSongs) {
        playlistDislikedSongs.distinctBy { (it.title.lowercase().trim()) + "___" + (it.artist.lowercase().trim()) }
    }
    val uniqueNew = remember(playlistNewSongs) {
        playlistNewSongs.distinctBy { (it.title.lowercase().trim()) + "___" + (it.artist.lowercase().trim()) }
    }
    val uniqueAll = remember(uniqueLiked, uniqueDisliked, uniqueNew, songs) {
        (uniqueLiked + uniqueDisliked + uniqueNew + songs).distinctBy { (it.title.lowercase().trim()) + "___" + (it.artist.lowercase().trim()) }
    }

    var showLikedPlaylistDialog by remember { mutableStateOf(false) }
    var showDislikedPlaylistDialog by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var showAllPlaylistDialog by remember { mutableStateOf(false) }

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

    if (showNewPlaylistDialog) {
        FilteredSongsDialog(
            title = "New / Upcoming Songs in Playlist",
            songs = uniqueNew,
            viewModel = viewModel,
            onDismiss = { showNewPlaylistDialog = false }
        )
    }

    if (showAllPlaylistDialog) {
        FilteredSongsDialog(
            title = "All Songs in Playlist",
            songs = uniqueAll,
            viewModel = viewModel,
            onDismiss = { showAllPlaylistDialog = false }
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
    
    var searchOrUrlInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.queryLocalStorageUri(context, uri)
        }
    }

    fun performSearchOrLoad() {
        val query = searchOrUrlInput.trim()
        if (query.isBlank()) return

        // Dismiss the software keyboard and drop cursor focus
        keyboardController?.hide()
        focusManager.clearFocus()

        if (query.startsWith("http://", true) ||
            query.startsWith("https://", true) ||
            query.contains("youtube.com", true) ||
            query.contains("youtu.be", true)
        ) {
            viewModel.loadPlaylist(query)
        } else {
            viewModel.searchPlaylists(query)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // 1. Static Top Bar (Unified Search / Playlist URL + Folder Scan)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchOrUrlInput,
                onValueChange = { searchOrUrlInput = it },
                placeholder = { Text("Search or Paste Playlist URL...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearchOrLoad() }),
                trailingIcon = {
                    IconButton(onClick = { performSearchOrLoad() }, enabled = !isPlaylistLoading) {
                        Icon(Icons.Default.Search, contentDescription = "Search / Load")
                    }
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = { directoryPickerLauncher.launch(null) },
                enabled = !isPlaylistLoading,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Scan Local Folder", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 2. Error message box if any
        errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        // 3. Static Chips Row: Liked, Disliked, New, All
        if (playlistTotal > 0 || uniqueLiked.isNotEmpty() || uniqueDisliked.isNotEmpty() || uniqueNew.isNotEmpty() || uniqueAll.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactCategoryChip(
                    count = uniqueLiked.size,
                    label = "liked",
                    icon = Icons.Default.Favorite,
                    iconColor = if (uniqueLiked.isNotEmpty()) Color.Red else Color.Gray,
                    enabled = uniqueLiked.isNotEmpty(),
                    onClick = { showLikedPlaylistDialog = true },
                    modifier = Modifier.weight(1f)
                )
                CompactCategoryChip(
                    count = uniqueDisliked.size,
                    label = "disliked",
                    icon = Icons.Default.Close,
                    iconColor = Color.Gray,
                    enabled = uniqueDisliked.isNotEmpty(),
                    onClick = { showDislikedPlaylistDialog = true },
                    modifier = Modifier.weight(1f)
                )
                CompactCategoryChip(
                    count = uniqueNew.size,
                    label = "new",
                    icon = Icons.Default.MusicNote,
                    iconColor = MaterialTheme.colorScheme.primary,
                    enabled = uniqueNew.isNotEmpty(),
                    onClick = { showNewPlaylistDialog = true },
                    modifier = Modifier.weight(1f)
                )
                CompactCategoryChip(
                    count = uniqueAll.size,
                    label = "all",
                    icon = Icons.Default.Layers,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    enabled = uniqueAll.isNotEmpty(),
                    onClick = { showAllPlaylistDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // 4. Center Area with TinderCard
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (songs.isEmpty() && (isPlaylistLoading || isBuffering)) {
                CircularProgressIndicator()
            } else if (songs.isNotEmpty()) {
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
    val currentOnSwipedLeft by rememberUpdatedState(onSwipedLeft)
    val currentOnSwipedRight by rememberUpdatedState(onSwipedRight)
    
    val isSongLoading by viewModel.isSongLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val hasUserSwiped by viewModel.hasUserSwiped.collectAsState()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxSize()
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
                                val newY = (offsetY.value + dragAmount.y).coerceAtMost(0f)
                                offsetY.snapTo(newY)
                            }
                        },
                        onDragEnd = {
                            viewModel.setHasUserSwiped()
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!hasUserSwiped) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Swipe, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Swipe: Left (NOPE) • Right (LIKE) • Up (SKIP)", style = MaterialTheme.typography.labelSmall)
                    }
                }

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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val isLocalFile = song.youtubeUrl.startsWith("content://") ||
                                  song.youtubeUrl.startsWith("file://") ||
                                  song.youtubeUrl.startsWith("/") ||
                                  song.id.startsWith("local_")

                val labelPrefix = if (isLocalFile) "File Name: " else "YouTube: "

                Text(
                    text = "$labelPrefix${song.rawTitle?.ifBlank { null } ?: song.title.ifBlank { "Unknown" }}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    modifier = Modifier.alpha(0.7f).fillMaxWidth()
                )
                if (song.isLiked || song.isDisliked) {
                    AssistChip(
                        onClick = { },
                        enabled = false,
                        label = { Text(if (song.isLiked) "Already Liked" else "Already Disliked") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (song.isLiked) Icons.Default.Favorite else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (song.isLiked) Color.Red else Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                if (!song.metadataSource.isNullOrBlank()) {
                    Text(
                        text = "Cleaned by: ${song.metadataSource}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                var editableTitle by remember(song.id) { mutableStateOf(song.title) }
                var editableArtist by remember(song.id) { mutableStateOf(song.artist) }

                LaunchedEffect(song.title, song.artist, song.isMetadataCleaned) {
                    editableTitle = song.title
                    editableArtist = song.artist
                }

                val isCleaned = song.isMetadataCleaned == true

                OutlinedTextField(
                    value = editableTitle,
                    onValueChange = {
                        editableTitle = it
                        viewModel.updateSongNameAndArtist(song, it, editableArtist)
                    },
                    enabled = isCleaned,
                    label = { Text(if (isCleaned) "Song Name" else "Song Name (Cleaning...)") },
                    trailingIcon = {
                        if (!isCleaned) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editableArtist,
                    onValueChange = {
                        editableArtist = it
                        viewModel.updateSongNameAndArtist(song, editableTitle, it)
                    },
                    enabled = isCleaned,
                    label = { Text(if (isCleaned) "Artist Name" else "Artist Name (Cleaning...)") },
                    trailingIcon = {
                        if (!isCleaned) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage?.contains("Playback error") == true) {
                    TextButton(onClick = { viewModel.playPreview(song) }) {
                        Text("Retry Preview")
                    }
                }
            }

            // Animated Swiping Stamps
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
                        .align(Alignment.BottomCenter)
                        .border(4.dp, Color.Gray, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
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
fun PlayerControls(
    viewModel: MusicViewModel,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    canGoPrevious: Boolean = true,
    canGoNext: Boolean = true
) {
    val player = viewModel.exoPlayer
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            isPlaying = player.isPlaying
            if (player.duration > 0) {
                progress = player.currentPosition.toFloat() / player.duration.toFloat()
            }
            delay(500)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Slider(
            value = progress,
            onValueChange = {
                player?.let { p ->
                    val seekPos = (it * p.duration).toLong()
                    p.seekTo(seekPos)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onPrevious?.invoke() },
                enabled = onPrevious != null && canGoPrevious,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Song",
                    modifier = Modifier.size(36.dp)
                )
            }

            val songs by viewModel.songsToSwipe.collectAsState()
            val currentSong = songs.firstOrNull()

            IconButton(
                onClick = {
                    player?.let { p ->
                        if (isPlaying) {
                            p.pause()
                        } else {
                            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED || p.mediaItemCount == 0) {
                                currentSong?.let { viewModel.playPreview(it, forceRefreshSource = true) }
                            } else {
                                p.play()
                            }
                        }
                    }
                },
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
                onClick = { onNext?.invoke() },
                enabled = onNext != null && canGoNext,
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
        
        val filteredLikedList = remember(likedSongs, searchQuery) {
            likedSongs.filter { 
                it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredLikedList) { song ->
                val isDownloading = downloadProgress.containsKey(song.id)
                val progress = downloadProgress[song.id] ?: 0f
                val isPlaying = currentlyPlayingId == song.id

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            viewModel.playSongInContext(song, filteredLikedList)
                        }
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        headlineContent = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = song.title,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isPlaying) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("Playing", style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = "Playing", tint = Color.Red, modifier = Modifier.size(12.dp)) }
                                    )
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
                        }
                    )
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
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsState()

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
                    text = "Select an alternate version to preview or replace your downloaded copy:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
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
                                Spacer(modifier = Modifier.height(4.dp))
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
                        val previewId = "preview_${version.youtubeUrl.hashCode()}"
                        val isPreviewPlaying = currentlyPlayingId == previewId
                        val effectiveThumbnail = if (!version.thumbnailUrl.isNullOrBlank()) {
                            version.thumbnailUrl
                        } else if (version.youtubeUrl.contains("watch?v=")) {
                            val id = version.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                            "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                        } else {
                            ""
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (effectiveThumbnail.isNotBlank()) {
                                        AsyncImage(
                                            model = effectiveThumbnail,
                                            contentDescription = null,
                                            modifier = Modifier.size(56.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = version.rawTitle ?: "Alternate Version",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2
                                        )
                                        Text(
                                            text = version.youtubeUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.playPreviewByUrl(version.youtubeUrl, version.rawTitle ?: song.title) }
                                    ) {
                                        Icon(
                                            imageVector = if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isPreviewPlaying) "Pause" else "Preview")
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.replaceLikedSongWithAlternateVersion(song, version)
                                            onDismiss()
                                        }
                                    ) {
                                        Text("Replace Download")
                                    }
                                }

                                if (isPreviewPlaying) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    PlayerControls(
                                        viewModel = viewModel,
                                        canGoPrevious = false,
                                        canGoNext = false
                                    )
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

        var showClearDbDialog by remember { mutableStateOf(false) }

        if (showClearDbDialog) {
            AlertDialog(
                onDismissRequest = { showClearDbDialog = false },
                title = { Text("Clear Library Database?") },
                text = { Text("Are you sure you want to clear the library database? This will delete all song records from the app, but will leave your downloaded music files intact on disk.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearDbDialog = false
                            viewModel.clearDatabase()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Clear Database")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDbDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Button(
            onClick = { showClearDbDialog = true },
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

        val filteredDislikedList = remember(dislikedSongs, searchQuery) {
            dislikedSongs.filter { 
                it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
            }
        }

        val playingIndex = filteredDislikedList.indexOfFirst { it.id == currentlyPlayingId }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredDislikedList) { song ->
                val isPlaying = currentlyPlayingId == song.id
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            viewModel.playSongInContext(song, filteredDislikedList)
                        }
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        headlineContent = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = song.title,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
                                )
                                if (isPlaying) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("Playing", style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = "Playing", tint = Color.Red, modifier = Modifier.size(12.dp)) }
                                    )
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
                        }
                    )
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
                    itemsIndexed(songs) { i, song ->
                        val isPlaying = currentlyPlayingId == song.id
                        val alternates = song.getAlternateVersionsList()

                        Column {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        onDismiss()
                                        viewModel.jumpToSongInSwipeList(song)
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
                                    PlayerControls(
                                        viewModel = viewModel,
                                        onPrevious = if (i > 0) {
                                            {
                                                val prev = songs[i - 1]
                                                viewModel.playLikedSong(prev) { viewModel.playPreview(prev) }
                                            }
                                        } else null,
                                        onNext = if (i < songs.lastIndex) {
                                            {
                                                val next = songs[i + 1]
                                                viewModel.playLikedSong(next) { viewModel.playPreview(next) }
                                            }
                                        } else null,
                                        canGoPrevious = i > 0,
                                        canGoNext = i < songs.lastIndex
                                    )
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
fun CompactCategoryChip(
    count: Int,
    label: String,
    icon: ImageVector,
    iconColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.85f else 0.35f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) iconColor else Color.Gray,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "$count $label",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
