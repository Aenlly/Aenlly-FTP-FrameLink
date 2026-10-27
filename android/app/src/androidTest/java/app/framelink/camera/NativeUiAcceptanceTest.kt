package app.framelink.camera

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用明确标记的本地模拟相机验证安装包交互，不签署实体机兼容结论 */
@RunWith(AndroidJUnit4::class)
class NativeUiAcceptanceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1)
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun installedAppConnectsBrowsesDownloadsAndShowsLiveCapture() {
        val runtime = (compose.activity.application as CameraApp).runtime
        try {
            compose.onNodeWithText("相机", useUnmergedTree = true).performClick()
            compose.onNodeWithText("连接相机").performClick()
            compose
                .onNode(hasSetTextAction() and hasText("相机IP地址"))
                .performTextReplacement("127.0.0.1")
            compose.onNode(hasSetTextAction() and hasText("相机IP地址")).performImeAction()
            compose.waitForIdle()
            compose.onNodeWithText("连接并读取相机能力").performScrollTo().performClick()
            try {
                compose.waitUntil(30000) { runtime.camera.value.info != null }
            } catch (error: Throwable) {
                throw AssertionError(
                    "相机未就绪：${runtime.camera.value.message}; ${runtime.notice.value}",
                    error,
                )
            }
            assertTrue(runtime.camera.value.info!!.model.contains("SIMULATED"))
            compose.onNodeWithText("浏览相机照片").performClick()
            compose.waitUntil(30000) { runtime.currentCameraIds.value.isNotEmpty() }
            val target = runBlocking {
                runtime.database.photos().observe().first().first {
                    it.id in runtime.currentCameraIds.value
                }
            }
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithContentDescription("选择 ${target.name}")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription("选择 ${target.name}").performClick()
            screenshot("android-gallery")
            compose.onNodeWithText("下载 1 张").performClick()
            compose.onNodeWithText("JPEG / HEIF").performClick()
            compose.waitUntil(30000) {
                runBlocking { runtime.database.photos().find(target.id)?.imported == true }
            }
            compose.onNodeWithText("图库", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("现场客户选片").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("review-grid").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("review-grid").performScrollToIndex(0)
            compose.onNodeWithContentDescription("客户选择 ${target.name}").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("完成选择").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("完成选择").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("导出清单").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("摄影师开放修改").assertIsDisplayed()
            screenshot("android-local-review")
            compose.onNodeWithContentDescription("退出现场选片").performClick()
            compose.onNodeWithText("相机", useUnmergedTree = true).performClick()
            compose.onNodeWithText("取景拍摄").performClick()
            compose.waitUntil(30000) { runtime.frame.value?.isNotEmpty() == true }
            compose.onNodeWithText("专业工具").performClick()
            screenshot("android-live")
            compose.onNodeWithContentDescription("拍摄").performClick()
            compose.waitUntil(30000) { runtime.lastCapture.value != null }
            compose.onNodeWithContentDescription("查看拍后照片").performClick()
            compose.onNodeWithText("已导入原片").assertIsDisplayed()
            screenshot("android-captured")
        } catch (error: Throwable) {
            screenshot("android-ui-failure")
            throw error
        } finally {
            runtime.stopAll()
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: error("截图未返回图像")
        val file =
            File(compose.activity.getExternalFilesDir(null), "qa-shots/$name.png").apply {
                parentFile!!.mkdirs()
            }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
