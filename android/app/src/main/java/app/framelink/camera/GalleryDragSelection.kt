package app.framelink.camera

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 普通滑动继续滚动，长按后才接管连续选择，停在边缘可继续选取屏外照片 */
@Composable
internal fun Modifier.galleryDragSelection(
    state: LazyGridState,
    contextKey: String,
    groups: List<List<String>>,
    selection: List<String>,
    onSelection: (List<String>) -> Unit,
    onDragging: (Boolean) -> Unit,
): Modifier {
    val latestGroups by rememberUpdatedState(groups)
    val latestSelection by rememberUpdatedState(selection)
    val changeSelection by rememberUpdatedState(onSelection)
    val draggingChanged by rememberUpdatedState(onDragging)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 48.dp.toPx() }
    val maxStep = with(density) { 12.dp.toPx() }
    var range by remember(state, contextKey) { mutableStateOf<PhotoSelectionRange?>(null) }
    var position by remember(state, contextKey) { mutableStateOf(Offset.Unspecified) }

    fun hit(point: Offset, count: Int): Int? = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        item.index in 0 until count && point.x >= item.offset.x && point.x < item.offset.x + item.size.width &&
            point.y >= item.offset.y && point.y < item.offset.y + item.size.height
    }?.index

    fun update(point: Offset) {
        val active = range ?: return
        val index = hit(point, active.groups.size) ?: return
        if (index != active.lastIndex) changeSelection(active.selectionAt(index))
    }

    LaunchedEffect(range) {
        while (range != null && isActive) {
            val height = state.layoutInfo.viewportSize.height.toFloat()
            if (position.isSpecified && height > 0) {
                val zone = minOf(edge, height / 4)
                val step = when {
                    position.y < zone -> -maxStep * ((zone - position.y) / zone).coerceIn(0f, 1f)
                    position.y > height - zone -> maxStep * ((position.y - height + zone) / zone).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (step < 0 && state.canScrollBackward || step > 0 && state.canScrollForward) {
                    state.scrollBy(step)
                    update(Offset(
                        position.x.coerceIn(0f, (state.layoutInfo.viewportSize.width - 1).coerceAtLeast(0).toFloat()),
                        position.y.coerceIn(0f, height - 1),
                    ))
                }
            }
            delay(16)
        }
    }
    return pointerInput(state, contextKey) {
        fun finish() {
            range = null
            position = Offset.Unspecified
            draggingChanged(false)
        }
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = { start ->
                    hit(start, latestGroups.size)?.let { anchor ->
                        draggingChanged(true)
                        range = PhotoSelectionRange(latestGroups.map { it.toList() }, latestSelection.toList(), anchor)
                        position = start
                        update(start)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, _ ->
                    if (range != null) {
                        change.consume()
                        position = change.position
                        update(position)
                    }
                },
                onDragEnd = ::finish,
                onDragCancel = ::finish,
            )
        } finally { finish() }
    }
}
