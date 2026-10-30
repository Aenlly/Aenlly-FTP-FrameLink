package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoTimeSourceTest {
    @Test fun pairedFileDateMatchesTheTimestampUsedForBrowsing(){
        val jpeg=CameraFile(1,"IMG_0001.JPG",fileModifiedAt=1000,fileDay="2026-10-23")
        val raw=CameraFile(2,"IMG_0001.CR3",fileModifiedAt=2000,fileDay="2026-10-24")
        for(files in listOf(listOf(jpeg,raw),listOf(raw,jpeg))){
            val item=Photo("pair","pair","IMG_0001",PhotoSource.CAMERA.name,0,Json.encode(files))
            assertEquals(2000L,item.browsingTime)
            assertEquals("2026-10-24",item.files().fileTimeDay())
        }
    }
    private fun photo(captured:Long,modified:Long)=Photo("camera:1","camera:1","IMG_0001.JPG",PhotoSource.CAMERA.name,captured,
        Json.encode(listOf(CameraFile(1,"IMG_0001.JPG",fileModifiedAt=modified,fileDay="2026-10-23"))))

    @Test fun captureTimeKeepsPriorityOverFileChanges(){
        assertEquals(1000L,photo(1000,9000).browsingTime)
    }

    @Test fun fileTimeDoesNotBecomeAnExifCaptureTime(){
        val item=photo(0,9000)
        assertEquals(9000L,item.browsingTime)
        assertEquals(0L,item.capturedAt)
        assertEquals(9000L,item.files().first().fileModifiedAt)
    }

    @Test fun unavailableTimeStaysUnknown(){
        assertEquals(0L,photo(0,0).browsingTime)
    }
}
