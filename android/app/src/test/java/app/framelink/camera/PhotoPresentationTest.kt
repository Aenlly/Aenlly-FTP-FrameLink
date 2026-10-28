package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoPresentationTest {
    @Test fun pairedFormatsKeepIndependentImportState() {
        val photo = Photo("one", "one", "DSC001.JPG", PhotoSource.CAMERA.name, 0,
            Json.encode(listOf(CameraFile(name="DSC001.JPG",originalPath="/app/media/a.jpg"),CameraFile(name="DSC001.ARW"))))
        assertEquals("DSC001",photo.displayTitle())
        assertEquals("JPEG + RAW",photo.formatTitle())
        assertEquals("JPEG 已导入 · RAW 未导入",photo.importTitle())
        assertTrue(photo.hasUnimportedFiles())
    }
    @Test fun secureReconnectRequiresRememberedTrustAndCredentials() {
        assertFalse(ConnectionConfig().canReconnectDirectly())
        assertTrue(ConnectionConfig(host="192.168.1.2").canReconnectDirectly())
        assertFalse(ConnectionConfig(host="192.168.1.2",user="camera",password="secret").canReconnectDirectly())
        assertTrue(ConnectionConfig(host="192.168.1.2",user="camera",password="secret",fingerprint="remembered").canReconnectDirectly())
    }
}
