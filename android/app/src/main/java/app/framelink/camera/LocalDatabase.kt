package app.framelink.camera

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY capturedAt DESC, id") fun observe(): Flow<List<Photo>>

    @Query("SELECT * FROM photos") suspend fun all(): List<Photo>

    @Query("SELECT * FROM photos WHERE id=:id") suspend fun find(id: String): Photo?

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<Photo>

    @Query("SELECT * FROM photos WHERE sourceKey=:key") suspend fun bySource(key: String): Photo?

    @Query("SELECT * FROM photos WHERE projectId=:id AND localHidden=0 ORDER BY capturedAt")
    suspend fun project(id: String): List<Photo>

    @Upsert suspend fun save(photo: Photo)

    @Upsert suspend fun saveAll(photos: List<Photo>)

    @Query("UPDATE photos SET mark=:mark WHERE id=:id") suspend fun mark(id: String, mark: String)

    @Query("UPDATE photos SET projectId=:project WHERE id IN (:ids)")
    suspend fun project(ids: List<String>, project: String)

    @Query("UPDATE photos SET previewPath='' WHERE previewPath LIKE :prefix")
    suspend fun clearPreviews(prefix: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") fun observe(): Flow<List<TransferTask>>

    @Query("SELECT * FROM tasks") suspend fun all(): List<TransferTask>

    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun find(id: String): TransferTask?

    @Query("SELECT * FROM tasks WHERE state=:state ORDER BY createdAt")
    suspend fun inState(state: String): List<TransferTask>

    @Query("SELECT * FROM tasks WHERE kind=:kind")
    suspend fun byKind(kind: String): List<TransferTask>

    @Query("SELECT * FROM tasks WHERE photoId=:photo AND kind=:kind AND state NOT IN (:terminal)")
    suspend fun active(photo: String, kind: String, terminal: List<String>): List<TransferTask>

    @Upsert suspend fun save(task: TransferTask)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun start(task: TransferTask)

    @Query(
        "UPDATE tasks SET done=:done,total=:total,updatedAt=:updated WHERE id=:id AND state=:running"
    )
    suspend fun progress(id: String, done: Long, total: Long, updated: Long, running: String)

    @Query("UPDATE tasks SET state=:next,error=:reason WHERE state=:old")
    suspend fun recover(old: String, next: String, reason: String)
}

@Database(
    entities = [Photo::class, TransferTask::class, LocalReview::class, SelectionBatch::class],
    version = 4,
    exportSchema = false,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun photos(): PhotoDao

    abstract fun tasks(): TaskDao

    abstract fun reviews(): LocalReviewDao
    abstract fun batches(): SelectionBatchDao

    companion object {
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS selection_batches (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,photoIdsJson TEXT NOT NULL,photosJson TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE photos ADD COLUMN localHidden INTEGER NOT NULL DEFAULT 0")
                }
            }

        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS local_reviews (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,photoIdsJson TEXT NOT NULL,choicesJson TEXT NOT NULL,locked INTEGER NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)"
                    )
                }
            }
    }
}
