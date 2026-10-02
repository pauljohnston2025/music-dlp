package com.example.musicdlp.ui

import com.example.musicdlp.data.PlaylistSearchResult
import com.example.musicdlp.data.SwipingMode

import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.example.musicdlp.data.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwipingScreen(viewModel: MusicViewModel) {
    val activePlayingList by viewModel.activePlayingList.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val swipingMode by viewModel.swipingMode.collectAsState()

    val currentSong = remember(activePlayingList, currentIndex) {
        if (currentIndex in activePlayingList.indices) activePlayingList[currentIndex] else null
    }

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
    val uniqueAll = remember(uniqueLiked, uniqueDisliked, uniqueNew, activePlayingList) {
        (uniqueLiked + uniqueDisliked + uniqueNew + activePlayingList).distinctBy { (it.title.lowercase().trim()) + "___" + (it.artist.lowercase().trim()) }
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
            title = "New Songs in Playlist",
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

    val showSearchResultDialog by viewModel.showSearchResultDialog.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val playlistSearchResults by viewModel.playlistSearchResults.collectAsState()
    val lastSearchQuery by viewModel.lastSearchQuery.collectAsState()

    if (showSearchResultDialog && (searchResults.isNotEmpty() || playlistSearchResults.isNotEmpty())) {
        SearchResultsDialog(
            query = lastSearchQuery,
            playlistResults = playlistSearchResults,
            results = searchResults,
            onSelectPlaylist = { pl ->
                viewModel.loadPlaylist(pl.playlistUrl)
                viewModel.dismissSearchResultDialog()
            },
            onSelect = { viewModel.selectSearchResult(it) },
            onDismiss = { viewModel.dismissSearchResultDialog() }
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

    val mergeConflict by viewModel.mergeConflict.collectAsState()

    if (mergeConflict != null) {
        val conflict = mergeConflict!!
        AlertDialog(
            onDismissRequest = { viewModel.clearMergeConflict() },
            title = { Text("Duplicate Song Name") },
            text = {
                Text("A song named \"${conflict.targetSong.artist} - ${conflict.targetSong.title}\" is already in your library.\n\nWould you like to merge \"${conflict.songToMerge.title}\" into it as an alternate version?")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmMergeConflict() }) {
                    Text("Merge as Alternate")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.keepSeparateConflict() }) {
                        Text("Keep Separate")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { viewModel.clearMergeConflict() }) {
                        Text("Cancel")
                    }
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
        // 1. Static Top Bar
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
                        if (isPlaylistLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Search / Load")
                        }
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

        // 2. Error Message
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

        if (playlistTotal > 0 || uniqueLiked.isNotEmpty() || uniqueDisliked.isNotEmpty() || uniqueNew.isNotEmpty() || uniqueAll.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
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

        // 4. Swiping Area / TinderCard Deck
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (isPlaylistLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Loading playlist...", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (activePlayingList.isEmpty() && isBuffering) {
                CircularProgressIndicator()
            } else if (currentSong != null && currentSong.id != "no_more_songs") {
                key(currentIndex, currentSong.id) {
                    TinderCard(
                        song = currentSong,
                        viewModel = viewModel,
                        onSwipedLeft = { viewModel.dislikeSong(currentSong, advance = true) },
                        onSwipedRight = { viewModel.likeSong(currentSong, advance = true) }
                    )
                }
            } else {
                Text(
                    text = "No more songs for playback mode: ${swipingMode.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

    LaunchedEffect(song.id) {
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
    }

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
                            val absX = abs(offsetX.value)
                            val absY = abs(offsetY.value)

                            if (offsetY.value < -400 && absY > absX * 1.5f) {
                                coroutineScope.launch {
                                    offsetY.animateTo(-1000f)
                                    viewModel.skipSong(song)
                                }
                            } else if (offsetX.value > 300) {
                                coroutineScope.launch {
                                    offsetX.animateTo(1000f)
                                    currentOnSwipedRight()
                                }
                            } else if (offsetX.value < -300) {
                                coroutineScope.launch {
                                    offsetX.animateTo(-1000f)
                                    currentOnSwipedLeft()
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
                val isDownloaded = viewModel.isSongDownloaded(song)
                val isAlternate by produceState(initialValue = false, song.id, song.youtubeUrl) {
                    value = viewModel.isAlternateVersionInDb(song)
                }
                val isParentLiked by produceState(initialValue = song.isLiked, song.id, song.youtubeUrl) {
                    value = viewModel.isParentLikedInDb(song)
                }
                val isParentDisliked by produceState(initialValue = song.isDisliked, song.id, song.youtubeUrl) {
                    value = viewModel.isParentDislikedInDb(song)
                }

                if (isParentLiked || isParentDisliked || isAlternate) {
                    val labelText = if (isParentLiked) {
                        if (!isAlternate && isDownloaded) "Already Liked" else "Already Liked (Alternate)"
                    } else {
                        if (!isAlternate && isDownloaded) "Already Disliked" else "Already Disliked (Alternate)"
                    }
                    AssistChip(
                        onClick = { },
                        enabled = false,
                        label = { Text(labelText) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isParentLiked) Icons.Default.ThumbUp else Icons.Default.ThumbDown,
                                contentDescription = null,
                                tint = if (isParentLiked) Color.Red else Color.Gray,
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
                val isChanged = editableTitle.trim() != song.title.trim() || editableArtist.trim() != song.artist.trim()

                OutlinedTextField(
                    value = editableTitle,
                    onValueChange = { editableTitle = it },
                    enabled = isCleaned,
                    label = { Text(if (isCleaned) "Song Name" else "Song Name (Cleaning...)") },
                    trailingIcon = {
                        if (!isCleaned) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (isChanged) {
                            IconButton(onClick = {
                                viewModel.updateSongNameAndArtist(song, editableTitle, editableArtist)
                            }) {
                                Icon(Icons.Default.Check, contentDescription = "Save Title", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (isChanged && isCleaned) {
                            viewModel.updateSongNameAndArtist(song, editableTitle, editableArtist)
                        }
                    }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editableArtist,
                    onValueChange = { editableArtist = it },
                    enabled = isCleaned,
                    label = { Text(if (isCleaned) "Artist Name" else "Artist Name (Cleaning...)") },
                    trailingIcon = {
                        if (!isCleaned) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (isChanged) {
                            IconButton(onClick = {
                                viewModel.updateSongNameAndArtist(song, editableTitle, editableArtist)
                            }) {
                                Icon(Icons.Default.Check, contentDescription = "Save Artist", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (isChanged && isCleaned) {
                            viewModel.updateSongNameAndArtist(song, editableTitle, editableArtist)
                        }
                    }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isChanged && isCleaned) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.updateSongNameAndArtist(song, editableTitle, editableArtist) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save Changes")
                    }
                }

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
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val swipingMode by viewModel.swipingMode.collectAsState()

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(currentSong?.id) {
        progress = 0f
    }

    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            isPlaying = player.isPlaying
            if (player.duration > 0) {
                progress = (player.currentPosition.toFloat() / player.duration.toFloat()).coerceIn(0f, 1f)
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
            IconButton(onClick = { viewModel.cycleSwipingMode() }) {
                val (modeIcon, modeTint) = when (swipingMode) {
                    SwipingMode.ONLY_NEW -> Icons.Default.FiberNew to MaterialTheme.colorScheme.primary
                    SwipingMode.NEW_AND_LIKED -> Icons.Default.ThumbUp to MaterialTheme.colorScheme.primary
                    SwipingMode.PLAY_ALL_RECATEGORISE -> Icons.Default.Repeat to MaterialTheme.colorScheme.secondary
                }
                Icon(
                    imageVector = modeIcon,
                    contentDescription = swipingMode.displayName,
                    tint = modeTint,
                    modifier = Modifier.size(28.dp)
                )
            }

            IconButton(
                onClick = {
                    if (onPrevious != null) onPrevious.invoke()
                    else viewModel.playPreviousInContext()
                },
                enabled = canGoPrevious,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Song",
                    modifier = Modifier.size(36.dp)
                )
            }

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
                onClick = {
                    if (onNext != null) onNext.invoke()
                    else viewModel.playNextInContext()
                },
                enabled = canGoNext,
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

    var songToPrompt by remember { mutableStateOf<Song?>(null) }

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

    CommonSongListScreen(
        title = "Liked Songs",
        searchLabel = "Search Liked (Artist or Song)",
        songs = likedSongs,
        viewModel = viewModel,
        emptyMessage = "No liked songs.",
        isFromLikedOrDisliked = true,
        trailingContent = { song ->
            val isDownloading = downloadProgress.containsKey(song.id)
            val progress = downloadProgress[song.id] ?: 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (!viewModel.isSongDownloaded(song) && !isDownloading) {
                    TextButton(onClick = { viewModel.retryDownload(song) }) {
                        Text("Retry")
                    }
                }
            }
        }
    )
}

@Composable
fun AlternateVersionsDialog(
    song: Song,
    viewModel: MusicViewModel,
    isLikedPage: Boolean = false,
    isSwipeScreen: Boolean = false,
    onDismiss: () -> Unit
) {
    val alternates = remember(song) { song.getAlternateVersionsList() }
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsState()

    val currentPreviewId = "preview_${song.youtubeUrl.hashCode()}"
    val isCurrentPlaying = currentlyPlayingId == song.id || currentlyPlayingId == currentPreviewId

    val headerSubtitleText = when {
        isLikedPage -> "Select an alternate version to preview or replace your downloaded copy:"
        isSwipeScreen -> "Select an alternate version to set as current version and play:"
        else -> "Select an alternate version to preview or set as current version:"
    }

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
                    text = headerSubtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                    item {
                        val currentThumbnail = if (song.thumbnailUrl.isNotBlank()) {
                            song.thumbnailUrl
                        } else if (song.youtubeUrl.contains("watch?v=")) {
                            val id = song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                            "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                        } else if (song.youtubeUrl.contains("youtu.be/")) {
                            val id = song.youtubeUrl.substringAfter("youtu.be/").substringBefore("&").substringBefore("?")
                            "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                        } else {
                            ""
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (isLikedPage) "[Current Downloaded Version]" else "[Current Version]",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (currentThumbnail.isNotBlank()) {
                                        AsyncImage(
                                            model = currentThumbnail,
                                            contentDescription = null,
                                            modifier = Modifier.size(56.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.rawTitle ?: song.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
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
                                Spacer(modifier = Modifier.height(8.dp))
                                if (!isSwipeScreen) {
                                    OutlinedButton(
                                        onClick = {
                                            if (isCurrentPlaying) {
                                                viewModel.togglePlayPause()
                                            } else {
                                                viewModel.playPreviewByUrl(song.youtubeUrl, song.rawTitle ?: song.title, song.artist)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isCurrentPlaying) "Pause" else "Preview Current")
                                    }
                                }
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
                                    if (!isSwipeScreen) {
                                        OutlinedButton(
                                            onClick = {
                                                if (isPreviewPlaying) {
                                                    viewModel.togglePlayPause()
                                                } else {
                                                    viewModel.playPreviewByUrl(version.youtubeUrl, version.rawTitle ?: "Alternate Version", song.artist)
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isPreviewPlaying) "Pause" else "Preview")
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            viewModel.setAlternateAsCurrentVersion(
                                                targetSong = song,
                                                selectedVersion = version,
                                                isLikedPage = isLikedPage,
                                                playImmediately = isSwipeScreen
                                            )
                                            onDismiss()
                                        }
                                    ) {
                                        Text(if (isLikedPage) "Replace Download" else "Set as Current")
                                    }
                                }

                                if (isPreviewPlaying && !isSwipeScreen) {
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
    CommonSongListScreen(
        title = "Disliked Songs",
        searchLabel = "Search Disliked (Artist or Song)",
        songs = dislikedSongs,
        viewModel = viewModel,
        emptyMessage = "No disliked songs.",
        isFromLikedOrDisliked = true
    )
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
            isLikedPage = title.contains("Liked", ignoreCase = true),
            isSwipeScreen = true,
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

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                ) {
                    itemsIndexed(
                        items = songs,
                        key = { _, song -> song.id }
                    ) { _, song ->
                        val isPlaying = currentlyPlayingId == song.id
                        val alternates = song.getAlternateVersionsList()

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    viewModel.jumpToSongInSwipeList(song)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val effectiveThumbnail = if (song.thumbnailUrl.isBlank() && song.youtubeUrl.contains("watch?v=")) {
                                    val id = song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                                    "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                                } else {
                                    song.thumbnailUrl
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1.2f)
                                        .clip(RoundedCornerShape(8.dp))
                                ) {
                                    if (effectiveThumbnail.isNotBlank()) {
                                        AsyncImage(
                                            model = effectiveThumbnail,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.MusicNote, contentDescription = null)
                                        }
                                    }

                                    if (isPlaying) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                                .padding(4.dp)
                                        ) {
                                            AnimatedEqualizer(color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 38.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 18.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (alternates.isNotEmpty()) {
                                        AssistChip(
                                            onClick = { songForAlternatesDialog = song },
                                            label = { Text("Alternates (${alternates.size})", fontSize = 11.sp) },
                                            leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                        )
                                    }
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

@Composable
fun CommonSongListScreen(
    title: String,
    searchLabel: String,
    songs: List<Song>,
    viewModel: MusicViewModel,
    emptyMessage: String = "No songs found.",
    isFromLikedOrDisliked: Boolean = false,
    onSongClick: ((Song, List<Song>) -> Unit)? = null,
    trailingContent: (@Composable (Song) -> Unit)? = null
) {
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var songForAlternatesDialog by remember { mutableStateOf<Song?>(null) }

    if (songForAlternatesDialog != null) {
        AlternateVersionsDialog(
            song = songForAlternatesDialog!!,
            viewModel = viewModel,
            isLikedPage = title.contains("Liked", ignoreCase = true),
            isSwipeScreen = false,
            onDismiss = { songForAlternatesDialog = null }
        )
    }

    val filteredList = remember(songs, searchQuery) {
        songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            (it.rawTitle?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "$title (${filteredList.size})",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(searchLabel) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (filteredList.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(emptyMessage)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredList, key = { it.id }) { song ->
                    val isPlaying = currentlyPlayingId == song.id
                    val alternates = song.getAlternateVersionsList()

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (onSongClick != null) {
                                    onSongClick(song, filteredList)
                                } else {
                                    viewModel.playSongInContext(song, filteredList, isFromLikedOrDisliked = isFromLikedOrDisliked)
                                }
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
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
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.MusicNote, contentDescription = null)
                                    }
                                }

                                if (isPlaying) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                                            .padding(4.dp)
                                    ) {
                                        AnimatedEqualizer(color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 38.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = song.artist.ifBlank { song.rawTitle ?: "Unknown Artist" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (alternates.isNotEmpty()) {
                                        AssistChip(
                                            onClick = { songForAlternatesDialog = song },
                                            label = { Text("Alternates (${alternates.size})", fontSize = 11.sp) },
                                            leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                        )
                                        if (trailingContent != null) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                    }
                                    if (trailingContent != null) {
                                        trailingContent(song)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NewSongsScreen(viewModel: MusicViewModel) {
    val activePlayingList by viewModel.activePlayingList.collectAsState()
    val playlistNewSongs by viewModel.playlistNewSongs.collectAsState()
    val dbNewSongs by viewModel.newSongs.collectAsState(initial = emptyList())

    val newSongsList = remember(dbNewSongs, playlistNewSongs, activePlayingList) {
        val queueNew = playlistNewSongs.ifEmpty { activePlayingList.filter { !it.isLiked && !it.isDisliked } }
        (dbNewSongs + queueNew)
            .filter { !it.isLiked && !it.isDisliked }
            .distinctBy { (it.title.lowercase().trim()) + "___" + (it.artist.lowercase().trim()) }
    }

    CommonSongListScreen(
        title = "New Songs",
        searchLabel = "Search New Songs (Artist or Title)",
        songs = newSongsList,
        viewModel = viewModel,
        emptyMessage = "No new songs in current queue.",
        isFromLikedOrDisliked = true,
    )
}

@Composable
fun AnimatedEqualizer(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 3
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")

    val height1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val height2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val height3 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val heights = listOf(height1, height2, height3)

    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.take(barCount).forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction)
                    .background(color = color, shape = RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
fun StackedDeckThumbnail(
    thumbnailUrl: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .offset(x = (-5).dp, y = (-5).dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        )
        Box(
            modifier = Modifier
                .size(50.dp)
                .offset(x = (-2.5).dp, y = (-2.5).dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
        )
        Box(
            modifier = Modifier
                .size(52.dp)
                .align(Alignment.BottomEnd)
        ) {
            if (thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(topStart = 6.dp, bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = "Playlist",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(2.5.dp)
                        .size(10.dp)
                )
            }
        }
    }
}

@Composable
fun SearchResultsDialog(
    query: String,
    playlistResults: List<PlaylistSearchResult> = emptyList(),
    results: List<Song>,
    onSelectPlaylist: (PlaylistSearchResult) -> Unit = {},
    onSelect: (Song) -> Unit,
    onDismiss: () -> Unit
) {
    val songPlaylists = remember(results) { results.filter { it.youtubeUrl.contains("list=") } }
    val songs = remember(results) { results.filter { !it.youtubeUrl.contains("list=") } }

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Search Results for \"$query\"", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = {
                            Text(
                                "Songs (${songs.size})",
                                fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = {
                            Text(
                                "Playlists (${playlistResults.size + songPlaylists.size})",
                                fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    if (selectedTabIndex == 0) {
                        if (songs.isEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No individual songs found.", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            items(songs, key = { it.id }) { song ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(song) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                        ) {
                                            val songThumb = if (song.thumbnailUrl.isNotBlank()) song.thumbnailUrl else if (song.youtubeUrl.contains("watch?v=")) "https://i.ytimg.com/vi/${song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")}/hqdefault.jpg" else ""
                                            if (songThumb.isNotBlank()) {
                                                AsyncImage(
                                                    model = songThumb,
                                                    contentDescription = song.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            song.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        val durationStr = song.getFormattedDuration()
                                        val subtitle = buildString {
                                            append(song.artist.ifBlank { "Unknown Artist" })
                                            if (!durationStr.isNullOrBlank()) {
                                                append(" • $durationStr")
                                            }
                                        }
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        FilledTonalButton(
                                            onClick = { onSelect(song) },
                                            modifier = Modifier.fillMaxWidth().height(32.dp),
                                            contentPadding = PaddingValues(vertical = 0.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Play", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (playlistResults.isEmpty() && songPlaylists.isEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No playlists found.", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            items(playlistResults, key = { it.playlistUrl }) { pl ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectPlaylist(pl) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1.2f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            StackedDeckThumbnail(
                                                thumbnailUrl = pl.thumbnailUrl,
                                                modifier = Modifier.size(64.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            pl.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        val subtitle = buildString {
                                            append(pl.uploader.ifBlank { "YouTube Playlist" })
                                            if (pl.songCount != null && pl.songCount > 0) {
                                                append(" • ${pl.songCount} songs")
                                            } else {
                                                append(" • Playlist")
                                            }
                                        }
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Button(
                                            onClick = { onSelectPlaylist(pl) },
                                            modifier = Modifier.fillMaxWidth().height(32.dp),
                                            contentPadding = PaddingValues(vertical = 0.dp)
                                        ) {
                                            Text("Load", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            items(songPlaylists, key = { it.id }) { pl ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(pl) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        val thumb = if (pl.thumbnailUrl.isNotBlank()) pl.thumbnailUrl else if (pl.youtubeUrl.contains("watch?v=")) "https://i.ytimg.com/vi/${pl.youtubeUrl.substringAfter("watch?v=").substringBefore("&")}/hqdefault.jpg" else ""
                                        Box(
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1.2f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            StackedDeckThumbnail(
                                                thumbnailUrl = thumb,
                                                modifier = Modifier.size(64.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            pl.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "${pl.artist.ifBlank { "YouTube Playlist" }} • Playlist",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Button(
                                            onClick = { onSelect(pl) },
                                            modifier = Modifier.fillMaxWidth().height(32.dp),
                                            contentPadding = PaddingValues(vertical = 0.dp)
                                        ) {
                                            Text("Load", fontSize = 12.sp)
                                        }
                                    }
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