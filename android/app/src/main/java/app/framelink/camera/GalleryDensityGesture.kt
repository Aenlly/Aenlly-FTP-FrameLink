package app.framelink.camera

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** 双指手势结束时切换一级密度，单指滚动和长按选片继续由图库处理 */
@Composable
internal fun Modifier.galleryDensityGesture(enabled:Boolean,onDensity:(Boolean)->Unit):Modifier{
    val change by rememberUpdatedState(onDensity)
    return pointerInput(enabled){
        if(!enabled)return@pointerInput
        awaitEachGesture{
            awaitFirstDown(requireUnconsumed=false)
            var scale=1f
            var multiple=false
            do{
                val event=awaitPointerEvent(PointerEventPass.Initial)
                if(event.changes.count{it.pressed}>=2){
                    multiple=true
                    scale*=event.calculateZoom()
                }
                if(multiple)event.changes.forEach{it.consume()}
            }while(event.changes.any{it.pressed})
            if(multiple&&(scale>1.2f||scale<.83f))change(scale>1f)
        }
    }
}
