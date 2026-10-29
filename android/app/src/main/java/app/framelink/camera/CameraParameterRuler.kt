package app.framelink.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal val LiveAccent = Color(0xffffd66b)

/** 滑块仅映射机身候选档位，拖动预览数值，松手后提交一次并回到机身确认值 */
@Composable
internal fun CameraParameterRuler(property: CameraProperty, vertical: Boolean, enabled: Boolean, onSelect: suspend (Long) -> Long, modifier: Modifier = Modifier, onPreview: (String?) -> Unit = {}) {
    val choices = CameraPropertyText.choices(property)
    if (choices.isEmpty()) return
    val confirmed = choices.indexOfFirst { it.value == property.value }.coerceAtLeast(0)
    var draft by remember(property.code) { mutableFloatStateOf(confirmed.toFloat()) }
    var dragging by remember(property.code) { mutableStateOf(false) }
    var applying by remember(property.code) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestChoices by rememberUpdatedState(choices)
    val actualIndex by rememberUpdatedState(confirmed)
    val select by rememberUpdatedState(onSelect)
    val preview by rememberUpdatedState(onPreview)
    val active by rememberUpdatedState(enabled && !applying)
    val last = choices.lastIndex.coerceAtLeast(1).toFloat()
    val shown by animateFloatAsState(draft, if (dragging) snap() else tween(100), label = "参数滑块")

    fun submit() {
        val index = draft.roundToInt().coerceIn(choices.indices)
        draft = index.toFloat()
        if (!active || applying || index == actualIndex) return
        applying = true
        scope.launch {
            try {
                val value = select(latestChoices[index].value)
                draft = latestChoices.indexOfFirst { it.value == value }.takeIf { it >= 0 }?.toFloat() ?: actualIndex.toFloat()
            } finally { applying = false; preview(null) }
        }
    }
    LaunchedEffect(property.code, property.value, vertical) {
        if (!dragging && !applying) draft = confirmed.toFloat()
    }
    BoxWithConstraints(modifier.testTag("live-property-choices")
        .progressSemantics(draft, 0f..last, (choices.size - 2).coerceAtLeast(0))
        .semantics {
            contentDescription = "滑动调节" + CameraPropertyText.name(property)
            stateDescription = choices[draft.roundToInt().coerceIn(choices.indices)].label
            setProgress { value ->
                if (!active || choices.size < 2 || !value.isFinite()) false else {
                    draft = value.coerceIn(0f, last)
                    submit()
                    true
                }
            }
        }) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val thumbWidth = if (vertical) maxWidth else minOf(34.dp, maxWidth)
        val thumbHeight = 30.dp
        val thumbAxis = with(density) { (if (vertical) thumbHeight else thumbWidth).toPx() }
        val length = ((if (vertical) height else width) - thumbAxis).coerceAtLeast(1f)
        val fraction = (shown / last).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().pointerInput(property.code, vertical, enabled, choices.size, width, height) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!active || choices.size < 2) return@awaitEachGesture
                val center = thumbAxis / 2 + (if (vertical) 1f - draft / last else draft / last) * length
                val start = if (vertical) down.position.y else down.position.x
                val hold = if (abs(start - center) <= thumbAxis / 2) start - center else 0f
                fun move(point: Offset) {
                    val position = ((if (vertical) point.y else point.x) - hold - thumbAxis / 2) / length
                    draft = ((if (vertical) 1f - position else position) * last).coerceIn(0f, last)
                    preview(latestChoices[draft.roundToInt().coerceIn(latestChoices.indices)].label)
                }
                var released = false
                dragging = true
                try {
                    down.consume()
                    move(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        move(change.position)
                        change.consume()
                        if (!change.pressed) { released = true; break }
                    }
                } finally {
                    dragging = false
                    if (released) { submit(); if(!applying)preview(null) }
                    else { draft = actualIndex.toFloat(); preview(null) }
                }
            }
        }) {
            Canvas(Modifier.fillMaxSize()) {
                val start = if (vertical) Offset(size.width / 2, thumbAxis / 2) else Offset(thumbAxis / 2, size.height / 2)
                val end = if (vertical) Offset(size.width / 2, size.height - thumbAxis / 2) else Offset(size.width - thumbAxis / 2, size.height / 2)
                drawLine(Color.White.copy(alpha = .2f), start, end, 3.dp.toPx())
                val count = minOf(choices.size, 17)
                for (tick in 0 until count) {
                    val part = if (count <= 1) 0f else tick.toFloat() / (count - 1)
                    val point = if (vertical) Offset(size.width / 2, thumbAxis / 2 + length * part) else Offset(thumbAxis / 2 + length * part, size.height / 2)
                    drawCircle(Color.White.copy(alpha = .6f), 2.dp.toPx(), point)
                }
            }
            val x = if (vertical) 0 else (fraction * length).roundToInt()
            val y = if (vertical) ((1f - fraction) * length).roundToInt() else ((height - with(density) { thumbHeight.toPx() }) / 2).roundToInt()
            Box(Modifier.offset { IntOffset(x, y) }.size(thumbWidth, thumbHeight)
                .background(Color(0xff30353d), RoundedCornerShape(12.dp))
                .border(1.dp, LiveAccent.copy(alpha = .7f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(18.dp)){
                    for(line in 0..2){
                        if(vertical)drawLine(LiveAccent.copy(alpha=.8f),Offset(3.dp.toPx(),(5+line*4).dp.toPx()),Offset(15.dp.toPx(),(5+line*4).dp.toPx()),1.dp.toPx())
                        else drawLine(LiveAccent.copy(alpha=.8f),Offset((5+line*4).dp.toPx(),3.dp.toPx()),Offset((5+line*4).dp.toPx(),15.dp.toPx()),1.dp.toPx())
                    }
                }
            }
        }
    }
}
