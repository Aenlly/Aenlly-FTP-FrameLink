package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class CameraIdentityTest {
    @Test
    fun trustedKeyMustMatchAndLegacyEncodingIsAccepted() {
        assertFalse(sameCameraFingerprint("", "SHA256:current"))
        assertFalse(sameCameraFingerprint("SHA256:old", "SHA256:new"))
        assertTrue(sameCameraFingerprint("current=", "SHA256:current"))
        assertTrue(sameCameraFingerprint("SHA256:current", "SHA256:current"))
    }

    @Test
    fun connectionMessagesExplainTheRecoveryStep() {
        assertTrue(cameraConnectionMessage(Exception("dial tcp 192.168.1.2:15740: connect: connection refused")).contains("电脑遥控"))
        assertTrue(cameraConnectionMessage(Exception("camera rejected the Access Authentication user/password")).contains("用户名和密码"))
        assertTrue(cameraConnectionMessage(Exception("SSH fingerprint does not match expected")).contains("核对"))
    }
}
