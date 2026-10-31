package app.framelink.camera

import android.Manifest
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
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

/** 独立手机图库端点，经真实Go/JNI、NativeCamera、Room与缓存导入，不是AutoSave或机身实测 */
@RunWith(AndroidJUnit4::class)
class FujiPhoneAcceptanceTest{
    @get:Rule(order=0)val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1)val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private class PhonePeer:AutoCloseable{
        val jpeg=ByteArrayOutputStream().also{out->
            val image=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888)
            try{image.eraseColor(android.graphics.Color.rgb(45,120,155));image.compress(Bitmap.CompressFormat.JPEG,90,out)}finally{image.recycle()}
        }.toByteArray()
        val raw="FUJIFILMCCD-RAW SYNTHETIC PHONE".toByteArray()+ByteArray((1 shl 20)+19){(it%241).toByte()}
        val originalReads=AtomicInteger()
        val listener=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        @Volatile var socket:Socket?=null
        @Volatile var failure:Throwable?=null
        val worker=thread(name="fuji-phone-fixture",isDaemon=true){
            try{listener.accept().use{connection->socket=connection;connection.soTimeout=30000;serve(connection)}}
            catch(error:Throwable){if(!listener.isClosed&&error !is java.io.EOFException)failure=error}
        }
        private fun u16(value:Int)=ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        private fun u32(value:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        private fun intAt(data:ByteArray,offset:Int=0)=ByteBuffer.wrap(data,offset,4).order(ByteOrder.LITTLE_ENDIAN).int
        private fun shortAt(data:ByteArray,offset:Int)=ByteBuffer.wrap(data,offset,2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        private fun text(value:String):ByteArray{
            val data=(value+"\u0000").toByteArray(Charsets.UTF_16LE)
            return byteArrayOf((data.size/2).toByte())+data
        }
        private data class Packet(val kind:Int,val code:Int,val tx:Int,val payload:ByteArray)
        private fun read(input:DataInputStream):Packet{
            val head=ByteArray(12);input.readFully(head);val size=intAt(head);check(size in 12..(1048576+12))
            val data=ByteArray(size-12);input.readFully(data)
            return Packet(shortAt(head,4),shortAt(head,6),intAt(head,8),data)
        }
        private fun objectInfo(handle:Int,original:Boolean):ByteArray{
            val file=if(handle==1)jpeg else raw
            return u32(1)+u16(if(handle==1)0x3801 else 0xb103)+u16(0)+u32(if(original)file.size else file.size/2)+ByteArray(26)+u32(7)+ByteArray(10)+
                text(if(handle==1)"QA_PHONE.JPG"else"QA_PHONE.RAF")+text("20261023T120000Z")+text("20261023T120000Z")+byteArrayOf(0)
        }
        private fun serve(connection:Socket){
            val input=DataInputStream(connection.getInputStream());val output=connection.getOutputStream()
            val init=ByteArray(82);input.readFully(init);check(intAt(init)==82&&intAt(init,8)==0x8f53e4f2.toInt())
            val name="X-T30 II\u0000".toByteArray(Charsets.UTF_16LE)
            output.write(u32(28+name.size)+u32(2)+ByteArray(20)+name);output.flush()
            fun send(kind:Int,code:Int,tx:Int,data:ByteArray=byteArrayOf()){
                output.write(u32(data.size+12)+u16(kind)+u16(code)+u32(tx)+data);output.flush()
            }
            var original=false
            while(!connection.isClosed){
                val packet=read(input);check(packet.kind==1)
                fun param(index:Int)=intAt(packet.payload,index*4)
                var data:ByteArray?=null
                var response=0x2001
                when(packet.code){
                    0x1002->check(packet.tx==1&&param(0)==1)
                    0x1001->{
                        val operations=listOf(0x1008,0x100a,0x101b)
                        data=u16(100)+u32(0)+u16(0)+text("")+u16(0)+u32(operations.size)+operations.fold(byteArrayOf()){all,value->all+u16(value)}+
                            u32(0)+u32(0)+u32(0)+u32(0)+text("Fujifilm")+text("X-T30 II")+text("synthetic-phone")+text("ANDROID-PHONE-FIXTURE")
                    }
                    0x1015->when(param(0)){
                        0xd212->data=u16(2)+u16(0xdf00)+u32(2)+u16(0xd222)+u32(2)
                        0xdf22,0xdf21,0xdf24->data=u32(0x2000c)
                        0xdf25->data=u32(5)
                        0xdf28->response=0x200a
                        else->error("手机通道请求了未声明属性")
                    }
                    0x1016->{
                        val property=param(0);val value=read(input)
                        check(value.kind==2&&value.code==packet.code&&value.tx==packet.tx)
                        when(property){
                            0xdf01->check(value.payload.contentEquals(u16(11)))
                            0xdf25->check(value.payload.contentEquals(u32(5)))
                            0xd227->{check(value.payload.contentEquals(u16(0))||value.payload.contentEquals(u16(1)));original=value.payload.contentEquals(u16(1))}
                            else->error("手机通道出现未允许的写入")
                        }
                    }
                    0x1008->{check(param(0) in 1..2);data=objectInfo(param(0),original)}
                    0x100a->{check(param(0) in 1..2);data=jpeg}
                    0x101b->{
                        check(original);val file=if(param(0)==1)jpeg else raw;val offset=param(1);val count=param(2)
                        check(count in 1..1048576&&offset>=0&&offset+count<=file.size)
                        data=file.copyOfRange(offset,offset+count);originalReads.incrementAndGet()
                    }
                    else->error("手机协议出现无关操作")
                }
                data?.let{send(2,packet.code,packet.tx,it)}
                send(3,response,packet.tx)
            }
        }
        override fun close(){listener.close();socket?.close();worker.join(2000)}
    }

    @Test fun phoneProtocolReachesGalleryTemporaryJpegAndIndependentRawImport(){
        check(android.os.Build.HARDWARE in setOf("ranchu","goldfish")){"合成富士手机端点只运行于独立模拟器"}
        val app=ApplicationProvider.getApplicationContext<CameraApp>()
        val token=UUID.randomUUID().toString()
        val directory=File(app.cacheDir,"qa-fuji-phone-$token").apply{mkdirs()}
        val context=object:ContextWrapper(app){
            override fun getFilesDir()=File(directory,"files").apply{mkdirs()}
            override fun getCacheDir()=File(directory,"cache").apply{mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=app.getSharedPreferences("qa-fuji-phone-$token-$name",mode)
        }
        val database=Room.inMemoryDatabaseBuilder(app,LocalDatabase::class.java).build()
        val runtime=CameraRuntime(context,database,SecureSettings(context))
        val peer=PhonePeer()
        try{
            runBlocking{runtime.connect(ConnectionConfig(host="127.0.0.1",port=peer.listener.localPort,protocol=CameraProtocols.FUJI_PHONE),rememberCredentials=true).join()}
            assertNotNull(runtime.camera.value.message,runtime.camera.value.info)
            assertEquals("FUJI_PHONE_EXPOSED_MEDIA",runtime.camera.value.info!!.mediaScope)
            assertFalse(runtime.camera.value.info!!.canCapture)
            assertEquals(CameraProtocols.FUJI_PHONE,runtime.savedConnection().protocol)
            runtime.browseRecent()
            compose.waitUntil(15000){runtime.currentCameraIds.value.isNotEmpty()&&!runtime.galleryHasMore.value&&!runtime.camera.value.loadingPhotos}
            val photo=runBlocking{database.photos().byIds(runtime.currentCameraIds.value.toList()).single()}
            val before=photo.filesJson
            runBlocking{runtime.ensurePreview(photo,true)}
            assertEquals(0,peer.originalReads.get())
            val cache=runBlocking{runtime.ensureOriginalPreview(photo)}
            assertArrayEquals(peer.jpeg,File(cache).readBytes())
            assertEquals(before,runBlocking{database.photos().find(photo.id)!!.filesJson})
            assertTrue(runBlocking{database.tasks().all().isEmpty()})
            val reads=peer.originalReads.get()
            runBlocking{runtime.enqueueDownload(listOf(photo.id),DownloadFormat.JPEG).join()}
            compose.waitUntil(15000){runBlocking{database.tasks().all().any{it.state==TransferState.COMPLETE.name}}}
            assertEquals(reads,peer.originalReads.get())
            runBlocking{runtime.enqueueDownload(listOf(photo.id),DownloadFormat.RAW).join()}
            compose.waitUntil(15000){runBlocking{database.photos().find(photo.id)!!.files().all{it.savedUri.isNotBlank()}}}
            val imported=runBlocking{database.photos().find(photo.id)!!}
            assertArrayEquals(peer.raw,File(imported.files().first{it.raw}.originalPath).readBytes())
            assertNull(peer.failure)
        }finally{
            runtime.disconnect();peer.close()
            runBlocking{runtime.scope.coroutineContext[Job]!!.cancelAndJoin()}
            runtime.bluetooth.close();database.close();directory.deleteRecursively()
            app.deleteSharedPreferences("qa-fuji-phone-$token-framelink.settings")
        }
    }
}
