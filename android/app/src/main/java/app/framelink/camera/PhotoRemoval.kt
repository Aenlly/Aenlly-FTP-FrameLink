package app.framelink.camera

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject

/** 确认前冻结照片与本机文件范围，不包含相机卡、云端资源或导入来源文件 */
data class PhotoRemovalPlan(
    val photos: List<Photo>,
    val assets: Map<String, Set<String>>,
    val retainedShared: Int,
    val reviewCount: Int,
)

data class PhotoRemovalResult(val removedIds: List<String>, val failures: List<String>, val warnings: List<String> = emptyList())

class PhotoRemovalPermission(val request: IntentSender) : Exception("需要系统授权后继续删除")

/** 保留照片身份与选片历史，移出图库不会被FTP恢复任务再次加入 */
class PhotoRemovalRepository(private val context: Context, private val db: LocalDatabase) {
    private val terminal = setOf(TransferState.COMPLETE.name, TransferState.CANCELLED.name, TransferState.FAILED.name)

    suspend fun plan(ids: List<String>): PhotoRemovalPlan {
        val all = db.photos().all()
        val idsSet = ids.toSet()
        val selected = all.filter { it.id in idsSet && it.inLibrary }
        require(selected.isNotEmpty()) { "请先选择已导入的照片" }
        val related = relatedTasks(all, db.tasks().all())
        checkIdle(selected, related)
        val selectedIds = selected.map { it.id }.toSet()
        val otherAssets = all.filter { it.id !in selectedIds }.flatMap { assets(it, related[it.id].orEmpty()) }.toSet()
        val chosen = selected.associate { it.id to assets(it, related[it.id].orEmpty()) }
        val shared = chosen.values.flatten().toSet().intersect(otherAssets)
        val reviews = db.reviews().all().count { review -> review.photoIds().any { it in selectedIds } } +
            db.batches().all().count { batch -> batch.photoIds().any { it in selectedIds } }
        return PhotoRemovalPlan(selected, chosen.mapValues { (_, files) -> files - shared }, shared.size, reviews)
    }

    suspend fun remove(plan: PhotoRemovalPlan, deleteFiles: Boolean): PhotoRemovalResult {
        val current = plan.photos.map { it.id }.chunked(500).flatMap { db.photos().byIds(it) }.filter { !it.localHidden }
        val expected = plan.photos.associateBy { it.id }
        check(current.all { it.filesJson == expected.getValue(it.id).filesJson }) { "照片文件已变化，请重新选择后操作" }
        val refreshed = this.plan(current.map { it.id })
        val allowedAssets = plan.assets.mapValues { (id, files) -> files.intersect(refreshed.assets[id].orEmpty()) }
        // 文件与系统相册不可参加数据库事务，先完成所有需要系统授权的副本删除
        val selectedAssets = current.flatMap { allowedAssets[it.id].orEmpty() }.toSet()
        if (deleteFiles) {
            selectedAssets.filter { it.startsWith("file:") }.forEach { requireManagedFile(File(Uri.parse(it).path!!)) }
            selectedAssets.filter { it.startsWith("content:") }.forEach { removeContent(Uri.parse(it)) }
        }
        val removed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (photo in current) {
            try {
                if (deleteFiles) {
                    allowedAssets[photo.id].orEmpty().filter { it.startsWith("file:") }.forEach { location ->
                        val file = requireManagedFile(File(Uri.parse(location).path!!))
                        if (file.exists() && !file.delete()) {
                            if (file.path.startsWith(File(context.cacheDir, "previews").canonicalPath + File.separator)) {
                                warnings += "预览缓存未清除，可在设置中重试清理"
                            } else error("无法删除${file.name}，请检查文件占用和存储权限")
                        }
                    }
                }
                // 逐张落库保留部分成功结果，单个文件失败不会撤回其他照片的处理状态
                db.photos().save(
                    if (deleteFiles) photo.copy(
                        filesJson = Json.encode(photo.files().map { it.copy(originalPath = "", localUri = "") }),
                        previewPath = "", published = false, localHidden = true,
                    ) else photo.copy(localHidden = true)
                )
                removed += photo.id
            } catch (error: Exception) {
                // 已删除的文件不再作为可用原片展示，其余记录保留供用户重试
                db.photos().save(photo.copy(filesJson = Json.encode(photo.files().map { part ->
                    part.copy(
                        originalPath = part.originalPath.takeIf { it.isNotBlank() && File(it).isFile }.orEmpty(),
                        localUri = part.localUri.takeUnless { locationKey(it) in selectedAssets }.orEmpty(),
                    )
                })))
                failures += "${photo.name}：${error.message ?: "删除未完成"}"
            }
        }
        return PhotoRemovalResult(removed, failures, warnings.distinct())
    }

    private fun checkIdle(photos: List<Photo>, related: Map<String, List<TransferTask>>) {
        check(photos.none { photo -> related[photo.id].orEmpty().any { it.state !in terminal } }) {
            "所选照片仍有待处理的传输，请先在传输页完成或取消，再移除"
        }
    }

