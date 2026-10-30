package app.framelink.camera

import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginalPreviewCacheTest{
    @get:Rule val temp=TemporaryFolder()
    private fun photo(id:String,size:Long)=Photo(id,id,"$id.JPG",PhotoSource.CAMERA.name,1,
        Json.encode(listOf(CameraFile(fileId=1,name="$id.JPG",size=size))))

    @Test fun cachedJpegIsSeparateAndRequiresCompleteBytes(){
        val cache=OriginalPreviewCache(temp.root)
        val photo=photo("one",1000)
        val target=cache.prepare(photo)
        RandomAccessFile(target,"rw").use{it.setLength(999)}
        assertNull(cache.cached(photo))
        RandomAccessFile(target,"rw").use{it.setLength(1000)}
        cache.complete(target,MediaFiles.digest(target.inputStream()))
        assertEquals(target,cache.cached(photo))
        assertTrue(cache.verifiedForImport(photo,photo.files().first()))
        assertFalse(photo.imported)
        assertTrue(photo.hasUnimportedFiles())
        assertTrue(target.canonicalPath.startsWith(temp.root.canonicalPath+"/original-previews/"))
        assertNull(cache.cached(photo.copy(id="another",sourceKey="another")))
    }

    @Test fun cacheQuotaEvictsOldestAndPreservesUnrelatedFiles(){
        val cache=OriginalPreviewCache(temp.root)
        val size=800L*1024*1024
        val old=cache.prepare(photo("old",size))
        RandomAccessFile(old,"rw").use{it.setLength(size)};old.setLastModified(1000)
        val recent=cache.prepare(photo("recent",size))
        RandomAccessFile(recent,"rw").use{it.setLength(size)};recent.setLastModified(2000)
        val unrelated=temp.newFile("formal.jpg").apply{writeText("original")}
        cache.prepare(photo("next",size))
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertEquals("original",unrelated.readText())
    }

    @Test fun explicitImportReusesOnlyMatchingAndVerifiedJpeg(){
        val cache=OriginalPreviewCache(temp.root)
        val photo=photo("one",1000)
        val target=cache.prepare(photo)
        target.writeBytes(ByteArray(1000){(it%127).toByte()})
        cache.complete(target,MediaFiles.digest(target.inputStream()))
        val output=java.io.File(temp.root,"saved/one.jpg")
        assertTrue(cache.copyForImport(photo,photo.files().first(),output){_,_->})
        assertArrayEquals(target.readBytes(),output.readBytes())
        assertFalse(photo.imported)
        assertFalse(cache.copyForImport(photo,photo.files().first().copy(name="one.ARW"),java.io.File(temp.root,"wrong.arw")){_,_->})
        RandomAccessFile(target,"rw").use{it.writeByte(99)}
        assertFalse(cache.copyForImport(photo,photo.files().first(),java.io.File(temp.root,"corrupted.jpg")){_,_->})
        assertFalse(target.exists())
        assertTrue(output.isFile)
    }
}
