package app.framelink.camera

/** 自动读取的SSH公钥身份，确认后才允许携带认证信息连接 */
data class CameraIdentity(
    val host: String,
    val port: Int = 22,
    val fingerprint: String,
    val algorithm: String = "",
)

/** 确认界面只接收公钥信息，账号密码保留在连接控制器内存中 */
data class CameraIdentityPrompt(
    val id: String,
    val identity: CameraIdentity,
    val previousFingerprint: String = "",
)

internal fun sameCameraFingerprint(expected: String, observed: String): Boolean {
    fun normalized(value: String) = value.trim().removePrefix("SHA256:").trimEnd('=')
    return expected.isNotBlank() && normalized(expected) == normalized(observed)
}

internal fun cameraConnectionMessage(error: Exception,protocol:String=CameraProtocols.SONY): String {
    val detail = error.message.orEmpty()
    if(protocol==CameraProtocols.USB)return detail.ifBlank{"USB连接未完成，请检查OTG、授权和机身读卡模式"}
    if(protocol==CameraProtocols.FUJI_PHONE)return when{
        "connection refused" in detail->"相机尚未开放手机图库连接，请确认蓝牙配对、Wi-Fi交接和机身访问提示；不要选择PC AutoSave"
        "timeout" in detail||"deadline exceeded" in detail->"富士连接超时，请在机身确认手机访问，并核对系统Wi-Fi提示"
        else->detail.ifBlank{"富士手机无线连接未完成，请检查配对与机身共享状态"}
    }
    if(protocol==CameraProtocols.CANON)return when{
        "connection refused" in detail->"机身未接受CCAPI连接，请核对IP、HTTP端口并确认相机已启用CCAPI"
        "timeout" in detail||"deadline exceeded" in detail->"CCAPI连接超时，请确认相机未休眠、手机与相机在同一网络，且机身HTTP服务已开启"
        else->detail.ifBlank{"CCAPI连接未完成，请核对机身HTTP设置"}
    }
    return when {
        "does not match" in detail && "fingerprint" in detail ->
            "相机身份与刚确认的指纹不一致，请重新读取并核对后连接"
        "rejected the Access Authentication" in detail || "unable to authenticate" in detail ->
            "访问认证失败，请核对相机显示的用户名和密码"
        "connection refused" in detail ->
            "相机未接受连接，请核对相机IP，并在机身开启电脑遥控和访问身份验证"
        "timeout" in detail || "deadline exceeded" in detail ->
            "连接相机超时，请确认机身未休眠、手机与相机位于同一网络，并断开其他控制App的相机会话"
        else -> detail.ifBlank { "连接未完成，请检查机身与网络后重试" }
    }
}