    private fun relatedTasks(photos: List<Photo>, tasks: List<TransferTask>): Map<String, List<TransferTask>> {
        val byId = photos.associateBy { it.id }
        val byKey = photos.associateBy { it.sourceKey }
        val byPath = photos.flatMap { photo -> photo.files().filter { it.originalPath.isNotBlank() }.map { it.originalPath to photo } }.toMap()
        val byImport = byPath.entries.mapNotNull { (path, photo) ->
            val name = File(path).name
            if (File(path).parentFile == File(context.filesDir, "media/import") && name.length > 36) name.take(36) to photo else null
        }.toMap()
        val result = mutableMapOf<String, MutableList<TransferTask>>()
        val payloads = tasks.associate { it.id to runCatching { JSONObject(it.payload) }.getOrNull() }
        for (task in tasks) {
            val data = payloads[task.id]
            val photo = byId[task.photoId] ?: byKey[data?.optString("photoKey")] ?: byPath[data?.optString("path")] ?: byImport[task.id]
            if (photo != null) result.getOrPut(photo.id) { mutableListOf() } += task
        }
        val byUri = photos.flatMap { photo ->
            assets(photo, result[photo.id].orEmpty()).mapNotNull { location -> transferUriKey(location)?.let { it to photo } }
        }.groupBy({ it.first }, { it.second })
        for (task in tasks) {
            val owners = byUri[payloads[task.id]?.optString("uri")?.let(::transferUriKey)].orEmpty().distinctBy { it.id }
            owners.forEach { owner ->
                val linked = result.getOrPut(owner.id) { mutableListOf() }
                if (linked.none { it.id == task.id }) linked += task
            }
        }
        return result
    }

    private fun assets(photo: Photo, tasks: List<TransferTask>): Set<String> {
        val result = mutableSetOf<String>()
        fun add(location: String) {
            locationKey(location)?.let { result += it }
        }
        photo.files().forEach { add(it.originalPath); add(it.localUri) }
        add(photo.previewPath)
        tasks.forEach { task ->
            val data = runCatching { JSONObject(task.payload) }.getOrNull() ?: return@forEach
            add(data.optString("exportedUri"))
            add(data.optString("compressedUri"))
        }
        return result
    }

    private fun locationKey(location: String): String? {
        if (location.isBlank()) return null
        val uri = Uri.parse(location)
        return when (uri.scheme) {
            "content" -> uri.toString()
            "file" -> File(requireNotNull(uri.path)).canonicalFile.toURI().toString()
            null -> File(location).canonicalFile.toURI().toString()
            else -> null
        }
    }

    /** 仅用于待传输关联匹配，保留原始URI执行删除，避免改变文件提供方的授权范围 */
    private fun transferUriKey(location: String): String? {
        if (location.isBlank()) return null
        val uri = Uri.parse(location)
        if (uri.authority == "com.android.providers.media.documents") {
            val document = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
            if (document.startsWith("image:") && document.substringAfter(':').toLongOrNull() != null)
                return "media-image:${document.substringAfter(':')}"
        }
        if (uri.authority == MediaStore.AUTHORITY) {
            val segments = uri.pathSegments
            if (segments.size == 4 && segments[0] in setOf("external", "external_primary") && segments[1] == "images" && segments[2] == "media")
                return "media-image:${segments[3]}"
        }
        if (uri.scheme == "content") {
            val document = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (document != null) return "document:${uri.authority}:$document"
        }
        return locationKey(location)
    }

    private fun requireManagedFile(file: File): File {
        val canonical = file.canonicalFile
        val roots = listOf(File(context.filesDir, "media"), File(context.cacheDir, "previews"))
        check(roots.any { canonical.path.startsWith(it.canonicalPath + File.separator) } && !canonical.isDirectory) {
            "文件不在App管理目录内，已保留文件；可选择仅从图库移除"
        }
        return canonical
    }

    private fun removeContent(uri: Uri) {
        try {
            val deleted = if (DocumentsContract.isDocumentUri(context, uri)) {
                if (DocumentsContract.deleteDocument(context.contentResolver, uri)) 1 else 0
            } else context.contentResolver.delete(uri, null, null)
            if (deleted == 0) {
                val exists = context.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() } ?: false
                check(!exists) { "系统未删除文件，请确认授权后重试" }
            }
        } catch (_: FileNotFoundException) {
            // 已在系统相册中删除的副本视为完成，继续清理App原片
        } catch (error: SecurityException) {
            if (Build.VERSION.SDK_INT >= 30 && uri.authority == MediaStore.AUTHORITY) {
                throw PhotoRemovalPermission(MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender)
            }
            if (Build.VERSION.SDK_INT == 29 && error is RecoverableSecurityException) {
                throw PhotoRemovalPermission(error.userAction.actionIntent.intentSender)
            }
            throw IllegalStateException("文件授权已失效，照片记录已保留；请恢复文件夹授权或仅从图库移除", error)
        }
    }
}
