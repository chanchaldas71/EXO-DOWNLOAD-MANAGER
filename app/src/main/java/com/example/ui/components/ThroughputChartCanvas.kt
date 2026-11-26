package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ui.theme.PlexoMonoStyle
import com.example.ui.util.Formatters

/**
 * High-performance 60-second real-time throughput line & area chart rendered purely on Canvas.
 */
@Composable
fun ThroughputChartCanvas(
    historyBytesPerSec: List<Long>,
    peakSpeedBytesPerSec: Long,
    useMbps: Boolean,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val safeHistory = if (historyBytesPerSec.size >= 2) historyBytesPerSec else List(60) { 0L }
    val maxScale = maxOf(safeHistory.maxOrNull() ?: 0L, peakSpeedBytesPerSec, 1024L)

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
                    text = "60-SECOND THROUGHPUT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Peak ${Formatters.formatSpeed(peakSpeedBytesPerSec, useMbps)}",
                    style = PlexoMonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    color = lineColor
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(124.dp)
            ) {
                val w = size.width
                val h = size.height
                val topPad = 6.dp.toPx()
                val bottomPad = 6.dp.toPx()
                val chartHeight = (h - topPad - bottomPad).coerceAtLeast(1f)

                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                for (g in 0..3) {
                    val y = topPad + (chartHeight / 3f) * g
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = if (g == 3) null else dashEffect
                    )
                }

                val stepX = w / (safeHistory.size - 1).coerceAtLeast(1)
                val points = safeHistory.mapIndexed { i, value ->
                    val ratio = (value.toFloat() / maxScale.toFloat()).coerceIn(0f, 1f)
                    Offset(
                        x = i * stepX,
                        y = topPad + chartHeight * (1f - ratio)
                    )
                }

                val strokePath = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        val prev = points[i - 1]
                        val curr = points[i]
                        val midX = (prev.x + curr.x) / 2f
                        cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                    }
                }

                val areaPath = Path().apply {
                    addPath(strokePath)
                    lineTo(points.last().x, topPad + chartHeight)
                    lineTo(points.first().x, topPad + chartHeight)
                    close()
                }

                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            lineColor.copy(alpha = 0.34f),
                            lineColor.copy(alpha = 0.02f)
                        ),
                        startY = topPad,
                        endY = topPad + chartHeight
                    )
                )

                drawPath(
                    path = strokePath,
                    color = lineColor,
                    style = Stroke(
                        width = 2.4.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Live right-edge beacon dot
                val tip = points.last()
                drawCircle(
                    color = lineColor.copy(alpha = 0.30f),
                    radius = 7.dp.toPx(),
                    center = tip
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = tip
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "-60s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "-30s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Now",
                    style = MaterialTheme.typography.labelSmall,
                    color = lineColor
                )
            }
        }
    }
}
