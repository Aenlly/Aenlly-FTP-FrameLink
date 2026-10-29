package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class GalleryWindowLayoutTest {
    @Test fun narrowSplitWindowsReturnToPhoneLayout(){
        assertEquals(GalleryWindowLayout(false,false),galleryWindowLayout(480))
        assertEquals(GalleryWindowLayout(true,false),galleryWindowLayout(800))
        assertEquals(GalleryWindowLayout(true,true),galleryWindowLayout(1280))
    }
    @Test fun thumbnailColumnsFollowTheAvailablePane(){
        assertEquals(5,galleryColumnCount(400,GalleryLayout.SMALL))
        assertEquals(3,galleryColumnCount(400,GalleryLayout.MEDIUM))
        assertEquals(2,galleryColumnCount(400,GalleryLayout.LARGE))
        assertEquals(9,galleryColumnCount(800,GalleryLayout.SMALL))
        assertEquals(6,galleryColumnCount(800,GalleryLayout.MEDIUM))
        assertEquals(4,galleryColumnCount(800,GalleryLayout.LARGE))
        assertEquals(1,galleryColumnCount(1400,GalleryLayout.LIST))
        assertEquals(4,galleryColumnCount(598,GalleryLayout.MEDIUM,adaptive=true))
    }
}
