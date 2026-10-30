package app.framelink.camera

import java.util.Locale

internal val SelectionAccent = androidx.compose.ui.graphics.Color(0xff287af3)

/** 相机总数来自当前日期索引，筛选未结束时仅将已匹配数量作为进度 */
internal fun galleryCountText(camera:Boolean,filtered:Boolean,loaded:Int,total:Int?,hasMore:Boolean):String = when {
    !camera -> "$loaded 张"
    filtered && hasMore -> "已匹配 $loaded 张" + (total?.let { " · 共 $it 张" } ?: "")
    filtered -> "$loaded 张"
    total != null -> "共 $total 张"
    hasMore -> "正在统计照片"
    else -> "共 $loaded 张"
}

/** 同一次拍摄的格式与保存状态分开呈现，JPEG已导入不代表RAW也已保存 */
internal fun CameraFile.formatTitle(): String = when {
    raw -> "RAW"
    name.substringAfterLast('.', "").uppercase(Locale.ROOT) in setOf("JPG", "JPEG") -> "JPEG"
    name.substringAfterLast('.', "").uppercase(Locale.ROOT) in setOf("HIF", "HEIF", "HEIC") -> "HEIF"
    else -> name.substringAfterLast('.', "文件").uppercase(Locale.ROOT)
}

internal fun Photo.formatTitle(): String = files().map { it.formatTitle() }.distinct().joinToString(" + ")

internal fun Photo.displayTitle(): String = if (files().map { it.formatTitle() }.distinct().size > 1) name.substringBeforeLast('.') else name

internal fun Photo.importTitle(): String = files().groupBy { it.formatTitle() }.entries.joinToString(" · ") { (format, parts) ->
    val saved = parts.count { it.savedUri.isNotBlank() }
    "$format " + when (saved) { 0 -> "未导入"; parts.size -> "已导入"; else -> "部分已导入" }
}

internal fun Photo.hasUnimportedFiles(): Boolean = files().any { it.image && it.savedUri.isBlank() }

/** 文件日期与浏览排序采用同一份文件时间，RAW/JPEG跨日写入也不会错分日期 */
internal fun List<CameraFile>.fileTimeDay():String = maxByOrNull { it.fileModifiedAt.takeIf { time->time>0 }?:it.fileCreatedAt }?.fileDay.orEmpty()

internal fun CameraInfo.displayName(): String = when (model) {
    "ILCE-7CM2" -> "Sony α7C II"
    "ILCE-7M4" -> "Sony α7 IV"
    "ILCE-7M5" -> "Sony α7 V"
    "ILCE-6700" -> "Sony α6700"
    else -> model.ifBlank { manufacturer.ifBlank { "相机" } }
}

internal fun ConnectionConfig.canReconnectDirectly(): Boolean = host.isNotBlank() && when(protocol){
    CameraProtocols.USB->usbIdentity.isNotBlank()
    CameraProtocols.CANON->port in 1..65535&&(user.isBlank()||password.isNotBlank())
    CameraProtocols.SONY->port in 1..65535&&(user.isBlank()||(password.isNotBlank()&&fingerprint.isNotBlank()))
    else->false
}

internal enum class GalleryLayout(val key: String, val title: String, val columns: Int) {
    SMALL("small", "小图", 5), MEDIUM("medium", "中图", 3), LARGE("large", "大图", 2), LIST("list", "列表", 1);
    companion object { fun from(key: String) = entries.firstOrNull { it.key == key } ?: MEDIUM }
}
