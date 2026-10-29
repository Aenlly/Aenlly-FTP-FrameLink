package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class SelectionResourceTest {
    @Test fun optimizedKeysKeepExistingCacheIdentifiers(){
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",MediaFiles.key("abc"))
    }
    private val jpeg=CameraFile(1,"DSC001.JPG",size=100,cameraDay="2026-10-29")
    private fun photo(files:List<CameraFile> = listOf(jpeg))=Photo("camera:1","camera:1","DSC001.JPG",PhotoSource.CAMERA.name,1234,Json.encode(files),contentId=1)

    @Test fun downloadAndFavoriteDoNotInvalidateTheSelection(){
        val original=photo()
        val imported=original.copy(mark=PhotoMark.FAVORITE.name,previewPath="/cache/new.jpg",filesJson=Json.encode(listOf(jpeg.copy(localUri="content://photos/1",originalPath="/original/1.jpg"))))
        assertEquals(original.selectionResourceKey(),imported.selectionResourceKey())
    }
    @Test fun reusedIdsWithChangedFileOrCaptureTimeMustBeCheckedAgain(){
        val original=photo()
        assertNotEquals(original.selectionResourceKey(),photo(listOf(jpeg.copy(size=101))).selectionResourceKey())
        assertNotEquals(original.selectionResourceKey(),photo(listOf(jpeg.copy(name="DSC002.JPG"))).selectionResourceKey())
        assertNotEquals(original.selectionResourceKey(),original.copy(capturedAt=1235).selectionResourceKey())
    }
    @Test fun anotherSourceOrCardCannotReuseASelection(){
        val original=photo()
        assertNotEquals(original.selectionResourceKey(),original.copy(sourceKey="another-camera:1").selectionResourceKey())
        assertNotEquals(original.selectionResourceKey(),original.copy(slot=2).selectionResourceKey())
    }
    @Test fun fileOrderingDoesNotChangeAnExistingShot(){
        val raw=CameraFile(2,"DSC001.ARW",size=300)
        assertEquals(photo(listOf(jpeg,raw)).selectionResourceKey(),photo(listOf(raw,jpeg)).selectionResourceKey())
    }
}
