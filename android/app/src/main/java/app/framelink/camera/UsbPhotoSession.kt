package app.framelink.camera

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** USB读卡只暴露读取操作，不包含删除、格式化、上传和机身设置写入 */
internal interface UsbPhotoAccess {
    val descriptor: UsbPhotoDescriptor
    fun connected():Boolean
    fun storages():IntArray
    fun handles(storage:Int):IntArray
    fun info(handle:Int):UsbPhotoObject?
    fun thumbnail(handle:Int):ByteArray?
    fun partial(handle:Int,offset:Long,size:Int,buffer:ByteArray,extended:Boolean):Long
    fun importOriginal(handle:Int,target:File):Boolean
    fun close()
}

internal data class UsbPhotoDescriptor(val manufacturer:String,val model:String,val serial:String,val firmware:String,val operations:Set<Int>)

internal data class UsbPhotoObject(
    val handle:Int,val storage:Int,val parent:Int,val name:String,val size:Long,
    val createdAt:Long,val modifiedAt:Long,val folder:Boolean=false,val thumbnailSize:Long=0,
)

internal object UsbPhotoOperations {
    const val STORAGES=0x1004
    const val HANDLES=0x1007
    const val INFO=0x1008
    const val OBJECT=0x1009
    const val THUMBNAIL=0x100A
    const val PARTIAL=0x101B
    const val PARTIAL_64=0x95C1
}

/** 同一USB连接的只读媒体索引，所有原片通过有界块或文件描述符写入 */
internal class UsbPhotoSession(private val access:UsbPhotoAccess){
    private val op=ReentrantLock()
    private val cancellation=AtomicLong(0)
    private val closed=AtomicBoolean(false)
    private val objects=mutableMapOf<Int,UsbPhotoObject>()
    private var indexedSlot=0
    private var indexed=emptyList<RemotePhoto>()
    private var datesFresh=false
    private var pagingDay=""
    val info:CameraInfo

    init {
        val descriptor=access.descriptor
        require(descriptor.serial.isNotBlank()){"USB设备未提供稳定标识，暂不建立照片索引"}
        val required=setOf(UsbPhotoOperations.STORAGES,UsbPhotoOperations.HANDLES,UsbPhotoOperations.INFO,UsbPhotoOperations.OBJECT)
        require(descriptor.operations.containsAll(required)){"此USB模式未提供读卡所需操作，请在相机选择USB读卡模式"}
        val identity=MediaFiles.key("usb-mtp:"+descriptor.manufacturer+":"+descriptor.model+":"+descriptor.serial).take(24)
        info=CameraInfo(identity,descriptor.model,descriptor.firmware,descriptor.manufacturer,
            canBrowse=true,canDownload=true,canPreview=UsbPhotoOperations.THUMBNAIL in descriptor.operations,
            hardwareVerified=false,dateSource=CameraTimeSource.FILE_METADATA,canAutoImport=false,connectionKind=CameraConnectionKinds.USB_READER)
    }

    fun connected()=!closed.get()&&access.connected()

    private fun snapshot(slot:Int){
        check(connected()){"USB相机连接已断开"}
        val storages=access.storages()
        require(slot in 1..storages.size){"相机未提供此存储卡"}
        val handles=access.handles(storages[slot-1])
        require(handles.size<=100000){"相机对象数超过单次索引范围"}
        val seen=mutableSetOf<Int>()
        val records=handles.asSequence().mapNotNull{handle->
            check(connected()){"USB相机连接已断开"}
            check(seen.add(handle)){"相机对象索引重复，请重新连接"}
            val item=access.info(handle)?:error("机身对象读取未完成，请刷新")
            if(item.folder||!CameraFile(name=item.name).image)null else item
        }.toList()
        val grouped=records.groupBy{it.parent.toString()+":"+it.name.substringBeforeLast('.',it.name).uppercase(Locale.ROOT)}
        val used=mutableSetOf<Int>()
        objects.clear()
        indexed=grouped.map{(group,values)->
            val sorted=values.sortedBy{it.name}
            val revision=Json.encode(listOf(info.id,slot,group,sorted.map{listOf(it.handle,it.name,it.size,it.createdAt,it.modifiedAt)}))
            val key=MediaFiles.key(revision)
            val content=(key.take(8).toLong(16) and 0x7fffffff).toInt().coerceAtLeast(1)
            check(used.add(content)){"USB资源编号冲突，请重新读取"}
            val files=sorted.map{item->
                objects[item.handle]=item
                val time=item.modifiedAt.takeIf{it>0}?:item.createdAt
                CameraFile(item.handle,item.name,cameraPath=storages[slot-1].toString()+":"+item.parent+":"+item.name,size=item.size,
                    fileModifiedAt=item.modifiedAt,fileCreatedAt=item.createdAt,
                    fileDay=if(time>0)SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(Date(time))else"")
            }
            RemotePhoto(info.id+":usb:"+key,content,slot,0,0,files)
        }.sortedWith(compareByDescending<RemotePhoto>{row->row.files.maxOfOrNull{it.fileModifiedAt.takeIf{time->time>0}?:it.fileCreatedAt}?:0}.thenBy{it.id})
        indexedSlot=slot
        datesFresh=true
    }

