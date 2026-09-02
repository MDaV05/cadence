package com.cadence.music.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Slim rounded seek bar — tap to seek, drag to scrub. The thumb grows with a
 * soft halo while dragging for continuous tactile feedback. While the finger
 * is down the dragged position is drawn; [onSeekFinished] fires once on
 * release (and on tap) with the target fraction.
 */
@Composable
fun SeekBar(
    value: Float,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val thumbRadius by animateDpAsState(
        if (dragging) 9.dp else 6.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "thumbRadius",
    )

    Spacer(
        modifier
            .height(28.dp)
            .semantics {
                contentDescription = "Seek bar"
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { pos ->
                    onSeekFinished((pos.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        onSeekFinished(dragFraction)
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
            .drawBehind {
                val shown = if (dragging) dragFraction else value.coerceIn(0f, 1f)
                val y = size.height / 2
                val x = size.width * shown
                val r = thumbRadius.toPx()
                val stroke = 4.dp.toPx()
                drawLine(inactive, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                drawLine(active, Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
                if (dragging) {
                    drawCircle(active.copy(alpha = 0.22f), radius = r + 7.dp.toPx(), center = Offset(x, y))
                }
                drawCircle(active, radius = r, center = Offset(x, y))
            }
    )
}
