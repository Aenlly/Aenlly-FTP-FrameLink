package app.framelink.camera

import app.framelink.camera.support.CameraPairingStore
import java.util.Locale

/** 复用已有加密设置，仅在明确保存连接后写入配对token，不保存热点密码 */
internal class SecureCameraPairingStore(private val settings:SecureValueStore):CameraPairingStore{
    private fun key(protocol:String,address:String):String{
        require(protocol.isNotBlank()&&address.isNotBlank()){"配对凭据缺少协议或设备身份"}
        val known=app.framelink.camera.support.CameraSupportRegistry.standard().requireProtocol(protocol)
        return "camera.pairing.v1."+known.id+"."+MediaFiles.key(address.uppercase(Locale.ROOT))
    }
    override fun loadToken(protocol:String,address:String):ByteArray?=
        settings.get(key(protocol,address)).takeIf{it.length in 2..512&&it.length%2==0&&it.matches(Regex("[0-9a-f]+"))}?.chunked(2)?.map{it.toInt(16).toByte()}?.toByteArray()
    override fun saveToken(protocol:String,address:String,token:ByteArray){
        require(token.size in 1..256){"配对token长度无效"}
        settings.put(key(protocol,address),token.joinToString(""){"%02x".format(it.toInt() and 255)})
    }
    override fun forget(protocol:String,address:String){settings.remove(key(protocol,address))}
}
