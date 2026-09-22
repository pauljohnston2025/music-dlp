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

    LaunchedEffect(currentPlayingSong) {
        currentPlayingSong?.let { song ->
            snackbarHostState.showSnackbar(
                message = "Now Playing: ${song.artist.ifBlank { "MusicDLP" }} - ${song.title}",
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination?.route
            val showBottomFloatingBar = currentDestination != "swipe" && currentPlayingSong != null

            Column {
                // Floating Now Playing Notification Banner (Hidden on "swipe" screen)
                if (showBottomFloatingBar) {
                    val song = currentPlayingSong!!
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate("swipe") {
                                    popUpTo("swipe") { inclusive = true }
                                }
                                viewModel.resumeSwiping()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
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
                                    text = "Tap to resume swiping • ${song.artist.ifBlank { song.rawTitle ?: "Now Playing" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { viewModel.goBackToPreviousSong() },
                                    enabled = canGoBack
                                ) {
                                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                                }

                                IconButton(onClick = { viewModel.togglePlayPause() }) {
                                    Icon(
                                        imageVector = if (viewModel.exoPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause"
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val active = viewModel.currentPlayingSong.value ?: viewModel.songsToSwipe.value.firstOrNull()
                                        active?.let { viewModel.skipSong(it) }
                                    }
                                ) {
                                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                                }
                            }
                        }
                    }
                }

                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Swipe") },
                        label = { Text("Swipe") },
                        selected = currentDestination == "swipe",
                        onClick = {
                            navController.navigate("swipe") {
                                popUpTo("swipe") { inclusive = true }
                            }
                            viewModel.resumeSwiping()
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
