package app.framelink.camera

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

/** 仅操作独立模拟器的合成照片与本机模拟相机，验证选择与断线行为 */
@RunWith(AndroidJUnit4::class)
class GalleryInteractionAcceptanceTest {
    private var previousLayout:String?=null
    @After fun restoreLayout(){previousLayout?.let{runtime.settings.put("gallery.layout",it)}}
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<CameraApp>().runtime
    private var touching = false
    private val owned = mutableListOf<Photo>()
    private var downTime = 0L
    private var touchOrigin = Offset.Zero
    private var lastTouch = Offset.Zero

    @Test
    fun viewerAndGridUseOneSelectionAndLegacyMarksDoNotSelectPhotos() {
        try {
            val photos = seed(2)
            runBlocking { runtime.database.photos().save(photos[0].copy(mark = PhotoMark.PICKED.name)) }
            gallery()
            compose.onNodeWithContentDescription("查看 ${photos[0].name}").performTouchInput { click(Offset(width * .25f, height * .25f)) }
            compose.onNodeWithText("排除").assertDoesNotExist()
            compose.onNodeWithText("选中").assertDoesNotExist()
            compose.onNodeWithText("选择", substring = false).performClick()
            compose.onNodeWithText("已选择").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭照片").performClick()
            checkbox(photos[0]).assertIsOn()
            compose.onNodeWithText("已选 1").assertIsDisplayed()
            compose.onNodeWithText("已选", substring = false).performClick()
            compose.onNodeWithContentDescription("查看 ${photos[0].name}").assertIsDisplayed()
            compose.onNodeWithContentDescription("查看 ${photos[1].name}").assertDoesNotExist()
            compose.onNodeWithContentDescription("查看 ${photos[0].name}").performTouchInput { click(Offset(width * .25f, height * .25f)) }
            compose.onNodeWithText("已选择").performClick()
            compose.onNodeWithContentDescription("关闭照片").performClick()
            compose.onNodeWithText("还没有选择照片").assertIsDisplayed()
            compose.onNodeWithText("查看全部").performClick()
            checkbox(photos[0]).assertIsOff()
            assertEquals(PhotoMark.PICKED.name, runBlocking { runtime.database.photos().find(photos[0].id)!!.mark })
        } finally { cleanup() }
    }

