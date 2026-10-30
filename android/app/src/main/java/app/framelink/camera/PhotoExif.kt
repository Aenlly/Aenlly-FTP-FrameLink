package app.framelink.camera

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** 单张照片的拍摄记录，仅来自文件EXIF，不使用相机当前参数补值 */
internal data class PhotoExif(
    val source:String,
    val fileName:String,
    val iso:String?=null,
    val shutter:String?=null,
    val aperture:String?=null,
    val focalLength:String?=null,
    val camera:String?=null,
    val lens:String?=null,
    val takenAt:String?=null,
    val dimensions:String?=null,
    val exposureBias:String?=null,
    val exposureMode:String?=null,
    val whiteBalance:String?=null,
    val metering:String?=null,
    val flash:String?=null,
    val equivalentFocalLength:String?=null,
    val software:String?=null,
    val artist:String?=null,
    val copyright:String?=null,
    val location:PhotoLocation?=null,
){
    val hasExposure get()=iso!=null&&shutter!=null&&aperture!=null
    val score get()=listOf(iso,shutter,aperture).count{it!=null}*3+listOf(focalLength,camera,lens,takenAt).count{it!=null}
}

internal data class PhotoLocation(val latitude:Double,val longitude:Double,val altitude:Double?,val datum:String?){
    init{require(latitude.isFinite()&&longitude.isFinite()&&latitude in -90.0..90.0&&longitude in -180.0..180.0)}
    val coordinates get()=String.format(Locale.ROOT,"%.6f, %.6f",latitude,longitude)
    val canOpenMap get()=datum==null||datum.uppercase(Locale.ROOT).filter{it.isLetterOrDigit()}=="WGS84"
    fun geoUri()=String.format(Locale.ROOT,"geo:%.7f,%.7f?q=%.7f,%.7f",latitude,longitude,latitude,longitude)
}

internal object PhotoExifText{
    fun decimal(value:Double):String?=value.takeIf{it.isFinite()}?.let{
        BigDecimal.valueOf(it).setScale(3,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
    fun number(value:Double):String?=value.takeIf{it.isFinite()&&it>0}?.let{
        decimal(it)
    }
    fun shutter(seconds:Double):String?{
        if(!seconds.isFinite()||seconds<=0)return null
        val denominator=(1.0/seconds).takeIf{it<Long.MAX_VALUE}?.roundToLong()
        return if(seconds<1&&denominator!=null&&denominator>1&&abs(seconds*denominator-1)<.02)"1/$denominator 秒"
            else number(seconds)?.let{"$it 秒"}
    }
}

internal object PhotoExifReader{
    /** 按原片、临时JPEG和已有预览的顺序读取；不触发网络、导入或相册写入 */
    fun read(context:Context,photo:Photo,cachedJpeg:String?):PhotoExif?{
        val sources=mutableListOf<Triple<String,String,String>>()
        photo.files().filter{!it.raw&&it.savedUri.isNotBlank()}.forEach{sources+=Triple(it.savedUri,"已导入原片",it.name)}
        if(!cachedJpeg.isNullOrBlank())sources+=Triple(cachedJpeg,"JPEG临时缓存",photo.files().firstOrNull{it.formatTitle()=="JPEG"}?.name.orEmpty())
        photo.files().filter{it.raw&&it.savedUri.isNotBlank()}.forEach{sources+=Triple(it.savedUri,"已导入RAW",it.name)}
        if(photo.previewPath.isNotBlank())sources+=Triple(photo.previewPath,"预览副本",photo.name)
        var best:PhotoExif?=null
        for((source,label,name) in sources.distinctBy{it.first}){
            val result=runCatching{
                val uri=Uri.parse(source)
                val stream=if(uri.scheme.isNullOrBlank())File(source).inputStream()else requireNotNull(context.contentResolver.openInputStream(uri))
                stream.use{extract(ExifInterface(it),label,name)}
            }.getOrNull()?:continue
            if(result.hasExposure)return result
            if(best==null||result.score>best.score)best=result
        }
        return best
    }

    private fun extract(exif:ExifInterface,source:String,fileName:String):PhotoExif{
        fun text(tag:String)=exif.getAttribute(tag)?.replace('\u0000',' ')?.trim()?.takeIf{it.isNotBlank()}
        val primaryIso=exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,0)
        val iso=primaryIso.takeIf{it in 1..65534}?:exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED,0).takeIf{it>0}
        val maker=text(ExifInterface.TAG_MAKE)
        val model=text(ExifInterface.TAG_MODEL)
        val camera=when{model==null->maker;maker==null||model.uppercase(Locale.ROOT).contains(maker.uppercase(Locale.ROOT))->model;else->"$maker $model"}
        val date=text(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeUnless{it.startsWith("0000")}?.replaceFirst(Regex("^(\\d{4}):(\\d{2}):(\\d{2})"),"$1-$2-$3")
        val offset=text(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        val width=exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION,0).takeIf{it>0}?:exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH,0)
        val height=exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION,0).takeIf{it>0}?:exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH,0)
        val coordinates=exif.latLong?.takeIf{it.size==2&&it.all{value->value.isFinite()}&&it[0] in -90.0..90.0&&it[1] in -180.0..180.0}
        val location=coordinates?.let{PhotoLocation(it[0],it[1],exif.getAltitude(Double.NaN).takeIf{v->v.isFinite()},text(ExifInterface.TAG_GPS_MAP_DATUM))}
        val bias=exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE,Double.NaN)
        val mode=when(exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM,-1)){
            1->"手动曝光";2->"程序自动";3->"光圈优先";4->"快门优先";5->"创意程序";6->"动作程序";7->"人像程序";8->"风景程序"
            else->when(exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_MODE,-1)){0->"自动曝光";1->"手动曝光";2->"包围曝光";else->null}
        }
        return PhotoExif(source=source,fileName=fileName,iso=iso?.toString(),
            shutter=PhotoExifText.shutter(exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME,Double.NaN)),
            aperture=PhotoExifText.number(exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER,Double.NaN))?.let{"f/$it"},
            focalLength=PhotoExifText.number(exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH,Double.NaN))?.let{"$it mm"},
            camera=camera,lens=text(ExifInterface.TAG_LENS_MODEL),takenAt=date?.let{it+if(offset!=null)" $offset"else""},
            dimensions=if(width>0&&height>0)"$width × $height"else null,
            exposureBias=PhotoExifText.decimal(bias)?.let{(if(bias>0)"+"else"")+it+" EV"},exposureMode=mode,
            whiteBalance=when(exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE,-1)){0->"自动";1->"手动";else->null},
            metering=when(exif.getAttributeInt(ExifInterface.TAG_METERING_MODE,-1)){1->"平均测光";2->"中央重点";3->"点测光";4->"多点测光";5->"多区测光";6->"局部测光";255->"其他";else->null},
            flash=exif.getAttributeInt(ExifInterface.TAG_FLASH,-1).takeIf{it>=0}?.let{if((it and 1)==1)"已触发"else"未触发"},
            equivalentFocalLength=exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,0).takeIf{it>0}?.let{"$it mm"},
            software=text(ExifInterface.TAG_SOFTWARE),artist=text(ExifInterface.TAG_ARTIST),copyright=text(ExifInterface.TAG_COPYRIGHT),location=location)
    }
}
