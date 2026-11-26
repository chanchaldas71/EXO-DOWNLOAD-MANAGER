package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.NetworkTransportType
import com.example.engine.download.ChunkScheduler
import com.example.ui.theme.LocalPlexoIsDark
import com.example.ui.theme.PlexoNetworkColors

/**
 * High-performance 192-cell (16 × 12) chunk map drawn directly onto a single Compose [Canvas]
 * so 4 Hz live telemetry updates require zero child composable allocations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChunkMapCanvas(
    cells: IntArray,
    workStealCount: Int,
    modifier: Modifier = Modifier
) {
    val isDark = LocalPlexoIsDark.current
    val pendingColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
    val completedColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
    val wifiColor = PlexoNetworkColors.colorFor(NetworkTransportType.WIFI, isDark)
    val cellularColor = PlexoNetworkColors.colorFor(NetworkTransportType.CELLULAR, isDark)
    val ethernetColor = PlexoNetworkColors.colorFor(NetworkTransportType.ETHERNET, isDark)
    val otherColor = PlexoNetworkColors.colorFor(NetworkTransportType.VPN, isDark)

    val completedCount = cells.count { it == ChunkScheduler.CELL_COMPLETED }
    val activeCount = cells.count { it >= ChunkScheduler.CELL_ACTIVE_BASE }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "192-CELL CHUNK MAP",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$completedCount/192 done · $activeCount active" +
                        if (workStealCount > 0) " · $workStealCount stolen" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
            ) {
                val cols = 16
                val rows = 12
                val gap = 3.dp.toPx()
                val cellW = (size.width - gap * (cols - 1)) / cols
                val cellH = (size.height - gap * (rows - 1)) / rows
                val radius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())

                val total = minOf(cells.size, cols * rows)
                for (index in 0 until total) {
                    val r = index / cols
                    val c = index % cols
                    val x = c * (cellW + gap)
                    val y = r * (cellH + gap)

                    val value = cells[index]
                    val fill = when {
                        value == ChunkScheduler.CELL_COMPLETED -> completedColor
                        value >= ChunkScheduler.CELL_ACTIVE_BASE -> {
                            when (value - ChunkScheduler.CELL_ACTIVE_BASE) {
                                0 -> wifiColor
                                1 -> cellularColor
                                2 -> ethernetColor
                                else -> otherColor
                            }
                        }
                        else -> pendingColor
                    }

                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(x, y),
                        size = Size(cellW, cellH),
                        cornerRadius = radius
                    )

                    // Subtle bright core for actively downloading chunks
                    if (value >= ChunkScheduler.CELL_ACTIVE_BASE) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.35f),
                            topLeft = Offset(x + cellW * 0.25f, y + cellH * 0.25f),
                            size = Size(cellW * 0.5f, cellH * 0.5f),
                            cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LegendDot("Done", completedColor)
                LegendDot("Wi-Fi", wifiColor)
                LegendDot("5G/Cell", cellularColor)
                LegendDot("Ethernet", ethernetColor)
                LegendDot("Pending", pendingColor)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
