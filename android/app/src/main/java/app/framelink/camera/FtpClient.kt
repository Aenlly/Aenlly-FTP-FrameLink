package app.framelink.camera

import android.net.Uri
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.json.JSONObject

/** FTP账号单独加密保存，任务只引用账号ID，不持久化明文密码 */
data class FtpProfile(
    val host: String = "",
    val port: Int = 21,
    val username: String = "",
    val password: String = "",
    val tls: Boolean = false,
    val encoding: String = "UTF-8",
    val directory: String = "/",
) {
    val id
        get() = MediaFiles.key("$host:$port:$username:$tls:$encoding")

    val label
        get() = "${if (tls) "FTPS" else "FTP"} · $host:$port"

    fun validate() {
        require(
            host.isNotBlank() &&
                !host.contains("://") &&
                host.none { it.isWhitespace() || it in "/\\\r\n" }
        ) {
            "请输入服务器地址，不包含协议或路径"
        }
        require(port in 1..65535) { "端口应为1至65535" }
        require(username.isNotBlank()) { "请填写登录账号；匿名服务器可使用anonymous" }
        require(encoding in listOf("UTF-8", "GBK", "ISO-8859-1")) { "不支持的文件名编码" }
        FtpPaths.safe(username)
        FtpPaths.safe(password)
        FtpPaths.normalize(directory)
    }
}

object FtpPaths {
    fun safe(value: String): String {
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "名称包含无效控制字符" }
        return value
    }

    fun normalize(path: String): String {
        safe(path)
        val segments = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "",
                "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/")
    }

    fun child(directory: String, name: String): String {
        safe(name)
        require(name.isNotBlank() && name !in listOf(".", "..") && '/' !in name && '\\' !in name) {
            "请输入有效文件名，不包含路径"
        }
        return normalize("${normalize(directory)}/$name")
    }

    fun parent(path: String) = normalize(normalize(path).substringBeforeLast('/', ""))
}

data class FtpEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
)

data class FtpBrowserState(
    val profile: FtpProfile? = null,
    val path: String = "/",
    val entries: List<FtpEntry> = emptyList(),
    val busy: Boolean = false,
    val connected: Boolean = false,
    val error: String = "",
)

/** 每个传输使用独立会话；目录命令和文件流不交错，默认二进制和被动模式 */
class FtpConnection(private val profile: FtpProfile) : Closeable {
    private val client: FTPClient = if (profile.tls) FTPSClient(false) else FTPClient()
    @Volatile private var transferStream: Closeable? = null

    fun connect() {
        profile.validate()
        client.connectTimeout = 10000
        client.defaultTimeout = 15000
        client.controlEncoding = profile.encoding
        client.autodetectUTF8 = profile.encoding == "UTF-8"
        client.setDataTimeout(Duration.ofSeconds(20))
        client.bufferSize = FtpTransferIo.BUFFER_BYTES
        client.setControlKeepAliveTimeout(Duration.ofSeconds(30))
        if (client is FTPSClient) client.setEndpointCheckingEnabled(true)
        client.connect(profile.host, profile.port)
        check(FTPReply.isPositiveCompletion(client.replyCode)) { "服务器拒绝连接（${client.replyCode}）" }
        check(client.login(profile.username, profile.password)) {
            "登录失败，请检查账号、密码和服务器权限（${client.replyCode}）"
        }
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        client.enterLocalPassiveMode()
        check(client.setFileType(FTP.BINARY_FILE_TYPE)) { "服务器不支持二进制传输" }
    }

