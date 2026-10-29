package app.framelink.camera

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class CameraUsabilityAcceptanceTest {
    @get:Rule(order=0) val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1) val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime get()=androidx.test.core.app.ApplicationProvider.getApplicationContext<CameraApp>().runtime

    @Test fun formatsAndImportStatesRemainClearAcrossAllLayouts() {
        val token=UUID.randomUUID().toString().take(8)
        val directory=File(compose.activity.filesDir,"media/qa-usability/$token").apply{mkdirs()}
        val jpg=File(directory,"QA_$token.JPG")
        val bitmap=Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff719ac4.toInt())}
        jpg.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
        val name=jpg.name
        val photo=Photo("qa-style:$token","qa-style:$token",name,PhotoSource.IMPORTED.name,Long.MAX_VALUE-10,
            Json.encode(listOf(CameraFile(name=name,size=jpg.length(),originalPath=jpg.path),CameraFile(fileId=2,name="QA_$token.ARW",size=30000000))))
        val oldLayout=runtime.settings.get("gallery.layout")
        try {
            runBlocking{runtime.database.photos().save(photo)}
            compose.onNodeWithTag("tab-gallery").performClick()
            compose.onNodeWithText("已导入").performClick()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            compose.onNodeWithText("JPEG + RAW").assertIsDisplayed()
            compose.onNodeWithContentDescription("显示方式").performClick()
            compose.onNodeWithText("列表",substring=false).performClick()
            compose.onNodeWithText("JPEG 已导入 · RAW 未导入").assertIsDisplayed()
            enterGallerySelection(compose)
            compose.onNodeWithContentDescription("选择 $name").performClick()
            GalleryLayout.entries.forEach { layout ->
                compose.onNodeWithContentDescription("显示方式").performClick()
                compose.onNodeWithText(layout.title,substring=false).performClick()
                compose.onNodeWithContentDescription("选择 $name").assertIsOn()
                assertEquals(layout.key,runtime.settings.get("gallery.layout"))
            }
            finishGallerySelection(compose)
            compose.onNodeWithTag("photo-${photo.id}").performClick()
            compose.onNodeWithContentDescription("文件信息").performClick()
            compose.onNodeWithText("原始文件").assertIsDisplayed()
            compose.onNodeWithText("QA_$token.ARW").assertIsDisplayed()
            compose.onNodeWithText(name,substring=false).assertIsDisplayed()
            compose.onNodeWithText("完成",substring=false).performClick()
            compose.onNodeWithContentDescription("关闭照片").performClick()
            filterGallery(compose,"未导入")
            compose.onNodeWithTag("photo-${photo.id}").assertIsDisplayed()
            filterGallery(compose,"全部")
            enterGallerySelection(compose)
            clearGallerySelection(compose)
            compose.onNodeWithText("全选",substring=false).performClick()
            compose.onNodeWithText("取消全选",substring=false).assertIsDisplayed()
            compose.onNodeWithText("取消全选",substring=false).performClick()
            compose.onNodeWithContentDescription("选择 $name").assertIsOff()
        } finally {
            runtime.settings.put("gallery.layout",oldLayout)
            runBlocking{runtime.database.photos().save(photo.copy(localHidden=true,filesJson="[]"))}
            directory.deleteRecursively()
        }
    }

    @Test fun rememberedCameraReconnectsWithoutOpeningSettings() {
        val old=runtime.settings.get("camera.config")
        try {
            runtime.connect(ConnectionConfig(host="127.0.0.1"))
            compose.waitUntil(30000){runtime.camera.value.info != null}
            runtime.disconnect()
            compose.onNodeWithTag("tab-gallery").performClick()
            compose.onNodeWithText("相机内",substring=false).performClick()
            compose.onNodeWithText("重新连接相机",substring=false).performClick()
            compose.waitUntil(30000){runtime.camera.value.info != null}
            compose.waitUntil(30000){runtime.currentCameraIds.value.isNotEmpty()}
            compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
            compose.onNodeWithTag("camera-connect-action").assertDoesNotExist()
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithContentDescription("相机连接设置").performClick()
            compose.onNodeWithTag("camera-connect-action").assertIsDisplayed()
        } finally {runtime.disconnect();runtime.settings.put("camera.config",old)}
    }

    @Test fun failedReconnectOpensPrefilledSettings() {
        val old=runtime.settings.get("camera.config")
        try {
            runtime.disconnect()
            runtime.settings.put("camera.config",Json.encode(ConnectionConfig(host="127.0.0.1",port=15741)))
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithText("连接相机",substring=false).performClick()
            compose.waitUntil(15000){compose.onAllNodesWithTag("camera-connect-action").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("127.0.0.1",substring=false).assertIsDisplayed()
            compose.onNodeWithText("未能直接重连",substring=true).assertIsDisplayed()
        } finally {runtime.disconnect();runtime.settings.put("camera.config",old)}
    }
}
