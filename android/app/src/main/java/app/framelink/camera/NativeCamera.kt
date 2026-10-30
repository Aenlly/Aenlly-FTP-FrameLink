package app.framelink.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import app.framelink.nativecamera.bridge.Bridge
import app.framelink.nativecamera.bridge.NetworkBinder
import app.framelink.nativecamera.bridge.Progress
import app.framelink.nativecamera.bridge.Session
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import org.json.JSONObject

/** 只为相机socket绑定局域网，云端保持Android默认互联网路径 */
class NativeCamera internal constructor(private val context: Context,private val usbOpener:UsbCameraOpener) {
    constructor(context:Context):this(context,AndroidUsbCameraOpener(context))
    private class Connection(val session:Session=Bridge.newSession()){
        @Volatile var usb:UsbPhotoSession?=null
        @Volatile var identity=""
        fun close(){usb?.close();session.close()}
    }
    private val lock=Any()
    @Volatile private var connection=Connection()
    @Volatile private var boundNetwork:android.net.Network?=null

    private fun current(photo:Photo?=null):Connection=synchronized(lock){
        val value=connection
        if(photo!=null)check(value.identity.isNotBlank()&&photo.sourceKey.startsWith(value.identity)){
            "照片不属于当前相机连接，请重新浏览后再操作"
        }
        value
    }

    private fun networkSession(value:Connection=current()):Session{
        check(value.usb==null){"USB读卡模式不提供取景或遥控，请在相机机身拍摄"}
        return value.session
    }

    fun connected():Boolean=current().let{it.usb?.connected()?:it.session.connected()}
    fun usesNetwork(network:android.net.Network)=boundNetwork==network
    internal fun usbTargets()=usbOpener.targets()

    fun connect(config:ConnectionConfig):CameraInfo{
        val next=Connection()
        val (previous,ticket)=synchronized(lock){
            val old=connection
            connection=next
            boundNetwork=null
            old to usbOpener.begin()
        }
        previous.close()
        try{
            val info=if(config.protocol==CameraProtocols.USB){
                val access=usbOpener.open(config.host,ticket)
                val reader=try{UsbPhotoSession(access)}catch(error:Exception){access.close();throw error}
                next.usb=reader
                if(config.usbIdentity.isNotBlank()&&config.usbIdentity!=reader.info.id){
                    error("USB机身与上次记录不同，请进入设置重新选择设备")
                }
                reader.info
            }else{
                val (network,binder)=cameraNetwork(config.host)
                synchronized(lock){check(connection===next){"连接已取消"};boundNetwork=network}
                next.session.setNetworkBinder(binder)
                Json.decode<CameraInfo>(next.session.connect(Json.encode(config)))
            }
            synchronized(lock){check(connection===next){"连接已取消"};next.identity=info.id}
            return info
        }catch(error:Exception){next.close();throw error}
    }

    fun inspectIdentity(host:String):CameraIdentity{
        val probe=Bridge.newSession()
        try{
            probe.setNetworkBinder(cameraNetwork(host).second)
            return Json.decode(probe.probeIdentity(host))
        }finally{probe.close()}
    }

    private fun cameraNetwork(host:String):Pair<android.net.Network?,NetworkBinder?>{
        require(android.util.Patterns.IP_ADDRESS.matcher(host).matches()){"请输入相机的局域网IP地址"}
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val target=InetAddress.getByName(host).address
        val network=manager.allNetworks.firstOrNull{candidate->
            val caps=manager.getNetworkCapabilities(candidate)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true&&
                manager.getLinkProperties(candidate)?.linkAddresses.orEmpty().any{address->
                    val local=address.address.address
                    if(address.address !is Inet4Address||local.size!=target.size)false
                    else{
                        val prefix=address.prefixLength
                        local.indices.all{index->
                            val bits=(prefix-index*8).coerceIn(0,8)
                            val mask=(0xff shl (8-bits)) and 0xff
                            (local[index].toInt() and mask)==(target[index].toInt() and mask)
                        }
                    }
                }
        }
        val binder=network?.let{
            object:NetworkBinder{
                override fun bindSocket(fd:Long){
                    ParcelFileDescriptor.fromFd(fd.toInt()).use{copy->it.bindSocket(copy.fileDescriptor)}
                }
            }
        }
        return network to binder
    }

    fun page(slot:Int,cursor:Long):CameraPage=current().let{
        it.usb?.page(slot,"",cursor)?:Json.decode(it.session.listPage(slot.toLong(),cursor))
    }
    fun newestPage(slot:Int,day:String,cursor:Long):CameraPage=current().let{
        it.usb?.page(slot,day,cursor)?:Json.decode(it.session.listNewestPage(slot.toLong(),day,cursor))
    }
    fun dates(slot:Int):List<String> = current().let{it.usb?.dates(slot)?:Json.list(it.session.dates(slot.toLong()))}

    fun preview(photo:Photo,file:CameraFile,target:File,large:Boolean){
        val value=current(photo)
        value.usb?.let{it.preview(photo,file,target);return}
        value.session.preview(photo.slot.toLong(),photo.contentId.toLong(),file.fileId.toLong(),target.absolutePath,large)
    }

    fun properties():List<CameraProperty> = current().let{if(it.usb!=null)emptyList()else Json.list(it.session.properties())}
    fun setProperty(code:Int,value:Long){networkSession().setProperty(code.toLong(),value)}
    fun capture(autofocus:Boolean){networkSession().capture(autofocus)}
    fun capturePhoto(single:Boolean,receive:Boolean)=JSONObject(networkSession().capturePhoto(true,single,receive))
    fun focusAt(x:Float,y:Float){networkSession().focusAt(x.toDouble(),y.toDouble())}
    fun shootingState():JSONObject=current().let{if(it.usb!=null)JSONObject().put("hostQueueAvailable",false)else JSONObject(it.session.shootingState())}
    fun configureCaptureStorage(receive:Boolean)=JSONObject(networkSession().configureCaptureStorage(receive))
    fun startLive(){networkSession().startLiveView()}
    fun nextFrame():ByteArray=networkSession().nextFrame()
    fun metadata():String=current().let{if(it.usb!=null)""else it.session.liveMetadata()}
    fun fetchCaptured(target:File)=JSONObject(networkSession().fetchCaptured(target.absolutePath))
    fun stopLive(){current().let{if(it.usb==null)it.session.stopLiveView()}}
    fun cancelTransfer(){current().let{it.usb?.cancelTransfer()?:it.session.cancelTransfer()}}

    fun close(){
        val previous=synchronized(lock){
            val old=connection
            connection=Connection()
            boundNetwork=null
            usbOpener.begin()
            old
        }
        previous.close()
    }

    fun download(photo:Photo,file:CameraFile,target:File,onProgress:(Long,Long)->Unit):String{
        val value=current(photo)
        value.usb?.let{return it.download(photo,file,target,onProgress)}
        val input=mapOf("slot" to photo.slot,"contentId" to photo.contentId,"fileId" to file.fileId,"size" to file.size)
        return value.session.download(Json.encode(input),target.absolutePath,object:Progress{
            override fun onProgress(done:Long,total:Long)=onProgress(done,total)
        })
    }
}
