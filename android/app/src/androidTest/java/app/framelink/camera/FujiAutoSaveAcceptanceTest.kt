package app.framelink.camera

import android.Manifest
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
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 按公开布局独立构造TCP端点，经Go/JNI、真实导入队列和Room；不是富士机身或实拍RAF */
@RunWith(AndroidJUnit4::class)
class FujiAutoSaveAcceptanceTest {
    @get:Rule(order=0)val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1)val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private class Peer:AutoCloseable{
        val jpeg=ByteArrayOutputStream().also{out->
            val image=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
            try{image.eraseColor(android.graphics.Color.rgb(74,120,148));image.compress(Bitmap.CompressFormat.JPEG,90,out)}finally{image.recycle()}
        }.toByteArray()
        val raw="FUJIFILMCCD-RAW SYNTHETIC".toByteArray()+ByteArray((2 shl 20)+37){(it%239).toByte()}
        val reads=AtomicInteger()
        val writes=Collections.synchronizedList(mutableListOf<Int>())
        val listener=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        @Volatile var active:Socket?=null
        @Volatile var failure:Throwable?=null
        val worker=thread(name="fuji-synthetic-endpoint",isDaemon=true){
            try{listener.accept().use{socket->active=socket;socket.soTimeout=20000;serve(socket)}}
            catch(error:Throwable){if(!listener.isClosed&&error !is java.io.EOFException)failure=error}
        }
        private fun u16(value:Int)=ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        private fun u32(value:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        private fun intAt(data:ByteArray,offset:Int=0)=ByteBuffer.wrap(data,offset,4).order(ByteOrder.LITTLE_ENDIAN).int
        private fun shortAt(data:ByteArray,offset:Int)=ByteBuffer.wrap(data,offset,2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        private fun text(value:String):ByteArray{
            val encoded=(value+"\u0000").toByteArray(Charsets.UTF_16LE)
            return byteArrayOf((encoded.size/2).toByte())+encoded
        }
        private fun objectInfo(handle:Int):ByteArray{
            val name=if(handle==1)"QA_FUJI.JPG" else "QA_FUJI.RAF"
            val data=if(handle==1)jpeg else raw
            return u32(1)+u16(if(handle==1)0x3801 else 0xb103)+u16(0)+u32(data.size)+ByteArray(26)+u32(9)+ByteArray(10)+
                text(name)+text("20261023T120000Z")+text("20261023T120000Z")+byteArrayOf(0)
        }
        private data class Packet(val kind:Int,val code:Int,val tx:Int,val payload:ByteArray)
        private fun read(input:DataInputStream):Packet{
            val header=ByteArray(12);input.readFully(header);val size=intAt(header)
            check(size in 12..(1048576+12));val data=ByteArray(size-12);input.readFully(data)
            return Packet(shortAt(header,4),shortAt(header,6),intAt(header,8),data)
        }
        private fun serve(socket:Socket){
            val input=DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
            val init=ByteArray(82);input.readFully(init);check(intAt(init)==82&&intAt(init,8)==0x8f53e4f2.toInt())
            val title="FUJI-ANDROID-FIXTURE\u0000".toByteArray(Charsets.UTF_16LE)
            val ack=u32(28+title.size)+u32(2)+ByteArray(20)+title
            output.write(ack.copyOfRange(0,3));output.write(ack.copyOfRange(3,ack.size));output.flush()
            fun send(kind:Int,code:Int,tx:Int,data:ByteArray=byteArrayOf()){
                output.write(u32(12+data.size)+u16(kind)+u16(code)+u32(tx)+data);output.flush()
            }
            while(!socket.isClosed){
                val packet=read(input);check(packet.kind==1)
                val code=packet.code;var response=0x2001;var data:ByteArray?=null
                when(code){
                    0x1002->check(packet.tx==1&&intAt(packet.payload)==1)
                    0x1001->response=0x2005 // This published mode may omit standard DeviceInfo
                    0x1015->{val property=intAt(packet.payload)
                        data=if(property==0xd212)u16(2)+u16(0xdf00)+u32(3)+u16(0xd222)+u32(2) else u32(0x20001)
                    }
                    0x1016->{val property=intAt(packet.payload);check(property in setOf(0xdf01,0xdf23,0xd227));writes+=property
                        val value=read(input);check(value.kind==2&&value.code==code&&value.tx==packet.tx)
                    }
                    0x1008->data=objectInfo(intAt(packet.payload))
                    0x101b->{reads.incrementAndGet();val handle=intAt(packet.payload);val offset=intAt(packet.payload,4);val size=intAt(packet.payload,8)
                        check(size in 1..1048576);val original=if(handle==1)jpeg else raw;check(offset>=0&&offset.toLong()+size<=original.size)
                        data=original.copyOfRange(offset,offset+size)
                    }
                    else->error("实验媒体通道发送了不允许的操作码0x${code.toString(16)}")
                }
                data?.let{send(2,code,packet.tx,it)};send(3,response,packet.tx)
            }
        }
        override fun close(){listener.close();active?.close();worker.join(3000);check(!worker.isAlive){"模拟端点未释放"}}
    }

    @Test fun autoSaveWireThroughJniImportsOriginalsAndPreservesSavedSonyProfile(){
        val app=ApplicationProvider.getApplicationContext<CameraApp>()
        val token=UUID.randomUUID().toString();val directory=File(app.cacheDir,"qa-fuji-"+token).apply{mkdirs()}
        val context=object:ContextWrapper(app){
            override fun getFilesDir()=File(directory,"files").apply{mkdirs()}
            override fun getCacheDir()=File(directory,"cache").apply{mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=app.getSharedPreferences("qa-fuji-$token-$name",mode)
        }
        val database=Room.inMemoryDatabaseBuilder(app,LocalDatabase::class.java).build()
        val settings=SecureSettings(context)
        val saved=ConnectionConfig(host="192.0.2.1")
        settings.put("camera.config",Json.encode(saved))
        val runtime=CameraRuntime(context,database,settings)
        val peer=Peer()
        try{
            val config=ConnectionConfig(host="127.0.0.1",port=peer.listener.localPort,protocol="fuji-autosave",experimentalFuji=true,fujiDeviceName="FUJIFILM-ANDROID-QA")
            runBlocking{runtime.connect(config).join()}
            val info=runtime.camera.value.info
            assertNotNull(runtime.camera.value.message,info)
            assertEquals("AUTOSAVE_PENDING_BATCH",info!!.mediaScope)
            assertFalse(info.canPreview);assertFalse(info.canCapture);assertFalse(info.canLiveView)
            assertEquals(saved,runtime.savedConnection())
            runtime.browseRecent()
            compose.waitUntil(20000){runtime.currentCameraIds.value.isNotEmpty()&&!runtime.camera.value.loadingPhotos}
            val photo=runBlocking{database.photos().byIds(runtime.currentCameraIds.value.toList()).single()}
            assertEquals(2,photo.files().size)
            assertEquals(0,peer.reads.get())
            try{runBlocking{runtime.ensurePreview(photo)};fail("未导入的AutoSave原片不能冒充预览")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("不提供预览"))}
            assertEquals(0,peer.reads.get())
            val batch=runBlocking{runtime.batches.save("QA Fuji "+token,listOf(photo.id))}
            runBlocking{runtime.enqueueDownload(listOf(photo.id),DownloadFormat.ALL).join()}
            compose.waitUntil(30000){runBlocking{database.tasks().all().any{it.photoId==photo.id&&it.state in setOf(TransferState.COMPLETE.name,TransferState.FAILED.name)}}}
            assertEquals(runBlocking{database.tasks().all().filter{it.photoId==photo.id}}.toString(),TransferState.COMPLETE.name,
                runBlocking{database.tasks().all().last{it.photoId==photo.id}}.state)
            val imported=runBlocking{database.photos().find(photo.id)!!}
            assertTrue(imported.inLibrary)
            imported.files().forEach{file->assertArrayEquals(if(file.raw)peer.raw else peer.jpeg,File(file.originalPath).readBytes())}
            val preview=runBlocking{runtime.ensurePreview(imported)}
            assertNotNull(BitmapFactory.decodeFile(preview))
            assertTrue(runBlocking{runtime.batches.contactSheets(batch)}.isNotEmpty())
            val collision=File(directory,"competing-original.RAF")
            try{
                runtime.native.download(imported,imported.files().first{it.raw},collision){_,_->
                    if(!collision.exists())collision.writeText("KEEP")
                }
                fail("发布时出现的原片不可覆盖")
            }catch(expected:Exception){assertEquals("KEEP",collision.readText())}
            assertEquals(setOf(0xdf01,0xdf23,0xd227),peer.writes.toSet())
            assertNull(peer.failure)
            runtime.disconnect()
            assertTrue(runtime.currentCameraIds.value.isEmpty())
            assertTrue(imported.files().all{File(it.originalPath).isFile})
        }finally{
            runtime.disconnect();peer.close()
            runBlocking{
                val imported=database.photos().all().filter{it.inLibrary}
                if(imported.isNotEmpty())runtime.removePhotos(runtime.photoRemoval.plan(imported.map{it.id}),true)
                runtime.scope.coroutineContext[Job]!!.cancelAndJoin()
            }
            runtime.bluetooth.close();database.close();directory.deleteRecursively()
            app.deleteSharedPreferences("qa-fuji-$token-framelink.settings")
        }
    }
}
