package app.framelink.camera

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.*
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.core.content.ContextCompat
import app.framelink.camera.support.FujiGattProtocol as Protocol
import app.framelink.camera.support.FujiDiscoveryRules
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 广播token只留内存，不参与界面、日志或配置序列化 */
internal class FujiNearbyDevice(val address:String,val name:String,@Transient val token:ByteArray?,
    val manufacturerVerified:Boolean=false,val variant:FujiDiscoveryRules.Variant=FujiDiscoveryRules.Variant.UNKNOWN){
    override fun toString()=name
}

/** 富士蓝牙配对到专用Wi-Fi，网络仅交给相机socket，不切换应用互联网默认路由 */
@SuppressLint("MissingPermission")
internal class FujiPhoneLink(private val context:Context,private val phase:(String)->Unit){
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private val generation=AtomicLong()
    private val closed=AtomicBoolean(false)
    private val lifecycle=Any()
    private val operations=Mutex()
    @Volatile private var gatt:BluetoothGatt?=null
    @Volatile private var pending:GattRequest?=null
    @Volatile private var networkPending:CompletableDeferred<Pair<Network,String>>?=null
    private var networkCallback:ConnectivityManager.NetworkCallback?=null
    private var sessionJob:Job?=null
    private var pairingToken:ByteArray?=null
    var firmware:String="";private set
    private data class GattRequest(val kind:String,val uuid:UUID,val reply:CompletableDeferred<ByteArray>)

