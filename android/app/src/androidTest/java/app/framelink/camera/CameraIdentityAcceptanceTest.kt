package app.framelink.camera

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只连接电脑上的合成SSH相机，验证确认前没有认证尝试与凭证记忆 */
@RunWith(AndroidJUnit4::class)
class CameraIdentityAcceptanceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime get() = (compose.activity.application as CameraApp).runtime

    @Test
    fun firstContactCancelConfirmReconnectAndChangedKeyRequireConsent() {
        val trustKey = "camera.trust.${MediaFiles.key("127.0.0.1")}"
        val oldConfig = runtime.settings.get("camera.config")
        val oldTrust = runtime.settings.get(trustKey)
        runtime.disconnect()
        runtime.settings.remove("camera.config")
        runtime.settings.remove(trustKey)
        try {
            compose.onNodeWithText("连接相机").performClick()
            compose.onNode(hasSetTextAction() and hasText("相机IP地址")).performTextReplacement("127.0.0.1")
            compose.onNode(hasSetTextAction() and hasText("访问认证账号")).performScrollTo().performTextReplacement("qa-camera")
            compose.onNode(hasSetTextAction() and hasText("访问认证密码")).performScrollTo().performTextReplacement("test-password-123")
            compose.onNodeWithContentDescription("显示密码").performClick()
            compose.waitForIdle()
            acceptanceScreenshot(compose.activity, "camera-connection-password-visible")
            compose.onNodeWithContentDescription("隐藏密码").assertIsDisplayed().performClick()
            val before = attempts()
            connect()
            compose.waitUntil(15000) { runtime.cameraIdentityPrompt.value != null }
            compose.onNodeWithText("确认相机身份").assertIsDisplayed()
            val fingerprint = runtime.cameraIdentityPrompt.value!!.identity.fingerprint
            compose.onNodeWithText(fingerprint).assertIsDisplayed()
            compose.waitForIdle()
            acceptanceScreenshot(compose.activity, "camera-identity-confirmation")
            assertEquals(before, attempts())
            compose.onNodeWithText("取消").performClick()
            assertEquals(before, attempts())
            assertEquals("", runtime.settings.get(trustKey))
            connect()
            compose.waitUntil(15000) { runtime.cameraIdentityPrompt.value != null }
            compose.onNodeWithText("确认并连接").performClick()
            compose.waitUntil(30000) { runtime.camera.value.info != null }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("camera-connect-action").fetchSemanticsNodes().isEmpty() }
            assertTrue(attempts() > before)
            val remembered = runtime.savedConnection()
            assertEquals("qa-camera", remembered.user)
            assertEquals("test-password-123", remembered.password)
            assertEquals(fingerprint, SecureSettings(compose.activity).get(trustKey))
            runtime.disconnect()
            compose.onNodeWithText("连接相机").performClick()
            compose.onNode(hasSetTextAction() and hasText("访问认证账号")).assertTextContains("qa-camera")
            connect()
            compose.waitUntil(30000) { runtime.camera.value.info != null }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("camera-connect-action").fetchSemanticsNodes().isEmpty() }
            assertNull(runtime.cameraIdentityPrompt.value)
            runtime.disconnect()
            runtime.settings.put(trustKey, "SHA256:previous-camera-key")
            // 已确认的按地址保存的指纹优先于上次成功配置，变化时不能自动接受
            runtime.settings.put("camera.config", Json.encode(remembered.copy(fingerprint = "")))
            compose.onNodeWithText("连接相机").performClick()
            val previousAttempts = attempts()
            connect()
            compose.waitUntil(15000) { runtime.cameraIdentityPrompt.value != null }
            compose.onNodeWithText("相机身份发生变化").assertIsDisplayed()
            compose.waitForIdle()
            acceptanceScreenshot(compose.activity, "camera-identity-changed")
            assertEquals(previousAttempts, attempts())
            compose.onNodeWithText("取消").performClick()
            assertEquals("SHA256:previous-camera-key", runtime.settings.get(trustKey))
        } finally {
            runtime.disconnect()
            if (oldConfig.isBlank()) runtime.settings.remove("camera.config") else runtime.settings.put("camera.config", oldConfig)
            if (oldTrust.isBlank()) runtime.settings.remove(trustKey) else runtime.settings.put(trustKey, oldTrust)
        }
    }

    private fun connect() {
        compose.onNode(hasSetTextAction() and hasText("相机IP地址")).performImeAction()
        compose.onNodeWithTag("camera-connect-action").performClick()
    }

    private fun attempts(): Long {
        val connection = URI("http://127.0.0.1:16023/").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try { connection.inputStream.bufferedReader().use { it.readText().trim().toLong() } } finally { connection.disconnect() }
    }
}
