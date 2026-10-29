package app.framelink.camera

import android.Manifest
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class LiveViewUsabilityAcceptanceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<CameraApp>().runtime

    @Test
    fun rotationRecreatesAFullWidthPreviewWindowAndControlsStayAboveGestures() {
        runtime.disconnect()
        try {
            runtime.connect(ConnectionConfig(host = "127.0.0.1"))
            compose.waitUntil(30000) { runtime.camera.value.info != null }
            compose.onNodeWithText("取景拍摄").performClick()
            compose.waitUntil(20000) { runtime.frame.value?.isNotEmpty() == true }
            dismissFullscreenEducation()
            compose.waitUntil(20000) { compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "取景画面已显示")).fetchSemanticsNodes().isNotEmpty() }
            val scene = compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().id
            compose.onNodeWithText("专业工具").performClick()
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            assertEquals("旋转不应重建取景窗口", scene, compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().id)
            compose.onNodeWithTag("live-tools-panel").assertIsDisplayed()
            compose.onNodeWithContentDescription("收起工具").performClick()
            compose.onNodeWithContentDescription("沉浸取景").performClick()
            val bounds = compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().boundsInWindow
            val metrics = compose.activity.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
            assertTrue("横屏窗口宽度=$bounds，屏幕=$metrics", bounds.width >= metrics.width() * .95f)
            assertTrue("横屏窗口起点应靠左", bounds.left < 8)
            compose.onNodeWithContentDescription("显示取景控制").assertIsDisplayed()
            val pictureBounds = compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow
            acceptanceScreenshot(compose.activity, "live-landscape-fullscreen")
            compose.onNodeWithContentDescription("显示取景控制").performClick()
            compose.onNodeWithContentDescription("拍摄").assertIsDisplayed()
            assertEquals("显示菜单不应缩小画面", pictureBounds, compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow)
            val rail = compose.onNodeWithTag("live-shutter-rail").fetchSemanticsNode().boundsInWindow
            assertTrue("快门使用右侧留边，避免横跨构图", rail.width <= bounds.width * .20f && rail.left > bounds.width * .75f)
            val quickBounds = compose.onNodeWithTag("live-quick-properties").fetchSemanticsNode().boundsInWindow
            val quickValues = compose.onAllNodesWithTag("live-quick-value", useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(4, quickValues.size)
            assertTrue("常用参数值不能被底部裁掉", quickValues.all { it.boundsInWindow.top >= quickBounds.top && it.boundsInWindow.bottom <= quickBounds.bottom })
            val shutter = compose.onNodeWithContentDescription("拍摄").fetchSemanticsNode().boundsInWindow
            compose.waitForIdle()
            acceptanceScreenshot(compose.activity, "live-landscape-before-shutter-check")
            compose.waitUntil(8000) {
                val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (image == null) false else try {
                    val pixel = image.getPixel(shutter.center.x.toInt(), shutter.center.y.toInt())
                    android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 && android.graphics.Color.blue(pixel) > 220
                } finally { image.recycle() }
            }
            compose.waitForIdle()
            acceptanceScreenshot(compose.activity, "live-landscape-glass-controls")
            compose.onNodeWithText("光圈").performClick()
            val picker = compose.onNodeWithTag("live-property-picker").fetchSemanticsNode().boundsInWindow
            assertTrue("横屏参数带保持紧凑", picker.width <= bounds.width * .65f && picker.height <= bounds.height * .22f)
            assertEquals("展开参数带不能缩小画面", pictureBounds, compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow)
            acceptanceScreenshot(compose.activity, "live-landscape-parameter-strip")
            val aperture = runtime.properties.value.first { it.code == 0x5007 }
            val nextAperture = CameraPropertyText.choices(aperture).first { it.value != aperture.value }
            compose.onNodeWithTag("live-property-choices").performScrollToNode(hasText(nextAperture.label))
            compose.onNodeWithText(nextAperture.label).performClick()
            compose.waitUntil(10000) { runtime.properties.value.first { it.code == aperture.code }.value == nextAperture.value }
            val originalAperture = CameraPropertyText.choices(aperture).first { it.value == aperture.value }
            compose.onNodeWithTag("live-property-choices").performScrollToNode(hasText(originalAperture.label))
            compose.onNode(hasText(originalAperture.label) and hasAnyAncestor(hasTestTag("live-property-choices"))).performClick()
            compose.waitUntil(10000) { runtime.properties.value.first { it.code == aperture.code }.value == aperture.value }
            compose.onNodeWithContentDescription("关闭参数").performClick()
            compose.onNodeWithText("专业工具").performClick()
            val panel = compose.onNodeWithTag("live-tools-panel").fetchSemanticsNode().boundsInWindow
            assertTrue("横屏参数应留在左侧窄区：$panel / $bounds", panel.width <= bounds.width * .24f && panel.center.x < bounds.width * .25f)
            compose.onNodeWithContentDescription("拍摄").assertIsDisplayed()
            assertEquals("专业面板不应缩小横屏取景", pictureBounds, compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow)
            acceptanceScreenshot(compose.activity, "live-landscape-compact-tools")
            compose.onNodeWithText("预览辅助").performClick()
            compose.onNodeWithTag("live-properties").performScrollToNode(hasContentDescription("直方图"))
            compose.onNodeWithContentDescription("直方图", substring = false).performClick()
            val histogram = compose.onNodeWithTag("live-histogram").fetchSemanticsNode().boundsInWindow
            assertTrue("直方图只作角落参考", histogram.width <= bounds.width * .18f && histogram.height <= bounds.height * .25f)
            compose.onNodeWithContentDescription("收起工具").performClick()
            acceptanceScreenshot(compose.activity, "live-landscape-small-histogram")
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithContentDescription("返回相机").performClick()
        } finally {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            runtime.disconnect()
        }
    }

    @Test
    fun chineseControlsExposeRealEnumsAndImmersivePreviewKeepsPicture() {
        runtime.disconnect()
        try {
            runtime.connect(ConnectionConfig(host = "127.0.0.1"))
            compose.waitUntil(30000) { runtime.camera.value.info != null }
            runtime.properties.value.firstOrNull { it.code == 0x500A }?.let { focus ->
                kotlinx.coroutines.runBlocking { runtime.setProperty(focus, 32772).join() }
            }
            compose.onNodeWithText("取景拍摄").performClick()
            compose.waitUntil(20000) { runtime.frame.value?.isNotEmpty() == true }
            dismissFullscreenEducation()
            compose.onNodeWithText("光圈").assertIsDisplayed()
            compose.onNodeWithText("专业工具").performClick()
            compose.onNodeWithTag("live-properties").performScrollToNode(hasText("对焦模式"))
            compose.onNodeWithText("对焦模式").performClick()
            compose.onNodeWithText("连续自动对焦（AF-C）").assertIsDisplayed()
            compose.onNodeWithText("32772").assertDoesNotExist()
            compose.onNodeWithText("单次自动对焦（AF-S）").performClick()
            compose.waitUntil(10000) { runtime.properties.value.firstOrNull { it.code == 0x500A }?.value == 2L }
            compose.onNodeWithTag("live-properties").performScrollToNode(hasText("对焦区域"))
            compose.onNodeWithText("对焦区域").performClick()
            compose.onNodeWithText("点：大").assertIsDisplayed()
            compose.onNodeWithText("跟踪：点（中）").assertIsDisplayed()
            compose.onNodeWithText("关闭", substring = false).performClick()
            compose.onNodeWithContentDescription("沉浸取景").performClick()
            compose.onNodeWithTag("live-properties").assertDoesNotExist()
            compose.onNodeWithContentDescription("显示取景控制").assertIsDisplayed()
            acceptanceScreenshot(compose.activity, "live-immersive-preview")
            compose.onNodeWithTag("live-preview").performTouchInput { click() }
            compose.onNodeWithTag("live-quick-properties").assertDoesNotExist()
            compose.onNodeWithContentDescription("显示取景控制").performClick()
            compose.onNodeWithTag("live-quick-properties").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回相机").performClick()
        } finally { runtime.disconnect() }
    }
}