    suspend fun connect(device:FujiNearbyDevice,savedToken:ByteArray?):Pair<Network,String> = coroutineScope{
        check(!closed.get()){"连接已关闭，请重新发起"}
        check(Build.VERSION.SDK_INT>=29){"蓝牙自动连接相机Wi-Fi需要Android 10或更新系统"}
        check(manager.adapter?.isEnabled==true){"请先打开手机蓝牙"}
        val ticket=generation.incrementAndGet()
        sessionJob=currentCoroutineContext()[Job]
        val connected=CompletableDeferred<Unit>()
        var services=CompletableDeferred<Unit>()
        fun active()=generation.get()==ticket&&!closed.get()
        fun complete(kind:String,uuid:UUID,status:Int,value:ByteArray=byteArrayOf()){
            if(!active())return
            val request=pending?:return
            if(request.kind!=kind||request.uuid!=uuid)return
            if(status==BluetoothGatt.GATT_SUCCESS)request.reply.complete(value.copyOf())
            else request.reply.completeExceptionally(IllegalStateException("蓝牙操作未确认（$status），请核对机身配对目标"))
        }
        val callbacks=object:BluetoothGattCallback(){
            override fun onConnectionStateChange(connection:BluetoothGatt,status:Int,newState:Int){
                if(!active()){runCatching{connection.close()};return}
                if(newState==BluetoothProfile.STATE_CONNECTED&&status==BluetoothGatt.GATT_SUCCESS)connected.complete(Unit)
                else if(newState==BluetoothProfile.STATE_DISCONNECTED){
                    val failure=IllegalStateException("富士蓝牙连接已中断，请重新配对")
                    connected.completeExceptionally(failure);services.completeExceptionally(failure);pending?.reply?.completeExceptionally(failure)
                }
            }
            override fun onServicesDiscovered(connection:BluetoothGatt,status:Int){
                if(!active())return
                if(status==BluetoothGatt.GATT_SUCCESS)services.complete(Unit)
                else services.completeExceptionally(IllegalStateException("未能读取相机蓝牙服务"))
            }
            override fun onCharacteristicRead(connection:BluetoothGatt,characteristic:BluetoothGattCharacteristic,value:ByteArray,status:Int){complete("read",characteristic.uuid,status,value)}
            @Deprecated("Android 12兼容回调")
            override fun onCharacteristicRead(connection:BluetoothGatt,characteristic:BluetoothGattCharacteristic,status:Int){
                if(Build.VERSION.SDK_INT<33){@Suppress("DEPRECATION") val value=characteristic.value?:byteArrayOf();complete("read",characteristic.uuid,status,value)}
            }
            override fun onCharacteristicWrite(connection:BluetoothGatt,characteristic:BluetoothGattCharacteristic,status:Int){complete("write",characteristic.uuid,status)}
            override fun onDescriptorWrite(connection:BluetoothGatt,descriptor:BluetoothGattDescriptor,status:Int){complete("subscribe",descriptor.characteristic.uuid,status)}
        }
        try{
            phase("正在连接相机蓝牙")
            synchronized(lifecycle){
                check(!closed.get()){"连接已取消"}
                gatt=manager.adapter.getRemoteDevice(device.address).connectGatt(context,false,callbacks,BluetoothDevice.TRANSPORT_LE)
                    ?:error("系统未能创建相机蓝牙连接")
            }
            withTimeout(15000){connected.await()}
            val connection=checkNotNull(gatt)
            check(connection.discoverServices()){"系统未能读取蓝牙服务"}
            withTimeout(12000){services.await()}
            val legacy=connection.getService(Protocol.LEGACY_SERVICE)?.takeIf{device.variant!=FujiDiscoveryRules.Variant.SECURE}
            val pairing=if(legacy!=null){
                pairingToken=(device.token?:savedToken)?.copyOf()
                val token=pairingToken?:error("请在机身进入蓝牙配对，让FrameLink重新扫描相机")
                write(checkNotNull(legacy.getCharacteristic(Protocol.LEGACY_PAIR)){"缺少富士配对特征"},token)
                legacy
            }else{
                check(connection.getService(Protocol.SECURE_SERVICE)!=null||device.manufacturerVerified||device.variant==FujiDiscoveryRules.Variant.SECURE){"设备未公布富士安全配对服务，请在机身进入配对模式后重试"}
                phase("请在手机与相机上确认蓝牙配对")
                bond(connection.device)
                services=CompletableDeferred()
                check(connection.discoverServices()){"配对后未能读取蓝牙服务"};withTimeout(12000){services.await()}
                val secure=connection.getService(Protocol.SECURE_SERVICE)?:error("机身未提供已适配的富士配对服务")
                val status=secure.getCharacteristic(Protocol.SECURE_STATUS)?:error("缺少富士安全配对状态")
                write(status,Protocol.secureAcknowledgement(read(status)))
                secure
            }
            write(checkNotNull(pairing.getCharacteristic(Protocol.CLIENT_NAME)){"缺少客户端身份特征"},"FrameLink".toByteArray(Charsets.US_ASCII))
            val firmwareCharacteristic=connection.getService(Protocol.FIRMWARE_SERVICE)?.getCharacteristic(Protocol.FIRMWARE)
            if(firmwareCharacteristic!=null)firmware=Protocol.decodeText(read(firmwareCharacteristic),64)
            val status=connection.getService(Protocol.STATUS_SERVICE)?.getCharacteristic(Protocol.WIFI_READY)
            if(status!=null)subscribe(status)
            val network=connection.getService(Protocol.NETWORK_SERVICE)?:error("机身未提供手机Wi-Fi交接服务")
            val ssidCharacteristic=network.getCharacteristic(Protocol.SSID)?:error("机身未提供Wi-Fi名称")
            phase("正在读取相机Wi-Fi信息")
            var ssid=Protocol.decodeText(read(ssidCharacteristic),64)
            val start=connection.getService(Protocol.WIFI_CONTROL_SERVICE)?.getCharacteristic(Protocol.WIFI_START)
                ?:error("机身未提供已适配的Wi-Fi启动特征")
            write(start,byteArrayOf(4,0))
            if(ssid.isEmpty()){delay(300);ssid=Protocol.decodeText(read(ssidCharacteristic),64)}
            check(ssid.isNotEmpty()&&ssid.toByteArray(Charsets.UTF_8).size<=32){"相机未返回有效Wi-Fi名称"}
            val passwordCharacteristic=network.getCharacteristic(Protocol.PASSWORD)
            val password=passwordCharacteristic?.let{Protocol.decodeText(read(it),128)}.orEmpty()
            check(password.isEmpty()||password.length in 8..63&&password.all{it.code in 32..126}){"机身返回的热点认证格式尚未适配"}
            check(active()){"连接已取消"}
            phase("请在系统提示中允许连接相机Wi-Fi")
            val specifier=WifiNetworkSpecifier.Builder().setSsid(ssid).apply{
                if(password.isNotEmpty()){setWpa2Passphrase(password);setIsHiddenSsid(true)}
            }.build()
            val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
            val ready=CompletableDeferred<Pair<Network,String>>()
            networkPending=ready
            val callback=object:ConnectivityManager.NetworkCallback(){
                private fun available(network:Network,properties:LinkProperties?){
                    if(!active())return
                    val gateway=properties?.routes.orEmpty().mapNotNull{it.gateway as? Inet4Address}
                        .firstOrNull{!it.isAnyLocalAddress&&!it.isLoopbackAddress}?.hostAddress
                    if(gateway!=null)ready.complete(network to gateway)
                }
                override fun onAvailable(network:Network){available(network,connectivity.getLinkProperties(network))}
                override fun onLinkPropertiesChanged(network:Network,properties:LinkProperties){available(network,properties)}
                override fun onUnavailable(){if(active())ready.completeExceptionally(IllegalStateException("相机Wi-Fi未连接，请在系统提示中确认"))}
                override fun onLost(network:Network){if(active()&&!ready.isCompleted)ready.completeExceptionally(IllegalStateException("相机Wi-Fi已断开"))}
            }
            synchronized(lifecycle){
                check(active()){"连接已取消"}
                networkCallback=callback
                connectivity.requestNetwork(request,callback,45000)
            }
            withTimeout(50000){ready.await()}.also{phase("正在核对相机身份和手机图库")}
        }catch(error:Exception){
            close(cancelSession=false)
            if(error is TimeoutCancellationException)throw IllegalStateException("等待相机连接超时，请确认机身配对与系统Wi-Fi提示后重试")
            if(error is SecurityException)throw IllegalStateException("附近设备权限不可用，请重新允许蓝牙和Wi-Fi访问")
            throw error
        }
    }