    fun list(path: String): List<FtpEntry> {
        val directory = FtpPaths.normalize(path)
        check(client.changeWorkingDirectory(directory)) { "无法打开目录，请检查路径或读取权限（${client.replyCode}）" }
        var entries = client.mlistDir()
        if (!FTPReply.isPositiveCompletion(client.replyCode)) entries = client.listFiles()
        check(FTPReply.isPositiveCompletion(client.replyCode)) { "读取目录失败（${client.replyCode}）" }
        return entries
            .filter { (it.isDirectory || it.isFile) && it.name !in listOf(".", "..") }
            .map {
                FtpEntry(
                    it.name,
                    FtpPaths.child(directory, it.name),
                    it.isDirectory,
                    it.size,
                    it.timestamp?.timeInMillis ?: 0L,
                )
            }
            .sortedWith(
                compareByDescending<FtpEntry> { it.directory }.thenBy { it.name.lowercase() }
            )
    }

    fun makeDirectory(path: String) {
        check(client.makeDirectory(FtpPaths.normalize(path))) {
            "新建文件夹失败，请检查写入权限或同名目录（${client.replyCode}）"
        }
    }

    fun download(path: String, output: OutputStream, progress: (Long) -> Unit): FtpTransferReceipt {
        val receipt =
            client.retrieveFileStream(FtpPaths.normalize(path))?.use { input ->
                transferStream = input
                try {
                    FtpTransferIo.copy(input, output, fingerprint = true, progress = progress)
                } finally {
                    transferStream = null
                }
            } ?: error("无法下载文件，请检查文件是否存在及读取权限（${client.replyCode}）")
        check(client.completePendingCommand()) { "服务器未确认下载完成，请重试（${client.replyCode}）" }
        return receipt
    }

    /** 使用任务专属暂存文件，成功后再改名，避免半文件被误认为已完成 */
    fun upload(path: String, taskId: String, input: InputStream, progress: (Long) -> Unit) {
        val destination = FtpPaths.normalize(path)
        val parent = FtpPaths.parent(destination)
        val name = destination.substringAfterLast('/')
        check(list(parent).none { it.name == name }) { "目标文件已存在，请刷新目录并重新上传，原文件不会被覆盖" }
        val temporary = FtpPaths.child(parent, ".framelink-$taskId.part")
        try {
            client.storeFileStream(temporary)?.use { output ->
                transferStream = output
                try {
                    FtpTransferIo.copy(input, output, progress = progress)
                } finally {
                    transferStream = null
                }
            } ?: error("无法上传，请检查目录写入权限（${client.replyCode}）")
            check(client.completePendingCommand()) { "服务器未确认上传完成（${client.replyCode}）" }
            check(list(parent).none { it.name == name }) { "上传期间出现同名文件，暂存文件保留，未替换已有文件" }
            check(client.rename(temporary, destination)) { "文件已上传到暂存目录但改名失败，请检查服务器权限" }
        } catch (error: Exception) {
            runCatching { client.deleteFile(temporary) }
            throw error
        }
    }

    override fun close() {
        runCatching { transferStream?.close() }
        runCatching { if (client.isConnected) client.disconnect() }
    }
}

/** 目录浏览与任务队列共享账号存储，连接失败可编辑重试；文件任务在进程重启后保留 */
class FtpClientController(private val runtime: CameraRuntime) {
    val state = MutableStateFlow(FtpBrowserState())
    private val browserLock = Mutex()
    private val transfers = ConcurrentHashMap<String, FtpConnection>()
    private val browserGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var browser: FtpConnection? = null

    fun profiles(): List<FtpProfile> =
        runCatching { Json.list<String>(runtime.settings.get("ftp.client.ids")) }
            .getOrDefault(emptyList())
            .mapNotNull { profile(it) }

    private fun profile(id: String): FtpProfile? =
        runCatching { Json.decode<FtpProfile>(runtime.settings.get("ftp.client.$id")) }.getOrNull()

    fun forget(id: String) {
        runtime.settings.remove("ftp.client.$id")
        runtime.settings.put(
            "ftp.client.ids",
            Json.encode(profiles().map { it.id }.filter { it != id }),
        )
    }

