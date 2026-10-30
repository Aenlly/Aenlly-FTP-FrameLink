package app.framelink.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class UsbCameraTarget(val key:String,val name:String)

internal class AndroidUsbCameraOpener(private val context:Context){
    private val manager=context.getSystemService(UsbManager::class.java)
    private val lock=Any()
    private var epoch=0L
    private var waiting:CountDownLatch?=null

    fun begin():Long=synchronized(lock){
        epoch++
        waiting?.countDown()
        waiting=null
        epoch
    }

    private fun valid(ticket:Long)=synchronized(lock){epoch==ticket}

    fun targets():List<UsbCameraTarget> = manager.deviceList.values.filter{device->
        (0 until device.interfaceCount).any{device.getInterface(it).interfaceClass==UsbConstants.USB_CLASS_STILL_IMAGE}
    }.map{UsbCameraTarget(it.deviceName,listOfNotNull(it.manufacturerName,it.productName).distinct().joinToString(" ").ifBlank{"USB相机"})}

    fun open(key:String,ticket:Long):UsbPhotoAccess {
        check(valid(ticket)){"USB连接已取消"}
        val candidates=targets()
        val selected=candidates.firstOrNull{it.key==key}?:candidates.singleOrNull()?:error("未找到USB相机，请检查OTG连接并选择USB读卡模式")
        val device=manager.deviceList[selected.key]?:error("USB相机已断开")
        if(!manager.hasPermission(device))requestPermission(device,ticket)
        check(valid(ticket)&&manager.hasPermission(device)){"USB访问未授权或连接已取消"}
        val connection=manager.openDevice(device)?:error("USB设备无法打开")
        val mtp=MtpDevice(device)
        check(mtp.open(connection)){"相机未进入可读取的PTP/MTP模式"}
        try{
            check(valid(ticket)){"USB连接已取消"}
            val info=mtp.deviceInfo?:error("未能读取USB机身信息")
            val serial=info.serialNumber?.takeIf(String::isNotBlank)?:device.serialNumber.orEmpty()
            val descriptor=UsbPhotoDescriptor(info.manufacturer.orEmpty(),info.model.orEmpty(),serial,info.version.orEmpty(),info.operationsSupported?.toSet().orEmpty())
            return AndroidUsbPhotoAccess(manager,device,mtp,descriptor)
        }catch(error:Exception){mtp.close();throw error}
    }

    private fun requestPermission(device:UsbDevice,ticket:Long){
        val action=context.packageName+".USB_CAMERA_PERMISSION."+UUID.randomUUID()
        val latch=CountDownLatch(1)
        val receiver=object:BroadcastReceiver(){
            override fun onReceive(context:Context,intent:Intent){
                if(intent.action==action||intent.action==UsbManager.ACTION_USB_DEVICE_DETACHED)latch.countDown()
            }
        }
        val filter=IntentFilter(action).apply{addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)}
        ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED)
        try{
            synchronized(lock){check(epoch==ticket){"USB连接已取消"};waiting=latch}
            val permission=PendingIntent.getBroadcast(context,0,Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_ONE_SHOT or (if(android.os.Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0))
            manager.requestPermission(device,permission)
            check(latch.await(30,TimeUnit.SECONDS)){"USB授权等待超时，请重新连接"}
            check(valid(ticket)&&manager.hasPermission(device)){"USB访问未授权或设备已断开"}
        }finally{
            synchronized(lock){if(waiting===latch)waiting=null}
            context.unregisterReceiver(receiver)
        }
    }
}

private class AndroidUsbPhotoAccess(
    private val manager:UsbManager,private val device:UsbDevice,private val mtp:MtpDevice,
    override val descriptor:UsbPhotoDescriptor,
):UsbPhotoAccess{
    @Volatile private var closed=false
    override fun connected()=!closed&&manager.deviceList.containsKey(device.deviceName)&&manager.hasPermission(device)
    override fun storages()=mtp.storageIds?:error("USB存储卡列表读取失败")
    override fun handles(storage:Int)=mtp.getObjectHandles(storage,0,0)?:error("USB媒体列表读取失败")
    override fun info(handle:Int):UsbPhotoObject?=mtp.getObjectInfo(handle)?.let{
        UsbPhotoObject(it.objectHandle,it.storageId,it.parent,it.name.orEmpty(),it.compressedSizeLong,it.dateCreated,it.dateModified,
            it.format==MtpConstants.FORMAT_ASSOCIATION,it.thumbCompressedSizeLong)
    }
    override fun thumbnail(handle:Int)=mtp.getThumbnail(handle)
    override fun partial(handle:Int,offset:Long,size:Int,buffer:ByteArray,extended:Boolean):Long=
        if(extended)mtp.getPartialObject64(handle,offset,size.toLong(),buffer)else mtp.getPartialObject(handle,offset,size.toLong(),buffer)
    override fun importOriginal(handle:Int,target:File):Boolean=
        ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use{
            mtp.importFile(handle,it)
        }
    override fun close(){closed=true;mtp.close()}
}
