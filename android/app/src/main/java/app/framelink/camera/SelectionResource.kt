package app.framelink.camera

/** 选择草稿绑定来源与资源元数据，导入路径和收藏状态不改变照片身份 */
internal fun Photo.selectionResourceKey(): String = MediaFiles.key(Json.encode(listOf(
    sourceKey, source, slot, contentId, capturedAt,
    files().sortedWith(compareBy<CameraFile> { it.fileId }.thenBy { it.name }).map {
        listOf(it.fileId, it.name, it.size, it.cameraDay, it.cameraPath) +
            if(it.fileModifiedAt>0||it.fileCreatedAt>0||it.fileDay.isNotBlank())listOf(it.fileModifiedAt,it.fileCreatedAt,it.fileDay)else emptyList()
    },
)))
