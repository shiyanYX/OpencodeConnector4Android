package com.opencode.remote.ui.recent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.remote.data.datastore.RecentSessionEntry
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.util.TimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    viewModel: RecentViewModel,
    onOpenSession: (sessionId: String, directory: String?) -> Unit,
    onOpenAllProjects: () -> Unit,
) {
    val s = AppLocale.strings
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var pendingRemove by remember { mutableStateOf<RecentSessionEntry?>(null) }

    Scaffold(
        // The Recent screen is a bottom-nav tab; the NavigationBar below it handles the
        // navigation-bar inset, so only the TopAppBar's status-bar inset applies here.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(s.recentTitle) },
                actions = {
                    // "All Projects" entry — always available, so the user can always
                    // open sessions beyond the 10 most recent ones.
                    IconButton(onClick = onOpenAllProjects) {
                        Icon(Icons.Default.ViewList, contentDescription = s.openAllProjects)
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyRecentState(onOpenAllProjects = onOpenAllProjects, padding = padding)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(entries, key = { it.sessionId }) { entry ->
                    RecentSessionRow(
                        entry = entry,
                        onClick = { onOpenSession(entry.sessionId, entry.directory.ifBlank { null }) },
                        onLongClick = { pendingRemove = entry },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    pendingRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(s.recentDeleteConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(entry.serverId, entry.sessionId)
                    pendingRemove = null
                }) {
                    Text(s.delete, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(s.close)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecentSessionRow(
    entry: RecentSessionEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val title = entry.title.ifBlank {
        entry.directory.substringAfterLast('/').ifBlank { entry.directory }
    }
    val directory = entry.directory.ifBlank { null }
    val time = TimeFormatter.formatRelativeTime(entry.openedAt)

    ListItem(
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            if (directory != null || time.isNotEmpty()) {
                Text(
                    text = listOfNotNull(directory, time.ifBlank { null }).joinToString(" · "),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    )
}

@Composable
private fun EmptyRecentState(
    onOpenAllProjects: () -> Unit,
    padding: PaddingValues,
) {
    val s = AppLocale.strings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = s.recentEmpty,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = s.recentEmptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpenAllProjects) {
            Text(
                text = s.openAllProjects,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
