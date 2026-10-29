package app.framelink.camera

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只由独立平板模拟器脚本运行，照片和任务均为本轮测试创建 */
@RunWith(AndroidJUnit4::class)
class TabletGalleryAcceptanceTest {
    @get:Rule(order=0)val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1)val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime get()=(compose.activity.application as CameraApp).runtime
    private val token=UUID.randomUUID().toString().take(8)
    private val photos=mutableListOf<Photo>()
    private val variant get()=InstrumentationRegistry.getArguments().getString("tabletVariant")?:"tablet"

    private fun command(value:String):String{
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(value)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use{it.readText()}
    }
    private fun screen(width:Int,height:Int){
        command("wm size ${width}x$height")
        compose.waitUntil(15000){compose.activity.resources.configuration.screenWidthDp in width/2-30..width/2+30}
        compose.waitForIdle()
    }
    private fun screenshot(name:String){
        if(compose.onAllNodesWithTag("photo-viewer-image",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty())compose.waitUntil(20000){
            compose.onAllNodesWithTag("photo-viewer-image",useUnmergedTree=true).fetchSemanticsNodes().all{it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)=="照片已显示"}
        }
        val config=compose.activity.resources.configuration
        val scroll=compose.onAllNodesWithTag("gallery-grid",useUnmergedTree=true).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
        println("TABLET_FRAME="+JSONObject().put("run",token).put("variant",variant).put("name",name).put("time",System.currentTimeMillis()).put("widthDp",config.screenWidthDp).put("heightDp",config.screenHeightDp).put("densityDpi",config.densityDpi).put("fontScale",config.fontScale).put("multiWindow",compose.activity.isInMultiWindowMode).put("gridScroll",scroll).put("activity",System.identityHashCode(compose.activity)))
        acceptanceScreenshot(compose.activity,"tablet-$variant-$name")
    }
    private fun seed(){
        check(InstrumentationRegistry.getArguments().getString("tabletQa")=="true"){"仅供独立平板验收设备"}
        val fixtures=File(compose.activity.filesDir,"visual-fixtures").listFiles().orEmpty().filter{it.extension=="jpg"}.sortedBy{it.name}
        check(fixtures.size==6){"请先准备授权样片"}
        val now=System.currentTimeMillis()+900000
        repeat(80){index->
            val file=fixtures[index%fixtures.size]
            val id="qa-tablet:$token:$index"
            photos+=Photo(id,id,"QA_${token}_$index.jpg",PhotoSource.IMPORTED.name,now-index*1000L,
                Json.encode(listOf(CameraFile(name="QA_${token}_$index.jpg",size=file.length(),localUri=file.toURI().toString(),originalPath=file.path))))
        }
        runBlocking{runtime.database.photos().saveAll(photos)}
        runtime.settings.put("gallery.layout","medium")
        compose.onNodeWithTag("tab-gallery",useUnmergedTree=true).performClick()
        compose.onNodeWithText("已导入").performClick()
    }
    private fun cleanup(){runBlocking{photos.forEach{runtime.database.photos().save(it.copy(localHidden=true,filesJson="[]"))}}}

    @Test fun configuredWindowUsesAvailableSpace(){
        try{
            seed()
            val config=compose.activity.resources.configuration
            val expected=galleryWindowLayout(config.screenWidthDp)
            if(expected.rail)compose.onNodeWithTag("gallery-navigation-rail",useUnmergedTree=true).assertIsDisplayed()else compose.onNodeWithTag("gallery-navigation-rail",useUnmergedTree=true).assertDoesNotExist()
            compose.onNodeWithTag("gallery-preview-pane",useUnmergedTree=true).assertDoesNotExist()
            val grid=compose.onNodeWithTag("gallery-grid",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val cell=compose.onNodeWithTag("photo-${photos[0].id}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val density=compose.activity.resources.displayMetrics.density
            val columns=galleryColumnCount((grid.width/density).toInt(),GalleryLayout.MEDIUM,config.screenWidthDp>=600)
            assertEquals("列数应由图库内容区宽度决定",columns,(grid.width/cell.width).toInt())
            enterGallerySelection(compose)
            compose.onNodeWithContentDescription("选择 ${photos[0].name}").performClick()
            compose.onNodeWithText("存选片",substring=false).assertIsDisplayed()
            screenshot("selection")
            compose.onNodeWithText("存选片",substring=false).performClick()
            compose.onNode(hasSetTextAction()and hasText("选片名称")).performClick().performTextReplacement("QA窗口-$token")
            compose.onNodeWithText("保存选片",substring=false).assertIsDisplayed()
            screenshot("save-dialog")
            compose.onNodeWithText("取消",substring=false).performClick()
            finishGallerySelection(compose)
            compose.onNodeWithContentDescription("查看 ${photos[0].name}").performClick()
            if(expected.preview)compose.onNodeWithTag("gallery-preview-pane",useUnmergedTree=true).assertIsDisplayed()else compose.onNodeWithTag("photo-viewer-image",useUnmergedTree=true).assertIsDisplayed()
            screenshot("preview")
            compose.onNodeWithContentDescription("关闭照片").performClick()
        }finally{cleanup()}
    }

    @Test fun realFreeformWindowKeepsPhotoSelectionScrollAndImportedTask(){
        try{
            seed()
            val sample=File(compose.activity.filesDir,"media/qa-tablet-import-$token.jpg").apply{
                parentFile!!.mkdirs()
                File(compose.activity.filesDir,"visual-fixtures/Sample-01-Lake.jpg").copyTo(this,overwrite=true)
            }
            val before=runBlocking{runtime.database.tasks().byKind(TransferKind.IMPORT.name).map{it.id}.toSet()}
            val uri=FileProvider.getUriForFile(compose.activity,compose.activity.packageName+".files",sample)
            runBlocking{runtime.importUris(listOf(uri)).join()}
            val task=runBlocking{runtime.database.tasks().byKind(TransferKind.IMPORT.name).first{it.id !in before}}
            assertEquals(TransferState.COMPLETE.name,task.state)
            compose.onNodeWithTag("gallery-grid",useUnmergedTree=true).performScrollToIndex(30)
            compose.onNodeWithTag("photo-${photos[0].id}",useUnmergedTree=true).assertIsNotDisplayed()
            screenshot("before-preview")
            compose.onNodeWithContentDescription("查看 ${photos[30].name}").performClick()
            compose.onNodeWithContentDescription("选择当前照片").performClick()
            screenshot("before-freeform")
            val taskId=compose.activity.taskId
            command("am start -f 0x20000000 --windowingMode 5 -n app.framelink.camera/.MainActivity")
            compose.waitUntil(15000){compose.activity.isInMultiWindowMode}
            assertEquals("应复用同一个任务",taskId,compose.activity.taskId)
            for((name,right)in listOf("half" to 1280,"third" to 852,"wide" to 2440)){
                command("am task resize $taskId 0 60 $right 1500")
                compose.waitUntil(15000){compose.activity.resources.configuration.screenWidthDp in right/2-30..right/2+30}
                compose.onNodeWithContentDescription("选择当前照片").assertIsOn()
                compose.onNodeWithText(photos[30].name,substring=true).assertIsDisplayed()
                assertEquals(TransferState.COMPLETE.name,runBlocking{runtime.database.tasks().find(task.id)!!.state})
                screenshot("freeform-$name")
            }
            compose.onNodeWithContentDescription("关闭照片").performClick()
            screenshot("after-close")
            val scroll=compose.onNodeWithTag("gallery-grid",useUnmergedTree=true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("窗口缩放后不应回到列表顶部",scroll>0f)
            enterGallerySelection(compose)
            compose.onNodeWithText("本次已选 1 张",substring=true).assertIsDisplayed()
            compose.onNodeWithContentDescription("选择 ${photos[30].name}").assertIsOn().assertIsDisplayed()
            screenshot("freeform-scroll-selection")
        }finally{
            command("am start -f 0x20000000 --windowingMode 1 -n app.framelink.camera/.MainActivity")
            File(compose.activity.filesDir,"media/qa-tablet-import-$token.jpg").delete()
            cleanup()
        }
    }

    @Test fun wideGalleryKeepsPreviewSelectionAndDialogStateThroughResizing(){
        try{
            seed()
            compose.onNodeWithTag("gallery-navigation-rail",useUnmergedTree=true).assertIsDisplayed()
            compose.onNodeWithContentDescription("查看 ${photos[0].name}").performClick()
            compose.onNodeWithTag("embedded-photo-viewer",useUnmergedTree=true).assertIsDisplayed()
            compose.onNodeWithContentDescription("选择当前照片").performClick()
            compose.onNodeWithContentDescription("查看 ${photos[1].name}").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText(photos[1].name,substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("选择当前照片").performClick()
            screenshot("wide-preview")
            screen(1600,2560)
            compose.onNodeWithTag("gallery-preview-pane",useUnmergedTree=true).assertDoesNotExist()
            compose.onNodeWithTag("photo-viewer-image",useUnmergedTree=true).assertIsDisplayed()
            compose.onNodeWithText(photos[1].name,substring=true).assertIsDisplayed()
            compose.onNodeWithContentDescription("选择当前照片").assertIsOn()
            screenshot("portrait-viewer")
            screen(2560,1600)
            compose.onNodeWithTag("embedded-photo-viewer",useUnmergedTree=true).assertIsDisplayed()
            compose.onNodeWithContentDescription("选择当前照片").assertIsOn()
            enterGallerySelection(compose)
            compose.onNodeWithText("本次已选 2 张",substring=true).assertIsDisplayed()
            compose.onNodeWithText("对比",substring=false).performClick()
            val first=compose.onNodeWithTag("compare-photo-${photos[0].id}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val second=compose.onNodeWithTag("compare-photo-${photos[1].id}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue("宽屏对比应左右排列",first.right<=second.left)
            screenshot("compare")
            screen(1600,2560)
            val portraitFirst=compose.onNodeWithTag("compare-photo-${photos[0].id}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val portraitSecond=compose.onNodeWithTag("compare-photo-${photos[1].id}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue("窄高窗口对比应上下排列",portraitFirst.bottom<=portraitSecond.top)
            screenshot("portrait-compare")
            screen(2560,1600)
            compose.onNodeWithContentDescription("关闭对比").performClick()
            compose.onNodeWithText("存选片",substring=false).performClick()
            compose.onNode(hasSetTextAction()and hasText("选片名称")).performClick().performTextReplacement("QA平板-$token")
            screen(960,1600)
            compose.onNode(hasSetTextAction()and hasText("选片名称")).assertTextContains("QA平板-$token")
            compose.onNodeWithText("保存选片",substring=false).assertIsDisplayed()
            screenshot("third-window-dialog")
            compose.onNodeWithText("取消",substring=false).performClick()
            compose.onNodeWithContentDescription("关闭照片").performClick()
            compose.onNodeWithTag("gallery-navigation-rail",useUnmergedTree=true).assertDoesNotExist()
            compose.onNodeWithText("本次已选 2 张",substring=true).assertIsDisplayed()
            screenshot("third-window-selection")
        }finally{command("wm size reset");cleanup()}
    }
}
