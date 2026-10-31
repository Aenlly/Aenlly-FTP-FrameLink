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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import app.framelink.camera.support.CameraPairingStore

/** 只为相机socket绑定局域网，云端保持Android默认互联网路径 */
class NativeCamera internal constructor(private val context: Context,private val usbOpener:UsbCameraOpener,
    private val pairings:CameraPairingStore=SecureCameraPairingStore(SecureSettings(context))) {
    constructor(context:Context):this(context,AndroidUsbCameraOpener(context))
    private class Connection(val session:Session=Bridge.newSession()){
        @Volatile var usb:UsbPhotoSession?=null
        @Volatile var identity=""
        @Volatile var fuji:FujiPhoneLink?=null
        @Volatile var config:ConnectionConfig?=null
        fun close(){usb?.close();session.close();fuji?.close()}
    }
    private val lock=Any()
    @Volatile private var connection=Connection()
    @Volatile private var boundNetwork:android.net.Network?=null
    internal val connectionPhase=MutableStateFlow("")
    internal val fujiDevices=MutableStateFlow<List<FujiNearbyDevice>>(emptyList())
    private val advertisements=mutableMapOf<String,FujiNearbyDevice>()
    private var discoveryEpoch=0L
    private var discoveryJob:Job?=null

    internal suspend fun discoverFuji():List<FujiNearbyDevice>{
        val owner=currentCoroutineContext()[Job]
        val ticket=synchronized(lock){
            advertisements.values.forEach{it.token?.fill(0)};advertisements.clear();fujiDevices.value=emptyList()
            discoveryJob=owner
            ++discoveryEpoch
        }
        try{
            val found=FujiPhoneLink.discover(context){publishFujiDevices(ticket,it,false)}
            publishFujiDevices(ticket,found,true)
            return found
        }catch(error:Exception){
            synchronized(lock){if(discoveryEpoch==ticket){discoveryEpoch++;discoveryJob=null;advertisements.values.forEach{it.token?.fill(0)};advertisements.clear();fujiDevices.value=emptyList()}}
            throw error
        }
    }

    private fun publishFujiDevices(ticket:Long,devices:List<FujiNearbyDevice>,complete:Boolean)=synchronized(lock){
        if(discoveryEpoch!=ticket)return@synchronized
        if(complete){discoveryEpoch++;discoveryJob=null}
        devices.forEach{advertisements[it.address]=it}
        fujiDevices.value=devices
    }

    internal fun completedConnection():ConnectionConfig?=current().config

    internal fun rememberFujiPairing(identity:String){
        val active=current()
        if(active.identity!=identity)return
        val address=active.config?.bluetoothAddress?.takeIf{it.isNotBlank()}?:return
        active.fuji?.tokenToRemember()?.let{token->
            pairings.saveToken(CameraProtocols.FUJI_PHONE,address,token)
            token.fill(0)
        }
    }

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

    fun connect(input:ConnectionConfig):CameraInfo{
        var config=registeredConnection(input)
        val next=Connection()
        val (previous,ticket)=synchronized(lock){
            val old=connection
            connection=next
            boundNetwork=null
            connectionPhase.value=""
            discoveryEpoch++
            discoveryJob?.cancel();discoveryJob=null
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
                val link=if(config.protocol==CameraProtocols.FUJI_PHONE&&config.bluetoothAddress.isNotBlank()){
                    synchronized(lock){
                        check(connection===next){"连接已取消"}
                        FujiPhoneLink(context){message->synchronized(lock){if(connection===next)connectionPhase.value=message}}.also{next.fuji=it}
                    }
                }else null
                val (network,binder)=if(link!=null){
                    val device=synchronized(lock){advertisements[config.bluetoothAddress]}?:FujiNearbyDevice(config.bluetoothAddress,"富士相机",null)
                    val token=pairings.loadToken(CameraProtocols.FUJI_PHONE,config.bluetoothAddress)?.takeIf{it.size==4}
                    val result=try{runBlocking{link.connect(device,token)}}finally{token?.fill(0)}
                    config=config.copy(host=result.second)
                    result.first to networkBinder(result.first)
                }else cameraNetwork(config.host)
                synchronized(lock){check(connection===next){"连接已取消"};boundNetwork=network}
                next.session.setNetworkBinder(binder)
                Json.decode<CameraInfo>(next.session.connect(Json.encode(config)))
            }
            synchronized(lock){check(connection===next){"连接已取消"};next.identity=info.id;next.config=config;connectionPhase.value=""}
            val firmware=next.fuji?.firmware.orEmpty()
            return (if(info.firmware.isBlank()&&firmware.isNotBlank())info.copy(firmware=firmware)else info).withSupport(config.protocol)
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
        return network to network?.let(::networkBinder)
    }

    private fun networkBinder(network:android.net.Network):NetworkBinder=
            object:NetworkBinder{
                override fun bindSocket(fd:Long){
                    ParcelFileDescriptor.fromFd(fd.toInt()).use{copy->network.bindSocket(copy.fileDescriptor)}
                }
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
    fun creativeLookDiagnostics():String=networkSession().creativeLookDiagnostics()
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
            connectionPhase.value=""
            discoveryEpoch++
            discoveryJob?.cancel();discoveryJob=null
            advertisements.values.forEach{it.token?.fill(0)};advertisements.clear();fujiDevices.value=emptyList()
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
