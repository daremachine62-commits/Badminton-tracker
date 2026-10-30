package com.example.badmintontracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TrainingTreemap(sessions: List<TrainingSession>) {
    val groups = remember(sessions) {
        sessions
            .groupBy { session ->
                YearMonth.from(
                    Instant.ofEpochMilli(session.completedAtMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate(),
                )
            }
            .map { (month, monthSessions) ->
                TreemapGroup(
                    month = month,
                    sessions = monthSessions,
                    weight = monthSessions.sumOf { it.durationMillis.toDouble().coerceAtLeast(1.0) },
                )
            }
            .sortedByDescending { it.month }
    }
    val colorScheme = MaterialTheme.colorScheme
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()) }
    val labelFormat = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val maxHitCount = sessions.maxOfOrNull { it.hitCount } ?: 0

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Session treemap", style = MaterialTheme.typography.titleLarge)
        Text(
            "Recent sessions: tile area shows duration; color shows tagged hits. Groups are by month.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (sessions.isEmpty()) {
            Text("Complete a session to see it here.", modifier = Modifier.padding(top = 8.dp))
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(top = 8.dp)
                    .semantics {
                        contentDescription =
                            "Treemap of the ${sessions.size} most recent training sessions, " +
                                "grouped by month. Tile area represents duration and color represents tagged hits."
                    },
            ) {
                val gap = 2.dp.toPx()
                val headerHeight = 18.dp.toPx()
                val outerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 12.sp.toPx()
                    color = colorScheme.onSurface.toArgb()
                }
                val sessionPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 11.sp.toPx()
                }
                val secondaryPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 10.sp.toPx()
                }
                val maxHits = maxHitCount.toFloat().coerceAtLeast(1f)

                layoutTreemap(groups, Rect(0f, 0f, size.width, size.height), { it.weight }) { group, bounds ->
                    val groupBounds = bounds.inset(gap)
                    if (groupBounds.width <= 0f || groupBounds.height <= headerHeight) {
                        return@layoutTreemap
                    }

                    drawRect(
                        color = colorScheme.surfaceVariant,
                        topLeft = Offset(groupBounds.left, groupBounds.top),
                        size = androidx.compose.ui.geometry.Size(groupBounds.width, groupBounds.height),
                    )
                    drawRect(
                        color = colorScheme.outline.copy(alpha = 0.7f),
                        topLeft = Offset(groupBounds.left, groupBounds.top),
                        size = androidx.compose.ui.geometry.Size(groupBounds.width, groupBounds.height),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        group.month.format(dateFormat),
                        groupBounds.left + gap,
                        groupBounds.top + outerPaint.textSize,
                        outerPaint,
                    )

                    val contents = Rect(
                        groupBounds.left + gap,
                        groupBounds.top + headerHeight,
                        groupBounds.right - gap,
                        groupBounds.bottom - gap,
                    )
                    if (contents.width <= 0f || contents.height <= 0f) {
                        return@layoutTreemap
                    }
                    layoutTreemap(
                        group.sessions,
                        contents,
                        { it.durationMillis.toDouble().coerceAtLeast(1.0) },
                    ) { session, sessionBounds ->
                        val tile = sessionBounds.inset(gap / 2f)
                        if (tile.width <= 0f || tile.height <= 0f) {
                            return@layoutTreemap
                        }
                        val hitRatio = (session.hitCount / maxHits).coerceIn(0f, 1f)
                        drawRect(
                            color = lerp(colorScheme.surface, colorScheme.primary, hitRatio),
                            topLeft = Offset(tile.left, tile.top),
                            size = androidx.compose.ui.geometry.Size(tile.width, tile.height),
                        )
                        drawRect(
                            color = colorScheme.outline.copy(alpha = 0.45f),
                            topLeft = Offset(tile.left, tile.top),
                            size = androidx.compose.ui.geometry.Size(tile.width, tile.height),
                            style = Stroke(width = 1.dp.toPx()),
                        )

                        val padding = 4.dp.toPx()
                        if (tile.width >= 64.dp.toPx() && tile.height >= 34.dp.toPx()) {
                            val textColor = if (hitRatio > 0.62f) {
                                colorScheme.onPrimary.toArgb()
                            } else {
                                colorScheme.onSurface.toArgb()
                            }
                            sessionPaint.color = textColor
                            secondaryPaint.color = textColor
                            val date = labelFormat.format(
                                Instant.ofEpochMilli(session.completedAtMillis)
                                    .atZone(ZoneId.systemDefault()),
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                date,
                                tile.left + padding,
                                tile.top + padding + sessionPaint.textSize,
                                sessionPaint,
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                "${formatElapsed(session.durationMillis)} · ${session.hitCount} hits",
                                tile.left + padding,
                                tile.top + padding + sessionPaint.textSize + secondaryPaint.textSize + 2.dp.toPx(),
                                secondaryPaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class TreemapGroup(
    val month: YearMonth,
    val sessions: List<TrainingSession>,
    val weight: Double,
)

private fun Rect.inset(amount: Float): Rect =
    Rect(left + amount, top + amount, right - amount, bottom - amount)

private fun <T> layoutTreemap(
    items: List<T>,
    bounds: Rect,
    weight: (T) -> Double,
    draw: (T, Rect) -> Unit,
) {
    val weightedItems = items
        .map { it to weight(it).coerceAtLeast(1.0) }
        .sortedByDescending { it.second }

    fun layoutRange(start: Int, end: Int, region: Rect) {
        val itemCount = end - start
        if (itemCount <= 0 || region.width <= 0f || region.height <= 0f) return
        if (itemCount == 1) {
            draw(weightedItems[start].first, region)
            return
        }

        val totalWeight = (start until end).sumOf { weightedItems[it].second }
        val midpoint = totalWeight / 2.0
        var firstWeight = 0.0
        var split = start + 1
        for (index in start until end - 1) {
            firstWeight += weightedItems[index].second
            split = index + 1
            if (firstWeight >= midpoint) break
        }
        firstWeight = (start until split).sumOf { weightedItems[it].second }
        val ratio = (firstWeight / totalWeight).toFloat().coerceIn(0f, 1f)

        if (region.width >= region.height) {
            val splitX = region.left + region.width * ratio
            layoutRange(start, split, Rect(region.left, region.top, splitX, region.bottom))
            layoutRange(split, end, Rect(splitX, region.top, region.right, region.bottom))
        } else {
            val splitY = region.top + region.height * ratio
            layoutRange(start, split, Rect(region.left, region.top, region.right, splitY))
            layoutRange(split, end, Rect(region.left, splitY, region.right, region.bottom))
        }
    }

    layoutRange(0, weightedItems.size, bounds)
}
