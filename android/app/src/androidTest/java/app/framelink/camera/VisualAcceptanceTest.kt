package app.framelink.camera

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用授权样片验收实际界面，排序与标记仅属于独立模拟器的测试索引 */
@RunWith(AndroidJUnit4::class)
class VisualAcceptanceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1)
    val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime
        get() = (compose.activity.application as CameraApp).runtime

    private val variant
        get() = InstrumentationRegistry.getArguments().getString("visualVariant") ?: "phone"

    @Test
    fun photographyScreensKeepActionsSelectionAndSafeInsets() {
        val source = File(compose.activity.getExternalFilesDir(null), "visual-fixtures")
        val images =
            source.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }
        assertEquals("先通过scripts/visual-qa.py准备授权样片", 6, images.size)
        val ids = images.map { "local:" + MediaFiles.digest(it.inputStream()) }
        val uris =
            images.map { sample ->
                val copy =
                    File(compose.activity.filesDir, "media/visual-qa/${sample.name}").apply {
                        parentFile!!.mkdirs()
                        sample.copyTo(this, overwrite = true)
                    }
                FileProvider.getUriForFile(
                    compose.activity,
                    compose.activity.packageName + ".files",
                    copy,
                )
            }
        val previous = runBlocking { runtime.database.photos().all().filter { it.id !in ids && it.inLibrary } }
        val previousLayout = runtime.settings.get("gallery.layout")
        runtime.settings.put("gallery.layout", "medium")
        try {
        runBlocking {
            runtime.database.photos().saveAll(previous.map { it.copy(localHidden = true) })
            runtime.importUris(uris).join()
            ids.forEachIndexed { index, id ->
                val photo = checkNotNull(runtime.database.photos().find(id)) { "样片没有正常导入：$id" }
                runtime.database
                    .photos()
                    .save(
                        photo.copy(
                            capturedAt = System.currentTimeMillis() - index * 1000,
                            mark = PhotoMark.FAVORITE.name,
                        )
                    )
            }
        }
        compose.onNodeWithTag("tab-camera").performClick()
        compose.onNodeWithTag("camera-home").performScrollToNode(hasText("已选 0"))
        compose.onNodeWithText("已选 0").assertIsDisplayed()
        compose.onNodeWithText("收藏 6").assertIsDisplayed()
        compose.onNodeWithTag("camera-home").performScrollToIndex(0)
        for (label in listOf("选片下载", "取景拍摄", "FTP 接收", "FTP 客户端", "快速快门", "本机导入")) compose
            .onNodeWithText(label)
            .assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithTag("tab-gallery").performClick()
        compose.onNodeWithText("已导入").performClick()
        filterGallery(compose,"收藏")
        compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
        compose.onNodeWithTag("photo-${ids[0]}").assertIsDisplayed()
        screenshot("gallery")
        enterGallerySelection(compose)
        compose.onNodeWithContentDescription("选择 ${images[0].name}").performClick()
        compose.onNodeWithContentDescription("选择 ${images[1].name}").performClick()
        compose.onNodeWithText("本次已选 2 张",substring=true).assertIsDisplayed()
        screenshot("selection")
        compose.onNodeWithContentDescription("更多选片操作").performClick()
        compose.onNodeWithText("移除所选照片").performClick()
        compose.waitUntil(10000) { !compose.onNodeWithText("确认移除").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        compose.onNodeWithText("仅从图库移除").assertIsDisplayed()
        compose.onNodeWithText("删除本机文件并移除").assertIsDisplayed()
        screenshot("removal")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("对比").performClick()
        compose.onNodeWithText("双图对比").assertIsDisplayed()
        screenshot("compare")
        compose.onNodeWithContentDescription("关闭对比").performClick()
        compose.onNodeWithContentDescription("选择 ${images[0].name}").performClick()
        compose.onNodeWithContentDescription("选择 ${images[1].name}").performClick()
        finishGallerySelection(compose)
        for (index in listOf(0, 1, 3)) {
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(index)
            compose.onNodeWithTag("photo-${ids[index]}").performClick()
            compose.onNodeWithText("已导入原片",substring=true).assertIsDisplayed()
            compose.onNodeWithContentDescription("分享照片").assertIsDisplayed()
            compose.onNodeWithContentDescription("照片更多操作").performClick()
            compose.onNodeWithText("系统修图（创建副本）").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            val fitted = screenshot("detail-$index-fit")
            if (index != 1) {
                compose.onNodeWithTag("photo-viewer-image").performTouchInput { doubleClick() }
                compose.waitUntil(10000) {
                    compose.onAllNodesWithText("还原").fetchSemanticsNodes().isNotEmpty()
                }
                val zoomed = screenshot("detail-$index-zoom",differentFrom=fitted)
                assertNotEquals("缩放状态变化后，照片区域的实际像素应发生变化", fitted, zoomed)
            }
            compose.onNodeWithContentDescription("关闭照片").performClick()
        }
        compose.onNodeWithTag("gallery-grid").performScrollToIndex(5)
        compose.onNodeWithTag("photo-${ids.last()}").assertIsDisplayed()
        val photoBottom =
            compose.onNodeWithTag("photo-${ids.last()}").fetchSemanticsNode().boundsInRoot.bottom
        val navigationTop =
            compose.onNodeWithTag("tab-camera").fetchSemanticsNode().boundsInRoot.top
        assertTrue("最后一张照片被底部导航遮挡", photoBottom < navigationTop)
        screenshot("gallery-bottom")
        compose.onNodeWithTag("tab-projects").performClick()
        compose.onNodeWithText("本地现场选片").assertIsDisplayed()
        screenshot("projects")
        compose.onNodeWithTag("tab-transfers").performClick()
        compose.onNodeWithText("传输任务").assertIsDisplayed()
        screenshot("transfers")
        compose.onNodeWithContentDescription("设置与空间").performClick()
        compose.onNodeWithText("清理预览缓存").performScrollTo().assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithContentDescription("关闭").performClick()
        } finally {
            runtime.settings.put("gallery.layout", previousLayout)
            runBlocking { runtime.database.photos().saveAll(previous) }
        }
    }

    private fun screenshot(name: String,differentFrom:String?=null): String {
        compose.waitUntil(15000) {
            compose
                .onAllNodes(
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "预览加载中")
                )
                .fetchSemanticsNodes()
                .isEmpty() &&
                compose
                    .onAllNodes(
                        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "照片加载中")
                    )
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        if (name.startsWith("detail-"))
            compose
                .onNodeWithTag("photo-viewer-image")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "照片已显示"))
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        var captured:Bitmap?=null
        var signature=""
        compose.waitUntil(10000){
            captured?.recycle()
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            captured=bitmap
            val pixels=java.security.MessageDigest.getInstance("SHA-256")
            for(y in bitmap.height/4 until bitmap.height*3/4 step 12){
                for(x in bitmap.width/5 until bitmap.width*4/5 step 12){
                    val color=bitmap.getPixel(x,y)
                    pixels.update(byteArrayOf((color shr 16).toByte(),(color shr 8).toByte(),color.toByte()))
                }
            }
            signature=pixels.digest().joinToString(""){"%02x".format(it)}
            differentFrom==null||signature!=differentFrom
        }
        val bitmap=checkNotNull(captured)
        val file =
            File(compose.activity.getExternalFilesDir(null), "qa-shots/visual-$variant-$name.png")
                .apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return signature
    }
}
