package app.framelink.camera

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 真实相机状态、照片索引和可恢复任务；界面不直接调用协议 */
class CameraRuntime(
    val context: Context,
    val database: LocalDatabase,
    val settings: SecureSettings,
) {
    val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, error ->
                    android.util.Log.e("FrameLink", "Background operation interrupted", error)
                    notice.value = "后台操作未完成，请检查存储空间并重试，已保存原片保留"
                }
        )
    val cloud = CloudClient(settings)
    val reviews = LocalReviewRepository(context, database)
    val batches = SelectionBatchRepository(context, database)
    val photoRemoval = PhotoRemovalRepository(context, database)
    val native = NativeCamera(context)
    val photos = database.photos().observe()
    val tasks = database.tasks().observe()
    private val mutableCamera = MutableStateFlow(CameraStatus())
    val camera = mutableCamera.asStateFlow()
    private val mutableFrame = MutableStateFlow<ByteArray?>(null)
    val frame = mutableFrame.asStateFlow()
    val notice = MutableStateFlow("")
    val properties = MutableStateFlow<List<CameraProperty>>(emptyList())
    val parameterUpdating = MutableStateFlow<Int?>(null)
    val captureDates = MutableStateFlow<List<String>>(emptyList())
    val galleryDay = MutableStateFlow("")
    val currentCameraIds = MutableStateFlow<Set<String>>(emptySet())
    val captureCountdown = MutableStateFlow(0)
    val lastCapture = MutableStateFlow<Photo?>(null)
    val liveMetadata = MutableStateFlow("")
    val ftpStatus = MutableStateFlow("未启动接收")
    val ftpClient = FtpClientController(this)
    val bluetooth = BluetoothRemote(context, scope, settings) { notice.value = it }
    private val queue = Mutex()
    private var liveJob: Job? = null
    private var connectionMonitor: Job? = null
    private var captureJob: Job? = null
    private val connectionGeneration = AtomicLong()
    val cameraIdentityPrompt = MutableStateFlow<CameraIdentityPrompt?>(null)
    @Volatile private var pendingCameraConnection: PendingCameraConnection? = null

    private data class PendingCameraConnection(
        val id: String,
        val generation: Long,
        val config: ConnectionConfig,
        val identity: CameraIdentity,
    )
    private val previewLocks = ConcurrentHashMap<String, Mutex>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private var cursor = 0L
    private var currentSlot = 1
    var ftp: FtpReceiver? = null
        private set

    init {
        scope.launch {
            restoreReceivedFiles()
            database
                .tasks()
                .recover(
                    TransferState.RUNNING.name,
                    TransferState.INTERRUPTED.name,
                    "上次运行中断，已保留文件和任务，可重试",
                )
            for (task in database.tasks().inState(TransferState.INTERRUPTED.name)) {
                if (
                    task.kind == TransferKind.FTP_RECEIVE.name &&
                        runCatching { JSONObject(task.payload).optBoolean("ready") }
                            .getOrDefault(false)
                )
                    database.tasks().save(task.copy(state = TransferState.QUEUED.name, error = ""))
            }
            runTasks()
        }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    wakeNetwork()
                }
            }
        )
        manager.registerNetworkCallback(
            android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: android.net.Network) {
                    scope.launch {
                        synchronized(this@CameraRuntime) {
                            if (mutableCamera.value.info != null && native.usesNetwork(network)) disconnect()
                        }
                    }
                }
            },
        )
    }

    /** 从已完成落盘目录恢复任务，覆盖确认后进程或数据库提交意外中断 */
    private suspend fun restoreReceivedFiles() {
        val directory = File(context.filesDir, "media/ftp/received")
        val known = database.tasks().byKind(TransferKind.FTP_RECEIVE.name).associateBy { it.id }
        for (file in directory.listFiles().orEmpty()) {
            if (!file.isFile || file.name.length < 38 || file.name[36] != '_') continue
            val id = file.name.take(36)
            if (runCatching { UUID.fromString(id) }.isFailure) continue
            val existing = known[id]
            if (
                existing != null &&
                    runCatching { JSONObject(existing.payload).optBoolean("ready") }
                        .getOrDefault(false)
            )
                continue
            val payload =
                JSONObject()
                    .put("path", file.absolutePath)
                    .put("name", file.name.substring(37))
                    .put("ready", true)
                    .toString()
            database
                .tasks()
                .save(
                    TransferTask(
                        id,
                        TransferKind.FTP_RECEIVE.name,
                        title = file.name.substring(37),
                        payload = payload,
                        state = TransferState.QUEUED.name,
                        total = file.length(),
                        done = file.length(),
                    )
                )
        }
    }

    fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java))
    }

    fun savedConnection(): ConnectionConfig =
        runCatching { Json.decode<ConnectionConfig>(settings.get("camera.config")) }
            .getOrDefault(ConnectionConfig())

    @Synchronized
    fun connect(input: ConnectionConfig): Job {
        connectionMonitor?.cancel()
        val generation = connectionGeneration.incrementAndGet()
        pendingCameraConnection = null
        cameraIdentityPrompt.value = null
        mutableCamera.value = CameraStatus(connecting = true, message = "正在连接相机，请保留相机网络连接")
        return scope.launch {
            try {
                startService()
                val guid =
                    settings.get("camera.guid").ifBlank {
                        savedConnection().guid.ifBlank {
                            UUID.randomUUID().toString().replace("-", "")
                        }
                    }
                settings.put("camera.guid", guid)
                var config = input.copy(guid = guid)
                if (config.user.isNotBlank()) {
                    mutableCamera.value = CameraStatus(connecting = true, message = "正在读取相机身份")
                    val identity = native.inspectIdentity(config.host)
                    if (connectionGeneration.get() != generation) return@launch
                    val trusted =
                        settings.get("camera.trust.${MediaFiles.key(config.host)}").ifBlank {
                            savedConnection().takeIf { it.host == config.host }?.fingerprint.orEmpty()
                        }
                    val expected = trusted.ifBlank { config.fingerprint }
                    if (!sameCameraFingerprint(expected, identity.fingerprint)) {
                        val id = UUID.randomUUID().toString()
                        pendingCameraConnection = PendingCameraConnection(id, generation, config, identity)
                        cameraIdentityPrompt.value = CameraIdentityPrompt(id, identity, expected)
                        mutableCamera.value = CameraStatus(message = "请确认相机身份后继续连接")
                        return@launch
                    }
                    config = config.copy(fingerprint = identity.fingerprint)
                }
                val info = native.connect(config)
                val choices = native.properties()
                val dates = runCatching { native.dates(1) }.getOrDefault(emptyList())
                synchronized(this@CameraRuntime) {
                    if (connectionGeneration.get() != generation) return@launch
                    settings.put("camera.config", Json.encode(config))
                    currentCameraIds.value = emptySet()
                    properties.value = choices
                    captureDates.value = dates
                    mutableCamera.value = CameraStatus(info = info, message = "已连接 ${info.model}")
                    connectionMonitor = scope.launch {
                        while (isActive) {
                            delay(1000)
                            synchronized(this@CameraRuntime) {
                                if (connectionGeneration.get() == generation && !native.connected()) disconnect()
                            }
                        }
                    }
                }
                database
                    .tasks()
                    .recover(TransferState.WAITING_CONNECTION.name, TransferState.QUEUED.name, "")
                runTasks()
            } catch (error: Exception) {
                synchronized(this@CameraRuntime) {
                    if (connectionGeneration.get() == generation) {
                        android.util.Log.e("FrameLink", "Camera connection failed", error)
                        native.close()
                        mutableCamera.value = CameraStatus(message = cameraConnectionMessage(error))
                        notice.value = mutableCamera.value.message
                    }
                }
            }
        }
    }

    fun confirmCameraIdentity(id: String) {
        val pending = pendingCameraConnection ?: return
        if (pending.id != id || pending.generation != connectionGeneration.get()) return
        settings.put("camera.trust.${MediaFiles.key(pending.config.host)}", pending.identity.fingerprint)
        connect(pending.config.copy(fingerprint = pending.identity.fingerprint))
    }

    @Synchronized
    fun disconnect() {
        connectionMonitor?.cancel()
        connectionMonitor = null
        connectionGeneration.incrementAndGet()
        pendingCameraConnection = null
        cameraIdentityPrompt.value = null
        cancelCountdown()
        stopLive()
        native.close()
        mutableCamera.value = CameraStatus(message = "相机已断开，手机中的照片已保留")
        currentCameraIds.value = emptySet()
        properties.value = emptyList()
        parameterUpdating.value = null
        captureDates.value = emptyList()
        galleryDay.value = ""
    }

    fun browseRecent() {
        val day = captureDates.value.firstOrNull().orEmpty()
        selectCameraDay(day)
    }

    fun selectCameraDay(day: String) {
        galleryDay.value = day
        val since =
            runCatching {
                    java.time.LocalDate.parse(day)
                        .atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli() - java.time.Duration.ofDays(1).toMillis()
                }
                .getOrDefault(0L)
        loadPhotos(true, sinceMillis = since)
    }

    fun loadPhotos(reset: Boolean = false, slot: Int = currentSlot, sinceMillis: Long = 0) =
        scope.launch {
            if (mutableCamera.value.loadingPhotos) return@launch
            if (mutableCamera.value.info == null) {
                notice.value = "请先连接相机"
                return@launch
            }
            if (!reset && cursor == 0L && currentSlot == slot) {
                notice.value = "请使用刷新开始读取相机照片"
                return@launch
            }
            mutableCamera.value = mutableCamera.value.copy(loadingPhotos = true)
            try {
                if (reset || slot != currentSlot) {
                    cursor = sinceMillis
                    currentSlot = slot
                    currentCameraIds.value = emptySet()
                }
                val page = native.page(currentSlot, cursor)
                val existingPhotos =
                    database.photos().byIds(page.items.map { it.id }).associateBy { it.id }
                val records =
                    page.items
                        .filter { it.files.any { file -> file.image } }
                        .map { item ->
                            val existing = existingPhotos[item.id]
                            val merged =
                                item.files.map { file ->
                                    existing
                                        ?.files()
                                        ?.find { canReuseCameraFile(file, it) }
                                        ?.let {
                                            file.copy(
                                                localUri = it.localUri,
                                                originalPath = it.originalPath,
                                            )
                                        } ?: file
                                }
                            Photo(
                                item.id,
                                item.id,
                                item.files.firstOrNull()?.name ?: "照片",
                                PhotoSource.CAMERA.name,
                                item.capturedAt,
                                Json.encode(merged),
                                item.contentId,
                                item.slot,
                                existing?.previewPath.orEmpty(),
                                existing?.mark ?: PhotoMark.NONE.name,
                                existing?.projectId.orEmpty(),
                                if (item.groupId > 0)
                                    "${mutableCamera.value.info?.id}:${item.groupId}"
                                else item.id,
                                existing?.published ?: false,
                                existing?.localHidden ?: false,
                            )
                        }
                database.photos().saveAll(records)
                currentCameraIds.value = currentCameraIds.value + records.map { it.id }
                cursor = page.nextCursor
                notice.value = if (cursor == 0L) "本次列表已读取完成" else "已读取一页，继续下拉加载更多"
            } catch (error: Exception) {
                notice.value = error.message ?: "照片列表读取失败"
            } finally {
                mutableCamera.value = mutableCamera.value.copy(loadingPhotos = false)
            }
        }

    suspend fun ensurePreview(photo: Photo, large: Boolean = false): String =
        previewLocks
            .getOrPut(photo.id) { Mutex() }
            .withLock {
                val target =
                    File(
                        context.cacheDir,
                        "previews/${MediaFiles.key(photo.id)}${if (large) "-large" else ""}.preview",
                    )
                if (target.exists() && target.length() > 0) return@withLock target.absolutePath
                if (
                    photo.source == PhotoSource.CAMERA.name &&
                        mutableCamera.value.info?.id?.let { photo.sourceKey.startsWith(it) } == true
                ) {
                    val file =
                        photo.files().firstOrNull { !it.raw && it.image }
                            ?: photo.files().firstOrNull { it.image }
                            ?: error("没有可预览文件")
                    native.preview(photo, file, target, large)
                    database.photos().find(photo.id)?.let {
                        database.photos().save(it.copy(previewPath = target.absolutePath))
                    }
                    target.absolutePath
                } else if (photo.imported) {
                    val uri =
                        Uri.parse(
                            photo
                                .files()
                                .firstOrNull { !it.raw && it.savedUri.isNotBlank() }
                                ?.savedUri
                                ?: photo.files().first { it.savedUri.isNotBlank() }.savedUri
                        )
                    val file = MediaFiles.preview(context, uri, photo.id)
                    database.photos().find(photo.id)?.let {
                        database.photos().save(it.copy(previewPath = file.absolutePath))
                    }
                    file.absolutePath
                } else error("相机未连接，暂时只能查看已经缓存的预览")
            }

    fun mark(photo: Photo, mark: PhotoMark) =
        scope.launch { database.photos().mark(photo.id, mark.name) }

    suspend fun removePhotos(plan: PhotoRemovalPlan, deleteFiles: Boolean): PhotoRemovalResult =
        queue.withLock {
            withContext(NonCancellable) {
                val result = photoRemoval.remove(plan, deleteFiles)
                if (lastCapture.value?.id in result.removedIds) lastCapture.value = null
                result
            }
        }

    fun importUris(uris: List<Uri>) =
        scope.launch {
            for (uri in uris) {
                val id = UUID.randomUUID().toString()
                database
                    .tasks()
                    .save(
                        TransferTask(
                            id,
                            TransferKind.IMPORT.name,
                            title = "导入本机照片",
                            payload = JSONObject().put("uri", uri.toString()).toString(),
                        )
                    )
            }
            runTasks()
        }

    fun enqueueDownload(ids: List<String>, format: DownloadFormat) =
        scope.launch {
            for (id in ids) {
                val photo = database.photos().find(id) ?: continue
                if (photo.localHidden) database.photos().save(photo.copy(localHidden = false))
                if (photo.downloadFiles(format).isEmpty()) {
                    notice.value = "${photo.name}没有所选格式"
                    continue
                }
                if (
                    database
                        .tasks()
                        .active(
                            id,
                            TransferKind.DOWNLOAD.name,
                            listOf(
                                TransferState.COMPLETE.name,
                                TransferState.CANCELLED.name,
                                TransferState.FAILED.name,
                            ),
                        )
                        .isNotEmpty()
                )
                    continue
                database
                    .tasks()
                    .save(
                        TransferTask(
                            UUID.randomUUID().toString(),
                            TransferKind.DOWNLOAD.name,
                            id,
                            photo.name,
                            JSONObject().put("format", format.name).toString(),
                            total = photo.downloadFiles(format).sumOf { it.size },
                        )
                    )
            }
            runTasks()
        }

    fun uploadPreviews(ids: List<String>, project: String) =
        scope.launch {
            database.photos().project(ids, project)
            for (id in ids) {
                val photo = database.photos().find(id) ?: continue
                database
                    .tasks()
                    .save(
                        TransferTask(
                            UUID.randomUUID().toString(),
                            TransferKind.CLOUD_PREVIEW.name,
                            id,
                            "上传预览 · ${photo.name}",
                            JSONObject()
                                .put("project", project)
                                .put("cloudUrl", cloud.baseUrl)
                                .put("cloudAccount", settings.get("cloud.email"))
                                .toString(),
                        )
                    )
            }
            scheduleNetwork()
            runTasks()
        }

    fun uploadDelivery(uri: Uri, project: String, parent: String) =
        scope.launch {
            database
                .tasks()
                .save(
                    TransferTask(
                        UUID.randomUUID().toString(),
                        TransferKind.CLOUD_DELIVERY.name,
                        title = "上传交付成片",
                        payload =
                            JSONObject()
                                .put("uri", uri.toString())
                                .put("project", project)
                                .put("parent", parent)
                                .put("cloudUrl", cloud.baseUrl)
                                .put("cloudAccount", settings.get("cloud.email"))
                                .toString(),
                    )
                )
            scheduleNetwork()
            runTasks()
        }

    fun retry(task: TransferTask) =
        scope.launch {
            cancelled.remove(task.id)
            database
                .tasks()
                .save(
                    task.copy(
                        state = TransferState.QUEUED.name,
                        error = "",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            runTasks()
        }

    fun cancel(task: TransferTask) =
        scope.launch {
            cancelled.add(task.id)
            ftpClient.cancel(task.id)
            if (task.kind == TransferKind.DOWNLOAD.name && task.state == TransferState.RUNNING.name)
                native.cancelTransfer()
            database.tasks().find(task.id)?.let {
                database
                    .tasks()
                    .save(it.copy(state = TransferState.CANCELLED.name, error = "已取消，已完成原片不受影响"))
            }
        }

    private fun networkAllowed(): Boolean {
        if (settings.get("cloud.mobile") == "true") return true
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager
            .getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    fun wakeNetwork() =
        scope.launch {
            database
                .tasks()
                .recover(TransferState.WAITING_NETWORK.name, TransferState.QUEUED.name, "")
            if (cloud.signedIn)
                database
                    .tasks()
                    .recover(TransferState.WAITING_CONNECTION.name, TransferState.QUEUED.name, "")
            runTasks()
        }

    suspend fun runTasks() =
        queue.withLock {
            while (true) {
                val task =
                    database.tasks().inState(TransferState.QUEUED.name).firstOrNull() ?: break
                if (cancelled.contains(task.id)) {
                    database.tasks().save(task.copy(state = TransferState.CANCELLED.name))
                    continue
                }
                val isCloud =
                    task.kind in
                        setOf(TransferKind.CLOUD_PREVIEW.name, TransferKind.CLOUD_DELIVERY.name)
                if (
                    task.kind == TransferKind.DOWNLOAD.name && mutableCamera.value.info == null ||
                        isCloud && !cloud.signedIn
                ) {
                    database
                        .tasks()
                        .save(
                            task.copy(
                                state = TransferState.WAITING_CONNECTION.name,
                                error = if (isCloud) "请先登录云服务" else "等待相机重新连接",
                            )
                        )
                    continue
                }
                if (isCloud && !networkAllowed()) {
                    database
                        .tasks()
                        .save(
                            task.copy(
                                state = TransferState.WAITING_NETWORK.name,
                                error = "等待非计费网络，可在设置中允许移动网络",
                            )
                        )
                    scheduleNetwork()
                    continue
                }
                database.tasks().save(task.copy(state = TransferState.RUNNING.name, error = ""))
                try {
                    when (TransferKind.valueOf(task.kind)) {
                        TransferKind.DOWNLOAD -> downloadTask(task)
                        TransferKind.IMPORT -> importTask(task)
                        TransferKind.CLOUD_PREVIEW,
                        TransferKind.CLOUD_DELIVERY -> cloudTask(task)
                        TransferKind.FTP_RECEIVE -> finalizeFtp(task)
                        TransferKind.FTP_DOWNLOAD,
                        TransferKind.FTP_UPLOAD ->
                            ftpClient.transfer(task) { done, total -> progress(task, done, total) }
                        else -> error("该任务由接收服务管理，请重新启动接收")
                    }
                    if (!cancelled.contains(task.id))
                        database.tasks().find(task.id)?.let {
                            database
                                .tasks()
                                .save(
                                    it.copy(
                                        state = TransferState.COMPLETE.name,
                                        done = it.total,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                        }
                } catch (error: Exception) {
                    if (!cancelled.contains(task.id))
                        database.tasks().find(task.id)?.let {
                            database
                                .tasks()
                                .save(
                                    it.copy(
                                        state =
                                            if (error is CancellationException)
                                                TransferState.INTERRUPTED.name
                                            else TransferState.FAILED.name,
                                        error = error.message ?: "任务未完成",
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                        }
                }
            }
        }

    private suspend fun progress(task: TransferTask, done: Long, total: Long) {
        check(!cancelled.contains(task.id)) { "任务已取消" }
        database
            .tasks()
            .progress(task.id, done, total, System.currentTimeMillis(), TransferState.RUNNING.name)
    }

    fun checkTaskActive(id: String) {
        check(!cancelled.contains(id)) { "任务已取消" }
    }

    private suspend fun downloadTask(task: TransferTask) {
        var photo = database.photos().find(task.photoId) ?: error("照片记录不存在")
        val format = DownloadFormat.valueOf(JSONObject(task.payload).getString("format"))
        var completed = 0L
        for (file in photo.downloadFiles(format)) {
            check(!cancelled.contains(task.id)) { "下载已取消" }
            if (file.savedUri.isNotBlank()) {
                completed += file.size
                continue
            }
            val output =
                File(
                    context.filesDir,
                    "media/${MediaFiles.key(photo.id)}/${file.fileId}_${file.name.substringAfterLast('/')}",
                )
            var last = 0L
            native.download(photo, file, output) { done, total ->
                if (System.currentTimeMillis() - last > 350 || done == total) {
                    last = System.currentTimeMillis()
                    val bytes = completed + done
                    scope.launch {
                        if (!cancelled.contains(task.id)) progress(task, bytes, task.total)
                    }
                }
            }
            val uri =
                runCatching { MediaFiles.publish(context, output, file.name).toString() }
                    .getOrElse { Uri.fromFile(output).toString() }
            photo =
                photo.copy(
                    filesJson =
                        Json.encode(
                            photo.files().map {
                                if (it.fileId == file.fileId)
                                    it.copy(localUri = uri, originalPath = output.absolutePath)
                                else it
                            }
                        ),
                    published = uri.startsWith("content:"),
                )
            database.photos().save(photo)
            completed += file.size
        }
        runCatching { ensurePreview(photo) }
        progress(task, task.total, task.total)
    }

    private suspend fun importTask(task: TransferTask) {
        val uri = Uri.parse(JSONObject(task.payload).getString("uri"))
        var name = "photo_${task.id}.jpg"
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use {
                if (it.moveToFirst())
                    name = it.getString(0).substringAfterLast('/').substringAfterLast('\\')
            }
        val output =
            File(context.filesDir, "media/import/${task.id}_$name").apply { parentFile!!.mkdirs() }
        val part = File(output.absolutePath + ".part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            part.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    check(!cancelled.contains(task.id)) { "导入已取消" }
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
        check(part.renameTo(output)) { "文件保存失败，已保留暂存文件" }
        val hash = MediaFiles.digest(output.inputStream())
        val id = "local:$hash"
        val existing = database.photos().find(id)
        if (existing == null || (existing.localHidden && !existing.imported)) {
            val local =
                runCatching { MediaFiles.publish(context, output, name).toString() }
                    .getOrDefault(Uri.fromFile(output).toString())
            val photo =
                Photo(
                    id,
                    id,
                    name,
                    PhotoSource.IMPORTED.name,
                    MediaFiles.capturedTime(output),
                    Json.encode(
                        listOf(
                            CameraFile(
                                name = name,
                                size = output.length(),
                                localUri = local,
                                originalPath = output.absolutePath,
                            )
                        )
                    ),
                )
            val imported = if (existing == null) photo else photo.copy(
                mark = existing.mark,
                projectId = existing.projectId,
                burstKey = existing.burstKey,
            )
            database.photos().save(imported)
            runCatching { ensurePreview(imported) }
        } else {
            if (existing.localHidden) database.photos().save(existing.copy(localHidden = false))
            output.delete()
        }
        progress(task, output.length(), output.length())
    }

    private suspend fun cloudTask(task: TransferTask) {
        val data = JSONObject(task.payload)
        check(
            data.optString("cloudUrl", cloud.baseUrl) == cloud.baseUrl &&
                data.optString("cloudAccount", settings.get("cloud.email")) ==
                    settings.get("cloud.email")
        ) {
            "此任务属于其他云服务或账号，请重新选择项目后上传"
        }
        val file =
            if (task.kind == TransferKind.CLOUD_PREVIEW.name) {
                val photo = database.photos().find(task.photoId) ?: error("照片不存在")
                val preview = ensurePreview(photo, true)
                MediaFiles.preview(context, Uri.fromFile(File(preview)), photo.id + "cloud")
            } else {
                val uri = Uri.parse(data.getString("uri"))
                var name = "delivery.jpg"
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) name = it.getString(0).substringAfterLast('/') }
                File(context.filesDir, "media/delivery/${task.id}_$name").apply {
                    parentFile!!.mkdirs()
                    if (!exists())
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            outputStream().use { input.copyTo(it, 65536) }
                        }
                }
            }
        var last = 0L
        cloud.upload(
            data.getString("project"),
            file,
            task.photoId.ifBlank { task.id },
            data.optString("parent").ifBlank { null },
        ) { done, total ->
            check(!cancelled.contains(task.id)) { "上传已取消" }
            if (System.currentTimeMillis() - last > 500 || done == total) {
                last = System.currentTimeMillis()
                scope.launch { if (!cancelled.contains(task.id)) progress(task, done, total) }
            }
        }
        progress(task, file.length(), file.length())
    }

    private suspend fun finalizeFtp(task: TransferTask) {
        val payload = JSONObject(task.payload)
        check(payload.optBoolean("ready")) { "接收尚未完成，请让相机重新发送" }
        val file = File(payload.getString("path"))
        check(
            file.canonicalPath.startsWith(
                File(context.filesDir, "media/ftp").canonicalPath + File.separator
            ) && file.isFile
        ) {
            "已接收文件不存在"
        }
        val name = payload.getString("name")
        finishFtpFile(task, file, name, "ftp:" + MediaFiles.digest(file.inputStream()))
        progress(task, task.total, task.total)
        ftpStatus.value =
            if (CameraFile(name = name).image) "照片已加入图库，继续等待上传" else "文件已保存，可在传输中打开，继续等待上传"
    }

    suspend fun finishFtpFile(task: TransferTask, file: File, name: String, key: String) {
        checkTaskActive(task.id)
        val payload =
            JSONObject(database.tasks().find(task.id)?.payload ?: task.payload)
                .put("path", file.absolutePath)
        if (CameraFile(name = name).image) payload.put("photoKey", key)
        database.tasks().find(task.id)?.let {
            database.tasks().save(it.copy(payload = payload.toString()))
        }
        val tree = settings.get("ftp.save.tree")
        if (tree.isNotBlank() && !payload.has("exportedUri")) {
            val exported = FtpStorage.copyToTree(context, file, name, Uri.parse(tree))
            payload.put("exportedUri", exported.toString())
            database.tasks().find(task.id)?.let {
                database.tasks().save(it.copy(payload = payload.toString()))
            }
        }
        if (!CameraFile(name = name).image) return
        val existing = database.photos().bySource(key)
        if (existing == null || (existing.localHidden && !existing.imported)) {
            val uri =
                runCatching { MediaFiles.publish(context, file, name) }
                    .getOrElse { Uri.fromFile(file) }
            val photo =
                Photo(
                    key,
                    key,
                    name,
                    PhotoSource.FTP.name,
                    MediaFiles.capturedTime(file),
                    Json.encode(
                        listOf(
                            CameraFile(
                                name = name,
                                size = file.length(),
                                localUri = uri.toString(),
                                originalPath = file.absolutePath,
                            )
                        )
                    ),
                    published = uri.scheme == "content",
                )
            val imported = if (existing == null) photo else photo.copy(mark = existing.mark, projectId = existing.projectId, burstKey = existing.burstKey)
            database.photos().save(imported)
            runCatching { ensurePreview(imported) }
        } else if (existing.localHidden && task.kind == TransferKind.FTP_DOWNLOAD.name) {
            database.photos().save(existing.copy(localHidden = false))
        }
        if (settings.get("ftp.compress") == "true" && !payload.has("compressedUri")) {
            val formats =
                settings
                    .get("ftp.compress.formats")
                    .ifBlank { "JPG,JPEG,PNG,HIF,HEIF,HEIC" }
                    .split(',')
                    .map { it.trim().uppercase(java.util.Locale.ROOT) }
            if (name.substringAfterLast('.').uppercase(java.util.Locale.ROOT) in formats) {
                val small =
                    MediaFiles.preview(
                        context,
                        Uri.fromFile(file),
                        task.id + "-share",
                        settings.get("ftp.compress.edge").toIntOrNull()?.coerceIn(640, 2560)
                            ?: 2560,
                        settings.get("ftp.compress.quality").toIntOrNull()?.coerceIn(50, 100) ?: 85,
                    )
                val copyName = name.substringBeforeLast('.') + "_share.jpg"
                val copyTree = settings.get("ftp.compress.tree")
                val albumEnabled = settings.get("ftp.compress.album") != "false"
                val uri =
                    if (copyTree.isNotBlank())
                        FtpStorage.copyToTree(context, small, copyName, Uri.parse(copyTree))
                    else if (albumEnabled)
                        MediaFiles.publish(context, small, copyName, "FrameLink/分享副本")
                    else {
                        val saved =
                            File(context.filesDir, "media/ftp-share/${task.id}/$copyName").apply {
                                parentFile!!.mkdirs()
                            }
                        small.inputStream().use { input ->
                            java.io.FileOutputStream(saved).use { output ->
                                input.copyTo(output, 65536)
                                output.fd.sync()
                            }
                        }
                        Uri.fromFile(saved)
                    }
                if (copyTree.isNotBlank() && albumEnabled)
                    MediaFiles.publish(context, small, copyName, "FrameLink/分享副本")
                payload.put("compressedUri", uri.toString())
                database.tasks().find(task.id)?.let {
                    database.tasks().save(it.copy(payload = payload.toString()))
                }
            }
        }
    }

    @Synchronized
    fun startLive() {
        if (liveJob?.isActive == true) return
        val previous = liveJob
        liveJob =
            scope.launch {
                previous?.join()
                if (!isActive) return@launch
                try {
                    startService()
                    native.startLive()
                    var analyzed = 0L
                    while (true) {
                        mutableFrame.value = native.nextFrame()
                        if (System.currentTimeMillis() - analyzed > 300) {
                            liveMetadata.value = native.metadata()
                            analyzed = System.currentTimeMillis()
                        }
                    }
                } catch (error: Exception) {
                    if (error !is CancellationException) notice.value = error.message ?: "取景已停止"
                } finally {
                    native.stopLive()
                }
            }
    }

    @Synchronized
    fun stopLive() {
        native.stopLive()
        liveJob?.cancel()
        mutableFrame.value = null
        liveMetadata.value = ""
    }

    @Synchronized
    fun capture(seconds: Int = 0): Job {
        captureJob
            ?.takeIf { it.isActive }
            ?.let {
                return it
            }
        val job =
            scope.launch {
                try {
                    for (left in seconds downTo 1) {
                        captureCountdown.value = left
                        delay(1000)
                    }
                    captureCountdown.value = 0
                    native.capture(true)
                    notice.value = "快门指令已完成，正在查看拍后结果"
                    val target = File(context.filesDir, "media/capture/${UUID.randomUUID()}.jpg")
                    val post = runCatching { native.fetchCaptured(target) }.getOrNull()
                    if (post?.optBoolean("ready") == true) {
                        val original = post.optBoolean("original")
                        val name = post.optString("name", "capture.jpg")
                        val actual =
                            if (original) target
                            else
                                File(context.cacheDir, "previews/${target.name}").apply {
                                    parentFile!!.mkdirs()
                                    check(target.renameTo(this)) { "预览暂存失败" }
                                }
                        val uri =
                            if (original)
                                runCatching { MediaFiles.publish(context, actual, name).toString() }
                                    .getOrDefault(Uri.fromFile(actual).toString())
                            else ""
                        val id = "capture:" + MediaFiles.digest(actual.inputStream())
                        val photo =
                            Photo(
                                id,
                                id,
                                name,
                                PhotoSource.CAPTURE.name,
                                System.currentTimeMillis(),
                                Json.encode(
                                    listOf(
                                        CameraFile(
                                            name = name,
                                            size = actual.length(),
                                            localUri = uri,
                                            originalPath = if (original) actual.absolutePath else "",
                                        )
                                    )
                                ),
                                previewPath = actual.absolutePath,
                                published = uri.startsWith("content:"),
                            )
                        database.photos().save(photo)
                        lastCapture.value = photo
                        notice.value = if (original) "拍摄结果已导入" else "拍后预览已就绪，原片可从相机图库导入"
                    } else {
                        notice.value = "快门指令已完成；机身未提供拍后文件，可到相机图库刷新查看"
                    }
                } catch (error: Exception) {
                    notice.value =
                        if (error is CancellationException) "倒计时已取消" else error.message.orEmpty()
                } finally {
                    captureCountdown.value = 0
                }
            }
        captureJob = job
        return job
    }

    fun cancelCountdown() {
        if (captureCountdown.value > 0) {
            captureJob?.cancel()
            captureCountdown.value = 0
        }
    }

    fun setProperty(property: CameraProperty, value: Long) =
        scope.launch {
            if (!parameterUpdating.compareAndSet(null, property.code)) { notice.value = "正在应用相机设置，请稍候"; return@launch }
            val generation = connectionGeneration.get()
            try {
                native.setProperty(property.code, value)
                var updated = native.properties()
                for (attempt in 0 until 10) {
                    if (updated.firstOrNull { it.code == property.code }?.value == value) break
                    delay(150)
                    updated = native.properties()
                }
                if (generation == connectionGeneration.get()) {
                    properties.value = updated
                    if (updated.firstOrNull { it.code == property.code }?.value != value)
                        notice.value = "设置已发送，机身状态尚未更新，请在相机上核对"
                }
            } catch (error: Exception) {
                android.util.Log.w("FrameLink", "Camera property update failed", error)
                notice.value = error.message?.takeIf { it.any { character -> character.code in 0x4e00..0x9fff } } ?: "相机未确认此设置，请检查机身模式与连接后重试"
            } finally {
                if (generation == connectionGeneration.get()) parameterUpdating.value = null
            }
        }

    fun startFtp(address: String, port: Int, user: String, password: String) =
        scope.launch {
            try {
                if (ftp != null) {
                    notice.value = "接收服务已运行"
                    return@launch
                }
                startService()
                ftp =
                    FtpReceiver(context, database, scope, ftpStatus, notice) {
                        scope.launch { runTasks() }
                    }
                ftp!!.start(address, port, user, password)
            } catch (error: Exception) {
                ftp?.stop()
                ftp = null
                ftpStatus.value = "启动失败"
                notice.value = error.message.orEmpty()
            }
        }

    fun stopFtp() {
        ftp?.stop()
        ftp = null
        ftpStatus.value = "接收已停止，已保存照片不受影响"
    }

    fun cleanupPreviews() =
        scope.launch {
            File(context.cacheDir, "previews").deleteRecursively()
            database.photos().clearPreviews(context.cacheDir.absolutePath + "%")
            notice.value = "预览缓存已清理，原片和选片结果保留"
        }

    fun stopAll() {
        disconnect()
        stopFtp()
        ftpClient.stopAll()
        bluetooth.close()
    }

    private fun scheduleNetwork() {
        val work =
            OneTimeWorkRequestBuilder<CloudRetryWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("cloud-network-retry", ExistingWorkPolicy.KEEP, work)
    }
}

class CloudRetryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = (applicationContext as CameraApp).runtime
        runtime.database
            .tasks()
            .recover(TransferState.WAITING_NETWORK.name, TransferState.QUEUED.name, "")
        runtime.runTasks()
        return if (runtime.database.tasks().inState(TransferState.WAITING_NETWORK.name).isEmpty())
            Result.success()
        else Result.retry()
    }
}