    fun connect(input: FtpProfile) {
        val generation = browserGeneration.incrementAndGet()
        browser?.close()
        state.value = FtpBrowserState(profile = input, busy = true)
        runtime.scope.launch {
            browserLock.withLock {
                if (generation != browserGeneration.get()) return@withLock
                val connection = FtpConnection(input)
                browser = connection
                try {
                    runtime.startService()
                    connection.connect()
                    val path = FtpPaths.normalize(input.directory)
                    val entries = connection.list(path)
                    if (generation != browserGeneration.get()) {
                        connection.close()
                        return@withLock
                    }
                    runtime.settings.put("ftp.client.${input.id}", Json.encode(input))
                    runtime.settings.put(
                        "ftp.client.ids",
                        Json.encode(
                            (listOf(input.id) + profiles().map { it.id }).distinct().take(10)
                        ),
                    )
                    state.value =
                        FtpBrowserState(
                            profile = input,
                            path = path,
                            entries = entries,
                            connected = true,
                        )
                } catch (error: Exception) {
                    connection.close()
                    if (generation == browserGeneration.get())
                        state.value =
                            FtpBrowserState(
                                profile = input,
                                error = error.message ?: "连接失败，请检查网络和服务器",
                            )
                }
            }
        }
    }

    fun browse(path: String) =
        runtime.scope.launch {
            browserLock.withLock {
                if (!state.value.connected) return@withLock
                state.value = state.value.copy(busy = true, error = "")
                try {
                    val entries = checkNotNull(browser) { "请重新连接服务器" }.list(path)
                    state.value =
                        state.value.copy(
                            path = FtpPaths.normalize(path),
                            entries = entries,
                            busy = false,
                        )
                } catch (error: Exception) {
                    state.value = state.value.copy(busy = false, error = error.message ?: "目录读取失败")
                }
            }
        }

    fun mkdir(name: String) =
        runtime.scope.launch {
            browserLock.withLock {
                try {
                    val path = FtpPaths.child(state.value.path, name)
                    checkNotNull(browser) { "请先连接服务器" }.makeDirectory(path)
                    state.value =
                        state.value.copy(entries = browser!!.list(state.value.path), error = "")
                } catch (error: Exception) {
                    state.value = state.value.copy(error = error.message.orEmpty())
                }
            }
        }

    fun disconnect() {
        browserGeneration.incrementAndGet()
        browser?.close()
        browser = null
        state.value = FtpBrowserState(profile = state.value.profile)
    }

    fun enqueueDownloads(entries: List<FtpEntry>) =
        runtime.scope.launch {
            val account = state.value.profile ?: return@launch
            val activePaths =
                runtime.database
                    .tasks()
                    .byKind(TransferKind.FTP_DOWNLOAD.name)
                    .filter {
                        it.state !in
                            listOf(
                                TransferState.COMPLETE.name,
                                TransferState.CANCELLED.name,
                                TransferState.FAILED.name,
                            )
                    }
                    .mapNotNull {
                        val data = JSONObject(it.payload)
                        if (data.optString("profile") == account.id) data.optString("remote")
                        else null
                    }
                    .toMutableSet()
            for (entry in entries.filter { !it.directory }) {
                if (!activePaths.add(entry.path)) continue
                runtime.database
                    .tasks()
                    .save(
                        TransferTask(
                            UUID.randomUUID().toString(),
                            TransferKind.FTP_DOWNLOAD.name,
                            title = "FTP下载 · ${entry.name}",
                            total = entry.size,
                            payload =
                                JSONObject()
                                    .put("profile", account.id)
                                    .put("remote", entry.path)
                                    .put("name", entry.name)
                                    .put("size", entry.size)
                                    .put("modified", entry.modified)
                                    .toString(),
                        )
                    )
            }
            runtime.notice.value = "已加入下载队列，可在传输查看进度"
            runtime.runTasks()
        }