    @Test
    fun longPressDragSelectsReversesDeselectsAndKeepsNormalScrolling() {
        try {
            val photos = seed(60)
            gallery()
            val start = point(photos[0])
            val secondRow = point(photos[5])
            down(start)
            move(secondRow)
            compose.onNodeWithText("已选 6").assertIsDisplayed()
            move(point(photos[2]))
            up()
            compose.onNodeWithText("已选 3").assertIsDisplayed()
            for (photo in photos.take(3)) checkbox(photo).assertIsOn()
            for (photo in photos.subList(3, 6)) checkbox(photo).assertIsOff()
            down(point(photos[0]))
            move(point(photos[2]))
            up()
            compose.onNodeWithContentDescription("清空本次选择").assertDoesNotExist()
            compose.onNodeWithTag("photo-viewer-image").assertDoesNotExist()
            compose.onNodeWithTag("gallery-grid").performTouchInput { swipeUp(durationMillis = 200) }
            compose.onNodeWithContentDescription("清空本次选择").assertDoesNotExist()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)

            // 保持手指停在下边缘，时钟驱动滚动；抬手后不再继续选择
            compose.mainClock.autoAdvance = false
            val gridBounds = compose.onNodeWithTag("gallery-grid").fetchSemanticsNode().boundsInRoot
            down(point(photos[0]))
            move(Offset(gridBounds.width / 2, gridBounds.height - 8f))
            compose.mainClock.advanceTimeBy(1200)
            up()
            compose.mainClock.autoAdvance = true
            val count = selectedCount()
            assertTrue("边缘滚动应继续选择屏外照片，实际$count", count > 9)
            compose.mainClock.advanceTimeBy(300)
            assertEquals(count, selectedCount())
            compose.onNodeWithContentDescription("清空本次选择").performClick()
            compose.onNodeWithContentDescription("清空本次选择").assertDoesNotExist()
        } finally { if (touching) inject(android.view.MotionEvent.ACTION_CANCEL, lastTouch); compose.mainClock.autoAdvance = true; cleanup() }
    }

    @Test
    fun burstCheckboxAndDragOperateOnWholeGroup() {
        try {
            val photos = seed(4)
            runBlocking { runtime.database.photos().saveAll(photos.take(2).map { it.copy(burstKey = "qa-burst") }) }
            gallery()
            compose.onNodeWithText("连拍分组", substring = false).performClick()
            checkbox(photos[0]).performClick()
            compose.onNodeWithText("已选 2").assertIsDisplayed()
            checkbox(photos[0]).assertIsOn()
            checkbox(photos[0]).performClick()
            compose.onNodeWithContentDescription("清空本次选择").assertDoesNotExist()
            down(point(photos[0]))
            move(point(photos[2]))
            up()
            compose.onNodeWithText("已选 3").assertIsDisplayed()
        } finally { cleanup() }
    }

    @Test
    fun cameraDropHidesRemoteListingButKeepsImportedFileAndReconnects() {
        runtime.disconnect()
        try {
            runtime.connect(ConnectionConfig(host = "127.0.0.1"))
            compose.waitUntil(30000) { runtime.camera.value.info != null }
            compose.onNodeWithTag("tab-gallery").performClick()
            compose.onNodeWithText("相机内").performClick()
            compose.waitUntil(30000) { runtime.currentCameraIds.value.isNotEmpty() }
            val before = runBlocking { runtime.database.photos().byIds(runtime.currentCameraIds.value.toList()) }
            val target = before.firstOrNull { !it.imported } ?: before.first()
            runBlocking { runtime.enqueueDownload(listOf(target.id), DownloadFormat.JPEG).join() }
            compose.waitUntil(30000) { runBlocking { runtime.database.photos().find(target.id)!!.imported } }
            val imported = runBlocking { runtime.database.photos().find(target.id)!! }
            val original = File(imported.files().first { it.originalPath.isNotBlank() }.originalPath)
            val request = URI("http://127.0.0.1:16023/drop").toURL().openConnection() as HttpURLConnection
            try { request.requestMethod = "POST"; request.connectTimeout = 3000; request.readTimeout = 3000; assertEquals(204, request.responseCode) }
            finally { request.disconnect() }
            compose.waitUntil(10000) { runtime.camera.value.info == null }
            assertTrue(runtime.currentCameraIds.value.isEmpty())
            compose.onNodeWithText("相机已断开", substring = false).assertIsDisplayed()
            compose.onNodeWithTag("gallery-grid").assertDoesNotExist()
            acceptanceScreenshot(compose.activity, "gallery-camera-disconnected")
            assertTrue(original.isFile)
            assertEquals(before.size, runBlocking { runtime.database.photos().byIds(before.map { it.id }).size })
            compose.onNodeWithText("已导入").performClick()
            val local = runBlocking { runtime.database.photos().observe().first().filter { it.inLibrary } }
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(local.indexOfFirst { it.id == imported.id })
            compose.onNodeWithTag("photo-${imported.id}").performClick()
            compose.onNodeWithText("已导入原片").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭照片").performClick()
            runtime.connect(ConnectionConfig(host = "127.0.0.1"))
            compose.waitUntil(30000) { runtime.camera.value.info != null }
            compose.onNodeWithText("相机内").performClick()
            compose.waitUntil(30000) { runtime.currentCameraIds.value.isNotEmpty() }
            compose.onNodeWithTag("gallery-grid").assertIsDisplayed()
        } finally { runtime.disconnect() }
    }

    private fun seed(count: Int): List<Photo> = runBlocking {
        val token = UUID.randomUUID().toString().take(8)
        val base = System.currentTimeMillis() + 900_000
        val directory = File(compose.activity.filesDir, "media/qa-gallery/$token").apply { mkdirs() }
        val photos = (0 until count).map { index ->
            val file = File(directory, "QA_${token}_$index.jpg")
            val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            image.eraseColor(0xff000000.toInt() or (file.name.hashCode() and 0xffffff))
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }; image.recycle()
            val id = "qa-gallery:$token:$index"
            Photo(id, id, file.name, PhotoSource.IMPORTED.name, base - index,
                Json.encode(listOf(CameraFile(name = file.name, localUri = file.toURI().toString(), originalPath = file.path, size = file.length()))))
        }
        runtime.database.photos().saveAll(photos)
        owned += photos
        photos
    }

    private fun gallery() {
        if(previousLayout==null) previousLayout=runtime.settings.get("gallery.layout")
        compose.onNodeWithTag("tab-gallery").performClick()
        compose.onNodeWithText("已导入").performClick()
        compose.onNodeWithContentDescription("显示方式").performClick()
        compose.onNodeWithText("小图",substring=false).performClick()
        compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
    }

    private fun checkbox(photo: Photo) = compose.onNodeWithContentDescription("选择 ${photo.name}")

    private fun point(photo: Photo): Offset {
        val photoBounds = compose.onNodeWithContentDescription("查看 ${photo.name}").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("gallery-grid").fetchSemanticsNode().boundsInRoot
        return Offset(photoBounds.left + photoBounds.width / 2 - grid.left, photoBounds.top + photoBounds.width / 2 - grid.top)
    }

    private fun down(position: Offset) {
        touchOrigin = compose.onNodeWithTag("gallery-grid").fetchSemanticsNode().boundsInRoot.topLeft
        downTime = android.os.SystemClock.uptimeMillis()
        inject(android.view.MotionEvent.ACTION_DOWN, position)
        compose.mainClock.advanceTimeBy(700)
    }

    private fun move(position: Offset) { inject(android.view.MotionEvent.ACTION_MOVE, position); compose.mainClock.advanceTimeBy(50) }
    private fun up() { inject(android.view.MotionEvent.ACTION_UP, lastTouch); compose.mainClock.advanceTimeByFrame() }

    private fun inject(action: Int, position: Offset) {
        touching = action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL
        lastTouch = position
        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, position.x + touchOrigin.x, position.y + touchOrigin.y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun selectedCount(): Int {
        val texts = compose.onNodeWithContentDescription("清空本次选择").fetchSemanticsNode().config[SemanticsProperties.Text]
        return texts.first { it.text.startsWith("已选 ") }.text.substringAfter(' ').toInt()
    }

    private fun cleanup() = runBlocking {
        owned.forEach { photo ->
            photo.files().forEach { File(it.originalPath).delete() }
            runtime.database.photos().find(photo.id)?.let { runtime.database.photos().save(it.copy(localHidden = true, filesJson = Json.encode(emptyList<CameraFile>()))) }
        }
        owned.clear()
    }
}
