package com.opencode.remote.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opencode.remote.ui.connection.ConnectionMode
import com.opencode.remote.ui.connection.ConnectionScreen
import com.opencode.remote.ui.serverlist.ServerListScreen
import com.opencode.remote.ui.serverlist.ServerListViewModel
import com.opencode.remote.ui.sessions.SessionsScreen
import com.opencode.remote.ui.sessions.ProjectSessionsScreen
import com.opencode.remote.ui.chat.ChatScreen
import com.opencode.remote.ui.help.HelpScreen
import com.opencode.remote.ui.recent.RecentScreen
import com.opencode.remote.ui.recent.RecentViewModel
import com.opencode.remote.ui.settings.SettingsScreen
import com.opencode.remote.ui.update.UpdateViewModel

object Routes {
    const val SERVER_LIST = "serverList"
    const val ADD_SERVER = "addServer"
    const val EDIT_SERVER = "editServer/{serverId}"
    const val RECENT = "recent"
    const val SESSIONS = "sessions"
    const val PROJECT_SESSIONS = "project/{directory}"
    const val CHAT = "chat/{sessionId}?directory={directory}"
    const val HELP = "help"
    const val SETTINGS = "settings"

    fun chat(sessionId: String, directory: String? = null): String {
        val encodedDir = directory?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        return "chat/$sessionId?directory=$encodedDir"
    }
    fun projectSessions(directory: String) = "project/${java.net.URLEncoder.encode(directory, "UTF-8")}"
    fun editServer(serverId: String) = "editServer/$serverId"
}

