package app.framelink.camera

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.Transaction
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

    @Upsert suspend fun write(task: TransferTask)

    @Transaction suspend fun save(task:TransferTask){if(!historyCleared(task.id))write(task)}

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(task: TransferTask)
    @Transaction suspend fun start(task:TransferTask){if(!historyCleared(task.id))insert(task)}

    @Query("SELECT EXISTS(SELECT 1 FROM cleared_transfer_tasks WHERE id=:id)") suspend fun historyCleared(id:String):Boolean
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun markCleared(task:ClearedTransferTask)
    @Query("DELETE FROM tasks WHERE id=:id AND state IN (:states)") suspend fun deleteEnded(id:String,states:List<String>):Int
    @Query("SELECT * FROM tasks WHERE id IN (:ids) AND state IN (:states)") suspend fun endedByIds(ids:List<String>,states:List<String>):List<TransferTask>
    @Query("DELETE FROM tasks WHERE id IN (:ids) AND state IN (:states)") suspend fun deleteEndedIds(ids:List<String>,states:List<String>):Int
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun markClearedAll(tasks:List<ClearedTransferTask>)
    @Upsert suspend fun saveFile(file:SavedTransferFile)
    @Upsert suspend fun saveFiles(files:List<SavedTransferFile>)
    @Query("SELECT * FROM saved_transfer_files ORDER BY savedAt DESC,id") fun savedFiles():Flow<List<SavedTransferFile>>

    @Query(
        "UPDATE tasks SET done=:done,total=:total,updatedAt=:updated WHERE id=:id AND state=:running"
    )
    suspend fun progress(id: String, done: Long, total: Long, updated: Long, running: String)

    /** 补齐任务文件元数据，不改变终态或恢复已取消的任务 */
    @Query("UPDATE tasks SET photoId=:photoId,title=:title,payload=:payload,updatedAt=:updated WHERE id=:id AND state=:running")
    suspend fun attachFiles(id:String,photoId:String,title:String,payload:String,updated:Long,running:String)

    @Query("UPDATE tasks SET state=:next,error=:reason WHERE state=:old")
    suspend fun recover(old: String, next: String, reason: String)
}

@Database(
    entities = [Photo::class, TransferTask::class, LocalReview::class, SelectionBatch::class,SavedTransferFile::class,ClearedTransferTask::class],
    version = 6,
    exportSchema = false,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun photos(): PhotoDao

    abstract fun tasks(): TaskDao

    abstract fun reviews(): LocalReviewDao
    abstract fun batches(): SelectionBatchDao

    companion object {
        val MIGRATION_5_6=object:androidx.room.migration.Migration(5,6){
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){db.execSQL("ALTER TABLE saved_transfer_files ADD COLUMN uri TEXT NOT NULL DEFAULT ''")}
        }
        val MIGRATION_4_5=object:androidx.room.migration.Migration(4,5){
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS saved_transfer_files (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,path TEXT NOT NULL,kind TEXT NOT NULL,savedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cleared_transfer_tasks (id TEXT NOT NULL PRIMARY KEY)")
            }
        }
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
