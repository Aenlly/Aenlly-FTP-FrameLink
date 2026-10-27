package app.framelink.camera

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

enum class PhotoSource {
    CAMERA,
    CAPTURE,
    IMPORTED,
    FTP,
}

enum class PhotoMark {
    NONE,
    FAVORITE,
    PICKED,
    REJECTED,
}

enum class TransferState {
    QUEUED,
    RUNNING,
    WAITING_CONNECTION,
    WAITING_NETWORK,
    INTERRUPTED,
    COMPLETE,
    FAILED,
    CANCELLED,
}

enum class TransferKind {
    IMPORT,
    DOWNLOAD,
    CLOUD_PREVIEW,
    CLOUD_DELIVERY,
    FTP_RECEIVE,
    FTP_DOWNLOAD,
    FTP_UPLOAD,
    PUBLISH,
}

enum class DownloadFormat {
    JPEG,
    RAW,
    ALL,
}

/** 一次快门对应的文件组，保留相机原始标识和RAW/JPEG配对 */
data class CameraFile(
    val fileId: Int = 0,
    val name: String = "",
    val cameraPath: String = "",
    val size: Long = 0,
    val localUri: String = "",
    val cameraDay: String = "",
    val originalPath: String = "",
) {
    /** App保留的原片与系统相册URI分开，避免相册变动影响原片 */
    val savedUri
        get() =
            originalPath.takeIf { it.isNotBlank() }?.let { java.io.File(it).toURI().toString() }
                ?: localUri

    val raw
        get() =
            name.substringAfterLast('.', "").uppercase(Locale.ROOT) in
                setOf("ARW", "DNG", "NEF", "CR2", "CR3", "RAF")

    val image
        get() =
            raw ||
                name.substringAfterLast('.', "").uppercase(Locale.ROOT) in
                    setOf("JPG", "JPEG", "PNG", "HIF", "HEIF", "HEIC")
}

@Entity(
    tableName = "photos",
    indices = [Index(value = ["sourceKey"], unique = true), Index("capturedAt")],
)
data class Photo(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val name: String,
    val source: String,
    val capturedAt: Long,
    val filesJson: String,
    val contentId: Int = 0,
    val slot: Int = 1,
    val previewPath: String = "",
    val mark: String = PhotoMark.NONE.name,
    val projectId: String = "",
    val burstKey: String = "",
    val published: Boolean = false,
) {
    @Ignore @Volatile private var decodedFiles: List<CameraFile>? = null

    fun files(): List<CameraFile> =
        decodedFiles ?: Json.list<CameraFile>(filesJson).also { decodedFiles = it }

    fun downloadFiles(format: DownloadFormat) =
        files().filter {
            it.image &&
                (format == DownloadFormat.ALL ||
                    if (format == DownloadFormat.RAW) it.raw else !it.raw)
        }

    @get:Ignore
    val imported
        get() = files().any { it.savedUri.isNotBlank() }

    @get:Ignore
    val displayUri
        get() = files().firstOrNull { !it.raw && it.savedUri.isNotBlank() }?.savedUri ?: previewPath
}

@Entity(tableName = "tasks", indices = [Index("state"), Index("createdAt")])
data class TransferTask(
    @PrimaryKey val id: String,
    val kind: String,
    val photoId: String = "",
    val title: String,
    val payload: String = "{}",
    val state: String = TransferState.QUEUED.name,
    val done: Long = 0,
    val total: Long = 0,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class CameraInfo(
    val id: String = "",
    val model: String = "",
    val firmware: String = "",
    val manufacturer: String = "",
    val canBrowse: Boolean = false,
    val canDownload: Boolean = false,
    val canPreview: Boolean = false,
    val canCapture: Boolean = false,
    val canLiveView: Boolean = false,
    val hardwareVerified: Boolean = false,
)

data class CameraPage(val items: List<RemotePhoto> = emptyList(), val nextCursor: Long = 0)

data class RemotePhoto(
    val id: String,
    val contentId: Int,
    val slot: Int,
    val capturedAt: Long,
    val groupId: Long,
    val files: List<CameraFile>,
)

data class PropertyChoice(val value: Long, val label: String)

data class CameraProperty(
    val code: Int,
    val name: String,
    val value: Long,
    val label: String,
    val writable: Boolean,
    val choices: List<PropertyChoice> = emptyList(),
)

data class ConnectionConfig(
    val host: String = "",
    val port: Int = 15740,
    val user: String = "",
    val password: String = "",
    val fingerprint: String = "",
    val guid: String = "",
)

data class CameraStatus(
    val connecting: Boolean = false,
    val info: CameraInfo? = null,
    val message: String = "连接相机，开始你的拍摄",
    val loadingPhotos: Boolean = false,
)

object Json {
    val gson = Gson()

    fun encode(value: Any): String = gson.toJson(value)

    inline fun <reified T> decode(value: String): T =
        requireNotNull(gson.fromJson<T>(value, object : TypeToken<T>() {}.type)) { "数据为空或格式不完整" }

    inline fun <reified T> list(value: String): List<T> = decode(value)
}
