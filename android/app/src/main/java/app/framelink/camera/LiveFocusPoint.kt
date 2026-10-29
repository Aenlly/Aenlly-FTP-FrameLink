package app.framelink.camera

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/** 将等比留边的预览坐标转换为相机归一化坐标，黑边不发送对焦请求 */
internal fun liveFocusPoint(point: Offset, viewport: IntSize, image: IntSize): Offset? {
    if (viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0) return null
    val scale = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val width = image.width * scale
    val height = image.height * scale
    val left = (viewport.width - width) / 2
    val top = (viewport.height - height) / 2
    if (point.x < left || point.x > left + width || point.y < top || point.y > top + height) return null
    return Offset(((point.x - left) / width).coerceIn(0f, 1f), ((point.y - top) / height).coerceIn(0f, 1f))
}
