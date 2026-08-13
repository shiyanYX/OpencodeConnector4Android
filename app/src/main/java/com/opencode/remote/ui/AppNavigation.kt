package com.opencode.remote.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opencode.remote.data.repository.ConnectionStatus
import com.opencode.remote.ui.chat.ChatScreen
import com.opencode.remote.ui.connection.ConnectionMode
import com.opencode.remote.ui.connection.ConnectionScreen
import com.opencode.remote.ui.help.HelpScreen
import com.opencode.remote.ui.recent.RecentScreen
import com.opencode.remote.ui.recent.RecentViewModel
import com.opencode.remote.ui.serverlist.ServerListScreen
import com.opencode.remote.ui.serverlist.ServerListViewModel
import com.opencode.remote.ui.sessions.ProjectSessionsScreen
import com.opencode.remote.ui.sessions.SessionsScreen
import com.opencode.remote.ui.settings.SettingsScreen
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.update.UpdateViewModel
import kotlinx.coroutines.delay

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

/**
 * Bottom-navigation tab destinations. Every other route is a pushed sub-screen
 * that hides the bottom bar and shows a back arrow instead.
 */
private val TAB_ROUTES = setOf(Routes.SERVER_LIST, Routes.RECENT, Routes.SETTINGS)

@Composable
fun OConnectorApp(
    initialIntent: Intent? = null,
    intentState: MutableState<Intent?>? = null
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val pendingDeepLink = remember { mutableStateOf<Pair<String, String?>?>(null) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

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

    Scaffold(
        // The screen-level Scaffolds already apply the status-bar inset via their TopAppBars;
        // here we only contribute the bottom bar so tab content sits flush above it.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentRoute in TAB_ROUTES) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            // Standard tab switching: pop to start, save/restore each tab's state
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            modifier = Modifier.padding(innerPadding),
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
            // === Server List (home tab) ===
            composable(Routes.SERVER_LIST) {
                val viewModel: ServerListViewModel = hiltViewModel()
                // Connected id comes from the repository's single connection state.
                val connState by viewModel.connectionState.collectAsStateWithLifecycle()
                val connectedServerId = (connState as? ConnectionStatus.Connected)?.serverId
                // True only after the user taps a server card (not for startup auto-connect).
                val goProjects by viewModel.navigateToProjects.collectAsStateWithLifecycle()

                // Notification deep link: after auto-connect, jump straight to the tapped chat.
                LaunchedEffect(connectedServerId) {
                    val deepLink = pendingDeepLink.value
                    if (connectedServerId != null && deepLink != null) {
                        pendingDeepLink.value = null
                        navController.navigate(Routes.chat(deepLink.first, deepLink.second)) {
                            popUpTo(Routes.SERVER_LIST) { inclusive = true }
                        }
                    }
                }

                // User tapped a server card → connect succeeded → land on the Projects screen.
                LaunchedEffect(goProjects) {
                    if (goProjects) {
                        viewModel.consumeNavigateToProjects()
                        navController.navigate(Routes.SESSIONS)
                    }
                }

                ServerListScreen(
                    viewModel = viewModel,
                    onAddServer = { navController.navigate(Routes.ADD_SERVER) },
                    onServerSelected = { serverId -> viewModel.connectToServer(serverId) },
                    onEditServer = { serverId -> navController.navigate(Routes.editServer(serverId)) },
                    onOpenProjects = { navController.navigate(Routes.SESSIONS) },
                )
            }

            // === Add Server ===
            composable(Routes.ADD_SERVER) {
                ConnectionScreen(
                    mode = ConnectionMode.ADD_SERVER,
                    onConnected = {
                        navController.navigate(Routes.SESSIONS) {
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

            // === Recent (tab) ===
            composable(Routes.RECENT) {
                val viewModel: RecentViewModel = hiltViewModel()
                RecentScreen(
                    viewModel = viewModel,
                    onOpenSession = { sessionId, directory ->
                        navController.navigate(Routes.chat(sessionId, directory))
                    },
                    onOpenAllProjects = { navController.navigate(Routes.SESSIONS) },
                )
            }

            // === Projects (sub-screen reached after connect or from Recent) ===
            composable(Routes.SESSIONS) {
                SessionsScreen(
                    onProjectClick = { directory ->
                        navController.navigate(Routes.projectSessions(directory))
                    },
                    onDisconnected = {
                        navController.navigate(Routes.SERVER_LIST) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
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

            // === Settings (tab) ===
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onHelp = { navController.navigate(Routes.HELP) },
                )
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val s = AppLocale.strings
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.SERVER_LIST,
            onClick = { onNavigate(Routes.SERVER_LIST) },
            icon = {
                Icon(
                    imageVector = if (currentRoute == Routes.SERVER_LIST) Icons.Filled.Home else Icons.Outlined.Home,
                    contentDescription = s.servers,
                )
            },
            label = { Text(s.servers) },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.RECENT,
            onClick = { onNavigate(Routes.RECENT) },
            icon = {
                Icon(
                    imageVector = if (currentRoute == Routes.RECENT) Icons.Filled.History else Icons.Outlined.History,
                    contentDescription = s.recentTitle,
                )
            },
            label = { Text(s.recentTitle) },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SETTINGS,
            onClick = { onNavigate(Routes.SETTINGS) },
            icon = {
                Icon(
                    imageVector = if (currentRoute == Routes.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                    contentDescription = s.settingsTitle,
                )
            },
            label = { Text(s.settingsTitle) },
        )
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
