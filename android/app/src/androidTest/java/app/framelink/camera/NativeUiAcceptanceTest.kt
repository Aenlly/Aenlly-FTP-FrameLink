package app.framelink.camera

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
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
        var dayAdded = false
        try {
            compose.onNodeWithText("相机", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("相机连接设置").performClick()
            val authentication = compose.onNodeWithContentDescription("使用相机访问身份验证")
            if (authentication.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.On)
                authentication.performScrollTo().performClick()
            compose
                .onNode(hasSetTextAction() and hasText("相机IP地址"))
                .performTextReplacement("127.0.0.1")
            compose.onNode(hasSetTextAction() and hasText("相机IP地址")).performImeAction()
            compose.waitForIdle()
            compose.onNodeWithTag("camera-connect-action").performClick()
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
            // 独立模拟相机夹具重置导入标记，确保这轮确实经过传输而不是命中旧结果
            runBlocking { runtime.database.photos().save(target.copy(filesJson=Json.encode(target.files().map{it.copy(localUri="",originalPath="")}))) }
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            enterGallerySelection(compose)
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithContentDescription("选择 ${target.name}")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription("选择 ${target.name}").performClick()
            screenshot("android-gallery")
            compose.onNodeWithText("导入 1 张").performClick()
            compose.onNodeWithText("JPEG / HEIF").performClick()
            compose.waitUntil(30000) {
                runBlocking { runtime.database.photos().find(target.id)?.imported == true }
            }
            compose.onNodeWithText("图库", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("图库更多操作").performClick()
            compose.onNodeWithContentDescription("现场客户选片").performClick()
            compose.onNodeWithText("开始选片").performClick()
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
            dismissFullscreenEducation()
            screenshot("android-live")
            compose.onNodeWithContentDescription("更多控制").performClick()
            screenshot("android-live-tools")
            compose.onNodeWithContentDescription("收起工具").performClick()
            compose.onNodeWithContentDescription("专业模式").performClick()
            compose.onNodeWithTag("live-preview").performTouchInput { click() }
            compose.waitUntil(10000) { !runtime.focusBusy.value }
            val latestFocus = runtime.focusAt(.25f, .5f).let { runtime.focusAt(.75f, .5f) }
            runBlocking { latestFocus.join() }
            val history = URI("http://127.0.0.1:16023/qa-focus-history").toURL().readText()
            val controls = org.json.JSONArray(history)
            val focusValues = (0 until controls.length()).map { controls.getJSONObject(it) }.filter { it.getInt("Code") == 0xD2E4 }
            assertEquals("快速点按必须执行最后的位置", (479L shl 16) or 240L, focusValues.last().getLong("Value"))
            compose.onNodeWithTag("live-quick-properties").assertIsDisplayed()
            compose.onNodeWithContentDescription("无延时").performClick()
            compose.onNodeWithContentDescription("拍摄").performClick()
            compose.waitUntil(5000) { runtime.captureCountdown.value > 0 }
            compose.onNodeWithContentDescription("查看相机照片").performClick()
            compose.waitUntil(10000) { runtime.captureCountdown.value == 0 && !runtime.captureBusy.value }
            compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithText("取景拍摄").performClick()
            compose.waitUntil(20000) { runtime.frame.value?.isNotEmpty() == true }
            val previousCapture = runtime.lastCapture.value
            runtime.settings.put("capture.auto_import", "false")
            compose.onNodeWithContentDescription("拍摄").performClick()
            compose.waitUntil(30000) { !runtime.captureBusy.value }
            assertEquals(previousCapture, runtime.lastCapture.value)
            assertFalse(runtime.postCaptureBusy.value)
            assertNotNull(runtime.camera.value.info)
            val newDay = qaNewDay("POST")
            dayAdded = true
            assertFalse("测试必须在连接后新增拍摄日期", newDay in runtime.captureDates.value)
            compose.onNodeWithContentDescription("查看相机照片").performClick()
            compose.waitUntil(30000) { runtime.galleryDay.value == newDay && runtime.currentCameraIds.value.isNotEmpty() && !runtime.camera.value.loadingPhotos }
            assertTrue(newDay in runtime.captureDates.value)
            assertNotNull(runtime.camera.value.info)
            compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
            screenshot("android-captured-in-camera")
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithText("取景拍摄").performClick()
            compose.waitUntil(20000) { runtime.frame.value?.isNotEmpty() == true }
            compose.onNodeWithContentDescription("更多控制").performClick()
            compose.onNodeWithContentDescription("拍摄设置").performClick()
            compose.onNodeWithContentDescription("拍后自动导入").assertIsOff().performClick()
            compose.onNodeWithText("完成", substring = false).performClick()
            val shotStart = System.currentTimeMillis()
            compose.onNodeWithContentDescription("拍摄").performClick()
            compose.waitUntil(30000) { runtime.lastCapture.value?.capturedAt?.let { it >= shotStart } == true && !runtime.captureBusy.value && !runtime.postCaptureBusy.value }
            assertTrue(runtime.lastCapture.value!!.imported)
            compose.onNodeWithContentDescription("查看相机照片").performClick()
            compose.waitUntil(30000) { runtime.currentCameraIds.value.isNotEmpty() }
            assertNotNull(runtime.camera.value.info)
            compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
        } catch (error: Throwable) {
            screenshot("android-ui-failure")
            throw error
        } finally {
            runtime.settings.put("capture.auto_import", "false")
            runtime.stopAll()
            if (dayAdded) qaNewDay("DELETE")
        }
    }

    private fun qaNewDay(method: String): String {
        val request = URI("http://127.0.0.1:16023/qa-new-day").toURL().openConnection() as HttpURLConnection
        try {
            request.requestMethod = method; request.connectTimeout = 3000; request.readTimeout = 3000
            assertEquals(if (method == "POST") 200 else 204, request.responseCode)
            return request.inputStream.bufferedReader().use { it.readText() }
        } finally { request.disconnect() }
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
