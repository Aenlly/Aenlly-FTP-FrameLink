package app.framelink.camera

/** 协议身份包含资源修订信息，复用本机副本时仍核对文件编号、名称、字节数与文件时间 */
internal fun canReuseCameraFile(current: CameraFile, cached: CameraFile): Boolean =
    current.fileId == cached.fileId && current.name == cached.name && current.size == cached.size &&
        current.fileModifiedAt == cached.fileModifiedAt && current.fileCreatedAt == cached.fileCreatedAt && current.fileDay == cached.fileDay
