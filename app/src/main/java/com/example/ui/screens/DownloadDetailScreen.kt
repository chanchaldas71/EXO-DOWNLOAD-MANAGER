package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.entity.ChunkEntity
import com.example.data.local.entity.DownloadEntity
import com.example.data.model.ChunkState
import com.example.data.model.DownloadStatus
import com.example.engine.download.ChunkScheduler
import com.example.engine.download.DownloadLiveMetrics
import com.example.engine.download.NetworkDownloadStats
import com.example.ui.components.ChunkMapCanvas
import com.example.ui.components.NetworkFlowCanvas
import com.example.ui.components.ThroughputChartCanvas
import com.example.ui.theme.LocalPlexoIsDark
import com.example.ui.theme.PlexoMonoStyle
import com.example.ui.theme.PlexoNetworkColors
import com.example.ui.util.Formatters

@Composable
fun DownloadDetailScreen(
    download: DownloadEntity?,
    chunks: List<ChunkEntity>,
    liveMetrics: DownloadLiveMetrics?,
    useMbps: Boolean,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpenFile: () -> Unit,
    onRefreshExpiredLink: () -> Unit,
    onAdjustNetworkStreams: (networkId: String, newStreams: Int) -> Unit
) {
    val isDark = LocalPlexoIsDark.current

    // Build offline/paused 192-cell chunk map directly from Room chunks when live metrics aren't running
    val chunkCells = remember(liveMetrics, chunks, download?.totalBytes, download?.status) {
        if (liveMetrics != null) {
            liveMetrics.chunkCells
        } else {
            buildOfflineCells(download, chunks)
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
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = download?.fileName ?: "Download Details",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = download?.status?.name ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (download != null) {
                        when (download.status) {
                            DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING -> {
                                IconButton(onClick = onPause) {
                                    Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.cd_pause))
                                }
                            }
                            DownloadStatus.COMPLETED -> {
                                IconButton(onClick = onOpenFile) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Open file")
                                }
                            }
                            DownloadStatus.EXPIRED_LINK -> {
                                IconButton(onClick = onRefreshExpiredLink) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh URL")
                                }
                            }
                            else -> {
                                IconButton(onClick = onResume) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_resume))
                                }
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (download == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("Download not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        val downloaded = liveMetrics?.downloadedBytes ?: download.downloadedBytes
        val total = if (download.totalBytes > 0L) download.totalBytes else (liveMetrics?.totalBytes ?: -1L)
        val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .navigationBarsPadding()
            ) {
                // 1. Live Network Flow Graphic
                item(key = "flow") {
                    NetworkFlowCanvas(
                        perNetworkStats = liveMetrics?.perNetworkStats.orEmpty(),
                        totalSpeedBytesPerSec = liveMetrics?.totalSpeedBytesPerSec ?: 0L,
                        useMbps = useMbps,
                        isActive = download.status == DownloadStatus.DOWNLOADING
                    )
                }

                // 2. Metrics Header Card (Total / Average / Peak / ETA + Progress)
                item(key = "metrics_header") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                MetricTile(
                                    label = "TOTAL",
                                    value = Formatters.formatSpeed(liveMetrics?.totalSpeedBytesPerSec ?: 0L, useMbps),
                                    highlight = true,
                                    modifier = Modifier.weight(1f)
                                )
                                MetricTile(
                                    label = "AVERAGE",
                                    value = Formatters.formatSpeed(liveMetrics?.averageSpeedBytesPerSec ?: 0L, useMbps),
                                    modifier = Modifier.weight(1f)
                                )
                                MetricTile(
                                    label = "PEAK",
                                    value = Formatters.formatSpeed(liveMetrics?.peakSpeedBytesPerSec ?: 0L, useMbps),
                                    modifier = Modifier.weight(1f)
                                )
                                MetricTile(
                                    label = "ETA",
                                    value = Formatters.formatEta(liveMetrics?.etaSeconds ?: -1L),
                                    modifier = Modifier.weight(0.75f)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            LinearProgressIndicator(
                                progress = { if (download.status == DownloadStatus.COMPLETED) 1f else progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(CircleShape),
                                color = if (download.status == DownloadStatus.COMPLETED) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${Formatters.formatBytes(downloaded)} of ${Formatters.formatBytes(total)}",
                                    style = PlexoMonoStyle.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${(progress * 100f).toInt()}%",
                                    style = PlexoMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Shared-upstream hint banner if Wi-Fi + Ethernet share the exact same router bottleneck
                if (liveMetrics?.sharedUpstreamHint == true) {
                    item(key = "shared_hint") {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                Text(
                                    text = "Both active adapters appear to share the same upstream router. Bond with 5G/4G Cellular for additive bandwidth.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }

                // Expired link quick action
                if (download.status == DownloadStatus.EXPIRED_LINK) {
                    item(key = "expired_cta") {
                        Button(
                            onClick = onRefreshExpiredLink,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Paste Refreshed URL to Resume")
                        }
                    }
                }

                // 3. 60-Second Throughput Chart
                item(key = "throughput_chart") {
                    ThroughputChartCanvas(
                        historyBytesPerSec = liveMetrics?.throughputHistory ?: List(60) { 0L },
                        peakSpeedBytesPerSec = liveMetrics?.peakSpeedBytesPerSec ?: 0L,
                        useMbps = useMbps
                    )
                }

                // 4. 192-Cell Chunk Map
                item(key = "chunk_map") {
                    ChunkMapCanvas(
                        cells = chunkCells,
                        workStealCount = liveMetrics?.workStealCount ?: 0
                    )
                }

                // 5. Per-Network Breakdown Table
                item(key = "per_network_table") {
                    PerNetworkBreakdownTable(
                        stats = liveMetrics?.perNetworkStats.orEmpty(),
                        useMbps = useMbps,
                        isDark = isDark,
                        onAdjustStreams = onAdjustNetworkStreams
                    )
                }

                // 6. File & Integrity Metadata Card
                item(key = "metadata") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "TRANSFER METADATA",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            MetaLine("Source URL", download.finalUrl)
                            MetaLine("Range Support", if (download.supportsRanges) "Byte ranges (Multi-stream)" else "Single stream only")
                            MetaLine("Chunk Size", Formatters.formatBytes(download.chunkSizeBytes))
                            if (!download.etag.isNullOrBlank()) {
                                MetaLine("ETag", download.etag)
                            }
                            if (download.hashVerified != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (download.hashVerified) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${download.hashAlgorithm.label}: " +
                                            if (download.hashVerified) "Verified" else "Mismatch",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (download.hashVerified) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = PlexoMonoStyle.copy(
                fontSize = if (highlight) 14.sp else 12.sp,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PerNetworkBreakdownTable(
    stats: List<NetworkDownloadStats>,
    useMbps: Boolean,
    isDark: Boolean,
    onAdjustStreams: (networkId: String, newStreams: Int) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "PER-NETWORK BREAKDOWN",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (stats.isEmpty()) {
                Text(
                    text = "Start or resume this download to view live per-network stream share and bandwidth contribution.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Network", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Streams", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.0f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Speed", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.9f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Share", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.6f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                stats.forEach { row ->
                    val accent = PlexoNetworkColors.colorFor(row.transportType, isDark)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1.4f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(accent, CircleShape)
                                )
                                Text(
                                    text = row.displayName,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${row.status.label} · ${Formatters.formatBytes(row.downloadedBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }

                        Row(
                            modifier = Modifier.weight(1.0f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onAdjustStreams(row.networkId, (row.maxStreams - 1).coerceAtLeast(1)) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Fewer streams", modifier = Modifier.size(14.dp))
                            }
                            Text(
                                text = "${row.activeStreams}/${row.maxStreams}",
                                style = PlexoMonoStyle.copy(fontSize = 11.sp),
                                color = accent
                            )
                            IconButton(
                                onClick = { onAdjustStreams(row.networkId, (row.maxStreams + 1).coerceAtMost(32)) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "More streams", modifier = Modifier.size(14.dp))
                            }
                        }

                        Text(
                            text = Formatters.formatSpeed(row.speedBytesPerSec, useMbps),
                            style = PlexoMonoStyle.copy(fontSize = 11.sp),
                            modifier = Modifier.weight(0.9f),
                            maxLines = 1
                        )

                        Text(
                            text = "${row.sharePercent.toInt()}%",
                            style = PlexoMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = accent,
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = PlexoMonoStyle.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

private fun buildOfflineCells(download: DownloadEntity?, chunks: List<ChunkEntity>): IntArray {
    val cells = IntArray(ChunkScheduler.CHUNK_MAP_CELL_COUNT)
    if (download == null) return cells
    if (download.status == DownloadStatus.COMPLETED) {
        cells.fill(ChunkScheduler.CELL_COMPLETED)
        return cells
    }
    val total = download.totalBytes
    if (total <= 0L || chunks.isEmpty()) return cells
    val bytesPerCell = total.toDouble() / cells.size.toDouble()
    chunks.forEach { chunk ->
        val startCell = (chunk.startByte / bytesPerCell).toInt().coerceIn(0, cells.lastIndex)
        val endCell = (chunk.endByte / bytesPerCell).toInt().coerceIn(0, cells.lastIndex)
        val doneUpTo = (chunk.startByte + chunk.downloadedBytes - 1L).coerceAtMost(chunk.endByte)
        for (c in startCell..endCell) {
            val cellEnd = (((c + 1) * bytesPerCell).toLong() - 1L)
            if (chunk.state == ChunkState.COMPLETED || doneUpTo >= cellEnd) {
                cells[c] = ChunkScheduler.CELL_COMPLETED
            }
        }
    }
    return cells
}
