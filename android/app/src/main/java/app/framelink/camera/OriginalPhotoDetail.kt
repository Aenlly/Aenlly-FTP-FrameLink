package app.framelink.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class OriginalDetailFrame(val bitmap:Bitmap,val destination:RectF)

/** 手势停稳后仅解码原片当前可见区域，避免把整张高像素照片常驻内存 */
@Composable
internal fun OriginalPhotoDetail(uri:String,zoom:Float,pan:Offset,viewport:IntSize){
    val context=LocalContext.current
    var frame by remember(uri,zoom,pan,viewport){mutableStateOf<OriginalDetailFrame?>(null)}
    LaunchedEffect(uri,zoom,pan,viewport){
        if(zoom<=1.15f||viewport.width<=0||viewport.height<=0)return@LaunchedEffect
        delay(120)
        try{frame=withContext(Dispatchers.IO){decodeOriginalDetail(context,uri,zoom,pan,viewport)}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){/* 不支持区域解码的格式继续显示已有整图 */}
    }
    frame?.let{detail->
        val image=remember(detail){detail.bitmap.asImageBitmap()}
        Canvas(Modifier.fillMaxSize().semantics{contentDescription="原片细节已显示"}){
            drawImage(image,dstOffset=IntOffset(detail.destination.left.roundToInt(),detail.destination.top.roundToInt()),
                dstSize=IntSize(detail.destination.width().roundToInt().coerceAtLeast(1),detail.destination.height().roundToInt().coerceAtLeast(1)))
        }
    }
}

/** EXIF变换保留镜像与旋转，区域坐标统一使用用户看到的正向照片 */
internal fun originalOrientation(orientation:Int,width:Int,height:Int):Matrix{
    val values=when(orientation){
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL->floatArrayOf(-1f,0f,0f,0f,1f,0f,0f,0f,1f)
        ExifInterface.ORIENTATION_ROTATE_180->floatArrayOf(-1f,0f,0f,0f,-1f,0f,0f,0f,1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL->floatArrayOf(1f,0f,0f,0f,-1f,0f,0f,0f,1f)
        ExifInterface.ORIENTATION_TRANSPOSE->floatArrayOf(0f,1f,0f,1f,0f,0f,0f,0f,1f)
        ExifInterface.ORIENTATION_ROTATE_90->floatArrayOf(0f,-1f,0f,1f,0f,0f,0f,0f,1f)
        ExifInterface.ORIENTATION_TRANSVERSE->floatArrayOf(0f,-1f,0f,-1f,0f,0f,0f,0f,1f)
        ExifInterface.ORIENTATION_ROTATE_270->floatArrayOf(0f,1f,0f,-1f,0f,0f,0f,0f,1f)
        else->floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
    }
    return Matrix().apply{
        setValues(values)
        val bounds=RectF(0f,0f,width.toFloat(),height.toFloat()).also{mapRect(it)}
        postTranslate(-bounds.left,-bounds.top)
    }
}

@Suppress("DEPRECATION")
internal fun decodeOriginalDetail(context:Context,source:String,zoom:Float,pan:Offset,viewport:IntSize):OriginalDetailFrame?{
    val uri=Uri.parse(source)
    fun open()=if(uri.scheme.isNullOrBlank())File(source).inputStream()else requireNotNull(context.contentResolver.openInputStream(uri))
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    open().use{BitmapFactory.decodeStream(it,null,bounds)}
    if(bounds.outWidth<=0||bounds.outHeight<=0)return null
    val orientation=open().use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}
    val matrix=originalOrientation(orientation,bounds.outWidth,bounds.outHeight)
    val full=RectF(0f,0f,bounds.outWidth.toFloat(),bounds.outHeight.toFloat()).also{matrix.mapRect(it)}
    val factor=minOf(viewport.width/full.width(),viewport.height/full.height())*zoom
    val left=(viewport.width-full.width()*factor)/2f+pan.x
    val top=(viewport.height-full.height()*factor)/2f+pan.y
    val visible=RectF(-left/factor,-top/factor,(viewport.width-left)/factor,(viewport.height-top)/factor)
    if(!visible.intersect(full))return null
    val inverse=Matrix();check(matrix.invert(inverse));inverse.mapRect(visible)
    val region=Rect(floor(visible.left).toInt().coerceAtLeast(0),floor(visible.top).toInt().coerceAtLeast(0),
        ceil(visible.right).toInt().coerceAtMost(bounds.outWidth),ceil(visible.bottom).toInt().coerceAtMost(bounds.outHeight))
    if(region.isEmpty)return null
    val logical=RectF(region).also{matrix.mapRect(it)}
    var sample=1
    while(logical.width()/(sample*2)>=logical.width()*factor&&logical.height()/(sample*2)>=logical.height()*factor)sample*=2
    val decoded=open().use{stream->
        val decoder=requireNotNull(BitmapRegionDecoder.newInstance(stream,false))
        try{decoder.decodeRegion(region,BitmapFactory.Options().apply{inSampleSize=sample})}finally{decoder.recycle()}
    }?:return null
    val oriented=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,originalOrientation(orientation,decoded.width,decoded.height),true)
    if(oriented!==decoded)decoded.recycle()
    return OriginalDetailFrame(oriented,RectF(left+logical.left*factor,top+logical.top*factor,left+logical.right*factor,top+logical.bottom*factor))
}
