package org.mlm.mages.ui.util

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject
import org.mlm.mages.settings.AppSettings
import kotlin.math.abs
import kotlin.math.exp

private const val FLING_STOP_VELOCITY_PX_PER_SEC = 120f
private const val FLING_FRAME_MILLIS = 16f
private const val FLING_FRICTION_PER_SEC = -4.2f
private const val MAX_FLING_PX_PER_SEC = 8000f
private const val MAX_FLING_VIEWPORTS = 3f

@Composable
fun Modifier.dragToScrollIfEnabled(
    state: LazyListState,
    reverse: Boolean = false,
    enabled: Boolean? = null,
): Modifier {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())
    if (!(enabled ?: settings.dragToScroll)) return this

    val direction = if (reverse) -1f else 1f
    val perFrameDecay = exp(FLING_FRICTION_PER_SEC * FLING_FRAME_MILLIS / 1000f)
    val stopSquared = FLING_STOP_VELOCITY_PX_PER_SEC * FLING_STOP_VELOCITY_PX_PER_SEC

    return draggable(
        state = rememberDraggableState { delta ->
            state.dispatchRawDelta(-delta * direction)
        },
        orientation = Orientation.Vertical,
        onDragStopped = { velocity ->
            val budget = state.layoutInfo.viewportSize.height * MAX_FLING_VIEWPORTS
            var remaining = budget
            var perSecond = (-velocity * direction).coerceIn(-MAX_FLING_PX_PER_SEC, MAX_FLING_PX_PER_SEC)
            state.scroll(MutatePriority.Default) {
                while (perSecond * perSecond > stopSquared && remaining > 0f) {
                    val step = (perSecond / 1000f * FLING_FRAME_MILLIS).coerceIn(-remaining, remaining)
                    scrollBy(step)
                    remaining -= abs(step)
                    perSecond *= perFrameDecay
                }
            }
        },
    )
}