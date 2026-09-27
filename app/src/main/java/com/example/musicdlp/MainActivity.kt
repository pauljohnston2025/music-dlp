package com.example.musicdlp

import com.example.musicdlp.data.SwipingMode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MoodBad
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.musicdlp.ui.theme.MusicDLPTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.musicdlp.ui.MusicViewModel
import com.example.musicdlp.ui.SwipingScreen
import com.example.musicdlp.ui.LikedSongsScreen
import com.example.musicdlp.ui.DislikedSongsScreen
import coil.compose.AsyncImage
import com.example.musicdlp.ui.SettingsScreen
import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicDLPTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val viewModel: MusicViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()
    val currentPlayingSong by viewModel.currentPlayingSong.collectAsState()
    val canGoBack by viewModel.canGoPreviousInContext.collectAsState()

    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Napier.d("POST_NOTIFICATIONS permission granted", tag = "DEBUG_METADATA")
            }
        }

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

// done with notification now
//    LaunchedEffect(currentPlayingSong) {
//        currentPlayingSong?.let { song ->
//            snackbarHostState.showSnackbar(
//                message = "Now Playing: ${song.artist.ifBlank { "MusicDLP" }} - ${song.title}",
//                duration = SnackbarDuration.Short
//            )
//        }
//    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination?.route

            Column {
                if (currentPlayingSong != null) {
                    BottomPlayerBar(
                        viewModel = viewModel,
                        onNavigateToSwipe = {
                            if (currentDestination != "swipe") {
                                navController.navigate("swipe") {
                                    popUpTo("swipe") { inclusive = true }
                                }
                            }
                        }
                    )
                }

                NavigationBar {
                    fun navigateTab(route: String) {
                        if (currentDestination != route) {
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }

                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Swipe") },
                        label = { Text("Swipe") },
                        selected = currentDestination == "swipe",
                        onClick = { navigateTab("swipe") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.FiberNew, contentDescription = "New") },
                        label = { Text("New") },
                        selected = currentDestination == "new",
                        onClick = { navigateTab("new") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Mood, contentDescription = "Liked") },
                        label = { Text("Liked") },
                        selected = currentDestination == "liked",
                        onClick = { navigateTab("liked") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.MoodBad, contentDescription = "Disliked") },
                        label = { Text("Disliked") },
                        selected = currentDestination == "disliked",
                        onClick = { navigateTab("disliked") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentDestination == "settings",
                        onClick = { navigateTab("settings") }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "swipe",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("swipe") { SwipingScreen(viewModel) }
            composable("new") { com.example.musicdlp.ui.NewSongsScreen(viewModel) }
            composable("liked") { LikedSongsScreen(viewModel) }
            composable("disliked") { DislikedSongsScreen(viewModel) }
            composable("settings") { SettingsScreen(viewModel) }
        }
    }
}

@Composable
fun BottomPlayerBar(
    viewModel: MusicViewModel,
    onNavigateToSwipe: () -> Unit
) {
    val currentPlayingSong by viewModel.currentPlayingSong.collectAsState()
    val canGoPrevious by viewModel.canGoPreviousInContext.collectAsState()
    val canGoNext by viewModel.canGoNextInContext.collectAsState()
    val swipingMode by viewModel.swipingMode.collectAsState()
    val song = currentPlayingSong ?: return

    val player = viewModel.exoPlayer
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(song.id) {
        progress = 0f
        currentPositionMs = 0L
        durationMs = 0L
    }

    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            isPlaying = player.isPlaying
            val dur = player.duration.coerceAtLeast(0L)
            val pos = player.currentPosition.coerceAtLeast(0L)
            durationMs = dur
            if (!isUserSeeking) {
                currentPositionMs = pos
                if (dur > 0) {
                    progress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                }
            }
            delay(300)
        }
    }

    fun formatMs(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Interactive, scrollable / seekable progress slider
            Slider(
                value = progress,
                onValueChange = { newValue ->
                    isUserSeeking = true
                    progress = newValue
                    if (durationMs > 0) currentPositionMs = (newValue * durationMs).toLong()
                },
                onValueChangeFinished = {
                    player?.let { p ->
                        if (p.duration > 0) {
                            val seekPos = (progress * p.duration).toLong()
                            p.seekTo(seekPos)
                        }
                    }
                    isUserSeeking = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(currentPositionMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatMs(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val effectiveThumbnail = if (song.thumbnailUrl.isBlank() && song.youtubeUrl.contains("watch?v=")) {
                    val id = song.youtubeUrl.substringAfter("watch?v=").substringBefore("&")
                    "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                } else {
                    song.thumbnailUrl
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToSwipe() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (effectiveThumbnail.isNotBlank()) {
                        AsyncImage(
                            model = effectiveThumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = song.artist.ifBlank { song.rawTitle ?: "Now Playing" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.cycleSwipingMode() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        val modeIcon = when (swipingMode) {
                            SwipingMode.ONLY_NEW -> Icons.Default.FiberNew
                            SwipingMode.NEW_AND_LIKED -> Icons.Default.LibraryMusic
                            SwipingMode.PLAY_ALL_RECATEGORISE -> Icons.Default.AllInclusive
                        }
                        val modeTint = when (swipingMode) {
                            SwipingMode.ONLY_NEW -> MaterialTheme.colorScheme.primary
                            SwipingMode.NEW_AND_LIKED -> MaterialTheme.colorScheme.primary
                            SwipingMode.PLAY_ALL_RECATEGORISE -> MaterialTheme.colorScheme.secondary
                        }
                        Icon(
                            imageVector = modeIcon,
                            contentDescription = swipingMode.displayName,
                            tint = modeTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.likeSong(song, advance = false) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (song.isLiked) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp,
                            contentDescription = "Like",
                            tint = if (song.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.dislikeSong(song, advance = false) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (song.isDisliked) Icons.Default.ThumbDown else Icons.Outlined.ThumbDown,
                            contentDescription = "Dislike",
                            tint = if (song.isDisliked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.playPreviousInContext() },
                        enabled = canGoPrevious,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.playNextInContext() },
                        enabled = canGoNext,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
