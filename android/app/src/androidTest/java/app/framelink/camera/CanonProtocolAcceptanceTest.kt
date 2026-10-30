package app.framelink.camera

import android.Manifest
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只连接本机独立CCAPI模拟服务，不作为EOS850D实机兼容证明 */
@RunWith(AndroidJUnit4::class)
class CanonProtocolAcceptanceTest {
    @get:Rule(order=0) val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1) val notifications=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context get()=ApplicationProvider.getApplicationContext<CameraApp>()
    private val runtime get()=context.runtime
    private val config get()=ConnectionConfig(host="127.0.0.1",port=18080,protocol=CameraProtocols.CANON)

    private fun server(path:String,payload:JSONObject?=null):JSONObject {
        val connection=URL("http://127.0.0.1:18080$path").openConnection() as HttpURLConnection
        connection.connectTimeout=5000
        connection.readTimeout=10000
        try {
            if(payload!=null){
                connection.requestMethod="POST"
                connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.bufferedWriter().use{it.write(payload.toString())}
            }
            return JSONObject(connection.inputStream.bufferedReader().use{it.readText()})
        }finally{connection.disconnect()}
    }

    private fun reset(){
        runtime.disconnect()
        assertTrue(server("/__test/reset",JSONObject().put("generation",UUID.randomUUID().toString())).getBoolean("synthetic"))
    }

    @Test fun connectionEntrySavesProtocolAndPortThenReconnectsDirectly(){
        reset()
        val previous=runtime.settings.get("camera.config")
        try{
            runtime.settings.put("camera.config","")
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithTag("camera-home").performScrollToIndex(0)
            compose.onNodeWithTag("camera-primary").performClick()
            compose.onNodeWithText("佳能 CCAPI",substring=false).performClick()
            compose.onNodeWithText("相机IP地址",substring=false).performScrollTo().performTextReplacement("127.0.0.1")
            compose.onNodeWithText("相机端口",substring=false).performScrollTo().performTextReplacement("18080")
            compose.onNodeWithTag("camera-connect-action").performClick()
            compose.waitUntil(30000){runtime.camera.value.info!=null}
            assertEquals(CameraProtocols.CANON,runtime.savedConnection().protocol)
            assertEquals(18080,runtime.savedConnection().port)
            assertNull(runtime.cameraIdentityPrompt.value)
            acceptanceScreenshot(compose.activity,"ccapi-connected-home")
            runtime.disconnect()
            compose.waitForIdle()
            compose.onNodeWithTag("camera-primary").performClick()
            compose.waitUntil(30000){runtime.camera.value.info!=null}
            compose.onNodeWithTag("camera-connect-action").assertDoesNotExist()
            assertTrue(runtime.native.connected())
        }finally{runtime.disconnect();runtime.settings.put("camera.config",previous)}
    }

    @Test fun jniPreservesOriginalsAndNegotiatesRealCCAPIOperations(){
        reset()
        val camera=NativeCamera(context)
        val directory=File(context.cacheDir,"qa-ccapi-${UUID.randomUUID()}").apply{mkdirs()}
        try {
            val info=camera.connect(config)
            assertEquals("Canon EOS 850D",info.model)
            assertEquals(CameraTimeSource.FILE_MODIFIED,info.dateSource)
            assertFalse(info.hardwareVerified)
            assertFalse(info.canAutoImport)
            assertTrue(info.canBrowse&&info.canDownload&&info.canCapture&&info.canLiveView&&info.canFocus)
            val day=camera.dates(1).first()
            val page=camera.newestPage(1,day,0)
            val remote=page.items.first{it.files.size==2}
            assertEquals(0L,remote.capturedAt)
            assertTrue(remote.files.all{it.cameraDay.isBlank()&&it.fileModifiedAt>0})
            val photo=Photo(remote.id,remote.id,remote.files.first().name,PhotoSource.CAMERA.name,remote.capturedAt,Json.encode(remote.files),remote.contentId,remote.slot)
            val hashes=server("/__stats").getJSONObject("files")
            for(file in remote.files){
                val preview=File(directory,file.name+".preview.jpg")
                camera.preview(photo,file,preview,false)
                assertNotNull(BitmapFactory.decodeFile(preview.path))
                val original=File(directory,file.name)
                val receipt=JSONObject(camera.download(photo,file,original){_,_->})
                assertEquals(file.size,original.length())
                val actual=MediaFiles.digest(original.inputStream())
                assertEquals(hashes.getString(file.name),actual)
                assertEquals(actual,receipt.getString("sha256"))
                assertTrue(original.length()>preview.length())
            }
            val aperture=camera.properties().first{it.code==0x5007}
            camera.setProperty(aperture.code,400)
            assertEquals(400L,camera.properties().first{it.code==aperture.code}.value)
            camera.startLive()
            val frame=camera.nextFrame()
            assertNotNull(BitmapFactory.decodeByteArray(frame,0,frame.size))
            camera.focusAt(.25f,.75f)
            val focused=server("/__stats")
            val actions=focused.getJSONArray("manual")
            assertEquals("half_press",actions.getString(actions.length()-2))
            assertEquals("release",actions.getString(actions.length()-1))
            assertEquals(0,focused.getInt("shots"))
            assertTrue(camera.capturePhoto(single=true,receive=false).getBoolean("shutterComplete"))
            val shot=server("/__stats")
            assertEquals(1,shot.getInt("shots"))
            assertEquals("continuous_high",shot.getString("drive"))
            val newest=camera.newestPage(1,"",0)
            assertTrue(newest.items.first().files.first().name.startsWith("CCAPI_SHOT_"))
            camera.stopLive()
            assertTrue(camera.connected())
            val sony=camera.connect(ConnectionConfig(host="127.0.0.1"))
            assertTrue(sony.model.contains("SIMULATED"))
            assertTrue(sony.canAutoImport)
            assertEquals(CameraTimeSource.CAPTURE,sony.dateSource)
            assertTrue(camera.connected())
        }finally{camera.close();directory.deleteRecursively()}
    }

    @Test fun cameraGallerySelectionAndOriginalTasksUseTheExistingLocalWorkflow(){
        reset()
        val previousConfig=runtime.settings.get("camera.config")
        val previousImport=runtime.settings.get("capture.auto_import")
        val previousLayout=runtime.settings.get("gallery.layout")
        val owned=mutableSetOf<String>()
        try {
            runtime.settings.put("gallery.layout","medium")
            runtime.settings.put("capture.auto_import","true")
            runtime.connect(config)
            compose.waitUntil(30000){runtime.camera.value.info!=null}
            assertFalse(runtime.camera.value.info!!.canAutoImport)
            assertNull(runtime.cameraIdentityPrompt.value)
            compose.onNodeWithTag("tab-camera").performClick()
            compose.onNodeWithTag("camera-home").performScrollToIndex(0)
            compose.onNodeWithTag("camera-browse").performClick()
            compose.waitUntil(30000){runtime.currentCameraIds.value.isNotEmpty()&&!runtime.camera.value.loadingPhotos}
            owned+=runtime.currentCameraIds.value
            val photo=runBlocking{runtime.database.photos().byIds(owned.toList()).maxBy{it.browsingTime}}
            assertEquals(0L,photo.capturedAt)
            assertTrue(photo.browsingTime>0)
            finishGallerySelection(compose)
            filterGallery(compose,"全部")
            compose.onNodeWithContentDescription("查看 ${photo.name}").performTouchInput{click(Offset(width*.25f,height*.25f))}
            compose.onNodeWithText("文件时间",substring=true).assertIsDisplayed()
            compose.onNodeWithContentDescription("选择当前照片").performClick()
            compose.onNodeWithContentDescription("选择当前照片").assertIsOn()
            compose.onNodeWithContentDescription("关闭照片").performClick()
            enterGallerySelection(compose)
            compose.onNodeWithContentDescription("选择 ${photo.name}").assertIsOn()
            acceptanceScreenshot(compose.activity,"ccapi-gallery-selection")
            runBlocking{runtime.enqueueDownload(listOf(photo.id),DownloadFormat.ALL).join()}
            compose.waitUntil(30000){
                runBlocking{runtime.database.tasks().all().any{it.photoId==photo.id&&it.kind==TransferKind.DOWNLOAD.name&&it.state==TransferState.COMPLETE.name}}
            }
            val saved=runBlocking{runtime.database.photos().find(photo.id)!!}
            assertTrue(saved.files().all{it.savedUri.isNotBlank()})
            val hashes=server("/__stats").getJSONObject("files")
            for(file in saved.files()){
                assertEquals(hashes.getString(file.name),MediaFiles.digest(File(file.originalPath).inputStream()))
            }
            val task=runBlocking{runtime.database.tasks().all().first{it.photoId==photo.id&&it.state==TransferState.COMPLETE.name}}
            assertEquals(task.total,task.done)
            assertTrue(task.total>0)
            val previousCapture=runtime.lastCapture.value
            runtime.capture().let{runBlocking{it.join()}}
            assertFalse(runtime.captureBusy.value)
            assertFalse(runtime.postCaptureBusy.value)
            assertEquals(1,server("/__stats").getInt("shots"))
            assertEquals(previousCapture,runtime.lastCapture.value)
            assertTrue(runtime.native.connected())
        }finally{
            runtime.disconnect()
            runBlocking{
                val imported=runtime.database.photos().byIds(owned.toList()).filter{it.inLibrary}
                if(imported.isNotEmpty())runtime.removePhotos(runtime.photoRemoval.plan(imported.map{it.id}),true)
            }
            runtime.settings.put("camera.config",previousConfig)
            runtime.settings.put("capture.auto_import",previousImport)
            runtime.settings.put("gallery.layout",previousLayout)
        }
    }
}