    fun tokenToRemember():ByteArray?=pairingToken?.copyOf()

    private suspend fun bond(device:BluetoothDevice){
        if(device.bondState==BluetoothDevice.BOND_BONDED)return
        val paired=CompletableDeferred<Unit>()
        val receiver=object:BroadcastReceiver(){
            override fun onReceive(context:Context,intent:Intent){
                try{
                val changed=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if(changed?.address!=device.address)return
                when(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_NONE)){
                    BluetoothDevice.BOND_BONDED->paired.complete(Unit)
                    BluetoothDevice.BOND_NONE->if(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,BluetoothDevice.BOND_NONE)==BluetoothDevice.BOND_BONDING)
                        paired.completeExceptionally(IllegalStateException("蓝牙配对已取消，请在机身选择此手机"))
                }
                }catch(_:SecurityException){paired.completeExceptionally(IllegalStateException("蓝牙权限已撤销，配对已停止"))}
            }
        }
        ContextCompat.registerReceiver(context,receiver,IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),ContextCompat.RECEIVER_EXPORTED)
        try{
            synchronized(lifecycle){
                check(!closed.get()){"连接已取消"}
                check(device.createBond()||device.bondState==BluetoothDevice.BOND_BONDED){"系统未能开始蓝牙配对"}
            }
            if(device.bondState==BluetoothDevice.BOND_BONDED)paired.complete(Unit)
            withTimeout(40000){paired.await()}
        }finally{context.unregisterReceiver(receiver)}
    }

    private suspend fun exchange(kind:String,uuid:UUID,start:(BluetoothGatt)->Boolean):ByteArray=operations.withLock{
        val connection=checkNotNull(gatt){"相机蓝牙已关闭"}
        val request=GattRequest(kind,uuid,CompletableDeferred())
        pending=request
        try{
            synchronized(lifecycle){check(!closed.get()){"连接已取消"};check(start(connection)){"蓝牙正忙，请重新连接"}}
            withTimeout(10000){request.reply.await()}
        }
        finally{if(pending===request)pending=null}
    }
    private suspend fun read(characteristic:BluetoothGattCharacteristic)=exchange("read",characteristic.uuid){it.readCharacteristic(characteristic)}
    private suspend fun write(characteristic:BluetoothGattCharacteristic,value:ByteArray){
        Protocol.checkWrite(characteristic.uuid,value)
        exchange("write",characteristic.uuid){connection->
            val type=when{
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE!=0->BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0->BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else->error("机身未开放此配对写入特征")
            }
            if(Build.VERSION.SDK_INT>=33)connection.writeCharacteristic(characteristic,value,type)==BluetoothStatusCodes.SUCCESS
            else{@Suppress("DEPRECATION") characteristic.value=value;characteristic.writeType=type;@Suppress("DEPRECATION") connection.writeCharacteristic(characteristic)}
        }
    }
    private suspend fun subscribe(characteristic:BluetoothGattCharacteristic){
        val value=when{
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0->BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE!=0->BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else->return
        }
        val descriptor=characteristic.getDescriptor(Protocol.CCCD)?:return
        exchange("subscribe",characteristic.uuid){connection->
            check(connection.setCharacteristicNotification(characteristic,true)){"无法订阅相机连接状态"}
            if(Build.VERSION.SDK_INT>=33)connection.writeDescriptor(descriptor,value)==BluetoothStatusCodes.SUCCESS
            else{@Suppress("DEPRECATION") descriptor.value=value;@Suppress("DEPRECATION") connection.writeDescriptor(descriptor)}
        }
    }

    fun close(cancelSession:Boolean=true)=synchronized(lifecycle){
        closed.set(true)
        generation.incrementAndGet()
        pending?.reply?.completeExceptionally(CancellationException("连接已取消"));pending=null
        networkPending?.completeExceptionally(CancellationException("连接已取消"));networkPending=null
        if(cancelSession)sessionJob?.cancel();sessionJob=null
        networkCallback?.let{runCatching{connectivity.unregisterNetworkCallback(it)}};networkCallback=null
        gatt?.let{runCatching{it.disconnect()};runCatching{it.close()}};gatt=null
        pairingToken?.fill(0);pairingToken=null
    }

    companion object{
        suspend fun discover(context:Context,onDevices:(List<FujiNearbyDevice>)->Unit={}):List<FujiNearbyDevice>{
            val adapter=context.getSystemService(BluetoothManager::class.java).adapter
            check(adapter?.isEnabled==true){"请先打开手机蓝牙"}
            val scanner=checkNotNull(adapter.bluetoothLeScanner){"蓝牙扫描暂不可用"}
            val found=FujiDiscoveryRules.Collector()
            fun devices()=found.snapshot().map{FujiNearbyDevice(it.address,it.name,it.token(),it.manufacturerVerified,it.variant)}.sortedBy{it.name}
            adapter.bondedDevices.forEach{found.bonded(it.address,it.name)}
            onDevices(devices())
            val failure=CompletableDeferred<Unit>()
            val callback=object:ScanCallback(){
                override fun onScanResult(type:Int,result:ScanResult){
                    try{
                    val record=result.scanRecord?:return
                    val services=record.serviceUuids.orEmpty().map{it.uuid}
                    val name=record.deviceName?:result.device.name
                    val entries=record.manufacturerSpecificData
                    val payload=record.getManufacturerSpecificData(FujiDiscoveryRules.COMPANY_ID)
                    val match=if(payload!=null)FujiDiscoveryRules.manufacturer(FujiDiscoveryRules.COMPANY_ID,payload,services.toSet())
                        else if(entries==null||entries.size()==0)FujiDiscoveryRules.services(services.toSet())else null
                    val changed=if(match!=null)found.offer(result.device.address,name,match)
                        else if(entries==null||entries.size()==0)found.nameOnly(result.device.address,name)else false
                    if(changed){
                        if(BuildConfig.DEBUG)android.util.Log.d("FrameLinkFujiScan","matched manufacturer=${match?.manufacturerVerified==true} payloadBytes=${payload?.size?:0} type=${payload?.firstOrNull()?.toInt()?.and(255)?:-1} namePresent=${!name.isNullOrBlank()} services=${services.size}")
                        onDevices(devices())
                    }
                    }catch(_:SecurityException){found.close();failure.completeExceptionally(IllegalStateException("附近设备权限已撤销，请重新授权后发现相机"))}
                }
                override fun onBatchScanResults(results:MutableList<ScanResult>){results.forEach{onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,it)}}
                override fun onScanFailed(code:Int){found.close();failure.completeExceptionally(IllegalStateException("蓝牙扫描未完成（$code）"))}
            }
            try{
                scanner.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),callback)
                withTimeoutOrNull(12000){failure.await()}
                return devices()
            }finally{found.close();scanner.stopScan(callback)}
        }
    }
}
