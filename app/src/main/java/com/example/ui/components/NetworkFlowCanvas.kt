package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.NetworkTransportType
import com.example.engine.download.NetworkDownloadStats
import com.example.ui.theme.LocalPlexoIsDark
import com.example.ui.theme.PlexoMonoStyle
import com.example.ui.theme.PlexoNetworkColors
import com.example.ui.util.Formatters

/**
 * Animated Compose Canvas diagram illustrating concurrent network interfaces (Wi-Fi, 5G, Ethernet)
 * bonding their individual byte streams into a single unified Plexo file stream.
 */
@Composable
fun NetworkFlowCanvas(
    perNetworkStats: List<NetworkDownloadStats>,
    totalSpeedBytesPerSec: Long,
    useMbps: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val isDark = LocalPlexoIsDark.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceElevated = MaterialTheme.colorScheme.surfaceContainerHigh

    val infiniteTransition = rememberInfiniteTransition(label = "network_flow_pulse")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_phase"
    )

    val displayNodes = if (perNetworkStats.isNotEmpty()) {
        perNetworkStats.take(4)
    } else {
        listOf(
            NetworkDownloadStats(
                networkId = "idle_wifi",
                displayName = "Wi-Fi",
                transportType = NetworkTransportType.WIFI,
                status = com.example.data.model.ReachabilityState.READY,
                activeStreams = 0,
                maxStreams = 8,
                speedBytesPerSec = 0L,
                downloadedBytes = 0L,
                sharePercent = 0f
            )
        )
    }

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
                    text = "LIVE BONDING FLOW",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isActive) {
                        "${displayNodes.count { it.activeStreams > 0 }} LINK(S) BONDED"
                    } else {
                        "STANDBY"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isActive) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp)
                    .padding(top = 8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val leftAnchorX = w * 0.34f
                    val hubCenterX = w * 0.78f
                    val hubCenterY = h * 0.5f
                    val hubRadius = 28.dp.toPx()

                    val nodeCount = displayNodes.size
                    val spacing = h / (nodeCount + 1)

                    displayNodes.forEachIndexed { idx, stat ->
                        val nodeColor = PlexoNetworkColors.colorFor(stat.transportType, isDark)
                        val startY = spacing * (idx + 1)
                        val start = Offset(leftAnchorX, startY)
                        val end = Offset(hubCenterX - hubRadius, hubCenterY)

                        val ctrl1 = Offset(leftAnchorX + (hubCenterX - leftAnchorX) * 0.45f, startY)
                        val ctrl2 = Offset(leftAnchorX + (hubCenterX - leftAnchorX) * 0.55f, hubCenterY)

                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            cubicTo(ctrl1.x, ctrl1.y, ctrl2.x, ctrl2.y, end.x, end.y)
                        }

                        val activeLine = isActive && (stat.activeStreams > 0 || stat.speedBytesPerSec > 0L)
                        val shareWeight = (stat.sharePercent / 100f).coerceIn(0.15f, 1f)
                        val strokeWidth = if (activeLine) (2.2f + 4.2f * shareWeight).dp.toPx() else 1.8.dp.toPx()

                        // Base conduit track
                        drawPath(
                            path = path,
                            color = if (activeLine) nodeColor.copy(alpha = 0.25f) else outlineColor.copy(alpha = 0.35f),
                            style = Stroke(width = strokeWidth + 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawPath(
                            path = path,
                            color = if (activeLine) nodeColor.copy(alpha = 0.85f) else outlineColor.copy(alpha = 0.5f),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Animated traveling data pulses along the cubic Bézier curve
                        if (activeLine) {
                            val pulses = (stat.activeStreams.coerceIn(1, 3))
                            for (p in 0 until pulses) {
                                val t = (phase + (p.toFloat() / pulses.toFloat())) % 1f
                                val pt = cubicBezierPoint(start, ctrl1, ctrl2, end, t)
                                drawCircle(
                                    color = nodeColor.copy(alpha = 0.32f),
                                    radius = 7.dp.toPx(),
                                    center = pt
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 3.dp.toPx(),
                                    center = pt
                                )
                            }
                        }

                        // Left terminal dot
                        drawCircle(
                            color = nodeColor,
                            radius = 4.5.dp.toPx(),
                            center = start
                        )
                    }

                    // Unified Plexo bonding hub on the right
                    val hubGlow = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = if (isActive) 0.40f else 0.12f),
                            Color.Transparent
                        ),
                        center = Offset(hubCenterX, hubCenterY),
                        radius = hubRadius * 1.9f
                    )
                    drawCircle(brush = hubGlow, radius = hubRadius * 1.9f, center = Offset(hubCenterX, hubCenterY))
                    drawRoundRect(
                        color = surfaceElevated,
                        topLeft = Offset(hubCenterX - hubRadius, hubCenterY - hubRadius),
                        size = Size(hubRadius * 2f, hubRadius * 2f),
                        cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                    )
                    drawRoundRect(
                        color = if (isActive) primaryColor else outlineColor,
                        topLeft = Offset(hubCenterX - hubRadius, hubCenterY - hubRadius),
                        size = Size(hubRadius * 2f, hubRadius * 2f),
                        cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Left labels aligned to each adapter node
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.34f)
                        .height(152.dp)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    displayNodes.forEach { stat ->
                        val color = PlexoNetworkColors.colorFor(stat.transportType, isDark)
                        Column {
                            Text(
                                text = stat.displayName,
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                                color = color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = Formatters.formatSpeed(stat.speedBytesPerSec, useMbps),
                                style = PlexoMonoStyle.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Right unified speed readout centered over the hub
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "PLEXO",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = primaryColor
                    )
                    Text(
                        text = Formatters.formatSpeed(totalSpeedBytesPerSec, useMbps),
                        style = PlexoMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun cubicBezierPoint(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    p3: Offset,
    t: Float
): Offset {
    val u = 1f - t
    val tt = t * t
    val uu = u * u
    val uuu = uu * u
    val ttt = tt * t
    val x = uuu * p0.x + 3f * uu * t * p1.x + 3f * u * tt * p2.x + ttt * p3.x
    val y = uuu * p0.y + 3f * uu * t * p1.y + 3f * u * tt * p2.y + ttt * p3.y
    return Offset(x, y)
}