    fun dates(slot:Int):List<String> = op.withLock {
        snapshot(slot)
        indexed.mapNotNull{it.files.fileTimeDay().takeIf(String::isNotBlank)}.distinct().sortedDescending()
    }

    fun page(slot:Int,day:String,cursor:Long):CameraPage = op.withLock {
        require(cursor>=0){"USB分页游标无效"}
        if(day.isNotBlank())require(runCatching{java.time.LocalDate.parse(day)}.isSuccess){"文件日期无效"}
        if(cursor==0L&&(!datesFresh||indexedSlot!=slot))snapshot(slot)
        check(indexedSlot==slot){"USB索引已变化，请刷新"}
        if(cursor>0)check(pagingDay==day){"USB分页日期已变化，请重新读取第一页"}
        else pagingDay=day
        datesFresh=false
        val rows=if(day.isBlank())indexed else indexed.filter{it.files.fileTimeDay()==day}
        require(cursor<=rows.size){"USB分页已变化，请刷新"}
        val until=minOf(cursor+100,rows.size.toLong()).toInt()
        CameraPage(rows.subList(cursor.toInt(),until),if(until==rows.size)0 else until.toLong(),rows.size)
    }

    private fun selected(photo:Photo,file:CameraFile):UsbPhotoObject{
        check(connected()){"USB相机连接已断开"}
        val row=indexed.firstOrNull{it.id==photo.id&&it.contentId==photo.contentId&&it.slot==photo.slot}?:error("照片不在当前USB索引中，请刷新")
        check(row.files.any{it.fileId==file.fileId&&canReuseCameraFile(it,file)}){"USB照片资源已变化"}
        val original=objects[file.fileId]?:error("USB文件已不在当前索引")
        val current=access.info(file.fileId)?:error("USB文件读取未完成")
        check(current==original){"USB文件已变化，请刷新后重新选择"}
        return original
    }

    fun preview(photo:Photo,file:CameraFile,target:File)=op.withLock {
        val item=selected(photo,file)
        check(UsbPhotoOperations.THUMBNAIL in access.descriptor.operations){"此USB模式未提供缩略图，可下载原片查看"}
        check(item.thumbnailSize in 0..(32L shl 20)){"机身缩略图长度无效"}
        val data=access.thumbnail(item.handle)?:error("机身未返回缩略图，可稍后重试或下载原片")
        check(data.isNotEmpty()&&data.size<=(32 shl 20)){"机身缩略图超出读取范围"}
        val jpeg=data.size>=3&&data[0]==0xff.toByte()&&data[1]==0xd8.toByte()&&data[2]==0xff.toByte()
        val png=data.size>=8&&data.take(8)==listOf(137,80,78,71,13,10,26,10).map(Int::toByte)
        check(jpeg||png){"机身未返回可识别的预览图片"}
        target.parentFile?.mkdirs()
        val part=File(target.path+".part")
        try {
            FileOutputStream(part).use{it.write(data);it.fd.sync()}
            Files.move(part.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{part.delete()}
    }

    fun download(photo:Photo,file:CameraFile,target:File,onProgress:(Long,Long)->Unit):String=run {
        val generation=cancellation.get()
        op.withLock {
        check(cancellation.get()==generation&&connected()){"USB导入已取消或设备断开"}
        val item=selected(photo,file)
        require(item.size>0){"机身未提供可验证的原片长度"}
        target.parentFile?.mkdirs()
        val part=File(target.path+".part")
        val operations=access.descriptor.operations
        val extended=UsbPhotoOperations.PARTIAL_64 in operations
        val chunked=extended||UsbPhotoOperations.PARTIAL in operations
        require(item.size<=0xffffffffL||extended){"此USB模式未验证超过4GB的读取，请通过读卡器导入"}
        try{
            if(chunked){
                FileOutputStream(part,false).use{output->
                    val buffer=ByteArray(1 shl 20)
                    var done=0L
                    var synced=0L
                    while(done<item.size){
                        check(cancellation.get()==generation&&connected()){"USB导入已取消或设备断开"}
                        val wanted=minOf(buffer.size.toLong(),item.size-done).toInt()
                        val read=access.partial(item.handle,done,wanted,buffer,extended)
                        check(read in 1..wanted.toLong()){"机身返回的原片长度无效"}
                        output.write(buffer,0,read.toInt())
                        done+=read
                        if(done-synced>=(4L shl 20)||done==item.size){output.fd.sync();synced=done;onProgress(done,item.size)}
                    }
                }
            }else{
                check(access.importOriginal(item.handle,part)){"USB原片读取未完成"}
                // Framework import has no per-block progress or cancellation API.
                // A cancellation discards the result once the current read returns.
                FileOutputStream(part,true).use{it.fd.sync()}
            }
            check(cancellation.get()==generation&&connected()){"USB导入已取消或设备断开"}
            check(part.length()==item.size){"USB原片长度与机身信息不符"}
            val digest=MediaFiles.digest(part.inputStream())
            check(selected(photo,file)==item){"读取期间USB文件已变化"}
            Files.move(part.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            onProgress(item.size,item.size)
            Json.encode(mapOf("path" to target.path,"size" to item.size,"sha256" to digest))
        }finally{part.delete()}
        }
    }

    fun cancelTransfer(){cancellation.incrementAndGet()}
    fun close(){closed.set(true);cancellation.incrementAndGet();access.close()}
}
