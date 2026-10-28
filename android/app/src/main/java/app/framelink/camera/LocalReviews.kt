package app.framelink.camera

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 现场客户选片独立保存，不修改摄影师的收藏和标记 */
@Entity(tableName = "local_reviews")
data class LocalReview(
    @PrimaryKey val id: String,
    val title: String,
    val photoIdsJson: String,
    val choicesJson: String = "{}",
    val locked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun photoIds(): List<String> = Json.list(photoIdsJson)

    fun choices(): Map<String, LocalChoice> = Json.decode(choicesJson)
}

data class LocalChoice(val selected: Boolean = false, val comment: String = "")

@Dao
interface LocalReviewDao {
    @Query("SELECT * FROM local_reviews") suspend fun all(): List<LocalReview>

    @Query("SELECT * FROM local_reviews ORDER BY updatedAt DESC")
    fun observe(): Flow<List<LocalReview>>

    @Query("SELECT * FROM local_reviews WHERE id=:id") fun observe(id: String): Flow<LocalReview?>

    @Query("SELECT * FROM local_reviews WHERE id=:id") suspend fun find(id: String): LocalReview?

    @Upsert suspend fun save(review: LocalReview)
    @Query("DELETE FROM local_reviews WHERE id=:id") suspend fun delete(id: String)
}

class LocalReviewRepository(private val context: Context, private val db: LocalDatabase) {
    private val writes = Mutex()

    suspend fun rename(id: String, title: String) = writes.withLock { withContext(Dispatchers.IO) {
        require(title.trim().isNotBlank() && title.trim().length <= 160) { "请填写160字以内的姓名、手机号或场次名称" }
        val review = db.reviews().find(id) ?: error("选片记录不存在")
        db.reviews().save(review.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
    } }

    suspend fun delete(id: String) = writes.withLock { withContext(Dispatchers.IO) {
        UUID.fromString(id)
        db.reviews().delete(id)
        File(context.filesDir, "media/reviews/$id.csv").delete()
        File(context.cacheDir, "selection-sheets/$id").deleteRecursively()
        Unit
    } }

    suspend fun create(title: String, photos: List<String>): LocalReview =
        withContext(Dispatchers.IO) {
            require(photos.isNotEmpty()) { "先选择一些照片，再进入客户选片" }
            require(title.trim().isNotBlank() && title.trim().length <= 160) { "请填写160字以内的姓名、手机号或场次名称" }
            val review =
                LocalReview(
                    UUID.randomUUID().toString(),
                    title.trim(),
                    Json.encode(photos.distinct()),
                )
            db.reviews().save(review)
            review
        }

    suspend fun choose(
        id: String,
        photoId: String,
        selected: Boolean? = null,
        comment: String? = null,
    ) =
        writes.withLock {
            withContext(Dispatchers.IO) {
                val review = db.reviews().find(id) ?: error("选片记录不存在")
                check(!review.locked) { "本轮已完成，请先开放修改" }
                require(
                    photoId in review.photoIds() && (comment == null || comment.length <= 2000)
                ) {
                    "选片记录无效"
                }
                val choices = review.choices().toMutableMap()
                val previous = choices[photoId] ?: LocalChoice()
                choices[photoId] =
                    previous.copy(
                        selected = selected ?: previous.selected,
                        comment = comment ?: previous.comment,
                    )
                db.reviews()
                    .save(
                        review.copy(
                            choicesJson = Json.encode(choices),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
            }
        }

    suspend fun lock(id: String, value: Boolean) =
        writes.withLock {
            withContext(Dispatchers.IO) {
                val review = db.reviews().find(id) ?: error("选片记录不存在")
                db.reviews()
                    .save(review.copy(locked = value, updatedAt = System.currentTimeMillis()))
            }
        }

    suspend fun export(id: String): File =
        withContext(Dispatchers.IO) {
            val review = db.reviews().find(id) ?: error("选片记录不存在")
            val photos =
                review
                    .photoIds()
                    .chunked(500)
                    .flatMap { db.photos().byIds(it) }
                    .associateBy { it.id }
            val file =
                File(context.filesDir, "media/reviews/$id.csv").apply { parentFile!!.mkdirs() }
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                out.write("\uFEFF文件名,客户已选,客户留言\r\n")
                for (photoId in review.photoIds()) {
                    val photo = photos[photoId] ?: continue
                    val choice = review.choices()[photoId] ?: LocalChoice()
                    for (part in photo.files()) {
                        out.write(
                            csv(part.name) +
                                "," +
                                (if (choice.selected) "是" else "否") +
                                "," +
                                csv(choice.comment) +
                                "\r\n"
                        )
                    }
                }
            }
            file
        }

    private fun csv(value: String): String {
        val safe =
            if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
