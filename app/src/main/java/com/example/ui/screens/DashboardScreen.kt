package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.entity.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.preferences.PlexoSettings
import com.example.engine.download.DownloadLiveMetrics
import com.example.engine.download.ResumeMismatchPrompt
import com.example.engine.network.NetworkAdapterInfo
import com.example.ui.theme.LocalPlexoIsDark
import com.example.ui.theme.PlexoMonoStyle
import com.example.ui.theme.PlexoNetworkColors
import com.example.ui.util.Formatters

@Composable
fun DashboardScreen(
    downloads: List<DownloadEntity>,
    adapters: List<NetworkAdapterInfo>,
    liveMetrics: Map<Long, DownloadLiveMetrics>,
    settings: PlexoSettings,
    freeStorageBytes: Long,
    selectedIds: Set<Long>,
    clipboardSuggestion: String?,
    resumeMismatchPrompt: ResumeMismatchPrompt?,
    expiredLinkTarget: DownloadEntity?,
    snackbarHostState: SnackbarHostState,
    onOpenNetworks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNewDownload: (prefillUrl: String) -> Unit,
    onDismissClipboard: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onPauseDownload: (Long) -> Unit,
    onResumeDownload: (Long) -> Unit,
    onDeleteDownload: (Long) -> Unit,
    onPauseSelected: () -> Unit,
    onResumeSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onMoveQueued: (Long, Boolean) -> Unit,
    onOpenExpiredLinkDialog: (DownloadEntity) -> Unit,
    onDismissExpiredLinkDialog: () -> Unit,
    onSubmitRefreshedUrl: (Long, String) -> Unit,
    onConfirmResumeMismatch: () -> Unit,
    onDismissResumeMismatch: () -> Unit,
    onOpenCompletedFile: (DownloadEntity) -> Unit,
    onToggleSlowMode: () -> Unit,
    onToggleSpeedUnit: () -> Unit
) {
    val isDark = LocalPlexoIsDark.current
    val enabledAdapters = adapters.filter { it.isEnabled }
    val totalLiveSpeed = liveMetrics.values.sumOf { it.totalSpeedBytesPerSec }

    val grouped = remember(downloads) {
        linkedMapOf(
            "Downloading" to downloads.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.VERIFYING },
            "Queued" to downloads.filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.queueOrder },
            "Paused" to downloads.filter { it.status == DownloadStatus.PAUSED },
            "Completed" to downloads.filter { it.status == DownloadStatus.COMPLETED },
            "Failed & Expired" to downloads.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.EXPIRED_LINK }
        ).filterValues { it.isNotEmpty() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectedIds.isNotEmpty()) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onClear = onClearSelection,
                    onPause = onPauseSelected,
                    onResume = onResumeSelected,
                    onDelete = onDeleteSelected
                )
            } else {
                PlexoDashboardTopBar(
                    adapters = adapters,
                    enabledCount = enabledAdapters.size,
                    totalSpeedBps = totalLiveSpeed,
                    useMbps = settings.speedUnitMbps,
                    isDark = isDark,
                    onOpenNetworks = onOpenNetworks,
                    onOpenSettings = onOpenSettings
                )
            }
        },
        bottomBar = {
            DashboardBottomStatusBar(
                freeStorageBytes = freeStorageBytes,
                slowModeEnabled = settings.slowModeEnabled,
                useMbps = settings.speedUnitMbps,
                onToggleSlowMode = onToggleSlowMode,
                onToggleSpeedUnit = onToggleSpeedUnit
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenNewDownload("") },
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_download)) },
                text = { Text("New") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_new_download")
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
            ) {
                AnimatedVisibility(visible = clipboardSuggestion != null) {
                    clipboardSuggestion?.let { clipUrl ->
                        ClipboardBanner(
                            url = clipUrl,
                            onDownload = {
                                onDismissClipboard()
                                onOpenNewDownload(clipUrl)
                            },
                            onDismiss = onDismissClipboard
                        )
                    }
                }

                if (downloads.isEmpty()) {
                    EmptyDashboardState(
                        adaptersCount = enabledAdapters.size,
                        onAddClick = { onOpenNewDownload("") },
                        onNetworksClick = onOpenNetworks
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 92.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        grouped.forEach { (sectionTitle, items) ->
                            item(key = "header_$sectionTitle") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = sectionTitle.uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${items.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            itemsIndexed(items, key = { _, item -> item.id }) { idx, entity ->
                                DownloadRowCard(
                                    entity = entity,
                                    metrics = liveMetrics[entity.id],
                                    isSelected = selectedIds.contains(entity.id),
                                    useMbps = settings.speedUnitMbps,
                                    isQueued = entity.status == DownloadStatus.QUEUED,
                                    canMoveUp = idx > 0,
                                    canMoveDown = idx < items.lastIndex,
                                    onClick = { onOpenDetail(entity.id) },
                                    onToggleSelect = { onToggleSelect(entity.id) },
                                    onPause = { onPauseDownload(entity.id) },
                                    onResume = { onResumeDownload(entity.id) },
                                    onDelete = { onDeleteDownload(entity.id) },
                                    onMoveUp = { onMoveQueued(entity.id, true) },
                                    onMoveDown = { onMoveQueued(entity.id, false) },
                                    onRefreshExpiredUrl = { onOpenExpiredLinkDialog(entity) },
                                    onOpenFile = { onOpenCompletedFile(entity) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Smart Resume Mismatch Dialog
    resumeMismatchPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onDismissResumeMismatch,
            title = { Text("Remote File Changed") },
            text = {
                Text(
                    "\"${prompt.fileName}\" changed on the server (${prompt.reason}). " +
                        "Resuming could corrupt the file. Restart from the beginning?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmResumeMismatch,
                    modifier = Modifier.testTag("confirm_restart_mismatch")
                ) {
                    Text("Restart Download")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissResumeMismatch) {
                    Text("Keep Paused")
                }
            }
        )
    }

    // Expired Link Recovery Dialog (HTTP 403 / 410)
    expiredLinkTarget?.let { target ->
        var refreshedUrl by remember(target.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismissExpiredLinkDialog,
            title = { Text("Refresh Expired Link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "The download link for \"${target.fileName}\" expired (HTTP 403/410). " +
                            "Paste a fresh link to resume right where you left off without losing downloaded chunks.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = refreshedUrl,
                        onValueChange = { refreshedUrl = it },
                        label = { Text("Fresh download URL") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("refresh_expired_url_input")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onSubmitRefreshedUrl(target.id, refreshedUrl) },
                    enabled = refreshedUrl.trim().startsWith("http", ignoreCase = true),
                    modifier = Modifier.testTag("confirm_refresh_url")
                ) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissExpiredLinkDialog) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PlexoDashboardTopBar(
    adapters: List<NetworkAdapterInfo>,
    enabledCount: Int,
    totalSpeedBps: Long,
    useMbps: Boolean,
    isDark: Boolean,
    onOpenNetworks: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Plexo",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (totalSpeedBps > 0L) {
                            Formatters.formatSpeed(totalSpeedBps, useMbps)
                        } else {
                            "Multi-Network Bonder"
                        },
                        style = PlexoMonoStyle.copy(fontSize = 11.sp),
                        color = if (totalSpeedBps > 0L) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Active Networks Pill
                Surface(
                    onClick = onOpenNetworks,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (enabledCount > 1) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    ),
                    modifier = Modifier.testTag("networks_pill_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val activeDots = adapters.filter { it.isEnabled }.take(3)
                        if (activeDots.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                            )
                        } else {
                            activeDots.forEach { adapter ->
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(
                                            PlexoNetworkColors.colorFor(adapter.transportType, isDark),
                                            CircleShape
                                        )
                                )
                            }
                        }
                        Text(
                            text = if (enabledCount == 1) "1 Net" else "$enabledCount Nets",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.cd_settings_button),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Clear selection")
            }
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onResume) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume selected")
            }
            IconButton(onClick = onPause) {
                Icon(Icons.Default.Pause, contentDescription = "Pause selected")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
            }
        }
    }
}

@Composable
private fun ClipboardBanner(
    url: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ContentPaste,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Link detected on clipboard",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onDownload) {
                Text("Download")
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun DownloadRowCard(
    entity: DownloadEntity,
    metrics: DownloadLiveMetrics?,
    isSelected: Boolean,
    useMbps: Boolean,
    isQueued: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRefreshExpiredUrl: () -> Unit,
    onOpenFile: () -> Unit
) {
    val downloaded = metrics?.downloadedBytes ?: entity.downloadedBytes
    val total = if (entity.totalBytes > 0L) entity.totalBytes else (metrics?.totalBytes ?: -1L)
    val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val speed = metrics?.totalSpeedBytesPerSec ?: 0L
    val eta = metrics?.etaSeconds ?: -1L

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (entity.status == DownloadStatus.DOWNLOADING) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("download_card_${entity.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when (entity.status) {
                        DownloadStatus.DOWNLOADING -> {
                            val nets = metrics?.perNetworkStats?.count { it.activeStreams > 0 } ?: 1
                            val streams = metrics?.activeStreamCount ?: 0
                            "$nets net(s) · $streams streams"
                        }
                        DownloadStatus.VERIFYING -> entity.errorMessage ?: "Verifying checksum…"
                        DownloadStatus.COMPLETED -> {
                            val hashBadge = if (entity.hashVerified == true) " · Checksum OK" else ""
                            "${Formatters.formatBytes(total)}$hashBadge"
                        }
                        DownloadStatus.EXPIRED_LINK -> "Link expired (403/410) — tap refresh"
                        DownloadStatus.FAILED -> entity.errorMessage ?: "Download failed"
                        DownloadStatus.PAUSED -> entity.errorMessage ?: "Paused"
                        DownloadStatus.QUEUED -> entity.errorMessage ?: "Queued"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (entity.status) {
                            DownloadStatus.FAILED, DownloadStatus.EXPIRED_LINK -> MaterialTheme.colorScheme.error
                            DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                            DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Queue reorder buttons for Queued downloads
                if (isQueued) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move up in queue", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move down in queue", modifier = Modifier.size(18.dp))
                    }
                }

                // Primary state action button
                when (entity.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING -> {
                        IconButton(
                            onClick = onPause,
                            modifier = Modifier.testTag("pause_download_${entity.id}")
                        ) {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = stringResource(R.string.cd_pause),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    DownloadStatus.EXPIRED_LINK -> {
                        IconButton(
                            onClick = onRefreshExpiredUrl,
                            modifier = Modifier.testTag("refresh_link_${entity.id}")
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh expired URL",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        IconButton(
                            onClick = onOpenFile,
                            modifier = Modifier.testTag("open_file_${entity.id}")
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = "Open file",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    else -> {
                        IconButton(
                            onClick = onResume,
                            modifier = Modifier.testTag("resume_download_${entity.id}")
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.cd_resume),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (entity.status == DownloadStatus.DOWNLOADING && total <= 0L) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            } else {
                LinearProgressIndicator(
                    progress = { if (entity.status == DownloadStatus.COMPLETED) 1f else progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape),
                    color = when (entity.status) {
                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                        DownloadStatus.FAILED, DownloadStatus.EXPIRED_LINK -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pctText = if (total > 0L) "${(progress * 100f).toInt()}%" else "—"
                Text(
                    text = "${Formatters.formatBytes(downloaded)} / ${Formatters.formatBytes(total)} ($pctText)",
                    style = PlexoMonoStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (entity.status == DownloadStatus.DOWNLOADING) {
                    Text(
                        text = "${Formatters.formatSpeed(speed, useMbps)} · ETA ${Formatters.formatEta(eta)}",
                        style = PlexoMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (entity.status == DownloadStatus.COMPLETED) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Done",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                } else if (entity.status == DownloadStatus.EXPIRED_LINK) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Expired",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else if (entity.status == DownloadStatus.FAILED) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDashboardState(
    adaptersCount: Int,
    onAddClick: () -> Unit,
    onNetworksClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.empty_downloads_title),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.empty_downloads_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onNetworksClick) {
                Text("$adaptersCount Active Network(s)")
            }
            androidx.compose.material3.Button(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add URL")
            }
        }
    }
}

@Composable
private fun DashboardBottomStatusBar(
    freeStorageBytes: Long,
    slowModeEnabled: Boolean,
    useMbps: Boolean,
    onToggleSlowMode: () -> Unit,
    onToggleSpeedUnit: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${Formatters.formatBytes(freeStorageBytes)} free",
                        style = PlexoMonoStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Slow Mode Toggle
                    Surface(
                        onClick = onToggleSlowMode,
                        shape = CircleShape,
                        color = if (slowModeEnabled) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.testTag("slow_mode_toggle")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = null,
                                tint = if (slowModeEnabled) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (slowModeEnabled) "Slow ON" else "Slow Mode",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (slowModeEnabled) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    // MB/s <-> Mbps Unit Switcher
                    Surface(
                        onClick = onToggleSpeedUnit,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.testTag("speed_unit_toggle")
                    ) {
                        Text(
                            text = if (useMbps) "Mbps" else "MB/s",
                            style = PlexoMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
