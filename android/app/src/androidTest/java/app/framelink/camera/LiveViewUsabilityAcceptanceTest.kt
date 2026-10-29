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

    private fun openLive() {
        runtime.connect(ConnectionConfig(host = "127.0.0.1"))
        compose.waitUntil(30000) { runtime.camera.value.info != null }
        compose.onNodeWithText("取景拍摄").performClick()
        compose.waitUntil(20000) { runtime.frame.value?.isNotEmpty() == true }
        dismissFullscreenEducation()
    }

    private fun openParameters() {
        compose.onNodeWithContentDescription("更多控制").performClick()
        compose.onNodeWithText("机身参数").performClick()
    }

    @Test
    fun rotationPreservesCameraFrameAndBothOrientationsOfferAParameterRuler() {
        runtime.disconnect()
        try {
            openLive()
            val scene = compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().id
            val portrait = compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow
            compose.onNodeWithTag("live-quick-properties").assertDoesNotExist()
            val exposureMode = runtime.properties.value.firstOrNull { it.code == 0x500E }?.value
            compose.onNodeWithContentDescription("专业模式").performClick()
            assertEquals(exposureMode, runtime.properties.value.firstOrNull { it.code == 0x500E }?.value)
            assertEquals("拍照与专业切换不能挤动取景", portrait, compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow)
            acceptanceScreenshot(compose.activity, "live-portrait-professional")
            compose.onNodeWithText("光圈").performClick()
            compose.onNode(hasText("光圈") and hasAnyAncestor(hasTestTag("live-property-picker"))).performTouchInput { click() }
            compose.onNodeWithTag("live-property-picker").assertIsDisplayed()
            val portraitPicker=compose.onNodeWithTag("live-property-picker").fetchSemanticsNode().boundsInWindow
            val portraitWindow=compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().boundsInWindow
            val rootInsets=compose.activity.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets
            val gestures=rootInsets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures() or android.view.WindowInsets.Type.displayCutout()).bottom
            val density=compose.activity.resources.displayMetrics.density
            assertTrue("底部仅保留手势区与紧凑间距",portraitWindow.bottom-portraitPicker.bottom<=gestures+16*density)
            val aperture = runtime.properties.value.first { it.code == 0x5007 }
            compose.onNodeWithTag("live-property-choices").performTouchInput { swipeLeft(durationMillis = 500) }
            compose.waitUntil(10000) { runtime.properties.value.first { it.code == aperture.code }.value != aperture.value }
            val original = CameraPropertyText.choices(aperture).first { it.value == aperture.value }
            val originalIndex=CameraPropertyText.choices(aperture).indexOfFirst { it.value==original.value }
            compose.onNodeWithTag("live-property-choices").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(originalIndex.toFloat())}
            compose.waitUntil(10000) { runtime.properties.value.first { it.code == aperture.code }.value == aperture.value }
            assertEquals(portrait, compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow)
            acceptanceScreenshot(compose.activity, "live-portrait-ruler")
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            assertEquals("旋转保留同一取景窗口", scene, compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().id)
            val window = compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().boundsInWindow
            val metrics = compose.activity.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
            assertTrue(window.width >= metrics.width() * .95f)
            val landscape = compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow
            val picker = compose.onNodeWithTag("live-property-picker").fetchSemanticsNode().boundsInWindow
            assertTrue("横屏参数尺留在操作区", picker.width < window.width * .18f && picker.left >= landscape.right - 4)
            compose.waitForIdle()
            acceptanceScreenshot(compose.activity, "live-landscape-ruler")
            compose.onNodeWithContentDescription("关闭参数").performClick()
            compose.onNodeWithContentDescription("拍摄").assertIsDisplayed()
            val shutter = compose.onNodeWithContentDescription("拍摄").fetchSemanticsNode().boundsInWindow
            assertTrue("快门位于右侧操作区", shutter.left >= landscape.right && shutter.bottom < window.bottom - 12)
            val quick = compose.onNodeWithTag("live-quick-properties").fetchSemanticsNode().boundsInWindow
            assertTrue(compose.onAllNodesWithTag("live-quick-value", useUnmergedTree = true).fetchSemanticsNodes().all { it.boundsInWindow.bottom <= quick.bottom })
            acceptanceScreenshot(compose.activity, "live-landscape-camera")
            compose.onNodeWithContentDescription("拍照模式").performClick()
            compose.onNodeWithTag("live-quick-properties").assertDoesNotExist()
            assertEquals(landscape, compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow)
            acceptanceScreenshot(compose.activity, "live-landscape-photo")
            compose.onNodeWithContentDescription("专业模式").performClick()
            compose.onNodeWithContentDescription("更多控制").performClick()
            val panel = compose.onNodeWithTag("live-tools-panel").fetchSemanticsNode().boundsInWindow
            assertTrue("菜单按需展开，控制宽度", panel.width <= window.width * .40f)
            compose.waitForIdle()
            acceptanceScreenshot(compose.activity, "live-landscape-glass-menu")
            assertEquals(landscape, compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow)
            compose.onNodeWithContentDescription("直方图", substring = false).performClick()
            compose.onNodeWithContentDescription("收起工具").performClick()
            val histogram = compose.onNodeWithTag("live-histogram").fetchSemanticsNode().boundsInWindow
            assertTrue(histogram.width <= window.width * .18f && histogram.height <= window.height * .25f)
            compose.onNodeWithContentDescription("沉浸取景").performClick()
            assertEquals(landscape, compose.onNodeWithTag("live-preview").fetchSemanticsNode().boundsInWindow)
            compose.onNodeWithContentDescription("显示取景控制").performClick()
            compose.onNodeWithContentDescription("返回相机").performClick()
        } finally {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            runtime.disconnect()
        }
    }

    @Test
    fun cameraWorkflowStaysConnectedThroughRulerFocusShotAlbumAndRotation() {
        runtime.disconnect()
        var original: CameraProperty? = null
        val previousImport=runtime.settings.get("capture.auto_import")
        try {
            openLive()
            runtime.settings.put("capture.auto_import","false")
            compose.onNodeWithContentDescription("专业模式").performClick()
            val aperture=runtime.properties.value.first { it.code==0x5007 }
            original=aperture
            compose.onNodeWithText("光圈").performClick()
            compose.onNodeWithTag("live-property-choices").performTouchInput { swipeLeft(durationMillis=500) }
            compose.waitUntil(10000) { runtime.properties.value.first { it.code==0x5007 }.value!=aperture.value }
            compose.onNodeWithContentDescription("关闭参数").performClick()
            val before=runtime.native.newestPage(1,"",0).items.map { it.id }.toSet()
            compose.onNodeWithTag("live-preview").performTouchInput { click() }
            compose.waitUntil(10000) { !runtime.focusBusy.value }
            assertEquals("对焦不应触发拍摄",before,runtime.native.newestPage(1,"",0).items.map { it.id }.toSet())
            val previousCapture=runtime.lastCapture.value
            compose.onNodeWithContentDescription("拍摄").performClick()
            compose.waitUntil(20000) { !runtime.captureBusy.value }
            assertEquals(previousCapture,runtime.lastCapture.value)
            compose.onNodeWithContentDescription("查看相机照片").performClick()
            compose.waitUntil(20000) { runtime.currentCameraIds.value.isNotEmpty()&&!runtime.camera.value.loadingPhotos }
            compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
            assertNotNull(runtime.camera.value.info)
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithText("取景拍摄").performClick()
            compose.waitUntil(20000) { runtime.frame.value?.isNotEmpty()==true }
            compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithContentDescription("拍摄").assertIsDisplayed()
            compose.onNodeWithContentDescription("专业模式").performClick()
            compose.onNodeWithText("光圈").performClick()
            compose.onNodeWithTag("live-property-picker").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭参数").performClick()
            compose.onNodeWithContentDescription("更多控制").performClick()
            compose.onNodeWithTag("live-tools-panel").assertIsDisplayed()
            compose.onNodeWithContentDescription("收起工具").performClick()
            compose.onNodeWithContentDescription("返回相机").performClick()
        } finally {
            original?.let { if(runtime.camera.value.info!=null) kotlinx.coroutines.runBlocking { runtime.setProperty(it,it.value).join() } }
            runtime.settings.put("capture.auto_import",previousImport)
            compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            runtime.disconnect()
        }
    }

    @Test
    fun glassMenuAndChineseOptionsRemainReadable() {
        runtime.disconnect()
        try {
            openLive()
            acceptanceScreenshot(compose.activity, "live-portrait-camera")
            compose.onNodeWithContentDescription("更多控制").performClick()
            compose.onNodeWithTag("live-tools-panel").assertIsDisplayed()
            compose.onNodeWithText("拍摄控制").performTouchInput { click() }
            assertFalse("菜单空白不应穿透对焦",runtime.focusBusy.value)
            compose.waitForIdle()
            acceptanceScreenshot(compose.activity, "live-portrait-glass-menu")
            compose.onNodeWithText("机身参数").performClick()
            compose.onNodeWithTag("live-properties").performScrollToNode(hasText("对焦模式"))
            compose.onNodeWithText("对焦模式").performClick()
            compose.onNodeWithText("连续自动对焦（AF-C）").assertIsDisplayed()
            compose.onNodeWithText("32772").assertDoesNotExist()
            compose.onNodeWithText("单次自动对焦（AF-S）").performClick()
            compose.waitUntil(10000) { runtime.properties.value.first { it.code == 0x500A }.value == 2L }
            compose.onNodeWithTag("live-properties").performScrollToNode(hasText("对焦区域"))
            compose.onNodeWithText("对焦区域").performClick()
            compose.onNodeWithText("点：大").assertIsDisplayed()
            compose.onNodeWithText("跟踪：点（中）").assertIsDisplayed()
            compose.onNodeWithText("关闭", substring = false).performClick()
            compose.onNodeWithContentDescription("沉浸取景").performClick()
            compose.onNodeWithContentDescription("显示取景控制").assertIsDisplayed()
            compose.onNodeWithTag("live-preview").performTouchInput { click() }
            compose.onNodeWithTag("live-tools-panel").assertDoesNotExist()
            compose.onNodeWithContentDescription("显示取景控制").performClick()
            compose.onNodeWithContentDescription("返回相机").performClick()
        } finally { runtime.disconnect() }
    }
}
