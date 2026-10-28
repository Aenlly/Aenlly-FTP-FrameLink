package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class CameraMediaIdentityTest {
    @Test
    fun reusedResourceIdCannotReuseAnotherShotsOriginal() {
        val first = CameraFile(1, "DSC1.JPG", size = 100)
        assertTrue(canReuseCameraFile(first, first.copy(originalPath = "/original/old.jpg")))
        assertFalse(canReuseCameraFile(first, first.copy(name = "DSC2.JPG")))
        assertFalse(canReuseCameraFile(first, first.copy(size = 101)))
    }
}
