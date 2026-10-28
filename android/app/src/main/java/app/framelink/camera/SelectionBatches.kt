package app.framelink.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 摄影师保存的本次选择快照，独立于客户选片轮次与收藏 */
@Entity(tableName = "selection_batches")
data class SelectionBatch(
    @PrimaryKey val id: String,
    val title: String,
    val photoIdsJson: String,
    val photosJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun photoIds(): List<String> = Json.list(photoIdsJson)
    fun snapshots(): List<Photo> = Json.list(photosJson)
}

@Dao
interface SelectionBatchDao {
    @Query("SELECT * FROM selection_batches ORDER BY updatedAt DESC") fun observe(): kotlinx.coroutines.flow.Flow<List<SelectionBatch>>
    @Query("SELECT * FROM selection_batches WHERE id=:id") suspend fun find(id: String): SelectionBatch?
    @Query("SELECT * FROM selection_batches") suspend fun all(): List<SelectionBatch>
    @Upsert suspend fun save(batch: SelectionBatch)
    @Query("DELETE FROM selection_batches WHERE id=:id") suspend fun delete(id: String)
}

class SelectionBatchRepository(private val context: Context, private val db: LocalDatabase) {
    suspend fun save(title: String, ids: List<String>): SelectionBatch = withContext(Dispatchers.IO) {
        val clean = title.trim()
        require(clean.isNotBlank() && clean.length <= 80) { "请填写80字以内的选片名称" }
        val unique = ids.distinct()
        require(unique.isNotEmpty()) { "请先选择照片" }
        val found = unique.chunked(500).flatMap { db.photos().byIds(it) }.associateBy { it.id }
        require(found.size == unique.size) { "部分照片记录已变化，请重新选择" }
        SelectionBatch(UUID.randomUUID().toString(), clean, Json.encode(unique), Json.encode(unique.map { found.getValue(it).copy() })).also { db.batches().save(it) }
    }

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        val batch = db.batches().find(id) ?: error("选片记录不存在")
        require(title.trim().isNotBlank() && title.trim().length <= 80) { "请填写80字以内的名称" }
        db.batches().save(batch.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        UUID.fromString(id)
        db.batches().delete(id)
        File(context.cacheDir, "selection-sheets/$id").deleteRecursively()
        Unit
    }

    /** 每页12张、逐张采样生成JPEG预览选片单，不读取整张RAW进入内存 */
    suspend fun contactSheets(batch: SelectionBatch): List<File> = withContext(Dispatchers.IO) {
        val current = batch.photoIds().chunked(500).flatMap { db.photos().byIds(it) }.associateBy { it.id }
        val photos = batch.snapshots().associate { snapshot ->
            val photo = current[snapshot.id]
            snapshot.id to if (photo != null && !photo.localHidden && photo.capturedAt == snapshot.capturedAt && photo.name == snapshot.name) photo else snapshot.copy(filesJson = "[]", previewPath = "")
        }
        val pages = batch.photoIds().chunked(12)
        val directory = File(context.cacheDir, "selection-sheets/${batch.id}/${UUID.randomUUID()}").apply { mkdirs() }
        val result = mutableListOf<File>()
        try {
            for ((pageIndex, page) in pages.withIndex()) {
                coroutineContext.ensureActive()
                val columns = minOf(3, page.size)
                val cellWidth = (1680 - (columns - 1) * 30) / columns
                val imageHeight = cellWidth * 2 / 3
                val rowHeight = imageHeight + 140
                val pageHeight = 280 + ((page.size + columns - 1) / columns) * rowHeight
                val bitmap = Bitmap.createBitmap(1800, pageHeight, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 40, 49); textSize = 52f; typeface = Typeface.DEFAULT_BOLD }
                    canvas.drawText(batch.title.take(28), 60f, 88f, paint)
                    paint.textSize = 28f; paint.typeface = Typeface.DEFAULT; paint.color = Color.DKGRAY
                    canvas.drawText("${batch.photoIds().size} 张已选 · " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(batch.createdAt)), 60f, 138f, paint)
                    for ((index, id) in page.withIndex()) {
                        coroutineContext.ensureActive()
                        val photo = photos[id]
                        val left = 60f + index % columns * (cellWidth + 30)
                        val top = 190f + index / columns * rowHeight
                        val area = RectF(left, top, left + cellWidth, top + imageHeight)
                        paint.color = Color.rgb(242, 243, 245); canvas.drawRoundRect(area, 12f, 12f, paint)
                        val preview = photo?.let { thumbnail(it) }
                        if (preview != null) {
                            val scale = minOf(area.width() / preview.width, area.height() / preview.height)
                            val width = preview.width * scale; val height = preview.height * scale
                            val target = RectF(area.centerX() - width / 2, area.centerY() - height / 2, area.centerX() + width / 2, area.centerY() + height / 2)
                            canvas.drawBitmap(preview, null, target, paint)
                            preview.recycle()
                        } else {
                            paint.color = Color.GRAY; paint.textSize = 28f
                            canvas.drawText("预览暂不可用", area.centerX() - 90f, area.centerY(), paint)
                        }
                        paint.color = Color.rgb(32, 40, 49); paint.textSize = 25f
                        val name = photo?.name ?: "照片记录已移除"
                        val labelPaint = android.text.TextPaint(paint)
                        val label = android.text.StaticLayout.Builder.obtain("${pageIndex * 12 + index + 1}. $name", 0, "${pageIndex * 12 + index + 1}. $name".length, labelPaint, cellWidth)
                            .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
                        canvas.save(); canvas.translate(left, top + imageHeight + 12f); label.draw(canvas); canvas.restore()
                        paint.color = Color.GRAY; paint.textSize = 22f
                        canvas.drawText(if (photo?.imported == true && !photo.localHidden) "已导入手机" else "相机照片 / 缓存预览", left, top + imageHeight + 79f, paint)
                    }
                    paint.textSize = 25f; paint.color = Color.GRAY
                    canvas.drawText("光屿 FrameLink · 预览选片单，非原片 · ${pageIndex + 1}/${pages.size}", 60f, pageHeight - 60f, paint)
                    val file = File(directory, "选片单-${pageIndex + 1}.jpg")
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) { "选片单保存失败" } }
                    result += file
                } finally { bitmap.recycle() }
            }
            result
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
    }

    private fun thumbnail(photo: Photo): Bitmap? = runCatching {
        val source = if (photo.previewPath.isNotBlank() && File(photo.previewPath).isFile) File(photo.previewPath).toURI().toString() else photo.displayUri
        if (source.isBlank()) return null
        fun open() = if (source.startsWith("content:") || source.startsWith("file:")) context.contentResolver.openInputStream(android.net.Uri.parse(source)) else File(source).inputStream()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 720) sample *= 2
        open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()
}
