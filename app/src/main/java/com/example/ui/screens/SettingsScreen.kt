package com.example.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ThemeMode
import com.example.data.preferences.PlexoSettings
import com.example.ui.theme.PlexoMonoStyle
import com.example.ui.util.Formatters

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: PlexoSettings,
    onBack: () -> Unit,
    onUpdateFolder: (uri: String, label: String) -> Unit,
    onUpdateMaxSimultaneous: (Int) -> Unit,
    onUpdateChunkSize: (Long) -> Unit,
    onUpdateDefaultStreams: (ethernet: Int?, wifi: Int?, cellular: Int?) -> Unit,
    onUpdateGlobalSpeedLimit: (Long) -> Unit,
    onUpdateSlowModeLimit: (Long) -> Unit,
    onUpdateWifiOnlyGuardrail: (Boolean) -> Unit,
    onUpdateAllowVpn: (Boolean) -> Unit,
    onUpdateAutoSort: (Boolean) -> Unit,
    onUpdateClipboardWatcher: (Boolean) -> Unit,
    onUpdateScheduler: (enabled: Boolean, startHour: Int, startMin: Int, endHour: Int, endMin: Int) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateDynamicColor: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val label = uri.lastPathSegment?.substringAfterLast(':') ?: "Selected Folder"
            onUpdateFolder(uri.toString(), label)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .navigationBarsPadding()
            ) {
                // Storage & Files
                item {
                    SettingsGroupCard(title = "STORAGE & ORGANIZATION") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Default Download Folder", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = settings.defaultFolderLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                                Text("Choose")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        SettingsToggleRow(
                            title = "Auto-sort into subfolders",
                            subtitle = "Organize completed files into Video, Music, APKs, Archives, and Documents",
                            checked = settings.autoSortByType,
                            onCheckedChange = onUpdateAutoSort
                        )

                        SettingsToggleRow(
                            title = "Clipboard URL watcher",
                            subtitle = "Offer to start a download when Plexo opens with a copied link",
                            checked = settings.clipboardWatcherEnabled,
                            onCheckedChange = onUpdateClipboardWatcher
                        )
                    }
                }

                // Engine & Stream Bonding
                item {
                    SettingsGroupCard(title = "BONDING ENGINE & CHUNKS") {
                        StepperRow(
                            title = "Max Simultaneous Downloads",
                            valueText = "${settings.maxSimultaneousDownloads}",
                            onDecrement = { onUpdateMaxSimultaneous(settings.maxSimultaneousDownloads - 1) },
                            onIncrement = { onUpdateMaxSimultaneous(settings.maxSimultaneousDownloads + 1) }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text("Default Chunk Size", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        val chunkOptions = listOf(
                            1L * 1024L * 1024L to "1 MB",
                            2L * 1024L * 1024L to "2 MB",
                            4L * 1024L * 1024L to "4 MB",
                            8L * 1024L * 1024L to "8 MB",
                            16L * 1024L * 1024L to "16 MB"
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunkOptions.forEach { (bytes, label) ->
                                FilterChip(
                                    selected = settings.defaultChunkSizeBytes == bytes,
                                    onClick = { onUpdateChunkSize(bytes) },
                                    label = { Text(label) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        StepperRow(
                            title = "Ethernet Default Streams",
                            valueText = "${settings.ethernetDefaultStreams}",
                            onDecrement = { onUpdateDefaultStreams((settings.ethernetDefaultStreams - 1).coerceAtLeast(1), null, null) },
                            onIncrement = { onUpdateDefaultStreams((settings.ethernetDefaultStreams + 1).coerceAtMost(32), null, null) }
                        )
                        StepperRow(
                            title = "Wi-Fi Default Streams",
                            valueText = "${settings.wifiDefaultStreams}",
                            onDecrement = { onUpdateDefaultStreams(null, (settings.wifiDefaultStreams - 1).coerceAtLeast(1), null) },
                            onIncrement = { onUpdateDefaultStreams(null, (settings.wifiDefaultStreams + 1).coerceAtMost(32), null) }
                        )
                        StepperRow(
                            title = "Cellular Default Streams",
                            valueText = "${settings.cellularDefaultStreams}",
                            onDecrement = { onUpdateDefaultStreams(null, null, (settings.cellularDefaultStreams - 1).coerceAtLeast(1)) },
                            onIncrement = { onUpdateDefaultStreams(null, null, (settings.cellularDefaultStreams + 1).coerceAtMost(16)) }
                        )
                    }
                }

                // Bandwidth & Guardrails
                item {
                    SettingsGroupCard(title = "BANDWIDTH & GUARDRAILS") {
                        SettingsToggleRow(
                            title = "Wi-Fi / Ethernet Only Guardrail",
                            subtitle = "Prevent cellular mobile data usage globally until disabled",
                            checked = settings.wifiOnlyGuardrail,
                            onCheckedChange = onUpdateWifiOnlyGuardrail
                        )

                        SettingsToggleRow(
                            title = "Allow VPN Tunnel Bonding",
                            subtitle = "Include VPN interfaces in multi-network bonding (excluded by default)",
                            checked = settings.allowVpnBonding,
                            onCheckedChange = onUpdateAllowVpn
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Global Speed Limit", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        val globalLimits = listOf(
                            0L to "Unlimited",
                            2L * 1024L * 1024L to "2 MB/s",
                            5L * 1024L * 1024L to "5 MB/s",
                            10L * 1024L * 1024L to "10 MB/s",
                            25L * 1024L * 1024L to "25 MB/s"
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            globalLimits.forEach { (bps, label) ->
                                FilterChip(
                                    selected = settings.globalSpeedLimitBytesPerSec == bps,
                                    onClick = { onUpdateGlobalSpeedLimit(bps) },
                                    label = { Text(label) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Slow Mode Cap", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        val slowLimits = listOf(
                            256L * 1024L to "256 KB/s",
                            512L * 1024L to "512 KB/s",
                            1024L * 1024L to "1 MB/s",
                            2048L * 1024L to "2 MB/s"
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            slowLimits.forEach { (bps, label) ->
                                FilterChip(
                                    selected = settings.slowModeLimitBytesPerSec == bps,
                                    onClick = { onUpdateSlowModeLimit(bps) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                // Time-Based Download Scheduler
                item {
                    SettingsGroupCard(title = "DOWNLOAD SCHEDULER") {
                        SettingsToggleRow(
                            title = "Schedule Queued Downloads",
                            subtitle = "Automatically run queued downloads only during the active window",
                            checked = settings.schedulerEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateScheduler(
                                    enabled,
                                    settings.scheduleStartHour,
                                    settings.scheduleStartMinute,
                                    settings.scheduleEndHour,
                                    settings.scheduleEndMinute
                                )
                            }
                        )

                        if (settings.schedulerEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            StepperRow(
                                title = "Start Window",
                                valueText = Formatters.formatTime(settings.scheduleStartHour, settings.scheduleStartMinute),
                                onDecrement = {
                                    onUpdateScheduler(
                                        true,
                                        (settings.scheduleStartHour + 23) % 24,
                                        settings.scheduleStartMinute,
                                        settings.scheduleEndHour,
                                        settings.scheduleEndMinute
                                    )
                                },
                                onIncrement = {
                                    onUpdateScheduler(
                                        true,
                                        (settings.scheduleStartHour + 1) % 24,
                                        settings.scheduleStartMinute,
                                        settings.scheduleEndHour,
                                        settings.scheduleEndMinute
                                    )
                                }
                            )
                            StepperRow(
                                title = "End Window",
                                valueText = Formatters.formatTime(settings.scheduleEndHour, settings.scheduleEndMinute),
                                onDecrement = {
                                    onUpdateScheduler(
                                        true,
                                        settings.scheduleStartHour,
                                        settings.scheduleStartMinute,
                                        (settings.scheduleEndHour + 23) % 24,
                                        settings.scheduleEndMinute
                                    )
                                },
                                onIncrement = {
                                    onUpdateScheduler(
                                        true,
                                        settings.scheduleStartHour,
                                        settings.scheduleStartMinute,
                                        (settings.scheduleEndHour + 1) % 24,
                                        settings.scheduleEndMinute
                                    )
                                }
                            )
                        }
                    }
                }

                // Appearance
                item {
                    SettingsGroupCard(title = "APPEARANCE") {
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onUpdateThemeMode(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingsToggleRow(
                                title = "Material You Dynamic Color",
                                subtitle = "Adapt Plexo surfaces to your Android wallpaper palette",
                                checked = settings.dynamicColor,
                                onCheckedChange = onUpdateDynamicColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StepperRow(
    title: String,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrement, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease $title", modifier = Modifier.size(18.dp))
            }
            Text(
                text = valueText,
                style = PlexoMonoStyle.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onIncrement, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Increase $title", modifier = Modifier.size(18.dp))
            }
        }
    }
}
