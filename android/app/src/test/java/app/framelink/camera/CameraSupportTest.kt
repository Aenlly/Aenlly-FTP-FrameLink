package app.framelink.camera

import app.framelink.camera.support.CameraSupportRegistry
import app.framelink.camera.support.CameraSupportRegistry.Capability
import org.junit.Assert.*
import org.junit.Test

class CameraSupportTest{
    @Test fun realConnectionBoundaryNormalizesOnlyMissingProtocolsAndKnownDefaultPorts(){
        assertEquals(CameraProtocols.SONY,registeredConnection(ConnectionConfig(host="192.168.1.2",protocol="")).protocol)
        assertEquals(8080,registeredConnection(ConnectionConfig(host="192.168.1.2",port=0,protocol=CameraProtocols.CANON)).port)
        assertThrows(IllegalArgumentException::class.java){registeredConnection(ConnectionConfig(host="192.168.1.2",protocol="not-a-driver"))}
    }
    @Test fun realCameraInfoMasksReadOnlyUsbCapabilitiesWithoutChangingIdentity(){
        val reported=CameraInfo(id="camera:one",model="X-T30 II",canBrowse=true,canDownload=true,canPreview=true,canCapture=true,canLiveView=true,canFocus=true)
        val allowed=reported.withSupport(CameraProtocols.USB)
        assertEquals(reported.id,allowed.id)
        assertTrue(allowed.canBrowse&&allowed.canDownload&&allowed.canPreview)
        assertFalse(allowed.canCapture||allowed.canLiveView||allowed.canFocus||allowed.canAutoImport)
        assertEquals(CameraSupportRegistry.Availability.READ_ONLY,reported.supportFor(CameraProtocols.USB).getValue(Capability.FOCUS).availability)
    }
    @Test fun unknownSonyModelKeepsNegotiatedMediaAndDoesNotInventShutter(){
        val reported=CameraInfo(id="camera:two",model="ILCE-FUTURE",canBrowse=true,canDownload=true)
        assertTrue(reported.withSupport(CameraProtocols.SONY).canBrowse)
        assertFalse(reported.withSupport(CameraProtocols.SONY).canCapture)
    }
    @Test fun pairedFujiMayResolveItsHostLaterAndDoesNotBorrowSonyCredentials(){
        val config=registeredConnection(ConnectionConfig(port=0,protocol=CameraProtocols.FUJI_PHONE,bluetoothAddress="aa:bb:cc:dd:ee:ff",user="sony-user",password="sony-password",fingerprint="sony-trust"))
        assertEquals("AA:BB:CC:DD:EE:FF",config.bluetoothAddress)
        assertEquals(55740,config.port)
        assertTrue(config.user.isBlank()&&config.password.isBlank()&&config.fingerprint.isBlank())
        assertThrows(IllegalArgumentException::class.java){registeredConnection(config.copy(bluetoothAddress="not-a-radio"))}
    }
}