    fun enqueueUploads(uris: List<Uri>) {
        val account = state.value.profile ?: return
        val directory = state.value.path
        runtime.scope.launch {
            for (uri in uris) {
                var name = "upload_${UUID.randomUUID()}"
                var size = 0L
                runtime.context.contentResolver
                    .query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )
                    ?.use {
                        if (it.moveToFirst()) {
                            name = it.getString(0)
                            if (!it.isNull(1)) size = it.getLong(1)
                        }
                    }
                val remote = FtpPaths.child(directory, name)
                runtime.database
                    .tasks()
                    .save(
                        TransferTask(
                            UUID.randomUUID().toString(),
                            TransferKind.FTP_UPLOAD.name,
                            title = "FTP上传 · $name",
                            total = size,
                            payload =
                                JSONObject()
                                    .put("profile", account.id)
                                    .put("remote", remote)
                                    .put("name", name)
                                    .put("uri", uri.toString())
                                    .toString(),
                        )
                    )
            }
            runtime.notice.value = "已加入上传队列，同名文件会提示处理"
            runtime.runTasks()
        }
    }

    fun cancel(taskId: String) {
        transfers[taskId]?.close()
    }

    fun stopAll() {
        disconnect()
        transfers.values.forEach { it.close() }
    }

    suspend fun transfer(task: TransferTask, progress: suspend (Long, Long) -> Unit) {
        val data = JSONObject(task.payload)
        val account = profile(data.getString("profile")) ?: error("账号已移除，请重新连接原服务器后重试")
        val connection = FtpConnection(account)
        transfers[task.id] = connection
        try {
            runtime.startService()
            connection.connect()
            runtime.checkTaskActive(task.id)
            val remote = data.getString("remote")
            var lastProgress = 0L
            var transferred = 0L
            val callback: (Long) -> Unit = { done ->
                transferred = done
                runtime.checkTaskActive(task.id)
                if (System.currentTimeMillis() - lastProgress > 300) {
                    lastProgress = System.currentTimeMillis()
                    kotlinx.coroutines.runBlocking { progress(done, maxOf(task.total, done)) }
                }
            }
            if (task.kind == TransferKind.FTP_DOWNLOAD.name) {
                val name = data.getString("name")
                val file = File(runtime.context.filesDir, "media/ftp-client/${task.id}/$name")
                file.parentFile!!.mkdirs()
                var sha256: String? = null
                if (!file.isFile) {
                    val entry =
                        connection.list(FtpPaths.parent(remote)).firstOrNull {
                            it.path == remote && !it.directory
                        } ?: error("文件已移走或无权读取，请刷新服务器目录")
                    check(
                        entry.size == data.getLong("size") &&
                            (data.optLong("modified") == 0L ||
                                entry.modified == 0L ||
                                entry.modified == data.optLong("modified"))
                    ) {
                        "远端文件已变化，请刷新目录后重新下载"
                    }
                    val part = File(file.absolutePath + ".part")
                    try {
                        FileOutputStream(part).use { output ->
                            sha256 = connection.download(remote, output, callback).sha256
                            output.fd.sync()
                        }
                        runtime.checkTaskActive(task.id)
                        check(part.length() == entry.size) { "下载字节数与文件大小不一致，请重试" }
                        check(part.renameTo(file)) { "原片保存失败，请检查存储空间" }
                        MediaFiles.syncDirectory(file.parentFile!!)
                    } catch (error: Exception) {
                        part.delete()
                        throw error
                    }
                }
                runtime.finishFtpFile(
                    task,
                    file,
                    name,
                    "ftp-client:" +
                        MediaFiles.key(
                            "${account.id}:$remote:" +
                                (sha256 ?: MediaFiles.digest(file.inputStream()))
                        ),
                )
                progress(file.length(), file.length())
            } else {
                val uri = Uri.parse(data.getString("uri"))
                runtime.context.contentResolver.openInputStream(uri)?.use { input ->
                    connection.upload(remote, task.id, input, callback)
                } ?: error("上传文件已不可访问，请重新选择文件")
                progress(transferred, transferred)
            }
        } finally {
            connection.close()
            transfers.remove(task.id)
        }
    }
}
