package app.framelink.camera

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import org.json.JSONObject

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
        val data = runCatching { JSONObject(task.payload) }.getOrNull() ?: return null
        if (task.kind == TransferKind.FTP_RECEIVE.name && !data.optBoolean("ready")) return null
        val path = data.optString("path")
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf {
            it.isFile &&
                it.canonicalPath.startsWith(
                    File(context.filesDir, "media").canonicalPath + File.separator
                )
        }
    }
}
