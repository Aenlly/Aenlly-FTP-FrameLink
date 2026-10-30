package app.framelink.camera

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 合成JPEG验证真实EXIF解析与原生信息页，不使用相机当前参数 */
class PhotoExifAcceptanceTest{
    @get:Rule(order=0)val foreground:org.junit.rules.TestRule=ForegroundAcceptanceRule()
    @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2)val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val runtime get()=ApplicationProvider.getApplicationContext<CameraApp>().runtime

    @Test fun cachedPhotoShowsExposureGpsAndCameraDetailsWithoutImport(){checkPhoto(true)}
    @Test fun absentExifDoesNotInventExposureOrLocation(){checkPhoto(false)}

    private fun checkPhoto(withTags:Boolean){
        runtime.disconnect()
        val token="qa-exif-"+UUID.randomUUID()
        val fixture=File(compose.activity.cacheDir,"$token.jpg")
        val bitmap=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888)
        try{fixture.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}}finally{bitmap.recycle()}
        if(withTags){ExifInterface(fixture).apply{
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,"640")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME,"0.004")
            setAttribute(ExifInterface.TAG_F_NUMBER,"4.5")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH,"50")
            setAttribute(ExifInterface.TAG_MAKE,"QA")
            setAttribute(ExifInterface.TAG_MODEL,"Photo Camera")
            setAttribute(ExifInterface.TAG_LENS_MODEL,"QA 50mm")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2026:10:30 12:34:56")
            setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM,"1")
            setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE,"-0.333333")
            setAttribute(ExifInterface.TAG_WHITE_BALANCE,"0")
            setAttribute(ExifInterface.TAG_METERING_MODE,"5")
            setAttribute(ExifInterface.TAG_FLASH,"0")
            setAttribute(ExifInterface.TAG_GPS_MAP_DATUM,"WGS-84")
            setLatLong(0.0,0.0);setAltitude(12.5);saveAttributes()
        }}
        val photo=Photo(token,token,"$token.JPG",PhotoSource.CAPTURE.name,System.currentTimeMillis()+1000000,
            Json.encode(listOf(CameraFile(fileId=1,name="$token.JPG",size=fixture.length()))))
        val cache=OriginalPreviewCache(compose.activity.cacheDir)
        val cached=cache.prepare(photo)
        fixture.copyTo(cached,true);cache.complete(cached,MediaFiles.digest(cached.inputStream()))
        val displayed=photo.copy(previewPath=cached.path)
        val beforeTasks=runBlocking{runtime.database.tasks().all().map{it.id}.toSet()}
        runBlocking{runtime.database.photos().save(displayed)}
        try{
            val details=requireNotNull(PhotoExifReader.read(compose.activity,displayed,cached.path))
            if(withTags){
                assertEquals("640",details.iso);assertEquals("1/250 秒",details.shutter);assertEquals("f/4.5",details.aperture)
                assertEquals("QA Photo Camera",details.camera);assertEquals("QA 50mm",details.lens)
                assertEquals(0.0,details.location!!.latitude,0.0);assertEquals(12.5,details.location!!.altitude!!,.01)
            }else{assertNull(details.iso);assertNull(details.shutter);assertNull(details.aperture);assertNull(details.location)}
            compose.onNodeWithTag("tab-gallery").performClick()
            compose.onNodeWithText("已导入").performClick()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            compose.onNodeWithContentDescription("查看 ${photo.name}").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(photo.displayTitle(),substring=true).assertExists()
            compose.onNodeWithContentDescription("文件信息").performClick()
            try{compose.waitUntil(10000){compose.onAllNodesWithText("来自JPEG临时缓存",substring=false).fetchSemanticsNodes().isNotEmpty()}}
            catch(error:Throwable){
                val current=runBlocking{runtime.database.photos().find(photo.id)}
                val text=compose.onAllNodes(hasText("",substring=true),useUnmergedTree=true).fetchSemanticsNodes().map{it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text){emptyList()}.joinToString()}
                throw AssertionError("EXIF信息页未稳定：缓存=${runtime.cachedOriginalPreview(displayed)!=null}，资源相同=${current?.selectionResourceKey()==displayed.selectionResourceKey()}，界面=$text",error)
            }
            compose.onNodeWithContentDescription(if(withTags)"ISO：640"else"ISO：未提供").assertExists()
            compose.onNodeWithContentDescription(if(withTags)"快门速度：1/250 秒"else"快门速度：未提供").assertExists()
            compose.onNodeWithContentDescription(if(withTags)"光圈：f/4.5"else"光圈：未提供").assertExists()
            if(withTags)compose.onNodeWithText("在地图中查看").performScrollTo().assertIsDisplayed()
            else compose.onNodeWithText("未记录",substring=false).performScrollTo().assertIsDisplayed()
            acceptanceScreenshot(compose.activity,if(withTags)"photo-exif-complete"else"photo-exif-missing")
            if(withTags){
                compose.activity.runOnUiThread{compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
                compose.onNodeWithContentDescription("ISO：640").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("完成",substring=false).assertIsDisplayed()
                acceptanceScreenshot(compose.activity,"photo-exif-landscape")
                compose.activity.runOnUiThread{compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
                compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
                compose.waitForIdle()
                try{compose.waitUntil(10000){compose.onAllNodesWithText("完成",substring=false).fetchSemanticsNodes().size==1}}
                catch(error:Throwable){
                    acceptanceScreenshot(compose.activity,"photo-exif-rotation-failure")
                    throw AssertionError("信息页旋转状态：关闭照片=${compose.onAllNodesWithContentDescription("关闭照片").fetchSemanticsNodes().size}，原始文件=${compose.onAllNodesWithText("原始文件").fetchSemanticsNodes().size}，窗口=${compose.activity.resources.configuration.screenWidthDp}x${compose.activity.resources.configuration.screenHeightDp}",error)
                }
            }
            compose.onNodeWithText("完成",substring=false).performClick()
            compose.onNodeWithContentDescription("关闭照片").performClick()
            assertEquals(photo.filesJson,runBlocking{runtime.database.photos().find(photo.id)!!.filesJson})
            assertFalse(runBlocking{runtime.database.photos().find(photo.id)!!.imported})
            assertEquals(beforeTasks,runBlocking{runtime.database.tasks().all().map{it.id}.toSet()})
        }finally{
            compose.activity.runOnUiThread{compose.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
            runBlocking{runtime.database.photos().save(displayed.copy(localHidden=true,previewPath=""))}
            fixture.delete();cached.delete();File(cached.path+".sha256").delete()
        }
    }
}
