package app.framelink.camera

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import org.json.JSONObject

/** 一个可操作的已保存原文件，展示名称与App内部保存路径分开 */
data class TaskFileChoice(val name:String,val file:File)

/** SAF保存副本，始终保留App内已落盘原文件，外部目录失效时可重新授权后重试 */
object FtpStorage {
    fun copyToTree(context: Context, file: File, name: String, tree: Uri): Uri {
        val parent =
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        val uri =
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                MediaFiles.mime(name),
                name,
            ) ?: error("无法创建文件，请重新选择有写入权限的保存目录")
        try {
            copyToUri(context, file, uri)
            return uri
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw error
        }
    }

    fun copyToUri(context: Context, file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output, 65536) }
        } ?: error("无法写入所选文件，请检查目录权限和剩余空间")
    }

    fun taskFile(context: Context, task: TransferTask): File? {
        return taskFiles(context,task).firstOrNull()?.file
    }

    /** 任务只引用App管理目录中的已落盘文件，多格式拍摄保留独立文件名 */
    fun taskFiles(context:Context,task:TransferTask):List<TaskFileChoice>{
        val data = runCatching { JSONObject(task.payload) }.getOrNull() ?: return emptyList()
        if (task.kind == TransferKind.FTP_RECEIVE.name && !data.optBoolean("ready")) return emptyList()
        val records=mutableListOf<Pair<String,String>>()
        data.optJSONArray("files")?.let{items->for(index in 0 until items.length())items.optJSONObject(index)?.let{records+=it.optString("name")to it.optString("path")}}
        if(records.isEmpty())records+=data.optString("name")to data.optString("path")
        val root=File(context.filesDir,"media").canonicalPath+File.separator
        return records.mapNotNull{(name,path)->
            if(path.isBlank())return@mapNotNull null
            val file=File(path)
            file.takeIf{it.isFile&&it.canonicalPath.startsWith(root)}?.let{TaskFileChoice(name.ifBlank{file.name},file)}
        }.distinctBy{it.file.canonicalPath}
    }
}
