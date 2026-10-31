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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.After
import org.junit.Test

/** 实机选片与取景验收；应用内触控模式仅分发到光屿自己的窗口，不依赖系统触控注入 */
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class PhysicalCoreUiAcceptanceTest {
    companion object { private const val GUARDED_CAPTURE_VERSION = 10 }
    private var stage = "准备连接"
    private val app get() = ApplicationProvider.getApplicationContext<CameraApp>()
    private val runtime get() = app.runtime
    private var automationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().getUiAutomation(automationFlags)
    private val appTouch get() = InstrumentationRegistry.getArguments().getString("appTouch") == "true"
    private val clearCacheMethod by lazy {
        runCatching { UiAutomation::class.java.getMethod("clearCache") }.getOrNull()
    }
    private val windowFlags = mutableListOf<Pair<android.view.Window, Boolean>>()
    private var touchWindowId: Int? = null

    @After fun restoreScreenPolicy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            windowFlags.forEach { (window, keptOn) ->
                if (!keptOn) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        rotate(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test fun localGallerySelectionAndPagingDoNotRequireCameraOrSystemTouchInjection(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPhysicalGallery") == "true")
        check(Build.MODEL in setOf("25113PN0EC","23013RK75C"))
        check(appTouch){"本地图库实机验收只允许应用内触控"}
        val service=automation.serviceInfo
        service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo=service
        check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked){"设备锁屏，停止验收"}
        val foreground=automation.rootInActiveWindow?.packageName?.toString()
        check(foreground==app.packageName||foreground=="com.miui.home"){"其他应用位于前台，停止验收"}
        if(foreground!=app.packageName){
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n app.framelink.camera/.MainActivity")).use{it.readBytes()}
            waitFor{appRoot()?.packageName==app.packageName}
        }
        val connectedBefore=runtime.camera.value.info?.id
        var name:String?=null
        var restoreSelection=false
        try{
            clickDescription("图库")
            clickText("已导入")
            waitFor{nodes().any{it.contentDescription?.startsWith("查看 ")==true}}
            name=nodes().first{it.contentDescription?.startsWith("查看 ")==true}.contentDescription.toString().removePrefix("查看 ")
            clickDescription("查看 $name")
            waitFor{findDescription("选择当前照片")!=null}
            if(findDescription("选择当前照片")?.isChecked!=true){
                clickDescription("选择当前照片")
                restoreSelection=true
            }
            waitFor{findDescription("选择当前照片")?.isChecked==true}
            screenshot("local-gallery-detail-selected")
            clickDescription("关闭照片")
            waitFor{findDescription("选择 $name")?.isChecked==true}
            screenshot("local-gallery-browse-selected")
            clickDescription("查看 $name")
            waitFor{findDescription("选择当前照片")?.isChecked==true}
            val image=nodes().filter{it.contentDescription?.toString()==name}.maxByOrNull{
                Rect().apply{it.getBoundsInScreen(this)}.let{bounds->bounds.width().toLong()*bounds.height()}
            }?:error("大图未显示")
            val bounds=Rect().also(image::getBoundsInScreen)
            touchWindowId=image.windowId
            if(runBlocking{runtime.database.photos().all().count{it.inLibrary}}>1){
                swipe(bounds.left+bounds.width()*.8f,bounds.exactCenterY(),bounds.left+bounds.width()*.2f,bounds.exactCenterY())
                SystemClock.sleep(500)
                swipe(bounds.left+bounds.width()*.2f,bounds.exactCenterY(),bounds.left+bounds.width()*.8f,bounds.exactCenterY())
                waitFor{nodes().any{it.contentDescription?.toString()==name}&&findDescription("选择当前照片")?.isChecked==true}
            }
            rotate(UiAutomation.ROTATION_FREEZE_90)
            waitFor{app.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            waitFor{findDescription("选择当前照片")?.isChecked==true}
            screenshot("local-gallery-landscape")
            rotate(UiAutomation.ROTATION_FREEZE_0)
            waitFor{app.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
            waitFor{findDescription("选择当前照片")?.isChecked==true}
            assertEquals(connectedBefore,runtime.camera.value.info?.id)
        }finally{
            if(restoreSelection&&name!=null){
                check(appRoot()?.packageName==app.packageName){"前台变化，停止恢复测试选择"}
                if(findDescription("关闭照片")!=null)clickDescription("关闭照片")
                clickDescription("查看 $name")
                waitFor{findDescription("选择当前照片")!=null}
                if(findDescription("选择当前照片")?.isChecked==true)clickDescription("选择当前照片")
                waitFor{findDescription("选择当前照片")?.isChecked==false}
            }
            if(findDescription("关闭照片")!=null)clickDescription("关闭照片")
        }
        report("local-gallery-result.json",JSONObject().put("passed",true).put("model",Build.MODEL)
            .put("version",BuildConfig.VERSION_NAME).put("cameraSessionPreserved",true))
    }

    private fun connect(synchronizeCaptureStorage:Boolean=true) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPhysicalCameraRead") == "true")
        check(Build.MODEL in setOf("25113PN0EC", "23013RK75C", "2410CRP4CC"))
        val service = automation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
        if (!appTouch) automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        // HyperOS会拦截测试进程从后台启动Activity，使用测试框架的shell身份启动本App
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("am start -W -n app.framelink.camera/.MainActivity")
        ).use { it.readBytes() }
        waitFor(15000) { appRoot()?.packageName == app.packageName }
        if(Build.MODEL!="2410CRP4CC")rotate(UiAutomation.ROTATION_FREEZE_0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach {
                    windowFlags += it.window to (it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                    it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
        }
        val config = runtime.savedConnection()
        check(config.host.isNotBlank())
        runtime.disconnect(); runtime.connect(config,synchronizeCaptureStorage=synchronizeCaptureStorage)
        waitFor(45000) { runtime.camera.value.info != null || runtime.cameraIdentityPrompt.value != null }
        assertNull(runtime.cameraIdentityPrompt.value)
        assertEquals("ILCE-7CM2", runtime.camera.value.info!!.model)
        waitFor(15000){appRoot()?.packageName==app.packageName&&nodes().isNotEmpty()}
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
            runtime.selectCameraDay("")
            var stableIds: Set<String>? = null
            var stableSince = 0L
            waitFor(90000) {
                val ids = runtime.currentCameraIds.value
                if (runtime.camera.value.loadingPhotos || ids.isEmpty() || runtime.galleryDay.value.isNotBlank()) stableSince = 0
                else if (ids != stableIds) { stableIds = ids; stableSince = SystemClock.uptimeMillis() }
                stableSince > 0 && SystemClock.uptimeMillis() - stableSince >= 700
            }
            clickDescription("显示方式"); clickText("中图")
            waitFor(90000) { !runtime.camera.value.loadingPhotos }
            val firstPage = runBlocking { runtime.database.photos().observe().first().filter { it.id in runtime.currentCameraIds.value } }
            val latestId = firstPage.first().id
            var scrolls = 0
            while (runtime.galleryHasMore.value && runtime.currentCameraIds.value.size <= firstPage.size && scrolls++ < 24) {
                nodes().filter { it.isScrollable }.maxByOrNull {
                    Rect().also(it::getBoundsInScreen).height()
                }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(250)
                waitFor(30000) { !runtime.camera.value.loadingPhotos }
            }
            val paginationCount = runtime.currentCameraIds.value.size
            assertTrue("接近底部应自动读取后续页面", !runtime.galleryHasMore.value || paginationCount > firstPage.size)
            val expanded = runBlocking { runtime.database.photos().observe().first().filter { it.id in runtime.currentCameraIds.value } }
            assertEquals("后续页面不能插入更新照片", latestId, expanded.first().id)
            runtime.browseRecent()
            waitFor(90000) { runtime.currentCameraIds.value.isNotEmpty() && !runtime.camera.value.loadingPhotos && runtime.galleryDay.value == runtime.captureDates.value.firstOrNull().orEmpty() }
            scrolls = 0
            while (runtime.galleryHasMore.value && scrolls++ < 100) {
                nodes().filter { it.isScrollable }.maxByOrNull { Rect().also(it::getBoundsInScreen).height() }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(250)
                waitFor(30000) { !runtime.camera.value.loadingPhotos }
            }
            assertFalse("当前日期读完应停止加载", runtime.galleryHasMore.value)
            val page = runBlocking { runtime.database.photos().observe().first().filter { it.id in runtime.currentCameraIds.value } }
            assertTrue(page.size >= 2)
            assertEquals("后续页面不能插入更新照片", latestId, page.first().id)
            assertTrue(page.zipWithNext().all { (left, right) -> left.capturedAt >= right.capturedAt })
            assertFalse(hasText("加载更多"))
            repeat(100) {
                if (findDescription("查看 ${page.first().name}") == null) {
                    nodes().filter { it.isScrollable }.maxByOrNull {
                        Rect().also(it::getBoundsInScreen).height()
                    }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    SystemClock.sleep(250)
                }
            }
            val first = page.first()
            clickDescription("查看 ${first.name}")
            clickDescription("选择当前照片"); waitFor { findDescription("选择当前照片")?.isChecked==true }
            clickDescription("关闭照片")
            clickDescription("选择照片")
            waitFor { findDescription("选择 ${first.name}")?.isChecked == true }
            clickDescription("选择 ${page[1].name}"); waitFor { hasSelectionCount(2) }
            GalleryLayout.entries.forEach { layout ->
                clickDescription("显示方式"); clickText(layout.title)
                waitFor { runtime.settings.get("gallery.layout") == layout.key && hasSelectionCount(2) }
                assertGridSelection(first.name, backwards = true)
                assertGridSelection(page[1].name)
            }
            screenshot("physical-gallery-list")
            clickDescription("显示方式"); clickText("中图")
            assertGridSelection(first.name, backwards = true)
            clickDescription("清空本次选择")
            clickText("清空选择")
            waitFor { hasSelectionCount(0) }
            dragBetween(findDescription("查看 ${first.name}")!!, findDescription("查看 ${page[1].name}")!!)
            screenshot("physical-drag-result")
            waitFor { hasSelectionCount(2) }
            screenshot("physical-connected-selection")
            clickText("存选片")
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
                .put("firstPageCount", firstPage.size).put("paginationListed", paginationCount).put("automaticPagination", paginationCount > firstPage.size)
                .put("endScope", runtime.galleryDay.value).put("endScopeHasMore", false)
                .put("newestFirst", true).put("firstPhoto", first.name)
                .put("directReconnect", true).put("layoutsPreserveSelection", GalleryLayout.entries.map { it.key }))
        } finally {
            runtime.settings.put("gallery.layout", previousLayout)
            batchId?.let { runBlocking { runtime.batches.delete(it) } }
            rotate(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    @Test
    fun singleShotFinishesWithoutImportAndAlbumKeepsConnection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPhysicalCapture") == "true")
        check(app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode >= GUARDED_CAPTURE_VERSION) {
            "请先安装具备单张保护的0.1.8或更新版本，禁止在旧主包触发实拍"
        }
        connect()
        val previousSingle = runtime.settings.get("capture.single")
        val previousImport = runtime.settings.get("capture.auto_import")
        val before = runtime.native.newestPage(1, "", 0).items.map { it.id }.toSet()
        val drive = runtime.native.shootingState().takeIf { it.has("driveMode") }?.getLong("driveMode")
        report("capture-before.json", JSONObject().put("driveBefore", drive).put("autoImport", false).put("newShotLimit", 1))
        val previousPhoto = runtime.lastCapture.value
        try {
            runtime.settings.put("capture.single", "true")
            runtime.settings.put("capture.auto_import", "false")
            clickText("取景拍摄")
            waitFor(30000) { runtime.frame.value?.isNotEmpty() == true }
            dismissFullscreenEducation()
            if (runtime.camera.value.info?.canFocus == true) {
                val focusError = runCatching { runtime.native.focusAt(.5f, .5f) }.exceptionOrNull()?.message
                report("focus-ui.json", JSONObject().put("requestAccepted", focusError == null)
                    .put("unavailableReason", focusError.orEmpty()).put("opticalFocusConfirmed", false))
                if (focusError == null) {
                    val afterFocus = runtime.native.newestPage(1, "", 0).items.map { it.id }.toSet()
                    assertEquals("点按对焦不能触发拍摄", before, afterFocus)
                }
            }
            val started = SystemClock.elapsedRealtime()
            clickDescription("拍摄")
            waitFor(30000) { !runtime.captureBusy.value }
            val shutterMs = SystemClock.elapsedRealtime() - started
            assertFalse("默认拍摄不能启动原片接收", runtime.postCaptureBusy.value)
            assertEquals(previousPhoto, runtime.lastCapture.value)
            val afterDrive = runtime.native.shootingState().takeIf { it.has("driveMode") }?.getLong("driveMode")
            assertNotNull("机身需提供可独立核对的驱动状态", drive)
            assertEquals("拍摄完成后应恢复机身驱动模式", drive, afterDrive)
            clickDescription("查看相机照片")
            waitFor(90000) { runtime.currentCameraIds.value.isNotEmpty() && !runtime.camera.value.loadingPhotos }
            assertNotNull("进入相册不能断开相机", runtime.camera.value.info)
            var added = emptyList<RemotePhoto>()
            repeat(6) {
                if (added.isEmpty()) {
                    added = runtime.native.newestPage(1, "", 0).items.filter { it.id !in before }
                    if (added.isEmpty()) SystemClock.sleep(600)
                }
            }
            assertEquals("一次轻按应只新增一次拍摄", 1, added.size)
            report("capture-ui.json", JSONObject().put("newShots", added.size).put("newFile", added.first().files.first().name)
                .put("shutterMs", shutterMs).put("autoImport", false).put("albumConnected", true)
                .put("driveBefore", drive).put("driveAfter", afterDrive))
        } finally {
            runtime.settings.put("capture.single", previousSingle)
            runtime.settings.put("capture.auto_import", previousImport)
        }
    }

    /** 实机核对完整数量和JPEG临时预览；不导入、不操作机身拍摄设置 */
    @Test fun galleryTotalAndOriginalZoomStayInDetailAcrossRotation(){
        check(Build.MODEL=="25113PN0EC"){"本轮原片细节验收仅允许小米17"}
        connect(synchronizeCaptureStorage=false)
        try{
            clickText("图库");clickText("相机内")
            runtime.selectCameraDay("")
            waitFor(90000){runtime.currentCameraIds.value.isNotEmpty()&&!runtime.camera.value.loadingPhotos&&!runtime.galleryHasMore.value}
            val total=requireNotNull(runtime.galleryTotalCount.value)
            assertEquals(total,runtime.currentCameraIds.value.size)
            waitFor{hasText("共 $total 张")}
            screenshot("physical-gallery-total")
            val rows=runBlocking{runtime.database.photos().observe().first().filter{it.id in runtime.currentCameraIds.value}}
            val photo=rows.take(6).first{!it.imported&&it.downloadFiles(DownloadFormat.JPEG).isNotEmpty()}
            val pending=rows.count{it.hasUnimportedFiles()}
            val beforeTasks=runBlocking{runtime.database.tasks().observe().first().map{it.id}.toSet()}
            val preview=runBlocking{runtime.ensurePreview(photo,true)}
            val previewBounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(preview,previewBounds)
            clickDescription("查看 ${photo.name}")
            val selected=findDescription("选择当前照片")?.isChecked
            if(!hasText("JPEG原片预览 · 未导入"))clickDescription("查看JPEG原片预览")
            waitFor(120000){runtime.cachedOriginalPreview(photo)!=null}
            waitFor{hasText("JPEG原片预览 · 未导入")}
            val cached=requireNotNull(runtime.cachedOriginalPreview(photo))
            assertEquals(photo.filesJson,runBlocking{runtime.database.photos().find(photo.id)!!.filesJson})
            assertEquals(beforeTasks,runBlocking{runtime.database.tasks().observe().first().map{it.id}.toSet()})
            assertEquals(pending,runBlocking{runtime.database.photos().observe().first().filter{it.id in runtime.currentCameraIds.value}.count{it.hasUnimportedFiles()}})
            val originalBounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(cached,originalBounds)
            assertTrue(originalBounds.outWidth>previewBounds.outWidth&&originalBounds.outHeight>previewBounds.outHeight)
            assertTrue("原片预览不能切换当前照片",nodes().any{it.text?.toString()?.endsWith(photo.displayTitle())==true})
            val image=requireNotNull(nodes().filter{it.contentDescription?.toString()==photo.name}.maxByOrNull{
                val area=Rect().also(it::getBoundsInScreen);area.width().toLong()*area.height()
            })
            val bounds=Rect().also(image::getBoundsInScreen)
            touchWindowId=image.windowId
            repeat(2){
                val started=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                    check(appRoot()?.packageName==app.packageName){"光屿不在前台，停止放大操作"}
                    val event=MotionEvent.obtain(started,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try{assertTrue(dispatch(event))}finally{event.recycle()}
                    SystemClock.sleep(40)
                }
                SystemClock.sleep(60)
            }
            waitFor(15000){findDescription("原片细节已显示")!=null}
            assertTrue("放大不能切换照片",nodes().any{it.text?.toString()?.endsWith(photo.displayTitle())==true})
            screenshot("physical-original-detail-portrait")
            assertTrue(rotate(UiAutomation.ROTATION_FREEZE_90))
            waitFor{app.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE&&findDescription("原片细节已显示")!=null}
            screenshot("physical-original-detail-landscape")
            assertTrue(rotate(UiAutomation.ROTATION_FREEZE_0))
            waitFor{app.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT&&findDescription("原片细节已显示")!=null}
            assertEquals(selected,findDescription("选择当前照片")?.isChecked)
            report("gallery-original-detail.json",JSONObject().put("total",total).put("file",photo.name)
                .put("previewWidth",previewBounds.outWidth).put("previewHeight",previewBounds.outHeight)
                .put("originalWidth",originalBounds.outWidth).put("originalHeight",originalBounds.outHeight)
                .put("originalBytes",File(cached).length()).put("regionDisplayed",true).put("temporaryCacheOnly",true)
                .put("pendingImportsUnchanged",true).put("importTaskCreated",false)
                .put("rotationPreservedDetail",true).put("selectionUnchanged",true).put("shutterTriggered",false))
        }finally{touchWindowId=null;rotate(UiAutomation.ROTATION_UNFREEZE);runtime.disconnect()}
    }

    @Test fun photoExifUiUsesExistingFileWithoutImport(){
        check(Build.MODEL=="25113PN0EC"){"本轮照片EXIF验收仅允许小米17"}
        connect(synchronizeCaptureStorage=false)
        try{
            clickText("相机照片");waitFor{hasText("相机内")};runtime.selectCameraDay("")
            waitFor(90000){runtime.currentCameraIds.value.isNotEmpty()&&!runtime.camera.value.loadingPhotos&&!runtime.galleryHasMore.value}
            val rows=runBlocking{runtime.database.photos().observe().first().filter{it.id in runtime.currentCameraIds.value}}
            val photo=rows.take(12).first{runtime.cachedOriginalPreview(it)!=null}
            val metadata=requireNotNull(PhotoExifReader.read(app,photo,runtime.cachedOriginalPreview(photo)))
            assertTrue("真实文件需包含核心拍摄参数",metadata.hasExposure)
            val tasks=runBlocking{runtime.database.tasks().all().map{it.id}.toSet()}
            clickDescription("查看 ${photo.name}");clickDescription("文件信息")
            waitFor{findDescription("ISO：${metadata.iso}")!=null&&findDescription("快门速度：${metadata.shutter}")!=null&&findDescription("光圈：${metadata.aperture}")!=null}
            screenshot("physical-photo-exif-exposure")
            repeat(5){
                val location=metadata.location?.coordinates?:"未记录"
                if(!hasText(location))nodes().filter{it.isScrollable}.maxByOrNull{Rect().also(it::getBoundsInScreen).height()}
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(150)
            }
            assertTrue(hasText(metadata.location?.coordinates?:"未记录"))
            screenshot("physical-photo-exif-location")
            assertEquals(photo.filesJson,runBlocking{runtime.database.photos().find(photo.id)!!.filesJson})
            assertEquals(tasks,runBlocking{runtime.database.tasks().all().map{it.id}.toSet()})
            report("photo-exif-ui.json",JSONObject().put("file",photo.name).put("source",metadata.source)
                .put("iso",metadata.iso).put("shutter",metadata.shutter).put("aperture",metadata.aperture)
                .put("focalLength",metadata.focalLength).put("camera",metadata.camera).put("lens",metadata.lens)
                .put("takenAt",metadata.takenAt).put("hasLocation",metadata.location!=null)
                .put("fileStateUnchanged",true).put("importTaskCreated",false).put("shutterTriggered",false))
            clickText("完成");clickDescription("关闭照片")
        }finally{runtime.disconnect()}
    }

    /** 只读核对取景快捷参数的属性、候选与可写状态，不提交机身设置 */
    @Test fun liveQuickParameterDescriptorsAreReadable(){
        check(Build.MODEL=="2410CRP4CC"){"本轮参数排查仅允许小米平板7"}
        connect(synchronizeCaptureStorage=false)
        try{
            clickText("取景拍摄")
            waitFor(30000){runtime.frame.value?.isNotEmpty()==true}
            dismissFullscreenEducation()
            clickDescription("专业模式")
            val relevant=runtime.properties.value.filter{it.code in CameraPropertyText.exposureOrder}
            report("live-parameter-descriptors.json",JSONObject().put("properties",org.json.JSONArray(relevant.map{
                JSONObject().put("code",it.code).put("value",it.value).put("writable",it.writable)
                    .put("rawChoiceCount",it.choices.size).put("recognizedChoiceCount",CameraPropertyText.choices(it).size)
                    .put("selectedForQuick",it in CameraPropertyText.quick(runtime.properties.value))
            })))
            screenshot("physical-tablet-parameters-before")
            val iso=requireNotNull(findDescription("调整感光度"))
            val bounds=Rect().also(iso::getBoundsInScreen)
            touchWindowId=iso.windowId
            val started=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                check(appRoot()?.packageName==app.packageName){"光屿不在前台，停止ISO触控"}
                val event=MotionEvent.obtain(started,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try{assertTrue(dispatch(event))}finally{event.recycle()}
                SystemClock.sleep(60)
            }
            waitFor{findDescription("关闭参数")!=null}
            screenshot("physical-tablet-iso-panel")
            clickDescription("关闭参数")
        }finally{runtime.disconnect()}
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
            clickDescription("专业模式")
            waitFor { findDescription("调整光圈")!=null }
            waitForFullWindow()
            assertFalse("主取景页不常驻协议型号", hasText("ILCE-7CM2"))
            screenshot("physical-live-portrait-camera")
            clickDescription("更多控制")
            clickText("机身参数")
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
                    assertFalse(hasText("32772")); clickDescription("关闭参数");clickDescription("更多控制")
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
            clickDescription("更多控制");clickDescription("沉浸取景"); waitFor { findDescription("显示取景控制") != null }
            clickDescription("显示取景控制")
            assertTrue(rotate(UiAutomation.ROTATION_FREEZE_90))
            waitFor { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && findDescription("更多控制") != null }
            val landscapeBounds = waitForFullWindow()
            val screen = app.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            clickDescription("更多控制");clickDescription("沉浸取景")
            waitFor { findDescription("取景画面，点按对焦")?.stateDescription == "取景画面已显示" }
            // 无障碍矩形可能向下取整，允许两个物理像素的边界舍入差
            assertTrue("横屏全屏窗口必须覆盖实际屏幕，屏幕=$screen，窗口=$landscapeBounds",
                kotlin.math.abs(screen.left-landscapeBounds.left) <= 2 && kotlin.math.abs(screen.top-landscapeBounds.top) <= 2 &&
                    kotlin.math.abs(screen.right-landscapeBounds.right) <= 2 && kotlin.math.abs(screen.bottom-landscapeBounds.bottom) <= 2)
            screenshot("physical-live-landscape")
            clickDescription("显示取景控制")
            waitFor { findDescription("拍摄") != null && findDescription("更多控制") != null }
            val menuBounds = Rect(); appRoot()!!.getBoundsInScreen(menuBounds)
            assertEquals("显示菜单不能挤小取景窗口", landscapeBounds, menuBounds)
            screenshot("physical-live-landscape-glass")
            clickDescription("更多控制")
            screenshot("physical-live-landscape-tools")
            clickDescription("收起工具")
            assertTrue(rotate(UiAutomation.ROTATION_FREEZE_0))
            waitFor { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT && findDescription("更多控制") != null }
            val parameterWriteRequested=InstrumentationRegistry.getArguments().getString("allowPhysicalParameterWrite")=="true"
            aperture = if(parameterWriteRequested)runtime.properties.value.firstOrNull { it.code == 0x5007 && it.writable } else null
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
                .put("shutterBottom", bounds.bottom).put("safeBottom", safeBottom).put("apertureRestored", changed)
                .put("parameterWriteRequested",parameterWriteRequested).put("parameterWritten",changed))
            clickDescription("返回相机")
        } finally {
            rotate(UiAutomation.ROTATION_UNFREEZE)
            if (changed) aperture?.let { original ->
                runtime.native.setProperty(original.code, original.value)
                waitFor(5000) { runtime.native.properties().first { it.code == original.code }.value == original.value }
            }
        }
    }

    @Test fun creativeLookUiReadsActualCameraSelectionWithoutWriting(){
        check(Build.MODEL=="25113PN0EC"){"创意外观界面只读验收仅允许小米17"}
        connect(synchronizeCaptureStorage=false)
        try{
            val actual=runtime.properties.value.first{it.code==CameraPropertyText.CREATIVE_LOOK}
            val label=CameraPropertyText.label(actual)?:error("当前机身风格暂未识别")
            clickText("取景拍摄")
            waitFor(30000){runtime.frame.value?.isNotEmpty()==true}
            clickDescription("创意外观")
            waitFor{hasText("创意外观 · $label")}
            screenshot("physical-creative-look-picker")
            assertEquals(actual.value,runtime.native.properties().first{it.code==actual.code}.value)
            report("creative-look-ui.json",JSONObject().put("camera",runtime.camera.value.info!!.model).put("firmware",runtime.camera.value.info!!.firmware)
                .put("code",actual.code).put("current",actual.value).put("label",label).put("advertisedChoices",actual.choices.size)
                .put("writable",actual.writable)
                .put("recognizedChoices",CameraPropertyText.choices(actual).size).put("pickerDisplayed",true).put("cameraSettingWritten",false).put("shutterTriggered",false))
            clickDescription("关闭参数");clickDescription("返回相机")
        }finally{runtime.stopLive();runtime.disconnect()}
    }

    @Test fun readCreativeLookDescriptorsWithoutChangingCameraSettings(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPhysicalCameraRead")=="true")
        check(Build.MODEL=="25113PN0EC"){"本轮创意外观只读采集仅允许小米17"}
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n app.framelink.camera/.MainActivity")).use{it.readBytes()}
        val probe=NativeCamera(app)
        try{
            // 独立原生连接不调用CameraRuntime的拍摄保存位置同步，只进行描述符读取
            val config=runtime.savedConnection()
            report("creative-look-connection.json",JSONObject().put("host",config.host).put("port",config.port).put("protocol",config.protocol).put("accessAuthentication",config.user.isNotBlank()).put("readOnly",true))
            val info=probe.connect(config)
            assertEquals("ILCE-7CM2",info.model)
            val result=JSONObject(probe.creativeLookDiagnostics())
            assertTrue(result.getBoolean("readOnly"))
            assertFalse(result.getBoolean("sdkCodeUsed"))
            report("creative-look-descriptors.json",result.put("shutterTriggered",false).put("cameraSettingWritten",false))
        }finally{probe.close()}
    }

    /** 真实机身切换后必须读回并恢复原值，不触发快门或更改风格细调 */
    @Test fun creativeLookSwitchReadsBackAndRestoresOriginal(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPhysicalCreativeLookWrite")=="true")
        check(Build.MODEL=="25113PN0EC"){"本轮创意外观写入验收仅允许小米17"}
        connect(synchronizeCaptureStorage=false)
        var original:CameraProperty?=null
        var attempted=false
        try{
            val before=runtime.properties.value.first{it.code==CameraPropertyText.CREATIVE_LOOK}
            original=before
            assertTrue("机身当前须允许设置创意外观",CameraPropertyText.editable(before))
            val candidates=CameraPropertyText.choices(before).filter{it.value!=before.value}
            val target=candidates.firstOrNull{it.label=="BW"}?:candidates.first()
            report("creative-look-write-before.json",JSONObject().put("original",before.value).put("target",target.value))
            clickText("取景拍摄")
            waitFor(30000){runtime.frame.value?.isNotEmpty()==true}
            dismissFullscreenEducation()
            File(app.getExternalFilesDir(null),"physical-read/creative-look-before.jpg").writeBytes(runtime.frame.value!!)
            clickDescription("创意外观")
            waitFor{hasText("标准")&&hasText("胶片")}
            screenshot("physical-creative-look-chinese")
            attempted=true
            clickText(target.label)
            waitFor(15000){runtime.parameterUpdating.value==null&&runtime.properties.value.first{it.code==before.code}.value==target.value}
            assertEquals(target.value,runtime.native.properties().first{it.code==before.code}.value)
            SystemClock.sleep(1000)
            File(app.getExternalFilesDir(null),"physical-read/creative-look-changed.jpg").writeBytes(runtime.frame.value!!)
            screenshot("physical-creative-look-changed")
            report("creative-look-write-result.json",JSONObject().put("original",before.value).put("target",target.value)
                .put("readback",target.value).put("chineseDisplayed",true).put("shutterTriggered",false))
        }finally{
            try{
                if(attempted)original?.let{before->
                    runtime.native.setProperty(before.code,before.value)
                    waitFor(10000){runtime.native.properties().first{it.code==before.code}.value==before.value}
                    report("creative-look-write-restored.json",JSONObject().put("original",before.value).put("restored",true))
                }
            }finally{runtime.stopLive();runtime.disconnect()}
        }
    }

    @Test fun measurePreviewReceptionAndNetworkWithoutCapture() {
        connect()
        try {
            clickText("取景拍摄")
            waitFor(30000) { runtime.frame.value?.isNotEmpty() == true }
            var frames = 0
            val dimensions=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            val firstFrame=runtime.frame.value!!
            BitmapFactory.decodeByteArray(firstFrame,0,firstFrame.size,dimensions)
            var bytes = 0L
            val started = SystemClock.elapsedRealtime()
            runBlocking {
                withTimeoutOrNull(4000) { runtime.frame.collect { value -> if (value != null) { frames++; bytes += value.size } } }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val host = runtime.savedConnection().host
            check(android.util.Patterns.IP_ADDRESS.matcher(host).matches())
            val ping = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("ping -c 5 -W 1 $host")).use { it.bufferedReader().readText() }
            val rtt = ping.lineSequence().firstOrNull { it.contains("min/avg/max") }.orEmpty().substringAfter("=", "").trim()
            report("preview-network.json", runtime.native.shootingState().put("receivedFrames", frames).put("sampleMs", elapsed)
                .put("frameWidth",dimensions.outWidth).put("frameHeight",dimensions.outHeight)
                .put("receivedFps", frames*1000.0/elapsed).put("jpegMbps", bytes*8.0/elapsed/1000)
                .put("pingSummary", rtt).put("endToEndLatencyMeasured", false))
            clickDescription("返回相机")
        } finally { runtime.stopLive() }
    }

    @Test fun recoverPendingFilesWithoutTakingPhoto() {
        connect()
        val before = runtime.native.shootingState()
        report("pending-before.json", before)
        runBlocking { runtime.receivePendingCaptures().join() }
        runtime.native.configureCaptureStorage(false)
        val after = runtime.native.shootingState()
        report("pending-after.json", after.put("shutterTriggered", false))
        assertEquals("仅拍照应使用相机存储卡", 0x10L, after.getLong("storeDestination"))
        assertEquals("机身待传队列仍有文件", 0, after.optInt("pendingCount"))
    }

    @Test fun focusTargetsDoNotFireShutter() {
        connect()
        val before = runtime.native.newestPage(1, "", 0).items.map { it.id }.toSet()
        try {
            clickText("取景拍摄")
            waitFor(30000) { runtime.frame.value?.isNotEmpty() == true }
            dismissFullscreenEducation()
            fun saveFrame(name: String, x: Float? = null, y: Float? = null) {
                File(app.getExternalFilesDir(null), "physical-read/$name.jpg").apply { parentFile!!.mkdirs() }.writeBytes(runtime.frame.value!!)
                report("$name.json", runtime.native.shootingState().put("requestedX", x).put("requestedY", y))
            }
            saveFrame("focus-scene")
            val arguments = InstrumentationRegistry.getArguments()
            for ((index, key) in listOf("focusNear", "focusFar").withIndex()) {
                val point = arguments.getString(key)?.split(',')?.map { it.toFloat() } ?: continue
                val node = findDescription("取景画面，点按对焦") ?: error("取景画面不可见")
                val bounds = Rect().also(node::getBoundsInScreen)
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val frame = runtime.frame.value!!; BitmapFactory.decodeByteArray(frame, 0, frame.size, dimensions)
                val scale = minOf(bounds.width().toFloat()/dimensions.outWidth, bounds.height().toFloat()/dimensions.outHeight)
                val width = dimensions.outWidth*scale; val height = dimensions.outHeight*scale
                val x = bounds.left + (bounds.width()-width)/2 + width*point[0]
                val y = bounds.top + (bounds.height()-height)/2 + height*point[1]
                touchWindowId = node.windowId
                val started = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(dispatch(event)) } finally { event.recycle() }
                    SystemClock.sleep(60)
                }
                waitFor(2000) { runtime.focusBusy.value }
                waitFor(12000) { !runtime.focusBusy.value }
                SystemClock.sleep(600)
                saveFrame("focus-target-${index+1}", point[0], point[1])
            }
            assertEquals("点按对焦不能触发拍摄", before, runtime.native.newestPage(1, "", 0).items.map { it.id }.toSet())
            clickDescription("返回相机")
        } finally { runtime.stopLive(); touchWindowId = null }
    }

    private fun appRoot(): AccessibilityNodeInfo? {
        val active = automation.rootInActiveWindow?.takeIf { it.packageName == app.packageName } ?: return null
        if (appTouch) {
            automation.windows.sortedByDescending { it.layer }.forEach { window ->
                if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)
                    window.root?.takeIf { it.packageName == app.packageName }?.let { return it }
            }
        }
        return active
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        clearCacheMethod?.invoke(automation)
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            // Android13部分厂商不会及时失效UiAutomation缓存，逐层刷新以读取实际窗口
            if (appTouch && clearCacheMethod == null && !node.refresh()) return
            if (node.isVisibleToUser) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        appRoot()?.takeIf { it.packageName == app.packageName }?.let(::visit)
        return result
    }
    private fun hasText(text: String) = nodes().any { it.text?.toString() == text }
    private fun hasSelectionCount(count:Int)=nodes().any{it.text?.toString()?.startsWith("本次已选 $count 张")==true}
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
        if (appTouch) {
            check(node.packageName == app.packageName)
            check(automation.rootInActiveWindow?.packageName == app.packageName) { "系统或其他应用遮挡光屿，停止应用内操作" }
            var focused=false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                focused=WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus()&&it.isShown&&it.isAttachedToWindow&&it.context.applicationContext.packageName==app.packageName }
            }
            check(focused) { "光屿已不在前台，停止应用内操作" }
            check(node.refresh()) { "光屿控件已变化，请重新定位" }
            assertTrue("应用内控件点击未生效", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return
        }
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
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= end) {
                val current=nodes()
                runCatching {
                    report("wait-timeout.json",JSONObject().put("stage",stage).put("nodes",org.json.JSONArray(current.map { node ->
                        JSONObject().put("text",node.text).put("description",node.contentDescription).put("state",node.stateDescription)
                            .put("parentState",node.parent?.stateDescription).put("clickable",node.isClickable)
                    })))
                    screenshot("physical-wait-timeout")
                }
                throw AssertionError("等待手机界面或相机状态超时，步骤=$stage，前台=${appRoot()?.packageName}，可见操作=${current.mapNotNull { it.text?.toString() }.distinct()}")
            }
            SystemClock.sleep(100)
        }
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
        if (rotation != UiAutomation.ROTATION_UNFREEZE) {
            // 红米Android13旋转后会保留失效节点，重建诊断连接，业务窗口保持不变
            automationFlags = if (automationFlags == 0) UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES else 0
            val service = automation.serviceInfo
            service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service
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
            val candidates = WindowInspector.getGlobalWindowViews().filter {
                it.hasWindowFocus() && it.isShown && it.isAttachedToWindow && it.context.applicationContext.packageName == app.packageName
            }
            val root = checkNotNull(candidates.lastOrNull { view ->
                val node = view.createAccessibilityNodeInfo()
                val matches = node?.windowId == touchWindowId
                node?.recycle()
                matches
            } ?: candidates.lastOrNull()) { "光屿窗口未聚焦，停止应用内触控" }
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
            .put("inputMode", if (appTouch) "app-actions-and-touch" else "system-injection")
        File(app.getExternalFilesDir(null), "physical-read/$name").apply { parentFile!!.mkdirs() }.writeText(value.toString(2))
    }
}
