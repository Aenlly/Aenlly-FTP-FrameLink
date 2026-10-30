package app.framelink.camera

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/** 将等比留边的预览坐标转换为相机归一化坐标，黑边不发送对焦请求 */
internal fun liveFocusPoint(point: Offset, viewport: IntSize, image: IntSize,rotation:Int=0): Offset? {
    if (viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0) return null
    val turn=((rotation%360)+360)%360
    val sideways=turn==90||turn==270
    val displayed=if(sideways)IntSize(image.height,image.width)else image
    val scale = minOf(viewport.width.toFloat() / displayed.width, viewport.height.toFloat() / displayed.height)
    val width = displayed.width * scale
    val height = displayed.height * scale
    val left = (viewport.width - width) / 2
    val top = (viewport.height - height) / 2
    if (point.x < left || point.x > left + width || point.y < top || point.y > top + height) return null
    val x=((point.x-left)/width).coerceIn(0f,1f)
    val y=((point.y-top)/height).coerceIn(0f,1f)
    return when(turn){90->Offset(y,1-x);180->Offset(1-x,1-y);270->Offset(1-y,x);else->Offset(x,y)}
}
