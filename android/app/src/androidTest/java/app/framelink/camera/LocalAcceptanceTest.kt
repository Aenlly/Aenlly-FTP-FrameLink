package app.framelink.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigationUsesRealEmptyAndSettingsStates() {
        compose.onNodeWithText("连接相机").assertIsDisplayed()
        compose.onNodeWithText("图库", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("图库更多操作").performClick()
        compose.onNodeWithText("导入本机照片").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("项目", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("项目更多").performClick()
        compose.onNodeWithText("启用云端选片").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("传输", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("transfer-workspace").assertIsDisplayed()
        compose.onNodeWithContentDescription("传输更多操作").performClick()
        compose.onNodeWithText("设置与空间",substring=false).performClick()
        compose.onNodeWithText("清理预览缓存").assertIsDisplayed()
    }

    @Test
    fun lateProgressCannotReopenACompletedTask() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        try {
            val task =
                TransferTask(
                    UUID.randomUUID().toString(),
                    TransferKind.IMPORT.name,
                    title = "QA",
                    state = TransferState.COMPLETE.name,
                    done = 100,
                    total = 100,
                )
            db.tasks().save(task)
            db.tasks()
                .progress(task.id, 5, 100, System.currentTimeMillis(), TransferState.RUNNING.name)
            db.tasks().attachFiles(task.id,"late-photo","late-title","{}",System.currentTimeMillis(),TransferState.RUNNING.name)
            val saved = db.tasks().find(task.id)!!
            assertEquals(TransferState.COMPLETE.name, saved.state)
            assertEquals(100L, saved.done)
            assertEquals("QA",saved.title)
            assertEquals("",saved.photoId)
        } finally {
            db.close()
        }
    }

    @Test
    fun localCustomerChoicesStayIndependentAndExportSafely() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        try {
            val photo =
                Photo(
                    "qa-local-review",
                    "qa-local-review",
                    "=QA.JPG",
                    PhotoSource.IMPORTED.name,
                    1,
                    Json.encode(listOf(CameraFile(name = "=QA.JPG"))),
                    mark = PhotoMark.FAVORITE.name,
                )
            db.photos().save(photo)
            val reviews = LocalReviewRepository(context, db)
            val review = reviews.create("QA现场选片", listOf(photo.id))
            reviews.choose(review.id, photo.id, selected = true, comment = "喜欢这张")
            reviews.lock(review.id, true)
            assertEquals(PhotoMark.FAVORITE.name, db.photos().find(photo.id)!!.mark)
            assertTrue(db.reviews().find(review.id)!!.choices()[photo.id]!!.selected)
            var rejected = false
            try {
                reviews.choose(review.id, photo.id, selected = false)
            } catch (error: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
            val csv = reviews.export(review.id)
            assertTrue(csv.readText().contains("'=QA.JPG"))
            csv.delete()
            Unit
        } finally {
            db.close()
        }
    }

    @Test
    fun ftpAcknowledgesOnlyDurableOriginalsAndKeepsSameNamesSeparate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val port = ServerSocket(0).use { it.localPort }
        val receiver = FtpReceiver(context, db, scope, MutableStateFlow(""), MutableStateFlow(""))
        val ownedFiles = mutableListOf<File>()
        try {
            receiver.start("127.0.0.1", port, "qa-camera", "test-password-123")
            val first = jpeg(0xff336699.toInt())
            val second = ByteArray(FtpTransferIo.BUFFER_BYTES * 17 + 73) { (it % 251).toByte() }
            for (payload in listOf(first, second)) {
                FtpClient(port).use { client ->
                    client.login()
                    client.upload("QA_same_name.JPG", payload)
                }
            }
            val received = db.tasks().inState(TransferState.QUEUED.name)
            assertEquals(2, received.size)
            val bodies =
                received.map { task ->
                    val meta = JSONObject(task.payload)
                    assertTrue(meta.getBoolean("ready"))
                    val file = File(meta.getString("path"))
                    ownedFiles += file
                    assertTrue(file.isFile)
                    assertEquals(task.total, file.length())
                    file.readBytes()
                }
            assertTrue(bodies.any { it.contentEquals(first) })
            assertTrue(bodies.any { it.contentEquals(second) })
            assertNotEquals(ownedFiles[0].canonicalPath, ownedFiles[1].canonicalPath)
            FtpClient(port).use { client ->
                client.login()
                assertEquals(502, client.command("DELE QA_same_name.JPG").take(3).toInt())
            }
        } finally {
            receiver.stop()
            scope.cancel()
            ownedFiles.forEach { it.delete() }
            db.close()
        }
    }

    private fun jpeg(color: Int): ByteArray {
        val image = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        image.eraseColor(color)
        val out = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 90, out)
        image.recycle()
        return out.toByteArray()
    }

    private class FtpClient(port: Int) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 15000 }
        private val input = socket.getInputStream().bufferedReader()
        private val output = socket.getOutputStream().bufferedWriter()

        init {
            reply()
        }

        fun command(text: String): String {
            output.write(text + "\r\n")
            output.flush()
            return reply()
        }

        private fun reply(): String {
            val first = input.readLine() ?: error("FTP control connection closed")
            if (first.length > 3 && first[3] == '-') {
                while (true) {
                    val line = input.readLine() ?: error("FTP multiline reply truncated")
                    if (line.startsWith(first.take(3) + " ")) break
                }
            }
            return first
        }

        fun login() {
            assertEquals(331, command("USER qa-camera").take(3).toInt())
            assertEquals(230, command("PASS test-password-123").take(3).toInt())
            assertEquals(200, command("TYPE I").take(3).toInt())
        }

        fun upload(name: String, bytes: ByteArray) {
            val pasv = command("PASV")
            assertTrue(pasv.startsWith("227"))
            val numbers =
                pasv.substringAfter('(').substringBefore(')').split(',').map { it.trim().toInt() }
            Socket("127.0.0.1", numbers[4] * 256 + numbers[5]).use { data ->
                assertTrue(command("STOR $name").startsWith("150"))
                data.getOutputStream().write(bytes)
                data.shutdownOutput()
            }
            assertTrue(reply().startsWith("226"))
        }

        override fun close() {
            socket.close()
        }
    }
}