@Composable
fun OConnectorApp(
    initialIntent: Intent? = null,
    intentState: MutableState<Intent?>? = null
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val pendingDeepLink = remember { mutableStateOf<Pair<String, String?>?>(null) }

    // Handle notification deep link from initial intent
    LaunchedEffect(initialIntent) {
        val intent = (context as? MainActivity)?.intent ?: initialIntent
        intent?.let { navigateFromIntent(it, navController, pendingDeepLink) }
    }

    // Handle notification deep link from subsequent intents (e.g. tapped while foregrounded)
    LaunchedEffect(intentState?.value) {
        val state = intentState ?: return@LaunchedEffect
        val intent = state.value ?: return@LaunchedEffect
        // Skip if this is the initial intent (already handled above)
        if (intent === initialIntent) return@LaunchedEffect

        // Debounce: delay 500ms and skip if a newer intent arrived
        delay(500)
        if (intent !== state.value) return@LaunchedEffect

        navigateFromIntent(intent, navController, pendingDeepLink)
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SERVER_LIST,
        enterTransition = { fadeIn(animationSpec = tween(300)) + slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(300)
        ) },
        exitTransition = { fadeOut(animationSpec = tween(300)) + slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(300)
        ) },
        popEnterTransition = { fadeIn(animationSpec = tween(300)) + slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(300)
        ) },
        popExitTransition = { fadeOut(animationSpec = tween(300)) + slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(300)
        ) },
    ) {
        // === Server List (home) ===
        composable(Routes.SERVER_LIST) {
            val viewModel: ServerListViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // Auto-navigate when a server connects (handles both normal auto-connect and deep-link)
            LaunchedEffect(uiState.connectedServerId) {
                if (uiState.connectedServerId != null) {
                    val deepLink = pendingDeepLink.value
                    if (deepLink != null) {
                        pendingDeepLink.value = null
                        navController.navigate(Routes.chat(deepLink.first, deepLink.second)) {
                            popUpTo(Routes.SERVER_LIST) { inclusive = true }
                        }
                    } else {
                        // Keep SERVER_LIST on the stack so the Recent page's back
                        // arrow returns to it without re-triggering navigation loops.
                        navController.navigate(Routes.RECENT)
                    }
                }
            }

            ServerListScreen(
                viewModel = viewModel,
                onAddServer = { navController.navigate(Routes.ADD_SERVER) },
                onServerSelected = { serverId -> viewModel.connectToServer(serverId) },
                onEditServer = { serverId -> navController.navigate(Routes.editServer(serverId)) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenRecent = {
                    navController.navigate(Routes.RECENT) {
                        launchSingleTop = true
                    }
                },
            )
        }

        // === Add Server ===
        composable(Routes.ADD_SERVER) {
            ConnectionScreen(
                mode = ConnectionMode.ADD_SERVER,
                onConnected = {
                    navController.navigate(Routes.RECENT) {
                        // Keep SERVER_LIST as the stack bottom, drop ADD_SERVER itself
                        popUpTo(Routes.SERVER_LIST)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // === Edit Server ===
        composable(Routes.EDIT_SERVER) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId") ?: return@composable
            ConnectionScreen(
                mode = ConnectionMode.EDIT_SERVER,
                serverId = serverId,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        // === Recent (landing page after connect) ===
        composable(Routes.RECENT) {
            val viewModel: RecentViewModel = hiltViewModel()
            RecentScreen(
                viewModel = viewModel,
                onOpenSession = { sessionId, directory ->
                    navController.navigate(Routes.chat(sessionId, directory))
                },
                onOpenAllProjects = { navController.navigate(Routes.SESSIONS) },
                onBack = { navController.popBackStack() },
            )
        }

        // === Sessions (all projects) ===
        composable(Routes.SESSIONS) {
            SessionsScreen(
                onProjectClick = { directory ->
                    navController.navigate(Routes.projectSessions(directory))
                },
                onDisconnected = {
                    navController.navigate(Routes.SERVER_LIST) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // === Project Sessions ===
        composable(Routes.PROJECT_SESSIONS) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("directory") ?: return@composable
            val directory = java.net.URLDecoder.decode(encoded, "UTF-8")
            // Share the same SessionsViewModel with SessionsScreen so session count updates are visible immediately
            // Use try-catch to handle deep link scenarios where SESSIONS route is not in the back stack
            // remember() — getBackStackEntry during composition must be keyed, or recomposition may re-resolve
            val sessionsEntry = remember(backStackEntry) {
                try {
                    navController.getBackStackEntry(Routes.SESSIONS)
                } catch (_: IllegalArgumentException) {
                    backStackEntry
                }
            }
            ProjectSessionsScreen(
                directory = directory,
                viewModel = hiltViewModel(sessionsEntry),
                onSessionClick = { sessionId ->
                    navController.navigate(Routes.chat(sessionId, directory))
                },
                onBack = { navController.popBackStack() },
            )
        }

        // === Chat ===
        composable(
            route = Routes.CHAT,
            enterTransition = { slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Up, animationSpec = tween(350)
            ) + fadeIn(animationSpec = tween(300)) },
            exitTransition = { slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Down, animationSpec = tween(350)
            ) + fadeOut(animationSpec = tween(300)) },
            popExitTransition = { slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Down, animationSpec = tween(350)
            ) + fadeOut(animationSpec = tween(300)) },
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val encodedDir = backStackEntry.arguments?.getString("directory") ?: ""
            val directory = if (encodedDir.isNotBlank()) java.net.URLDecoder.decode(encodedDir, "UTF-8") else null
            ChatScreen(
                sessionId = sessionId,
                directory = directory,
                onBack = { navController.popBackStack() }
            )
        }

        // === Help ===
        composable(Routes.HELP) {
            val updateVm: UpdateViewModel = hiltViewModel()
            HelpScreen(
                onBack = { navController.popBackStack() },
                updateViewModel = updateVm,
            )
        }

        // === Settings ===
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onHelp = { navController.navigate(Routes.HELP) },
            )
        }
    }
}

private fun navigateFromIntent(intent: Intent, navController: NavController, pendingDeepLink: MutableState<Pair<String, String?>?>) {
    val sessionId = intent.getStringExtra("sessionId") ?: return
    val directory = intent.getStringExtra("directory")
    // Only navigate if we're not already on the chat screen
    if (navController.currentDestination?.route?.startsWith("chat") == true) return

    // Store deep-link data for the ServerListScreen to consume after auto-connect
    pendingDeepLink.value = Pair(sessionId, directory)

    // Navigate to server list — the ViewModel auto-connects to the last server,
    // and the LaunchedEffect redirects to chat with the deep-link data
    navController.navigate(Routes.SERVER_LIST) {
        popUpTo(0) { inclusive = true }
    }
}
