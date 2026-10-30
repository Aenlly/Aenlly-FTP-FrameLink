package app.framelink.camera

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import androidx.room.ColumnInfo
import android.net.Uri
import java.io.File
import org.json.JSONObject

/** 文件索引独立于传输历史，清记录不丢失打开、分享和另存入口 */
@Entity(tableName="saved_transfer_files")
data class SavedTransferFile(@PrimaryKey val id:String,val name:String,val path:String,val kind:String,val savedAt:Long,@ColumnInfo(defaultValue="''")val uri:String="")

/** 已清历史的任务ID用于拒绝迟到回调和接收恢复重新生成旧记录 */
@Entity(tableName="cleared_transfer_tasks")
data class ClearedTransferTask(@PrimaryKey val id:String)

internal object TransferHistory{
    // 为SQLite常见的999个绑定参数限制保留状态参数余量
    private const val DELETE_BATCH_SIZE=900
    val ended=listOf(TransferState.COMPLETE.name,TransferState.CANCELLED.name)
    fun removable(task:TransferTask)=task.state in ended
    private fun retainedFiles(runtime:CameraRuntime,task:TransferTask):List<SavedTransferFile>{
        val files=FtpStorage.taskFiles(runtime.context,task).map{file->SavedTransferFile(MediaFiles.key(file.file.canonicalPath),file.name,file.file.canonicalPath,task.kind,task.updatedAt)}.toMutableList()
        val data=runCatching{JSONObject(task.payload)}.getOrNull()?:return files
        val name=data.optString("name").ifBlank{task.title}
        val root=File(runtime.context.filesDir,"media").canonicalPath+File.separator
        // 外部保存与压缩副本可能只由任务引用，清历史时保留URI，不访问或删除外部文件
        for(key in listOf("compressedUri","exportedUri","uri")){
            val raw=data.optString(key);if(raw.isBlank())continue
            val uri=Uri.parse(raw);val label=if(key=="compressedUri")name.substringBeforeLast('.')+"_share.jpg"else name
            if(uri.scheme=="content"&&!uri.authority.isNullOrBlank())files+=SavedTransferFile(MediaFiles.key("uri:$raw"),label,"",task.kind,task.updatedAt,raw)
            else if(uri.scheme=="file"){
                val file=uri.path?.let(::File)?:continue
                if(file.isFile&&file.canonicalPath.startsWith(root))files+=SavedTransferFile(MediaFiles.key(file.canonicalPath),label,file.canonicalPath,task.kind,task.updatedAt)
            }
        }
        return files
    }

    suspend fun clear(runtime:CameraRuntime,ids:List<String>):Int=runtime.database.withTransaction{
        var removed=0
        // 确认时的ID快照限定范围，事务内重新核对状态，避免清掉已开始重试的任务
        for(batch in ids.distinct().chunked(DELETE_BATCH_SIZE)){
            val tasks=runtime.database.tasks().endedByIds(batch,ended)
            val files=tasks.flatMap{task->retainedFiles(runtime,task)}
                .groupBy{it.id}.map{(_,values)->values.maxBy{it.savedAt}}
            runtime.database.tasks().saveFiles(files)
            runtime.database.tasks().markClearedAll(tasks.map{ClearedTransferTask(it.id)})
            removed+=runtime.database.tasks().deleteEndedIds(tasks.map{it.id},ended)
        }
        removed
    }
}
