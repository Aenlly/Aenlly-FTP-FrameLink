package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoPresentationTest {
    @Test fun cameraCountUsesSnapshotTotalBeforeAllPagesAreLoaded(){
        assertEquals(-1,Json.decode<CameraPage>("{\"items\":[],\"nextCursor\":100}").totalCount)
        assertEquals("共 250 张",galleryCountText(true,false,100,250,true))
        assertEquals("共 250 张",galleryCountText(true,false,200,250,true))
        assertEquals("已匹配 25 张 · 共 250 张",galleryCountText(true,true,25,250,true))
        assertEquals("25 张",galleryCountText(true,true,25,250,false))
        assertEquals("正在统计照片",galleryCountText(true,false,100,null,true))
        assertEquals("10 张",galleryCountText(false,false,10,250,false))
    }
    @Test fun reconnectRequirementsFollowTheActualTransport(){
        assertTrue(ConnectionConfig(host="192.168.1.3",port=8080,user="camera",password="secret",protocol=CameraProtocols.CANON).canReconnectDirectly())
        assertFalse(ConnectionConfig(host="192.168.1.3",port=8080,user="camera",protocol=CameraProtocols.CANON).canReconnectDirectly())
        assertTrue(ConnectionConfig(host="/dev/bus/usb/001/002",port=0,protocol=CameraProtocols.USB,usbIdentity="verified-device").canReconnectDirectly())
        assertFalse(ConnectionConfig(host="/dev/bus/usb/001/002",port=0,protocol=CameraProtocols.USB).canReconnectDirectly())
    }
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
