package com.example.musicdlp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination?.route
            
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
        }
    }
}
