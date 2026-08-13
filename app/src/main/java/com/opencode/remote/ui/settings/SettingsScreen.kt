package com.opencode.remote.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.remote.data.download.DownloadHelper
import com.opencode.remote.data.github.GitHubReleaseService
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.update.UpdateDialog
import com.opencode.remote.ui.update.UpdateUiState
import com.opencode.remote.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onHelp: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s = AppLocale.strings
    val context = LocalContext.current
    val language by viewModel.language.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val keepAlive by viewModel.keepAlive.collectAsStateWithLifecycle()

    // Battery-optimization state — refreshed every time the screen resumes,
    // since the user may jump to system settings and come back.
    var batteryOptimized by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryOptimized = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    val updateViewModel: UpdateViewModel = hiltViewModel()
    val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(language) {
        AppLocale.language = language
    }

    Scaffold(
        // The Settings screen is a bottom-nav tab; the NavigationBar below it handles the
        // navigation-bar inset, so only the TopAppBar's status-bar inset applies here.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(s.settingsTitle) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Language ──
            SettingsCard(title = s.settingsLanguage) {
                LanguageRow(
                    label = s.settingsLanguageZh,
                    selected = language == "zh",
                    onClick = { viewModel.setLanguage("zh") },
                )
                LanguageRow(
                    label = s.settingsLanguageEn,
                    selected = language == "en",
                    onClick = { viewModel.setLanguage("en") },
                )
            }

            // ── Appearance ──
            SettingsCard(title = s.settingsAppearance) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleDarkMode() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.settingsDarkMode,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = darkMode,
                        onCheckedChange = { viewModel.toggleDarkMode() },
                    )
                }
            }

            // ── Notifications ──
            SettingsCard(title = s.settingsNotifications) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setNotificationsEnabled(!notificationsEnabled) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.notificationsDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                    )
                }
            }

            // ── Keep alive (power saving) ──
            SettingsCard(title = s.settingsKeepAlive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setKeepAliveEnabled(!keepAlive) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.keepAliveDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = keepAlive,
                        onCheckedChange = { viewModel.setKeepAliveEnabled(it) },
                    )
                }
            }

            // ── Battery optimization (read-only status + jump to system settings) ──
            SettingsCard(title = s.batteryOptimization) {
                SettingsRow(
                    icon = Icons.Default.BatterySaver,
                    title = if (batteryOptimized) s.batteryOptimizationIgnored else s.batteryOptimizationNotIgnored,
                    subtitle = s.batteryOptimizationDesc,
                    trailing = s.batteryOptimizationAction,
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                )
            }

            // ── Update ──
            SettingsCard(title = s.settingsAbout) {
                SettingsRow(
                    icon = Icons.Default.SystemUpdate,
                    title = s.settingsCheckUpdate,
                    subtitle = updateSubtitle(updateState, s.settingsCheckUpdate),
                    onClick = {
                        updateViewModel.checkForUpdate()
                    },
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Default.IosShare,
                    title = s.settingsExportLogs,
                    subtitle = if (isExporting) s.helpCheckUpdateChecking else null,
                    enabled = !isExporting,
                    onClick = {
                        isExporting = true
                        scope.launch {
                            val uri = viewModel.exportLogs()
                            isExporting = false
                            if (uri == null) {
                                Toast.makeText(context, s.logExportNoLogs, Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("log", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, s.settingsExportLogs)
                            )
                        }
                    },
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Default.HelpOutline,
                    title = s.helpTitle,
                    onClick = onHelp,
                )
            }

            // ── Version ──
            Text(
                text = s.helpVersion,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally),
            )

            // ── Quick tips ──
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = s.tipsTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = s.tipsContent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    )
                }
            }
        }
    }

    if (showUpdateDialog && updateState is UpdateUiState.Available) {
        val avail = updateState as UpdateUiState.Available
        UpdateDialog(
            version = avail.version,
            changelog = avail.changelog,
            changelogTitle = s.updateChangelog,
            downloadText = s.updateDownload,
            closeText = s.updateClose,
            noChangelogText = "No changelog provided.",
            onDownload = {
                showUpdateDialog = false
                if (avail.downloadUrl != null) {
                    val proxiedUrl = GitHubReleaseService.proxiedDownloadUrl(avail.downloadUrl)
                    DownloadHelper.downloadApk(
                        context,
                        proxiedUrl,
                        "OConnector-v${avail.version}.apk"
                    )
                    Toast.makeText(context, s.updateDownload, Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(avail.releaseUrl))
                    context.startActivity(intent)
                }
            },
            onDismiss = { showUpdateDialog = false },
        )
    }

    LaunchedEffect(updateState) {
        if (updateState is UpdateUiState.Available) {
            showUpdateDialog = true
        }
    }

    var lastShownError by remember { mutableStateOf("") }
    LaunchedEffect(updateState) {
        if (updateState is UpdateUiState.Error) {
            val msg = (updateState as UpdateUiState.Error).message
            if (msg != lastShownError) {
                lastShownError = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Default.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}

private fun updateSubtitle(state: UpdateUiState, default: String): String {
    val s = AppLocale.strings
    return when (state) {
        is UpdateUiState.Checking -> s.updateChecking
        is UpdateUiState.Available -> "${s.updateAvailable}: v${state.version}"
        is UpdateUiState.UpToDate -> s.updateUpToDate
        is UpdateUiState.Error -> state.message
        is UpdateUiState.Idle -> default
    }
}
