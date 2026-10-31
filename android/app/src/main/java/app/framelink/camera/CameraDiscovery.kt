package app.framelink.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** 在相机Wi-Fi中发现Sony设备，不改变应用默认互联网网络 */
object CameraDiscovery {
    /** 热点网关仅作为候选，连接成功仍须由富士协议确认机身身份 */
    fun phoneGateways(context:Context):List<String>{
        val manager=context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.filter{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}
            .flatMap{manager.getLinkProperties(it)?.routes.orEmpty()}
            .mapNotNull{it.gateway as? java.net.Inet4Address}.filter{!it.isAnyLocalAddress&&!it.isLoopbackAddress}
            .mapNotNull{it.hostAddress}.distinct()
    }

    fun discover(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val networks =
            manager.allNetworks.filter {
                manager
                    .getNetworkCapabilities(it)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        val found = linkedSetOf<String>()
        val lock =
            context
                .getSystemService(WifiManager::class.java)
                .createMulticastLock("framelink.discovery")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        try {
            for (network in networks) {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    socket.soTimeout = 900
                    val message =
                        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"
                            .toByteArray()
                    socket.send(
                        DatagramPacket(
                            message,
                            message.size,
                            InetAddress.getByName("239.255.255.250"),
                            1900,
                        )
                    )
                    val end = System.currentTimeMillis() + 3500
                    while (System.currentTimeMillis() < end) {
                        runCatching {
                            val bytes = ByteArray(8192)
                            val packet = DatagramPacket(bytes, bytes.size)
                            socket.receive(packet)
                            val response = String(bytes, 0, packet.length)
                            if (
                                response.contains("sony", true) ||
                                    response.contains("DigitalImaging", true)
                            )
                                packet.address.hostAddress?.let(found::add)
                        }
                    }
                }
            }
        } finally {
            lock.release()
        }
        return found.toList()
    }
}
