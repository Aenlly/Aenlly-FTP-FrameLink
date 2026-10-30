package app.framelink.camera

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 用同一传感方向状态驱动真实取景页，核对内容转正不会重建取景和移动操作位置 */
class LiveDeviceOrientationAcceptanceTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val runtime get()=ApplicationProvider.getApplicationContext<CameraApp>().runtime
    private fun liveSurface():LiveViewSurface?{
        fun find(view:android.view.View):LiveViewSurface?{
            if(view is LiveViewSurface)return view
            if(view is android.view.ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let{return it}
            return null
        }
        return android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
    }

    /** 实际点击ISO入口，覆盖握持转向后的命中区域及机身确认值回读 */
    @Test fun isoTouchOpensPickerAndReadsBackWithoutReplacingLiveView(){
        val orientation=LiveDeviceOrientation()
        runtime.connect(ConnectionConfig(host="127.0.0.1"))
        try{
            compose.waitUntil(30000){runtime.camera.value.info!=null}
            compose.runOnUiThread{compose.activity.setContent{MaterialTheme{LiveScreen(runtime,{},false,{},orientation)}}}
            compose.waitUntil(20000){runtime.frame.value?.isNotEmpty()==true}
            compose.waitUntil(15000){liveSurface()?.isAvailable==true}
            compose.onNodeWithContentDescription("专业模式").performClick()
            val scene=compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().id
            val surface=liveSurface()
            val session=runtime.camera.value.info!!.id
            val original=CameraPropertyText.quick(runtime.properties.value).first{CameraPropertyText.quickCode(it)=="ISO"}
            val choices=CameraPropertyText.choices(original)
            val target=choices.indexOfFirst{it.value!=original.value}
            assertTrue("模拟机身必须提供不同的ISO候选",target>=0)
            for(degrees in listOf(0,90,270)){
                compose.runOnIdle{orientation.update(degrees)}
                compose.waitForIdle()
                compose.onNodeWithContentDescription("调整感光度").performTouchInput{click()}
                compose.onNodeWithTag("live-property-picker").assertIsDisplayed()
                compose.onNodeWithTag("live-property-choices").assertIsDisplayed()
                if(degrees==0){
                    compose.onNodeWithTag("live-property-choices").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(target.toFloat())}
                    compose.waitUntil(10000){runtime.properties.value.first{it.code==original.code}.value==choices[target].value}
                    val originalIndex=choices.indexOfFirst{it.value==original.value}
                    compose.onNodeWithTag("live-property-choices").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(originalIndex.toFloat())}
                    compose.waitUntil(10000){runtime.properties.value.first{it.code==original.code}.value==original.value}
                }
                acceptanceScreenshot(compose.activity,"live-iso-touch-$degrees")
                compose.onNodeWithContentDescription("关闭参数").performTouchInput{click()}
                compose.onNodeWithTag("live-property-picker").assertDoesNotExist()
                assertEquals(scene,compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().id)
                assertSame(surface,liveSurface())
                assertEquals(session,runtime.camera.value.info!!.id)
            }
        }finally{runtime.disconnect()}
    }

    @Test fun holdingDirectionOnlyRotatesContentAndKeepsTheSameScene(){
        val orientation=LiveDeviceOrientation()
        runtime.connect(ConnectionConfig(host="127.0.0.1"))
        try{
            compose.waitUntil(30000){runtime.camera.value.info!=null}
            compose.runOnUiThread{compose.activity.setContent{MaterialTheme{LiveScreen(runtime,{},false,{},orientation)}}}
            compose.waitUntil(20000){runtime.frame.value?.isNotEmpty()==true}
            compose.waitUntil(15000){liveSurface()?.isAvailable==true}
            compose.waitForIdle()
            val surface=liveSurface()
            val texture=surface!!.surfaceTexture
            val scene=compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().id
            val preview=compose.onNodeWithTag("live-preview").fetchSemanticsNode().id
            val session=runtime.camera.value.info!!.id
            val tags=listOf("live-preview","live-portrait-dock","live-album-control","live-look-control")
            val before=tags.associateWith{compose.onNodeWithTag(it).fetchSemanticsNode().boundsInWindow}
            val shutter=compose.onNodeWithContentDescription("拍摄").fetchSemanticsNode().boundsInWindow
            compose.onNodeWithContentDescription("专业模式").performClick()
            val professional=compose.onNodeWithTag("live-portrait-dock").fetchSemanticsNode().boundsInWindow
            for(degrees in listOf(90,270,180,0)){
                compose.runOnIdle{orientation.update(degrees)}
                compose.waitForIdle()
                assertEquals(scene,compose.onNodeWithTag("fullscreen-scene").fetchSemanticsNode().id)
                assertEquals(preview,compose.onNodeWithTag("live-preview").fetchSemanticsNode().id)
                assertEquals(session,runtime.camera.value.info!!.id)
                assertSame("转向不能重建渲染View",surface,liveSurface())
                assertSame("转向不能重建取景纹理",texture,liveSurface()!!.surfaceTexture)
                assertEquals(professional,compose.onNodeWithTag("live-portrait-dock").fetchSemanticsNode().boundsInWindow)
                listOf("live-preview","live-album-control","live-look-control").forEach{tag->
                    assertEquals("握持方向不能移动$tag",before[tag],compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow)
                }
                assertEquals(shutter,compose.onNodeWithContentDescription("拍摄").fetchSemanticsNode().boundsInWindow)
                compose.onNodeWithTag("live-quick-properties").assertIsDisplayed()
                acceptanceScreenshot(compose.activity,"live-fixed-direction-$degrees")
            }
            compose.onNodeWithContentDescription("更多控制").performClick()
            val panel=compose.onNodeWithTag("live-tools-panel").fetchSemanticsNode().id
            compose.runOnIdle{orientation.update(90)}
            compose.waitForIdle()
            assertEquals(panel,compose.onNodeWithTag("live-tools-panel").fetchSemanticsNode().id)
            compose.onNodeWithContentDescription("收起工具").assertIsDisplayed()
        }finally{runtime.disconnect()}
    }
}
