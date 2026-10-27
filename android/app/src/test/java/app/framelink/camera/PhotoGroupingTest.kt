package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoGroupingTest {
    @Test
    fun emptySavedConfigurationCannotBecomeANullConnection() {
        assertThrows(IllegalArgumentException::class.java) { Json.decode<ConnectionConfig>("") }
    }

    @Test
    fun rawAndJpegRemainOneShotWithExplicitDownloadTargets() {
        val photo =
            Photo(
                "camera:1",
                "camera:1",
                "DSC00001",
                PhotoSource.CAMERA.name,
                1,
                Json.encode(
                    listOf(
                        CameraFile(1, "DSC00001.JPG", size = 4),
                        CameraFile(2, "DSC00001.ARW", size = 40),
                    )
                ),
            )
        assertEquals(1, photo.downloadFiles(DownloadFormat.JPEG).size)
        assertEquals("DSC00001.ARW", photo.downloadFiles(DownloadFormat.RAW).single().name)
        assertEquals(44, photo.downloadFiles(DownloadFormat.ALL).sumOf { it.size })
        assertFalse(photo.imported)
    }

    @Test
    fun heifBelongsToRenderedImageChoiceAndVideoIsNotImportedAsAStill() {
        val photo =
            Photo(
                "id",
                "key",
                "shot",
                PhotoSource.CAMERA.name,
                0,
                Json.encode(listOf(CameraFile(1, "DSC.HIF"), CameraFile(2, "CLIP.MP4"))),
            )
        assertEquals(listOf("DSC.HIF"), photo.downloadFiles(DownloadFormat.ALL).map { it.name })
    }
}
