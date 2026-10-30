package app.framelink.camera

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** 独立Sony协议模拟端点验证设置与真实回读，不冒充A7C II机身实测 */
@RunWith(AndroidJUnit4::class)
class CreativeLookAcceptanceTest {
    @get:Rule(order=0)val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1)val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime get()=ApplicationProvider.getApplicationContext<CameraApp>().runtime

    @Test fun modernLookSelectionUsesCameraReadbackAndReconnects(){
        runtime.disconnect()
        try{
            runtime.connect(ConnectionConfig(host="127.0.0.1"))
            compose.waitUntil(30000){runtime.camera.value.info!=null}
            val before=runtime.properties.value.first{it.code==CameraPropertyText.CREATIVE_LOOK}
            assertTrue(before.writable)
            assertEquals(1L,before.value)
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithTag("camera-home").performScrollToIndex(0)
            compose.onNodeWithText("取景拍摄").performClick()
            compose.waitUntil(20000){runtime.frame.value?.isNotEmpty()==true}
            dismissFullscreenEducation()
            compose.onNodeWithContentDescription("创意外观").performClick()
            compose.onNodeWithTag("live-property-picker").assertIsDisplayed()
            compose.onNode(hasText("标准") and hasAnyAncestor(hasTestTag("live-property-picker"))).assertIsDisplayed()
            compose.onNode(hasText("胶片") and hasAnyAncestor(hasTestTag("live-property-picker"))).assertIsDisplayed()
            compose.onNode(hasText("FL") and hasAnyAncestor(hasTestTag("live-property-picker"))).performClick()
            compose.waitUntil(12000){runtime.properties.value.first{it.code==CameraPropertyText.CREATIVE_LOOK}.value==6L&&runtime.parameterUpdating.value==null}
            assertEquals(6L,runtime.native.properties().first{it.code==CameraPropertyText.CREATIVE_LOOK}.value)
            acceptanceScreenshot(compose.activity,"creative-look-fl-readback")
            compose.onNodeWithContentDescription("关闭参数").performClick()
            compose.onNodeWithContentDescription("创意外观").assert(hasStateDescription("FL"))
            compose.onNodeWithContentDescription("返回相机").performClick()
            runtime.disconnect()
            runtime.connect(ConnectionConfig(host="127.0.0.1"))
            compose.waitUntil(30000){runtime.camera.value.info!=null}
            assertEquals(6L,runtime.properties.value.first{it.code==CameraPropertyText.CREATIVE_LOOK}.value)
            val current=runtime.properties.value.first{it.code==CameraPropertyText.CREATIVE_LOOK}
            kotlinx.coroutines.runBlocking{runtime.setProperty(current,1).join()}
            assertEquals(1L,runtime.native.properties().first{it.code==CameraPropertyText.CREATIVE_LOOK}.value)
        }finally{runtime.disconnect()}
    }
}
