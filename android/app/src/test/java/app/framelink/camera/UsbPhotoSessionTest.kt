package app.framelink.camera

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 使用SDK访问层假设备验证媒体逻辑，不能代替X-T30 II的真实USB握手 */
class UsbPhotoSessionTest {
    @get:Rule val temporary=TemporaryFolder()
    private class Access(partial:Boolean=true):UsbPhotoAccess{
        override val descriptor=UsbPhotoDescriptor("FUJIFILM","X-T30 II","SYNTHETIC-USB-ONLY","fixture",
            setOf(UsbPhotoOperations.STORAGES,UsbPhotoOperations.HANDLES,UsbPhotoOperations.INFO,UsbPhotoOperations.OBJECT,UsbPhotoOperations.THUMBNAIL)+if(partial)setOf(UsbPhotoOperations.PARTIAL)else emptySet())
        var online=true
        val data=mapOf(1 to byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),11,12,13,14),
            2 to ByteArray((5 shl 20)+17){(it%251).toByte()},3 to byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),21,22,23))
        val objects=mutableMapOf(
            1 to UsbPhotoObject(1,1,10,"DSC0001.JPG",data.getValue(1).size.toLong(),1000,2000),
            2 to UsbPhotoObject(2,1,10,"DSC0001.RAF",data.getValue(2).size.toLong(),1000,2000),
            3 to UsbPhotoObject(3,1,10,"DSC0002.JPG",data.getValue(3).size.toLong(),3000,4000))
        var partialReads=0
        var fullImports=0
        var onRead:(()->Unit)?=null
        override fun connected()=online
        override fun storages()=intArrayOf(1)
        override fun handles(storage:Int)=objects.keys.toIntArray()
        override fun info(handle:Int)=objects[handle]
        override fun thumbnail(handle:Int)=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),1,2,3)
        override fun partial(handle:Int,offset:Long,size:Int,buffer:ByteArray,extended:Boolean):Long{
            partialReads++
            val bytes=data.getValue(handle)
            val count=minOf(size,bytes.size-offset.toInt())
            bytes.copyInto(buffer,0,offset.toInt(),offset.toInt()+count)
            onRead?.invoke()
            return count.toLong()
        }
        override fun importOriginal(handle:Int,target:File):Boolean{fullImports++;target.writeBytes(data.getValue(handle));onRead?.invoke();return true}
        override fun close(){online=false}
    }
    private fun photo(row:RemotePhoto)=Photo(row.id,row.id,row.files.first().name,PhotoSource.CAMERA.name,row.capturedAt,Json.encode(row.files),row.contentId,row.slot)

    @Test fun pairsRawJpegAndKeepsFileTimeSeparateFromCaptureTime(){
        val source=Access()
        val session=UsbPhotoSession(source)
        try{
            session.dates(1)
            val page=session.page(1,"",0)
            assertEquals(2,page.items.size)
            assertEquals(0L,page.nextCursor)
            assertEquals("DSC0002.JPG",page.items.first().files.first().name)
            val pair=page.items.first{it.files.size==2}
            assertEquals(0L,pair.capturedAt)
            assertTrue(pair.files.any{it.raw})
            assertTrue(pair.files.all{it.cameraDay.isBlank()&&it.fileCreatedAt==1000L&&it.fileModifiedAt==2000L})
            assertEquals(2000L,photo(pair).browsingTime)
            assertFalse(session.info.canCapture||session.info.canFocus||session.info.canLiveView||session.info.canAutoImport||session.info.hardwareVerified)
        }finally{session.close()}
    }

    @Test fun chunkedOriginalRetainsEveryByteAndReportsDurableProgress(){
        val source=Access()
        val session=UsbPhotoSession(source)
        try{
            val row=session.page(1,"",0).items.first{it.files.size==2}
            val file=row.files.first{it.raw}
            val target=File(temporary.root,file.name)
            val progress=mutableListOf<Long>()
            val receipt=Json.decode<Map<String,Any>>(session.download(photo(row),file,target){done,total->
                assertEquals(file.size,total)
                progress+=done
            })
            assertArrayEquals(source.data.getValue(file.fileId),target.readBytes())
            assertEquals(MediaFiles.digest(target.inputStream()),receipt["sha256"])
            assertEquals(6,source.partialReads)
            assertEquals(0,source.fullImports)
            assertTrue(progress.first()>=(4L shl 20))
            assertEquals(file.size,progress.last())
        }finally{session.close()}
    }

    @Test fun descriptorImportFallbackPublishesOnlyACompleteOriginal(){
        val source=Access(partial=false)
        val session=UsbPhotoSession(source)
        try{
            val row=session.page(1,"",0).items.first{it.files.size==2}
            val file=row.files.first{it.raw}
            val target=File(temporary.root,file.name)
            session.download(photo(row),file,target){_,_->}
            assertEquals(1,source.fullImports)
            assertEquals(0,source.partialReads)
            assertArrayEquals(source.data.getValue(file.fileId),target.readBytes())
        }finally{session.close()}
    }

    @Test fun changedObjectCannotReuseThePreviousOriginal(){
        val source=Access()
        val session=UsbPhotoSession(source)
        try{
            val row=session.page(1,"",0).items.first{it.files.size==2}
            val file=row.files.first{it.raw}
            source.objects[file.fileId]=source.objects.getValue(file.fileId).copy(modifiedAt=9000)
            val target=File(temporary.root,file.name)
            target.writeText("existing original")
            assertThrows(IllegalStateException::class.java){session.download(photo(row),file,target){_,_->}}
            assertEquals(0,source.partialReads)
            assertEquals("existing original",target.readText())
        }finally{session.close()}
    }

    @Test fun cancellationDiscardsPartialAndKeepsTheExistingFile(){
        for(partial in listOf(false,true)){
            val source=Access(partial)
            val session=UsbPhotoSession(source)
            try{
                val row=session.page(1,"",0).items.first{it.files.size==2}
                val file=row.files.first{it.raw}
                val target=File(temporary.root,if(partial)"partial.RAF"else"descriptor.RAF")
                target.writeText("existing original")
                source.onRead={session.cancelTransfer()}
                assertThrows(IllegalStateException::class.java){session.download(photo(row),file,target){_,_->}}
                assertEquals("existing original",target.readText())
                assertFalse(File(target.path+".part").exists())
                assertTrue(session.connected())
            }finally{session.close()}
        }
    }

    @Test fun capabilityMissingObjectReadIsNotAdvertisedAsAnImportChannel(){
        val source=Access()
        val unsupported=object:UsbPhotoAccess by source{
            override val descriptor=source.descriptor.copy(operations=source.descriptor.operations-UsbPhotoOperations.OBJECT)
        }
        assertThrows(IllegalArgumentException::class.java){UsbPhotoSession(unsupported)}
    }

    @Test fun duplicatedHandleDoesNotProduceAFalseCompletePage(){
        val source=Access()
        val broken=object:UsbPhotoAccess by source{override fun handles(storage:Int)=intArrayOf(1,1)}
        val session=UsbPhotoSession(broken)
        try{
            assertThrows(IllegalStateException::class.java){session.page(1,"",0)}
            assertEquals(0,source.partialReads)
        }finally{session.close()}
    }
}
