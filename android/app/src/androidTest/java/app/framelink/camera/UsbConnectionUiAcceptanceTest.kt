package app.framelink.camera

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 无USB机身的独立模拟器只验证入口和空设备状态，真实USB握手另验 */
@RunWith(AndroidJUnit4::class)
class UsbConnectionUiAcceptanceTest {
    @get:Rule(order=0) val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1) val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime get()=androidx.test.core.app.ApplicationProvider.getApplicationContext<CameraApp>().runtime
    @Test fun readerEntryShowsDeviceStateWithoutNetworkOrRemoteClaims(){
        runtime.disconnect()
        val previous=runtime.settings.get("camera.config")
        try{
            assertTrue("此用例只能在没有PTP/MTP设备的验收模拟器执行",runtime.native.usbTargets().isEmpty())
            runtime.settings.put("camera.config","")
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithTag("camera-home").performScrollToIndex(0)
            compose.onNodeWithTag("camera-primary").performClick()
            compose.onNodeWithText("USB读卡",substring=false).performClick()
            compose.onNodeWithText("不提供取景遥控",substring=true).assertIsDisplayed()
            compose.onNodeWithText("尚未发现PTP/MTP设备",substring=true).assertIsDisplayed()
            compose.onNodeWithText("相机IP地址",substring=false).assertDoesNotExist()
            compose.onNodeWithText("相机端口",substring=false).assertDoesNotExist()
            compose.onNodeWithTag("camera-connect-action").assertIsNotEnabled()
            acceptanceScreenshot(compose.activity,"usb-reader-entry-no-device")
        }finally{runtime.disconnect();runtime.settings.put("camera.config",previous)}
    }
}
