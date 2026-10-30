package app.framelink.camera

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 假SDK设备经过真实NativeCamera、Room、任务队列和选片仓库，不冒充真实USB协议 */
@RunWith(AndroidJUnit4::class)
class UsbWorkflowAcceptanceTest {
    @get:Rule(order=0)val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1)val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private class Device(val serial:String,val creationOnly:Boolean=false){
        val jpeg=ByteArrayOutputStream().also{out->
            val image=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
            try{image.eraseColor(android.graphics.Color.rgb(60,110,140));image.compress(Bitmap.CompressFormat.JPEG,90,out)}finally{image.recycle()}
        }.toByteArray()
        val raw=ByteArray((5 shl 20)+123){(it%251).toByte()}
        val opened=mutableListOf<Access>()
        @Volatile var block:CountDownLatch?=null
        @Volatile var began=CountDownLatch(1)
        fun open()=Access(this).also{synchronized(opened){opened+=it}}
        fun release(){block?.countDown();block=null}
    }
    private class Access(private val device:Device):UsbPhotoAccess{
        private val live=AtomicBoolean(true)
        override val descriptor=UsbPhotoDescriptor("FUJIFILM","X-T30 II",device.serial,"SDK-FAKE",
            setOf(UsbPhotoOperations.STORAGES,UsbPhotoOperations.HANDLES,UsbPhotoOperations.INFO,UsbPhotoOperations.OBJECT,UsbPhotoOperations.THUMBNAIL,UsbPhotoOperations.PARTIAL))
        override fun connected()=live.get()
        override fun storages()=intArrayOf(1)
        override fun handles(storage:Int)=if(device.creationOnly)intArrayOf(1,2,3)else intArrayOf(1,2)
        override fun info(handle:Int):UsbPhotoObject?{
            if(!connected())return null
            val data=if(handle==2)device.raw else device.jpeg
            val name=when(handle){1->"QA_USB.JPG";2->"QA_USB.RAF";else->"QA_NEW.JPG"}
            val created=1792713600000+if(handle==3)60000 else 0
            return UsbPhotoObject(handle,1,10,name,data.size.toLong(),created,if(device.creationOnly)0 else created)
        }
        override fun thumbnail(handle:Int)=device.jpeg
        override fun partial(handle:Int,offset:Long,size:Int,buffer:ByteArray,extended:Boolean):Long{
            if(handle==2&&offset==0L){
                device.began.countDown()
                check(device.block?.await(10,TimeUnit.SECONDS)!=false){"假设备读取等待超时"}
            }
            check(connected()){"假USB已断开"}
            val data=if(handle==2)device.raw else device.jpeg
            val n=minOf(size,data.size-offset.toInt())
            data.copyInto(buffer,0,offset.toInt(),offset.toInt()+n)
            return n.toLong()
        }
        override fun importOriginal(handle:Int,target:File):Boolean=error("本夹具应使用分块读取")
        override fun close(){live.set(false)}
    }
    private class Opener(private val device:Device):UsbCameraOpener{
        private var token=0L
        override fun begin()=++token
        override fun targets()=listOf(UsbCameraTarget("qa-usb",device.descriptorName()))
        override fun open(key:String,ticket:Long):UsbPhotoAccess{
            check(key=="qa-usb"&&ticket==token)
            return device.open()
        }
        private fun Device.descriptorName()="SDK fake X-T30 II"
    }
    private inner class Harness(creationOnly:Boolean=false) {
        val token=UUID.randomUUID().toString()
        val app=ApplicationProvider.getApplicationContext<CameraApp>()
        val directory=File(app.cacheDir,"qa-usb-workflow-"+token).apply{mkdirs()}
        val context=object:ContextWrapper(app){
            override fun getFilesDir()=File(directory,"files").apply{mkdirs()}
            override fun getCacheDir()=File(directory,"cache").apply{mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=app.getSharedPreferences("qa-usb-"+token+"-"+name,mode)
        }
        val database=Room.inMemoryDatabaseBuilder(app,LocalDatabase::class.java).build()
        val device=Device(token,creationOnly)
        val camera=NativeCamera(context,Opener(device))
        val runtime=CameraRuntime(context,database,SecureSettings(context),camera)
        val config=ConnectionConfig(host="qa-usb",port=0,protocol=CameraProtocols.USB)
        fun connect(expected:String=""){
            runBlocking{runtime.connect(config.copy(usbIdentity=expected)).join()}
            assertNotNull(runtime.camera.value.message,runtime.camera.value.info)
            runtime.browseRecent()
            compose.waitUntil(15000){runtime.currentCameraIds.value.isNotEmpty()&&!runtime.camera.value.loadingPhotos}
        }
        fun photo()=runBlocking{database.photos().byIds(runtime.currentCameraIds.value.toList()).single()}
        fun task(photo:Photo)=runBlocking{database.tasks().all().last{it.photoId==photo.id}}
        fun waitTask(photo:Photo,state:TransferState){
            compose.waitUntil(20000){runBlocking{database.tasks().all().any{it.photoId==photo.id&&it.state==state.name}}}
        }
        fun close(){
            device.release()
            runtime.disconnect()
            runBlocking{
                val imported=database.photos().all().filter{it.inLibrary}
                if(imported.isNotEmpty())runtime.removePhotos(runtime.photoRemoval.plan(imported.map{it.id}),true)
                runtime.scope.coroutineContext[Job]!!.cancelAndJoin()
            }
            runtime.bluetooth.close()
            database.close()
            directory.deleteRecursively()
            app.deleteSharedPreferences("qa-usb-"+token+"-framelink.settings")
        }
    }

    @Test fun sdkUsbToRealImportTasksLocalMediaAndNamedSelectionPreservesOriginals(){
        val h=Harness()
        try{
            h.connect()
            val photo=h.photo()
            assertEquals(0L,photo.capturedAt)
            val preview=runBlocking{h.runtime.ensurePreview(photo)}
            assertNotNull(BitmapFactory.decodeFile(preview))
            val batch=runBlocking{h.runtime.batches.save("QA USB "+h.token,listOf(photo.id))}
            runBlocking{h.runtime.enqueueDownload(listOf(photo.id),DownloadFormat.ALL).join()}
            h.waitTask(photo,TransferState.COMPLETE)
            val saved=runBlocking{h.database.photos().find(photo.id)!!}
            assertTrue(saved.inLibrary)
            assertTrue(saved.files().all{it.originalPath.isNotBlank()})
            for(file in saved.files()){
                val original=File(file.originalPath)
                assertArrayEquals(if(file.raw)h.device.raw else h.device.jpeg,original.readBytes())
                assertEquals(file.size,original.length())
            }
            assertEquals(h.task(photo).total,h.task(photo).done)
            assertEquals(listOf(photo.id),runBlocking{h.database.batches().find(batch.id)!!.photoIds()})
            val sheets=runBlocking{h.runtime.batches.contactSheets(batch)}
            assertTrue(sheets.isNotEmpty()&&sheets.all{BitmapFactory.decodeFile(it.path)!=null})
            val identity=h.runtime.camera.value.info!!.id
            h.runtime.disconnect()
            assertTrue(runBlocking{h.database.photos().find(photo.id)!!.inLibrary})
            h.connect(identity)
            assertEquals(photo.id,h.photo().id)
            assertTrue(h.photo().inLibrary)
        }finally{h.close()}
    }

    @Test fun creationOnlyUsbDatesOrderTheActualRuntimePhotoFlow(){
        val h=Harness(creationOnly=true)
        try{
            h.connect()
            val rows=runBlocking{h.runtime.photos.first{it.size==2}}
            assertTrue(rows.all{it.capturedAt==0L&&it.files().all{file->file.fileModifiedAt==0L&&file.fileCreatedAt>0}})
            assertEquals("QA_NEW.JPG",rows.first().name)
            assertTrue(rows.first().browsingTime>rows.last().browsingTime)
        }finally{h.close()}
    }

    @Test fun cancelAndReconnectRetryKeepAnExistingOriginalUntilACompleteReplacement(){
        val h=Harness()
        try{
            h.connect()
            val photo=h.photo()
            val raw=photo.files().first{it.raw}
            val target=File(h.context.filesDir,"media/"+MediaFiles.key(photo.id)+"/"+raw.fileId+"_"+raw.name).apply{parentFile!!.mkdirs();writeText("keep-existing")}
            h.device.block=CountDownLatch(1)
            h.runtime.enqueueDownload(listOf(photo.id),DownloadFormat.RAW)
            assertTrue(h.device.began.await(10,TimeUnit.SECONDS))
            val task=h.task(photo)
            runBlocking{h.runtime.cancel(task).join()}
            h.device.release()
            h.waitTask(photo,TransferState.CANCELLED)
            compose.waitUntil(10000){!File(target.path+".part").exists()}
            assertEquals("keep-existing",target.readText())
            assertFalse(runBlocking{h.database.photos().find(photo.id)!!.imported})
            val identity=h.runtime.camera.value.info!!.id
            h.runtime.disconnect()
            h.connect(identity)
            runBlocking{h.runtime.retry(h.task(photo)).join()}
            h.waitTask(photo,TransferState.COMPLETE)
            assertArrayEquals(h.device.raw,target.readBytes())
            assertEquals(TransferState.COMPLETE.name,h.task(photo).state)
        }finally{h.close()}
    }

    @Test fun unplugDoesNotPublishPartialAndReconnectKeepsTheSelectionRecord(){
        val h=Harness()
        try{
            h.connect()
            val photo=h.photo()
            val batch=runBlocking{h.runtime.batches.save("QA reconnect "+h.token,listOf(photo.id))}
            h.device.block=CountDownLatch(1)
            h.runtime.enqueueDownload(listOf(photo.id),DownloadFormat.RAW)
            assertTrue(h.device.began.await(10,TimeUnit.SECONDS))
            val identity=h.runtime.camera.value.info!!.id
            h.runtime.disconnect()
            h.device.release()
            compose.waitUntil(10000){runBlocking{h.database.tasks().all().none{it.state==TransferState.RUNNING.name}}}
            assertFalse(runBlocking{h.database.photos().find(photo.id)!!.imported})
            assertNotEquals(TransferState.COMPLETE.name,h.task(photo).state)
            h.connect(identity)
            assertEquals(listOf(photo.id),runBlocking{h.database.batches().find(batch.id)!!.photoIds()})
            runBlocking{h.runtime.retry(h.task(photo)).join()}
            h.waitTask(photo,TransferState.COMPLETE)
            val saved=runBlocking{h.database.photos().find(photo.id)!!}
            assertArrayEquals(h.device.raw,File(saved.files().first{it.raw}.originalPath).readBytes())
        }finally{h.close()}
    }
}
