package app.framelink.camera

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 必须在明确授权的实体手机单独运行，仅连接、读参数和预览，不操作快门或设置 */
class PhysicalCameraReadAcceptanceTest {
    @Test
    fun readConnectedCameraPropertiesAndPreview() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowPhysicalCameraRead") == "true")
        check(Build.MODEL == "25113PN0EC") { "此验收仅授权当前Xiaomi17" }
        val app = ApplicationProvider.getApplicationContext<CameraApp>()
        val runtime = app.runtime
        val config = runtime.savedConnection()
        check(config.host.isNotBlank()) { "手机没有成功保存过的相机连接" }
        runtime.disconnect()
        try {
            val info = runtime.native.connect(config)
            assertEquals("ILCE-7CM2", info.model)
            val properties = runtime.native.properties()
            assertTrue(properties.isNotEmpty())
            val directory = File(app.getExternalFilesDir(null), "physical-read").apply { mkdirs() }
            val report = JSONObject()
                .put("phone", Build.MODEL).put("android", Build.VERSION.RELEASE)
                .put("camera", JSONObject(Json.encode(info)))
                .put("properties", org.json.JSONArray(Json.encode(properties)))
            File(directory, "properties.json").writeText(report.toString(2))
            if (info.canLiveView) {
                runtime.native.startLive()
                val frame = runtime.native.nextFrame()
                assertTrue(frame.isNotEmpty())
                File(directory, "live-preview.jpg").writeBytes(frame)
                runtime.native.stopLive()
            }
        } finally { runtime.native.close() }
    }
}
