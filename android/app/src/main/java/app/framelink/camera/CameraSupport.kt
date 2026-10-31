package app.framelink.camera

import app.framelink.camera.support.CameraSupportRegistry
import app.framelink.camera.support.CameraSupportRegistry.Capability

/** 连接前先校验注册协议，错误配置不能拆掉正在使用的设备会话 */
internal fun registeredConnection(config:ConnectionConfig):ConnectionConfig{
    val protocol=CameraSupportRegistry.standard().requireProtocol(config.protocol)
    val pairedPhone=protocol.id==CameraSupportRegistry.FUJI_PHONE&&config.bluetoothAddress.isNotBlank()
    require(config.host.isNotBlank()||pairedPhone){"请先选择或填写相机地址"}
    if(pairedPhone)require(config.bluetoothAddress.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))){"相机蓝牙地址无效，请重新发现设备"}
    if(protocol.transport!=CameraSupportRegistry.Transport.USB){
        require(config.port==0||config.port in 1..65535){"相机端口无效"}
        if(!pairedPhone)require(CameraSupportRegistry.numericAddress(config.host)){"请输入相机的局域网IP地址"}
    }
    if(protocol.id==CameraSupportRegistry.FUJI_AUTOSAVE){
        require(config.experimentalFuji&&config.fujiDeviceName.isNotBlank()){"请先显式启用Fuji AutoSave实验通道并完成发现"}
    }
    return config.copy(protocol=protocol.id,port=if(config.port==0)protocol.defaultPort else config.port,
        user=if(protocol.accessCredentials)config.user else"",password=if(protocol.accessCredentials)config.password else"",
        fingerprint=if(protocol.id==CameraSupportRegistry.SONY)config.fingerprint else"",
        bluetoothAddress=if(pairedPhone)config.bluetoothAddress.uppercase(java.util.Locale.ROOT)else"")
}

/** 将机身协商结果与当前适配器边界相交，profile不能凭型号开放能力 */
internal fun CameraInfo.supportFor(protocol:String):Map<Capability,CameraSupportRegistry.Decision>{
    val registry=CameraSupportRegistry.standard()
    val adapter=registry.requireProtocol(protocol)
    return registry.assess(adapter,registry.match(adapter.family,model,firmware),mapOf(
        Capability.BROWSE to canBrowse,Capability.DOWNLOAD to canDownload,
        Capability.PREVIEW to canPreview,Capability.LIVE_VIEW to canLiveView,
        Capability.FOCUS to canFocus,Capability.SHUTTER to canCapture,Capability.AUTO_IMPORT to canAutoImport,
    ))
}

internal fun CameraInfo.withSupport(protocol:String):CameraInfo{
    val available=supportFor(protocol)
    fun has(capability:Capability)=available.getValue(capability).available()
    return copy(canBrowse=has(Capability.BROWSE),canDownload=has(Capability.DOWNLOAD),canPreview=has(Capability.PREVIEW),
        canLiveView=has(Capability.LIVE_VIEW),canFocus=has(Capability.FOCUS),canCapture=has(Capability.SHUTTER),
        canAutoImport=has(Capability.AUTO_IMPORT))
}
