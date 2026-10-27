package app.framelink.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import app.framelink.nativecamera.bridge.Bridge
import app.framelink.nativecamera.bridge.NetworkBinder
import app.framelink.nativecamera.bridge.Progress
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import org.json.JSONObject

/** 只为相机socket绑定局域网，云端保持Android默认互联网路径 */
class NativeCamera(private val context: Context) {
    private val session = Bridge.newSession()

    fun connect(config: ConnectionConfig): CameraInfo {
        session.setNetworkBinder(cameraNetwork(config.host))
        return Json.decode(session.connect(Json.encode(config)))
    }

    fun inspectIdentity(host: String): CameraIdentity {
        val probe = Bridge.newSession()
        try {
            probe.setNetworkBinder(cameraNetwork(host))
            return Json.decode(probe.probeIdentity(host))
        } finally {
            probe.close()
        }
    }

    private fun cameraNetwork(host: String): NetworkBinder? {
        require(android.util.Patterns.IP_ADDRESS.matcher(host).matches()) { "请输入相机的局域网IP地址" }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val target = InetAddress.getByName(host).address
        val network =
            manager.allNetworks.firstOrNull { network ->
                val caps = manager.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    manager.getLinkProperties(network)?.linkAddresses.orEmpty().any { address ->
                        val local = address.address.address
                        if (address.address !is Inet4Address || local.size != target.size) false
                        else {
                            val prefix = address.prefixLength
                            local.indices.all { index ->
                                val bits = (prefix - index * 8).coerceIn(0, 8)
                                val mask = (0xff shl (8 - bits)) and 0xff
                                (local[index].toInt() and mask) == (target[index].toInt() and mask)
                            }
                        }
                    }
            }
        return if (network == null) null
            else
                object : NetworkBinder {
                    override fun bindSocket(fd: Long) {
                        ParcelFileDescriptor.fromFd(fd.toInt()).use {
                            network.bindSocket(it.fileDescriptor)
                        }
                    }
                }
    }

    fun page(slot: Int, cursor: Long): CameraPage =
        Json.decode(session.listPage(slot.toLong(), cursor))

    fun dates(slot: Int): List<String> = Json.list(session.dates(slot.toLong()))

    fun preview(photo: Photo, file: CameraFile, target: File, large: Boolean) {
        session.preview(
            photo.slot.toLong(),
            photo.contentId.toLong(),
            file.fileId.toLong(),
            target.absolutePath,
            large,
        )
    }

    fun properties(): List<CameraProperty> = Json.list(session.properties())

    fun setProperty(code: Int, value: Long) {
        session.setProperty(code.toLong(), value)
    }

    fun capture(autofocus: Boolean) {
        session.capture(autofocus)
    }

    fun startLive() {
        session.startLiveView()
    }

    fun nextFrame(): ByteArray = session.nextFrame()

    fun metadata(): String = session.liveMetadata()

    fun fetchCaptured(target: File): JSONObject =
        JSONObject(session.fetchCaptured(target.absolutePath))

    fun stopLive() {
        session.stopLiveView()
    }

    fun cancelTransfer() {
        session.cancelTransfer()
    }

    fun close() {
        session.close()
    }

    fun download(
        photo: Photo,
        file: CameraFile,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val input =
            mapOf(
                "slot" to photo.slot,
                "contentId" to photo.contentId,
                "fileId" to file.fileId,
                "size" to file.size,
            )
        return session.download(
            Json.encode(input),
            target.absolutePath,
            object : Progress {
                override fun onProgress(done: Long, total: Long) = onProgress(done, total)
            },
        )
    }
}
