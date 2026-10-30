package app.framelink.camera

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 合成高像素JPEG验证平板大图与临时缓存，不连接相机、不导入个人照片 */
class TabletCachedPreviewAcceptanceTest{
    @get:Rule(order=0)val foreground:org.junit.rules.TestRule=ForegroundAcceptanceRule()
    @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
    private val runtime get()=(compose.activity.application as CameraApp).runtime
    private fun command(value:String):String{
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(value)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use{it.readText()}
    }
    private fun checkDetail(name:String,photo:Photo){
        compose.waitUntil(15000){compose.onAllNodesWithContentDescription("原片细节已显示",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle()
        compose.onNodeWithContentDescription("选择当前照片",useUnmergedTree=true).assertIsOn().assertIsDisplayed()
        compose.onNodeWithText(photo.displayTitle(),substring=true).assertIsDisplayed()
        compose.onNodeWithText("JPEG原片预览 · 未导入",substring=false).assertIsDisplayed()
        val viewer=compose.onNodeWithTag("photo-viewer-image",useUnmergedTree=true).fetchSemanticsNode().boundsInWindow
        val window=compose.activity.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        assertTrue(viewer.left>=0&&viewer.top>=0&&viewer.right<=window.width()&&viewer.bottom<=window.height())
        acceptanceScreenshot(compose.activity,"tablet-cached-detail-$name")
        val config=compose.activity.resources.configuration
        InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply{
            putString("tabletWindow",org.json.JSONObject().put("name",name).put("widthDp",config.screenWidthDp).put("heightDp",config.screenHeightDp)
                .put("densityDpi",config.densityDpi).put("multiWindow",compose.activity.isInMultiWindowMode).put("taskId",compose.activity.taskId).toString())
        })
    }

    @Test fun cachedDetailKeepsSelectionAndOriginalStateAcrossTabletRotation(){
        check(InstrumentationRegistry.getArguments().getString("tabletQa")=="true")
        check(Build.MODEL=="2410CRP4CC"||Build.HARDWARE in setOf("ranchu","goldfish"))
        val token="qa-tablet-cache-"+UUID.randomUUID()
        val fixture=File(compose.activity.cacheDir,"$token.jpg")
        val bitmap=Bitmap.createBitmap(3072,2048,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val paint=Paint()
        for(y in 0 until 2048 step 32)for(x in 0 until 3072 step 32){
            paint.color=if((x/32+y/32)%2==0)android.graphics.Color.LTGRAY else android.graphics.Color.DKGRAY
            canvas.drawRect(x.toFloat(),y.toFloat(),x+32f,y+32f,paint)
        }
        try{fixture.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,96,it)}}finally{bitmap.recycle()}
        val photo=Photo(token,token,"$token.JPG",PhotoSource.CAPTURE.name,System.currentTimeMillis()+900000,
            Json.encode(listOf(CameraFile(fileId=1,name="$token.JPG",size=fixture.length()))))
        val cache=OriginalPreviewCache(compose.activity.cacheDir)
        val cached=cache.prepare(photo)
        fixture.copyTo(cached,true);cache.complete(cached,MediaFiles.digest(cached.inputStream()))
        val displayed=photo.copy(previewPath=cached.path)
        val previousOrientation=compose.activity.requestedOrientation
        val resize=InstrumentationRegistry.getArguments().getString("resizeWindow")=="true"
        if(resize)check(Build.HARDWARE in setOf("ranchu","goldfish")){"动态窗口软件验收仅允许独立模拟器"}
        val tasks=runBlocking{runtime.database.tasks().all().map{it.id}.toSet()}
        runBlocking{runtime.database.photos().save(displayed)}
        try{
            compose.onNodeWithTag("tab-gallery",useUnmergedTree=true).performClick()
            compose.onNodeWithText("已导入",substring=false).performClick()
            compose.onNodeWithTag("gallery-grid",useUnmergedTree=true).performScrollToIndex(0)
            compose.onNodeWithContentDescription("查看 ${photo.name}",useUnmergedTree=true).performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("JPEG原片预览 · 未导入",substring=false).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("选择当前照片",useUnmergedTree=true).performClick().assertIsOn()
            compose.onNode(hasContentDescription(photo.name)and hasAnyAncestor(hasTestTag("photo-viewer-pager")),useUnmergedTree=true).performTouchInput{doubleClick()}
            compose.waitUntil(15000){compose.onAllNodesWithContentDescription("原片细节已显示",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("photo-viewer-pager",useUnmergedTree=true).performTouchInput{
                swipe(center,center+androidx.compose.ui.geometry.Offset(80f,45f),durationMillis=300)
            }
            compose.waitUntil(15000){compose.onAllNodesWithContentDescription("原片细节已显示",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            for((name,orientation)in listOf("landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,"portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)){
                compose.runOnUiThread{compose.activity.requestedOrientation=orientation}
                try{compose.waitUntil(15000){
                    val expected=if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
                    compose.activity.resources.configuration.orientation==expected&&compose.onAllNodesWithContentDescription("原片细节已显示",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
                }}catch(error:Throwable){
                    acceptanceScreenshot(compose.activity,"tablet-cache-rotation-failure")
                    val config=compose.activity.resources.configuration
                    throw AssertionError("缓存旋转状态：阶段=$name，方向=${config.orientation}，窗口=${config.screenWidthDp}x${config.screenHeightDp}，大图=${compose.onAllNodesWithTag("photo-viewer-image",useUnmergedTree=true).fetchSemanticsNodes().size}，原片细节=${compose.onAllNodesWithContentDescription("原片细节已显示",useUnmergedTree=true).fetchSemanticsNodes().size}",error)
                }
                checkDetail(name,photo)
            }
            if(resize){
                compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                compose.waitUntil(15000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
                val task=compose.activity.taskId
                command("am start -f 0x20000000 --windowingMode 5 -n app.framelink.camera/.MainActivity")
                compose.waitUntil(15000){compose.activity.isInMultiWindowMode}
                for((name,width)in listOf("half" to 1280,"third" to 852,"wide" to 2440)){
                    command("am task resize $task 0 60 $width 1500")
                    compose.waitUntil(15000){compose.activity.resources.configuration.screenWidthDp in width/2-30..width/2+30}
                    assertEquals(task,compose.activity.taskId)
                    checkDetail(name,photo)
                }
            }
            assertEquals(photo.filesJson,runBlocking{runtime.database.photos().find(photo.id)!!.filesJson})
            assertEquals(tasks,runBlocking{runtime.database.tasks().all().map{it.id}.toSet()})
            assertFalse(runBlocking{runtime.database.photos().find(photo.id)!!.imported})
            compose.onNodeWithContentDescription("关闭照片",useUnmergedTree=true).performClick()
        }finally{
            if(resize)command("am start -f 0x20000000 --windowingMode 1 -n app.framelink.camera/.MainActivity")
            compose.runOnUiThread{compose.activity.requestedOrientation=previousOrientation}
            runBlocking{runtime.database.photos().save(displayed.copy(localHidden=true,previewPath=""))}
            fixture.delete();cached.delete();File(cached.path+".sha256").delete()
        }
    }
}
