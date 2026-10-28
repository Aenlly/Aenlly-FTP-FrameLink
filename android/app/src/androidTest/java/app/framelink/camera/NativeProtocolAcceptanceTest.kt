package app.framelink.camera

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 仅验证本地模拟协议，不作为Sony实体机兼容证明 */
@RunWith(AndroidJUnit4::class)
class NativeProtocolAcceptanceTest {
    @Test
    fun androidJniBrowsePreviewDownloadLiveAndCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val camera = NativeCamera(context)
        val directory = File(context.cacheDir, "qa-native-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val info = camera.connect(ConnectionConfig(host = "127.0.0.1"))
            assertTrue(info.model.contains("SIMULATED"))
            assertFalse(info.hardwareVerified)
            assertTrue(info.canBrowse)
            assertTrue(info.canCapture)
            val page = camera.page(1, 0)
            assertTrue(page.items.isNotEmpty())
            val item = page.items.first { p -> p.files.any { it.name == "QA_DSC00001.JPG" } }
            val photo =
                Photo(
                    item.id,
                    item.id,
                    item.files.first().name,
                    PhotoSource.CAMERA.name,
                    item.capturedAt,
                    Json.encode(item.files),
                    item.contentId,
                    item.slot,
                )
            val file = item.files.first()
            val preview = File(directory, "preview.jpg")
            camera.preview(photo, file, preview, false)
            assertNotNull(BitmapFactory.decodeFile(preview.absolutePath))
            val output = File(directory, "original.jpg")
            val receipt = camera.download(photo, file, output) { _, _ -> }
            assertEquals(file.size, output.length())
            assertEquals(
                MediaFiles.digest(output.inputStream()),
                org.json.JSONObject(receipt).getString("sha256"),
            )
            val property = camera.properties().first { it.name == "ISO" }
            camera.setProperty(property.code, 200)
            assertEquals(200L, camera.properties().first { it.code == property.code }.value)
            camera.startLive()
            val frame = camera.nextFrame()
            assertNotNull(BitmapFactory.decodeByteArray(frame, 0, frame.size))
            camera.stopLive()
            camera.capture(true)
            val captured = camera.fetchCaptured(File(directory, "captured.jpg"))
            assertTrue(captured.getBoolean("ready"))
            assertTrue(captured.getBoolean("original"))
            assertNotNull(BitmapFactory.decodeFile(captured.getString("path")))
        } finally {
            camera.close()
            directory.deleteRecursively()
        }
    }
}
