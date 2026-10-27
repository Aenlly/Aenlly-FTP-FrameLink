package app.framelink.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** 仅保存在独立验收设备，供本机查看，不随业务安装包分发 */
internal fun acceptanceScreenshot(context: Context, name: String) {
    InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ?: error("验收截图未返回")
    val file = File(context.getExternalFilesDir(null), "qa-shots/$name.png").apply { parentFile!!.mkdirs() }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
