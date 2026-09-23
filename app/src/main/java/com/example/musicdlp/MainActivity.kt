package com.example.musicdlp

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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val canGoBack by viewModel.canGoBack.collectAsState()

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
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Swipe") },
                        label = { Text("Swipe") },
                        selected = currentDestination == "swipe",
                        onClick = {
                            if (currentDestination != "swipe") {
                                navController.navigate("swipe") {
                                    popUpTo("swipe") { inclusive = true }
                                }
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Favorite, contentDescription = "Liked") },
                        label = { Text("Liked") },
                        selected = currentDestination == "liked",
                        onClick = { navController.navigate("liked") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.List, contentDescription = "Disliked") },
                        label = { Text("Disliked") },
                        selected = currentDestination == "disliked",
                        onClick = { navController.navigate("disliked") }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentDestination == "settings",
                        onClick = { navController.navigate("settings") }
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
    val song = currentPlayingSong ?: return

    val player = viewModel.exoPlayer
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

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

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = progress,
                onValueChange = { newProgress ->
                    progress = newProgress
                    player?.let { p ->
                        if (p.duration > 0) {
                            p.seekTo((newProgress * p.duration).toLong())
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .padding(horizontal = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                    .clickable { onNavigateToSwipe() },
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
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = song.artist.ifBlank { song.rawTitle ?: "Now Playing" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.playPreviousInContext() },
                        enabled = canGoPrevious
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                    }

                    IconButton(onClick = { viewModel.togglePlayPause() }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause"
                        )
                    }

                    IconButton(
                        onClick = { viewModel.playNextInContext() },
                        enabled = canGoNext
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next")
                    }
                }
            }
        }
    }
}
