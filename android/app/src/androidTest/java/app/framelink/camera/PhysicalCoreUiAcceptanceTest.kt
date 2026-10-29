package app.framelink.camera

import android.Manifest
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 实机选片与取景验收；应用内触控模式仅分发到光屿自己的窗口，不依赖系统触控注入 */
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class PhysicalCoreUiAcceptanceTest {
    private var stage = "准备连接"
    private val app get() = ApplicationProvider.getApplicationContext<CameraApp>()
    private val runtime get() = app.runtime
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val appTouch get() = InstrumentationRegistry.getArguments().getString("appTouch") == "true"
    private val clearCacheMethod by lazy {
        runCatching { UiAutomation::class.java.getMethod("clearCache") }.getOrNull()
    }

    private fun connect() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPhysicalCameraRead") == "true")
        check(Build.MODEL in setOf("25113PN0EC", "23013RK75C"))
        val service = automation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
        if (!appTouch) automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        // HyperOS会拦截测试进程从后台启动Activity，使用测试框架的shell身份启动本App
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("am start -W -n app.framelink.camera/.MainActivity")
        ).use { it.readBytes() }
        waitFor(15000) { appRoot()?.packageName == app.packageName }
        rotate(UiAutomation.ROTATION_FREEZE_0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach {
                    it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
        }
        val config = runtime.savedConnection()
        check(config.host.isNotBlank())
        runtime.disconnect(); runtime.connect(config)
        waitFor(45000) { runtime.camera.value.info != null || runtime.cameraIdentityPrompt.value != null }
        assertNull(runtime.cameraIdentityPrompt.value)
        assertEquals("ILCE-7CM2", runtime.camera.value.info!!.model)
        if (!hasText("取景拍摄")) clickText("相机")
    }

    @Test
    fun connectedGallerySelectionDownloadBatchAndReconnectWorkOnPhone() {
        connect()
        val originals = runBlocking { runtime.database.photos().all() }.flatMap { it.files() }.map { it.originalPath }.filter { it.isNotBlank() && File(it).isFile }.distinct()
        val hashes = originals.associateWith { MediaFiles.digest(File(it).inputStream()) }
        val previousLayout = runtime.settings.get("gallery.layout")
        var batchId: String? = null
        try {
            clickText("图库"); clickText("相机内")
            waitFor(45000) { runtime.currentCameraIds.value.isNotEmpty() }
            clickDescription("显示方式"); clickText("中图")
            waitFor(90000) { !runtime.camera.value.loadingPhotos }
            val firstPage = runBlocking { runtime.database.photos().observe().first().filter { it.id in runtime.currentCameraIds.value } }
            val latestId = firstPage.first().id
            var scrolls = 0
            while (runtime.galleryHasMore.value && scrolls++ < 100) {
                nodes().filter { it.isScrollable }.maxByOrNull {
                    Rect().also(it::getBoundsInScreen).height()
                }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(250)
                waitFor(30000) { !runtime.camera.value.loadingPhotos }
            }
            assertFalse("滑到底应自动补齐相机列表", runtime.galleryHasMore.value)
            val page = runBlocking { runtime.database.photos().observe().first().filter { it.id in runtime.currentCameraIds.value } }
            assertTrue(page.size >= 2)
            assertEquals("后续页面不能插入更新照片", latestId, page.first().id)
            assertTrue(page.zipWithNext().all { (left, right) -> left.capturedAt >= right.capturedAt })
            assertFalse(hasText("加载更多"))
            repeat(100) {
                if (findDescription("查看 ${page.first().name}") == null)
                    nodes().filter { it.isScrollable }.maxByOrNull {
                        Rect().also(it::getBoundsInScreen).height()
                    }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            val first = page.first()
            clickDescription("查看 ${first.name}")
            clickText("选择"); waitFor { hasText("已选择") }
            clickDescription("关闭照片")
            waitFor { findDescription("选择 ${first.name}")?.isChecked == true }
            clickDescription("选择 ${page[1].name}"); waitFor { hasText("已选 2") }
            GalleryLayout.entries.forEach { layout ->
                clickDescription("显示方式"); clickText(layout.title)
                waitFor { runtime.settings.get("gallery.layout") == layout.key && hasText("已选 2") }
                assertGridSelection(first.name, backwards = true)
                assertGridSelection(page[1].name)
            }
            screenshot("physical-gallery-list")
            clickDescription("显示方式"); clickText("中图")
            assertGridSelection(first.name, backwards = true)
            clickDescription("清空本次选择")
            waitFor { nodes().none { it.text?.toString()?.matches(Regex("已选 \\d+")) == true } }
            dragBetween(findDescription("查看 ${first.name}")!!, findDescription("查看 ${page[1].name}")!!)
            screenshot("physical-drag-result")
            waitFor { hasText("已选 2") }
            screenshot("physical-connected-selection")
            clickText("保存")
            val title = "真机验收选片-${System.currentTimeMillis()}"
            waitFor { nodes().any { it.isEditable } }
            assertTrue(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title) }))
            clickText("保存选片")
            waitFor { runBlocking { runtime.database.batches().all().any { it.title == title } } }
            val batch = runBlocking { runtime.database.batches().all().first { it.title == title } }; batchId = batch.id
            val sheets = runBlocking { runtime.batches.contactSheets(batch) }
            assertTrue(sheets.isNotEmpty() && sheets.all { it.length() > 1000 })
            clickDescription("关闭选片记录")
            if (!hasText("相机内")) clickText("图库")
            val download = page.firstOrNull { !it.imported && it.downloadFiles(DownloadFormat.JPEG).isNotEmpty() } ?: first
            val format = if (download.downloadFiles(DownloadFormat.JPEG).isNotEmpty()) DownloadFormat.JPEG else DownloadFormat.RAW
            runBlocking { runtime.enqueueDownload(listOf(download.id), format).join() }
            waitFor(120000) { runBlocking { runtime.database.photos().find(download.id)!!.imported } }
            val imported = runBlocking { runtime.database.photos().find(download.id)!! }
            val output = imported.files().first { it.originalPath.isNotBlank() }
            assertEquals(output.size, File(output.originalPath).length())
            runtime.disconnect()
            waitFor { hasText("相机已断开") }
            assertFalse(nodes().any { it.contentDescription?.toString() == "查看 ${first.name}" })
            assertTrue(File(output.originalPath).isFile)
            clickText("已导入")
            // 断线后直接使用已经下载的本机原片，原始字节保持完整
            assertEquals(output.size, File(output.originalPath).length())
            clickText("相机"); clickText("连接相机")
            waitFor(45000) { runtime.camera.value.info != null }
            assertFalse("已保存相机应直接重连，不打开设置页", hasText("相机IP地址"))
            hashes.forEach { (file, hash) -> assertEquals(hash, MediaFiles.digest(File(file).inputStream())) }
            report("core-ui.json", JSONObject().put("camera", "ILCE-7CM2").put("firmware", runtime.camera.value.info!!.firmware)
                .put("listed", page.size).put("selected", 2).put("dragSelection", true).put("downloadedBytes", File(output.originalPath).length())
                .put("preexistingOriginalsVerified", hashes.size).put("batchPages", sheets.size).put("reconnect", true)
                .put("automaticPagination", true).put("newestFirst", true).put("firstPhoto", first.name)
                .put("directReconnect", true).put("layoutsPreserveSelection", GalleryLayout.entries.map { it.key }))
        } finally {
            runtime.settings.put("gallery.layout", previousLayout)
            batchId?.let { runBlocking { runtime.batches.delete(it) } }
            rotate(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    @Test
    fun liveLabelsAndFooterStayVisibleAndApertureRestoresExactly() {
        connect()
        var aperture: CameraProperty? = null
        var changed = false
        try {
            clickText("取景拍摄")
            waitFor(30000) { runtime.frame.value?.isNotEmpty() == true }
            dismissFullscreenEducation()
            waitFor { hasText("光圈") }
            waitForFullWindow()
            assertFalse("主取景页不常驻协议型号", hasText("ILCE-7CM2"))
            screenshot("physical-live-portrait-camera")
            clickText("专业工具")
            val focus = runtime.properties.value.firstOrNull { it.code == 0x500A }
            if (focus != null && CameraPropertyText.editable(focus)) {
                // 专业参数在独立面板中纵向滚动
                repeat(4) {
                    if (!hasText("对焦模式")) {
                        nodes().firstOrNull { it.isScrollable }
                            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(250)
                    }
                }
                if (hasText("对焦模式")) {
                    clickText("对焦模式")
                    waitFor { hasText("连续自动对焦（AF-C）") }
                    assertFalse(hasText("32772")); clickText("关闭")
                }
            }
            screenshot("physical-live-chinese")
            clickDescription("收起工具")
            val metrics = app.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
            val safeBottom = metrics.bounds.height() - metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom
            val bounds = Rect(); findDescription("拍摄")!!.getBoundsInScreen(bounds)
            screenshot("physical-live-footer-check")
            report("footer-check.json", JSONObject().put("shutter", bounds.toShortString()).put("window", metrics.bounds.toShortString()).put("safeBottom", safeBottom))
            assertTrue("快门应位于系统手势区上方，快门=$bounds，安全底部=$safeBottom，窗口=${metrics.bounds}", bounds.bottom < safeBottom - 12)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; val frame = runtime.frame.value!!
            BitmapFactory.decodeByteArray(frame, 0, frame.size, options)
            clickDescription("沉浸取景"); waitFor { findDescription("显示取景控制") != null }
            clickDescription("显示取景控制")
            assertTrue(rotate(UiAutomation.ROTATION_FREEZE_90))
            waitFor { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && findDescription("沉浸取景") != null }
            val landscapeBounds = waitForFullWindow()
            val screen = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            clickDescription("沉浸取景")
            waitFor { findDescription("显示取景控制")?.stateDescription == "取景画面已显示" }
            // 无障碍矩形可能向下取整，允许两个物理像素的边界舍入差
            assertTrue("横屏全屏窗口必须覆盖实际屏幕，屏幕=$screen，窗口=$landscapeBounds",
                kotlin.math.abs(screen.left-landscapeBounds.left) <= 2 && kotlin.math.abs(screen.top-landscapeBounds.top) <= 2 &&
                    kotlin.math.abs(screen.right-landscapeBounds.right) <= 2 && kotlin.math.abs(screen.bottom-landscapeBounds.bottom) <= 2)
            screenshot("physical-live-landscape")
            clickDescription("显示取景控制")
            waitFor { findDescription("拍摄") != null && hasText("专业工具") }
            val menuBounds = Rect(); appRoot()!!.getBoundsInScreen(menuBounds)
            assertEquals("显示菜单不能挤小取景窗口", landscapeBounds, menuBounds)
            screenshot("physical-live-landscape-glass")
            clickText("专业工具")
            screenshot("physical-live-landscape-tools")
            clickDescription("收起工具")
            assertTrue(rotate(UiAutomation.ROTATION_FREEZE_0))
            waitFor { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT && findDescription("沉浸取景") != null }
            aperture = runtime.properties.value.firstOrNull { it.code == 0x5007 && it.writable }
            aperture?.let { property ->
                property.choices.firstOrNull { it.value != property.value }?.let { next ->
                    check(appRoot()?.packageName == app.packageName) { "手机已切换应用，停止参数测试" }
                    report("parameter-before.json", JSONObject().put("code", property.code).put("value", property.value))
                    changed = true
                    runBlocking { runtime.setProperty(property, next.value).join() }
                    assertEquals(next.value, runtime.native.properties().first { it.code == property.code }.value)
                    runtime.native.setProperty(property.code, property.value)
                    waitFor(5000) { runtime.native.properties().first { it.code == property.code }.value == property.value }
                }
            }
            report("live-ui.json", JSONObject().put("frameWidth", options.outWidth).put("frameHeight", options.outHeight)
                .put("shutterBottom", bounds.bottom).put("safeBottom", safeBottom).put("apertureRestored", changed))
            clickDescription("返回相机")
        } finally {
            rotate(UiAutomation.ROTATION_UNFREEZE)
            if (changed) aperture?.let { original ->
                runtime.native.setProperty(original.code, original.value)
                waitFor(5000) { runtime.native.properties().first { it.code == original.code }.value == original.value }
            }
        }
    }

    private fun appRoot(): AccessibilityNodeInfo? {
        if (appTouch) {
            val focused = automation.windows.firstOrNull { it.isFocused }?.root
            if (focused != null) return focused.takeIf { it.packageName == app.packageName }
        }
        return automation.rootInActiveWindow?.takeIf { it.packageName == app.packageName }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        clearCacheMethod?.invoke(automation)
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { if (node.isVisibleToUser) result += node; for (index in 0 until node.childCount) node.getChild(index)?.let(::visit) }
        appRoot()?.takeIf { it.packageName == app.packageName }?.let(::visit)
        return result
    }
    private fun hasText(text: String) = nodes().any { it.text?.toString() == text }
    private fun findDescription(description: String): AccessibilityNodeInfo? {
        val all = nodes()
        return all.firstOrNull { it.contentDescription?.toString() == description }
            ?: all.firstOrNull { it.contentDescription?.toString()?.startsWith(description) == true }
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && current.packageName == app.packageName) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }
    private fun clickText(text: String) {
        stage = "点击文字：$text"
        android.util.Log.i("FrameLinkPhysicalQA", stage)
        waitFor { nodes().any { it.text?.toString() == text && clickable(it) != null } }
        tap(clickable(nodes().first { it.text?.toString() == text && clickable(it) != null })!!)
        SystemClock.sleep(200)
    }
    private fun clickDescription(description: String) {
        stage = "点击控件：$description"
        android.util.Log.i("FrameLinkPhysicalQA", stage)
        waitFor { findDescription(description)?.let(::clickable) != null }
        tap(clickable(findDescription(description)!!)!!); SystemClock.sleep(200)
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        check(!bounds.isEmpty)
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            check(appRoot()?.packageName == app.packageName)
            val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(dispatch(motion)) } finally { motion.recycle() }
            SystemClock.sleep(60)
        }
    }
    private fun waitFor(timeout: Long = 15000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { if (SystemClock.elapsedRealtime() >= end) throw AssertionError("等待手机界面或相机状态超时，步骤=$stage，前台=${appRoot()?.packageName}，可见操作=${nodes().mapNotNull { it.text?.toString() }.distinct()}"); SystemClock.sleep(100) }
    }
    private fun dragBetween(first: AccessibilityNodeInfo, second: AccessibilityNodeInfo) {
        val a = Rect(); val b = Rect(); first.getBoundsInScreen(a); second.getBoundsInScreen(b)
        swipe(a.centerX().toFloat(), a.top + a.width() / 2f, b.centerX().toFloat(), b.top + b.width() / 2f, true)
    }
    private fun assertGridSelection(name: String, backwards: Boolean = false) {
        repeat(8) {
            findDescription("选择 $name")?.let { node ->
                assertTrue("显示方式切换后应保留选择：$name", node.isChecked)
                return
            }
            nodes().filter { it.isScrollable }.maxByOrNull { node -> Rect().apply { node.getBoundsInScreen(this) }.height() }?.performAction(
                if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        }
        error("滚动后未找到照片：$name")
    }
    private fun swipe(x: Float, y: Float, endX: Float, endY: Float, longPress: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, px: Float, py: Float) {
            check(appRoot()?.packageName == app.packageName) { "手机已切换应用，停止触控" }
            val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, px, py, 0)
            motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(dispatch(motion)) } finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(if (longPress) 650 else 30)
        for (i in 1..8) { event(MotionEvent.ACTION_MOVE, x + (endX - x) * i / 8, y + (endY - y) * i / 8); SystemClock.sleep(30) }
        event(MotionEvent.ACTION_UP, endX, endY); SystemClock.sleep(250)
    }
    private fun screenshot(name: String) {
        check(appRoot()?.packageName == app.packageName) { "手机已切换应用，停止截图" }
        val bitmap = automation.takeScreenshot() ?: error("手机截图不可用")
        val file = File(app.getExternalFilesDir(null), "qa-shots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun rotate(rotation: Int): Boolean {
        if (!appTouch) return automation.setRotation(rotation)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach {
                    it.requestedOrientation = when (rotation) {
                        UiAutomation.ROTATION_FREEZE_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        UiAutomation.ROTATION_FREEZE_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
        }
        return true
    }

    private fun waitForFullWindow(): Rect {
        val expected = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val actual = Rect()
        val until = SystemClock.uptimeMillis() + 5000
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < until) {
            appRoot()?.takeIf { it.packageName == app.packageName }?.getBoundsInScreen(actual)
            val full = kotlin.math.abs(expected.left-actual.left) <= 2 && kotlin.math.abs(expected.top-actual.top) <= 2 &&
                kotlin.math.abs(expected.right-actual.right) <= 2 && kotlin.math.abs(expected.bottom-actual.bottom) <= 2
            if (full) {
                if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() - stableSince >= 300) return Rect(actual)
            } else stableSince = 0
            SystemClock.sleep(100)
        }
        report("window-size-failure.json", JSONObject().put("screen", expected.toShortString()).put("window", actual.toShortString()))
        screenshot("physical-window-size-failure")
        throw AssertionError("全屏窗口未覆盖屏幕，屏幕=$expected，窗口=$actual")
    }

    private fun dispatch(motion: MotionEvent): Boolean {
        if (!appTouch) return automation.injectInputEvent(motion, true)
        var dispatched = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = checkNotNull(WindowInspector.getGlobalWindowViews().lastOrNull {
                it.hasWindowFocus() && it.isShown && it.context.applicationContext.packageName == app.packageName
            }) { "光屿窗口未聚焦，停止应用内触控" }
            val origin = IntArray(2).also(root::getLocationOnScreen)
            val local = MotionEvent.obtain(motion)
            try {
                local.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                dispatched = root.dispatchTouchEvent(local)
            } finally { local.recycle() }
        }
        return dispatched
    }
    private fun report(name: String, value: JSONObject) {
        value.put("phoneModel", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("inputMode", if (appTouch) "app-window-dispatch" else "system-injection")
        File(app.getExternalFilesDir(null), "physical-read/$name").apply { parentFile!!.mkdirs() }.writeText(value.toString(2))
    }
}
