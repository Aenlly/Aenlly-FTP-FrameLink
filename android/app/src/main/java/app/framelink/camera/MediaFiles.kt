package app.framelink.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** 原片与预览分离；预览重新编码以移除定位信息 */
object MediaFiles {
    fun syncDirectory(directory: File) {
        val descriptor =
            android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try {
            android.system.Os.fsync(descriptor)
        } finally {
            android.system.Os.close(descriptor)
        }
    }

    fun capturedTime(file: File): Long =
        runCatching {
                val exif = ExifInterface(file)
                val raw =
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                        ?: return@runCatching file.lastModified()
                val date =
                    java.time.LocalDateTime.parse(
                        raw,
                        java.time.format.DateTimeFormatter.ofPattern(
                            "yyyy:MM:dd HH:mm:ss",
                            java.util.Locale.ROOT,
                        ),
                    )
                val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                if (offset != null) date.toInstant(java.time.ZoneOffset.of(offset)).toEpochMilli()
                else date.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            .getOrDefault(file.lastModified())

    fun key(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    fun digest(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun preview(
        context: Context,
        uri: Uri,
        name: String,
        maxEdge: Int = 2560,
        quality: Int = 88,
    ): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "此文件暂无法生成预览，原片仍可保存和分享" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val bitmap =
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } ?: error("预览解码失败")
        val orientation =
            runCatching {
                    context.contentResolver.openInputStream(uri)!!.use {
                        ExifInterface(it)
                            .getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                    }
                }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix =
            Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        postRotate(90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        postRotate(270f)
                        postScale(-1f, 1f)
                    }
                }
            }
        val normalized =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val output =
            File(context.cacheDir, "previews/${key(name)}.jpg").apply { parentFile!!.mkdirs() }
        output.outputStream().use { normalized.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (normalized !== bitmap) normalized.recycle()
        bitmap.recycle()
        return output
    }

    fun publish(
        context: Context,
        file: File,
        displayName: String,
        album: String = "FrameLink",
    ): Uri {
        if (Build.VERSION.SDK_INT < 29) return Uri.fromFile(file)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$album")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(displayName))
                put(MediaStore.Images.Media.DATE_TAKEN, capturedTime(file))
            }
        val uri =
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建相册文件")
        try {
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out, 65536) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return uri
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    fun mime(name: String): String =
        when (name.substringAfterLast('.').lowercase()) {
            "arw" -> "image/x-sony-arw"
            "hif",
            "heif",
            "heic" -> "image/heif"
            "png" -> "image/png"
            "jpg",
            "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
}
