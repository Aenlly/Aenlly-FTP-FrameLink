package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class FtpPathsTest {
    @Test
    fun pathsKeepChineseAndSpacesAndResolveParents() {
        assertEquals("/照片/客户 A.JPG", FtpPaths.child("/照片/", "客户 A.JPG"))
        assertEquals("/相册", FtpPaths.normalize("//相册/连拍/.././"))
        assertEquals("/", FtpPaths.parent("/相册"))
        assertEquals("/", FtpPaths.normalize("../../"))
    }

    @Test
    fun commandInjectionAndInvalidNamesAreRejected() {
        for (name in listOf("../file.jpg", "a/b", "a\\b", ".", "..", "a\r\nDELE x", "a\u0000b")) {
            assertTrue(name, runCatching { FtpPaths.child("/", name) }.isFailure)
        }
    }
}
