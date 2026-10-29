package app.framelink.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal fun enterGallerySelection(compose:ComposeContentTestRule){
    if(compose.onAllNodesWithContentDescription("选择照片").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("选择照片").performClick()
}
internal fun finishGallerySelection(compose:ComposeContentTestRule){
    if(compose.onAllNodesWithContentDescription("完成选择").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("完成选择").performClick()
}
internal fun clearGallerySelection(compose:ComposeContentTestRule){
    compose.onNodeWithContentDescription("清空本次选择").performClick()
    compose.onNodeWithText("清空选择",substring=false).performClick()
}
internal fun filterGallery(compose:ComposeContentTestRule,label:String){
    compose.onNodeWithContentDescription("筛选照片").performClick()
    compose.onNodeWithText(label,substring=false).performClick()
}

/** 仅保存在独立验收设备，供本机查看，不随业务安装包分发 */
internal fun acceptanceScreenshot(context: Context, name: String) {
    InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ?: error("验收截图未返回")
    val file = File(context.getExternalFilesDir(null), "qa-shots/$name.png").apply { parentFile!!.mkdirs() }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
