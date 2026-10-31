package app.framelink.camera

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 用阻塞的真实图片请求检查清晰度升级期间的画面，而非只检查最终加载成功 */
@RunWith(AndroidJUnit4::class)
class RetainedPhotoImageAcceptanceTest{
    @get:Rule val compose=createComposeRule()

    @Test fun resolutionUpgradeKeepsThePreviousPixelsUntilReady(){checkTransition(false)}
    @Test fun changingResourceDoesNotReuseThePreviousPhotosPixels(){checkTransition(true)}

    private fun checkTransition(changeResource:Boolean){
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val file=File(context.cacheDir,"qa-retained-${UUID.randomUUID()}.png")
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.GREEN)}
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val calls=AtomicInteger()
        val next=CompletableDeferred<Unit>()
        val loader=ImageLoader.Builder(context).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
            .components{add(object:Interceptor{
                override suspend fun intercept(chain:Interceptor.Chain):ImageResult{
                    if(calls.incrementAndGet()>1)next.await()
                    return chain.proceed(chain.request)
                }
            })}.build()
        var full by mutableStateOf(false)
        var resource by mutableStateOf("first")
        var successes by mutableIntStateOf(0)
        var loadingWithImage:Boolean? by mutableStateOf(null)
        try{
            compose.setContent{
                Box(Modifier.size(120.dp).background(Color.Black).testTag("retained-image")){
                    RetainedPhotoImage(resource,file.path,full,"测试图片",Modifier.size(120.dp),loader,
                        onLoading={loadingWithImage=it},onSuccess={successes++})
                }
            }
            compose.waitUntil(10000){successes==1}
            compose.runOnIdle{loadingWithImage=null;if(changeResource)resource="second"else full=true}
            compose.waitUntil(10000){calls.get()>1&&loadingWithImage!=null}
            compose.runOnIdle{assertEquals(!changeResource,loadingWithImage)}
            val pixels=compose.onNodeWithTag("retained-image").captureToImage().toPixelMap()
            val center=pixels[pixels.width/2,pixels.height/2]
            if(changeResource)assertTrue("新资源不能继续显示旧照片",center.green<.1f)
            else assertTrue("清晰图等待期间不应出现黑帧",center.green>.9f&&center.red<.1f)
            next.complete(Unit)
            compose.waitUntil(10000){successes>=2}
        }finally{next.complete(Unit);loader.shutdown();file.delete()}
    }
}
