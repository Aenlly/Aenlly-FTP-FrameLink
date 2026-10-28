package app.framelink.camera

/** 协议身份已包含拍摄时间，复用本机副本时仍核对文件编号、名称与字节数 */
internal fun canReuseCameraFile(current: CameraFile, cached: CameraFile): Boolean =
    current.fileId == cached.fileId && current.name == cached.name && current.size == cached.size
