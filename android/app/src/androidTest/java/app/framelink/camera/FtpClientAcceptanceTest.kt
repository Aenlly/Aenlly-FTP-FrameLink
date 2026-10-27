package app.framelink.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.Md5PasswordEncryptor
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 独立本机FTP服务器验证真实命令、原始字节、失败状态和安装包入口，不访问客户服务器 */
@RunWith(AndroidJUnit4::class)
class FtpClientAcceptanceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1)
    val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val runtime
        get() = (compose.activity.application as CameraApp).runtime

    @Test
    fun protocolRoundTripChineseNamesAuthenticationAndNoOverwrite() {
        Server(compose.activity).use { server ->
            val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            File(server.root, "中文 原文件.bin").writeBytes(bytes)
            FtpConnection(server.profile).use { ftp ->
                ftp.connect()
                val entry = ftp.list("/").single { it.name == "中文 原文件.bin" }
                assertEquals(bytes.size.toLong(), entry.size)
                val saved = ByteArrayOutputStream()
                val receipt = ftp.download(entry.path, saved) {}
                assertArrayEquals(bytes, saved.toByteArray())
                assertEquals(bytes.size.toLong(), receipt.bytes)
                assertEquals(MediaFiles.digest(bytes.inputStream()), receipt.sha256)
                ftp.makeDirectory("/交付")
                ftp.upload("/交付/中文 成片.bin", UUID.randomUUID().toString(), bytes.inputStream()) {}
                assertArrayEquals(bytes, File(server.root, "交付/中文 成片.bin").readBytes())
                assertTrue(
                    runCatching {
                            ftp.upload(
                                "/交付/中文 成片.bin",
                                UUID.randomUUID().toString(),
                                byteArrayOf(1, 2).inputStream(),
                            ) {}
                        }
                        .isFailure
                )
                assertArrayEquals(bytes, File(server.root, "交付/中文 成片.bin").readBytes())
                assertTrue(runCatching { ftp.list("/不存在") }.isFailure)
            }
            FtpConnection(server.profile.copy(password = "wrong-password")).use {
                assertTrue(runCatching { it.connect() }.isFailure)
            }
            FtpConnection(server.profile).use { ftp ->
                ftp.connect()
                assertTrue(
                    runCatching {
                            ftp.upload(
                                "/cancelled.bin",
                                UUID.randomUUID().toString(),
                                bytes.inputStream(),
                            ) {
                                error("QA取消")
                            }
                        }
                        .isFailure
                )
            }
            assertFalse(File(server.root, "cancelled.bin").exists())
        }
    }

    @Test
    fun queueDownloadsUploadAndRetryKeepBytesAndFileTypes() = runBlocking {
        Server(compose.activity).use { server ->
            runtime.settings.put("ftp.compress", "true")
            runtime.settings.remove("ftp.save.tree")
            runtime.settings.remove("ftp.compress.tree")
            val jpeg = portrait()
            val raw = ByteArray(512 * 1024) { (it % 199).toByte() }
            val text = "QA通用FTP文件，不加入照片图库".toByteArray()
            File(server.root, "QA-photo.jpg").writeBytes(jpeg)
            File(server.root, "QA-photo.arw").writeBytes(raw)
            File(server.root, "QA-readme.txt").writeBytes(text)
            runtime.ftpClient.connect(server.profile)
            waitFor {
                runtime.ftpClient.state.value.connected ||
                    runtime.ftpClient.state.value.error.isNotBlank()
            }
            assertTrue(runtime.ftpClient.state.value.error, runtime.ftpClient.state.value.connected)
            val entries = runtime.ftpClient.state.value.entries.filter { !it.directory }
            runtime.ftpClient.enqueueDownloads(entries).join()
            val tasks =
                runtime.database.tasks().byKind(TransferKind.FTP_DOWNLOAD.name).filter {
                    JSONObject(it.payload).optString("profile") == server.profile.id
                }
            assertEquals(3, tasks.size)
            assertTrue(
                tasks.map { it.state }.toString(),
                tasks.all { it.state == TransferState.COMPLETE.name },
            )
            for (task in tasks) {
                val data = JSONObject(task.payload)
                val file = FtpStorage.taskFile(compose.activity, task)!!
                assertArrayEquals(
                    File(server.root, data.getString("name")).readBytes(),
                    file.readBytes(),
                )
                val photo = runtime.database.photos().find(data.optString("photoKey"))
                if (data.getString("name").endsWith("txt")) assertNull(photo)
                else assertNotNull(photo)
                if (data.getString("name").endsWith("jpg")) {
                    assertTrue(data.has("compressedUri"))
                    val preview =
                        compose.activity.contentResolver
                            .openInputStream(
                                android.net.Uri.parse(data.getString("compressedUri"))
                            )!!
                            .use { it.readBytes() }
                    assertTrue(preview.size < jpeg.size)
                }
            }
            val upload =
                File(compose.activity.filesDir, "media/qa-upload/${UUID.randomUUID()}.bin").apply {
                    parentFile!!.mkdirs()
                    writeBytes(raw)
                }
            val uri =
                FileProvider.getUriForFile(
                    compose.activity,
                    compose.activity.packageName + ".files",
                    upload,
                )
            runtime.ftpClient.enqueueUploads(listOf(uri))
            waitFor {
                runBlocking {
                    runtime.database.tasks().byKind(TransferKind.FTP_UPLOAD.name).any {
                        JSONObject(it.payload).optString("uri") == uri.toString() &&
                            it.state in
                                listOf(TransferState.COMPLETE.name, TransferState.FAILED.name)
                    }
                }
            }
            val sent =
                runtime.database.tasks().byKind(TransferKind.FTP_UPLOAD.name).first {
                    JSONObject(it.payload).optString("uri") == uri.toString()
                }
            assertEquals(sent.error, TransferState.COMPLETE.name, sent.state)
            assertArrayEquals(raw, File(server.root, upload.name).readBytes())
            val missing =
                TransferTask(
                    UUID.randomUUID().toString(),
                    TransferKind.FTP_DOWNLOAD.name,
                    title = "QA可重试",
                    total = 4,
                    payload =
                        JSONObject()
                            .put("profile", server.profile.id)
                            .put("remote", "/retry.bin")
                            .put("name", "retry.bin")
                            .put("size", 4)
                            .put("modified", 0)
                            .toString(),
                )
            runtime.database.tasks().save(missing)
            runtime.runTasks()
            val failed = runtime.database.tasks().find(missing.id)!!
            assertEquals(TransferState.FAILED.name, failed.state)
            assertNull(FtpStorage.taskFile(compose.activity, failed))
            File(server.root, "retry.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
            runtime.retry(failed).join()
            assertEquals(
                TransferState.COMPLETE.name,
                runtime.database.tasks().find(missing.id)!!.state,
            )
            val cancel = missing.copy(id = UUID.randomUUID().toString())
            runtime.database.tasks().save(cancel)
            runtime.cancel(cancel).join()
            runtime.runTasks()
            assertEquals(
                TransferState.CANCELLED.name,
                runtime.database.tasks().find(cancel.id)!!.state,
            )
            upload.delete()
            runtime.ftpClient.disconnect()
            runtime.settings.remove("ftp.compress")
        }
    }

    @Test
    fun installedUiShowsCoreActionsConnectsAndDownloads() {
        runtime.ftpClient.disconnect()
        Server(compose.activity).use { server ->
            File(server.root, "客户照片").mkdir()
            val photoName = "QA-portrait-${UUID.randomUUID().toString().take(8)}.jpg"
            File(server.root, "客户照片/$photoName").writeBytes(portrait())
            compose.onNodeWithText("相机", useUnmergedTree = true).performClick()
            compose.onNodeWithText("FTP 接收").assertIsDisplayed()
            compose.onNodeWithText("FTP 客户端").assertIsDisplayed()
            compose.onNodeWithText("取景拍摄").assertIsDisplayed()
            screenshot("android-home-bento")
            compose.onNodeWithText("FTP 接收").performClick()
            compose
                .onNode(hasSetTextAction() and hasText("接收端口"))
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("保存位置与压缩副本").performScrollTo().performClick()
            compose.onNodeWithText("另存压缩副本").performScrollTo().assertIsDisplayed()
            screenshot("android-ftp-receiver")
            compose.onNodeWithContentDescription("关闭").performClick()
            compose.onNodeWithText("FTP 客户端").performClick()
            compose
                .onNode(hasSetTextAction() and hasText("服务器地址"))
                .performTextReplacement("127.0.0.1")
            compose
                .onNode(hasSetTextAction() and hasText("端口"))
                .performTextReplacement(server.port.toString())
            compose
                .onNode(hasSetTextAction() and hasText("登录账号"))
                .performTextReplacement(server.profile.username)
            compose
                .onNode(hasSetTextAction() and hasText("登录密码"))
                .performTextReplacement(server.profile.password)
            compose.onNodeWithText("连接并浏览文件").performScrollTo().performClick()
            compose.waitUntil(20000) {
                runtime.ftpClient.state.value.connected ||
                    runtime.ftpClient.state.value.error.isNotBlank()
            }
            assertTrue(runtime.ftpClient.state.value.error, runtime.ftpClient.state.value.connected)
            compose.onNodeWithText("客户照片").performClick()
            compose.onNodeWithText(photoName).assertIsDisplayed()
            compose.onNodeWithText(photoName).performClick()
            screenshot("android-ftp-client")
            compose.onNodeWithText("下载 1 个文件").performClick()
            compose.onNodeWithText("传输任务").assertIsDisplayed()
            compose.waitUntil(20000) {
                runBlocking {
                    runtime.database.tasks().byKind(TransferKind.FTP_DOWNLOAD.name).any {
                        JSONObject(it.payload).optString("profile") == server.profile.id &&
                            it.state == TransferState.COMPLETE.name
                    }
                }
            }
            compose.onNodeWithText("图库", useUnmergedTree = true).performClick()
            compose.onNodeWithText("已导入").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(photoName).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            compose.onNodeWithContentDescription("选择 $photoName").assertIsDisplayed()
            compose.onNodeWithText(photoName).performClick()
            compose.onNodeWithText("已导入原片").assertIsDisplayed()
            screenshot("android-photo-glass")
            compose.onNodeWithContentDescription("关闭照片").performClick()
            runtime.ftpClient.disconnect()
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(100)
        }
        error("QA操作20秒内未完成")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val file =
            File(compose.activity.getExternalFilesDir(null), "qa-shots/$name.png").apply {
                parentFile!!.mkdirs()
            }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun portrait(): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 1700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader =
            android.graphics.LinearGradient(
                0f,
                0f,
                900f,
                1700f,
                intArrayOf(
                    Color.rgb(99, 181, 233),
                    Color.rgb(191, 222, 206),
                    Color.rgb(42, 106, 92),
                ),
                null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        canvas.drawRect(0f, 0f, 900f, 1700f, paint)
        paint.shader = null
        for (i in 0..18) {
            paint.color = if (i % 2 == 0) Color.rgb(232, 228, 207) else Color.rgb(83, 139, 110)
            canvas.drawCircle(i * 65f, 850f + (i % 3) * 110f, 220f, paint)
        }
        paint.color = Color.WHITE
        paint.textSize = 36f
        canvas.drawText("FRAMELINK · QA SYNTHETIC", 80f, 220f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private class Server(context: Context) : AutoCloseable {
        val root = File(context.cacheDir, "qa-ftp-${UUID.randomUUID()}").apply { mkdirs() }
        val port = ServerSocket(0).use { it.localPort }
        val profile = FtpProfile("127.0.0.1", port, "qa-client", "qa-password-123")
        private val usersFile =
            File(root.parentFile, root.name + "-users.properties").apply { writeText("") }
        private val server: FtpServer

        init {
            val factory = FtpServerFactory()
            factory.addListener(
                "default",
                ListenerFactory()
                    .apply {
                        setPort(this@Server.port)
                        serverAddress = "127.0.0.1"
                    }
                    .createListener(),
            )
            val users =
                PropertiesUserManagerFactory()
                    .apply {
                        file = usersFile
                        passwordEncryptor = Md5PasswordEncryptor()
                    }
                    .createUserManager()
            users.save(
                BaseUser().apply {
                    name = profile.username
                    setPassword(profile.password)
                    homeDirectory = root.absolutePath
                    authorities = listOf(WritePermission())
                }
            )
            factory.userManager = users
            server = factory.createServer().also { it.start() }
        }

        override fun close() {
            server.stop()
            root.deleteRecursively()
            usersFile.delete()
        }
    }
}
