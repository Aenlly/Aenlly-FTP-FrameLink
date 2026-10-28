@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package app.framelink.camera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val Ink = Color(0xff1f252c)
internal val Muted = Color(0xff68717d)
internal val Brand = Color(0xff416a8c)
internal val CanvasColor = Color(0xfff5f5f7)
internal val Line = Color(0xffe7e9ee)

private enum class Tab(val title: String, val icon: ImageVector) {
    CAMERA("相机", Icons.Outlined.CameraAlt),
    GALLERY("图库", Icons.Outlined.Collections),
    PROJECTS("项目", Icons.Outlined.FolderOpen),
    TRANSFERS("传输", Icons.Outlined.SyncAlt),
}

private enum class Sheet {
    CONNECT,
    FTP,
    FTP_CLIENT,
    BLUETOOTH,
    CLOUD,
    PROJECT_PICKER,
    STORAGE,
}

@Composable
fun FrameLink(runtime: CameraRuntime) {
    val glass = remember { HazeState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos by runtime.photos.collectAsStateWithLifecycle(emptyList())
    val tasks by runtime.tasks.collectAsStateWithLifecycle(emptyList())
    val camera by runtime.camera.collectAsStateWithLifecycle()
    val message by runtime.notice.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.CAMERA) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var connectionFailure by remember { mutableStateOf("") }
    var reconnectAttempt by remember { mutableIntStateOf(0) }
    var afterReconnect by remember { mutableStateOf<(() -> Unit)?>(null) }
    var source by rememberSaveable { mutableStateOf("local") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var selection by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var viewer by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var savingSelection by remember { mutableStateOf(false) }
    var savedBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var localReview by rememberSaveable { mutableStateOf<String?>(null) }
    var newReviewPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var compare by remember { mutableStateOf(false) }
    var comparingIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var live by rememberSaveable { mutableStateOf(false) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    var pendingNetwork by remember { mutableStateOf<(() -> Unit)?>(null) }
    var networkPermissionHelp by remember { mutableStateOf(false) }
    val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingNetwork
        pendingNetwork = null
        if (LocalNetworkAccess.granted(context)) action?.invoke()
        else networkPermissionHelp = true
    }
    fun ensureNetwork(action: () -> Unit) {
        if (LocalNetworkAccess.granted(context)) action()
        else {
            pendingNetwork = action
            networkPermission.launch(arrayOf(LocalNetworkAccess.permission, Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    fun connectionSettings(error: String = "", keepIntent: Boolean = false) {
        if (!LocalNetworkAccess.granted(context)) { ensureNetwork { connectionSettings(error, keepIntent) }; return }
        reconnectAttempt++
        if (!keepIntent) afterReconnect = null
        connectionFailure = error
        sheet = Sheet.CONNECT
    }
    fun reconnect(onReady: () -> Unit = {}) {
        if (!LocalNetworkAccess.granted(context)) { ensureNetwork { reconnect(onReady) }; return }
        val status = runtime.camera.value
        if (status.connecting) return
        if (status.info != null) { onReady(); return }
        val saved = runtime.savedConnection()
        afterReconnect = onReady
        if (!saved.canReconnectDirectly()) { connectionSettings(keepIntent = true); return }
        val attempt = ++reconnectAttempt
        scope.launch {
            runtime.connect(saved).join()
            if (attempt == reconnectAttempt && runtime.camera.value.info == null) {
                if (runtime.cameraIdentityPrompt.value != null) connectionSettings(keepIntent = true)
                else connectionSettings(runtime.camera.value.message, keepIntent = true)
            }
        }
    }
    fun togglePhoto(id: String) {
        selection = selection.toMutableSet().apply { if (!remove(id)) add(id) }.toList()
    }
    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            result ->
            if (result.values.all { it }) sheet = Sheet.BLUETOOTH
            else runtime.notice.value = "需要蓝牙权限才能发现和连接相机"
        }
    fun notifyPermission() {
        if (Build.VERSION.SDK_INT >= 33)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            runtime.importUris(uris)
            tab = Tab.TRANSFERS
        }
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snackbar.showSnackbar(message)
            if (runtime.notice.value == message) runtime.notice.value = ""
        }
    }
    LaunchedEffect(tab, live) { snackbar.currentSnackbarData?.dismiss() }
    LaunchedEffect(camera.info?.id) {
        if (camera.info != null) {
            val ready = afterReconnect
            afterReconnect = null
            if (ready != null) ready()
            else if (tab == Tab.GALLERY && source == "camera") runtime.refreshCameraPhotos()
        }
        if (camera.info == null && source == "camera") selection = emptyList()
    }
    MaterialTheme(
        typography = UiTokens.typography,
        shapes = UiTokens.shapes,
        colorScheme =
            lightColorScheme(
                primary = Brand,
                secondary = Brand,
                secondaryContainer = Color(0xffe8f0f8),
                onSecondaryContainer = Brand,
                background = CanvasColor,
                surface = Color.White,
                onSurface = Ink,
                onBackground = Ink,
                surfaceVariant = Color(0xffedf0f4),
                outline = Line,
            ),
    ) {
        Scaffold(
            containerColor = CanvasColor,
            snackbarHost = {
                SnackbarHost(
                    snackbar,
                    modifier =
                        Modifier.padding(
                            bottom =
                                if (tab == Tab.GALLERY && selection.isNotEmpty()) 72.dp else 0.dp
                        ),
                )
            },
            bottomBar = {
                Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    GlassNavigation(glass, Tab.entries.map { NavigationEntry(it.title, it.icon, "tab-${it.name.lowercase(Locale.ROOT)}") }, tab.ordinal) { tab = Tab.entries[it] }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize()
                    .hazeSource(glass)
                    .background(CanvasColor)
                    .padding(
                        top = padding.calculateTopPadding(),
                        bottom =
                            if (tab == Tab.GALLERY && selection.isEmpty()) 0.dp
                            else padding.calculateBottomPadding(),
                    )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tab == Tab.CAMERA)
                        Box(
                            Modifier.size(30.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color(0xff29435c)),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.brand_icon),null,Modifier.fillMaxSize())
                        }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            if (tab == Tab.CAMERA) "光屿"
                            else if (tab == Tab.TRANSFERS) "传输任务" else tab.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { sheet = Sheet.CLOUD }) {
                        Icon(
                            Icons.Outlined.CloudQueue,
                            "云端设置",
                            tint = Muted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { sheet = Sheet.STORAGE }) {
                        Icon(
                            Icons.Outlined.Settings,
                            "设置与空间",
                            tint = Muted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                when (tab) {
                    Tab.CAMERA ->
                        CameraHome(
                            runtime,
                            photos,
                            camera,
                            onConnect = {
                                ensureNetwork { notifyPermission(); reconnect() }
                            },
                            onConnectionSettings = { connectionSettings() },
                            onBrowse = {
                                source = "camera"
                                tab = Tab.GALLERY
                                if (camera.info == null) reconnect()
                                else runtime.browseRecent()
                            },
                            onFtp = {
                                ensureNetwork { notifyPermission(); sheet = Sheet.FTP }
                            },
                            onFtpClient = {
                                ensureNetwork { notifyPermission(); sheet = Sheet.FTP_CLIENT }
                            },
                            onImport = {
                                picker.launch(arrayOf("image/*", "application/octet-stream"))
                            },
                            onRemote = {
                                if (camera.info?.canLiveView == true) live = true
                                else reconnect { if (runtime.camera.value.info?.canLiveView == true) live = true else runtime.notice.value = "当前机身模式未提供取景，请检查相机设置" }
                            },
                            onBluetooth = {
                                val required =
                                    if (Build.VERSION.SDK_INT >= 31)
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                            Manifest.permission.BLUETOOTH_SCAN,
                                        )
                                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                permissions.launch(required)
                            },
                            onPhoto = { viewer = it.id },
                        )
                    Tab.GALLERY ->
                        Gallery(
                            runtime,
                            photos,
                            camera,
                            source,
                            filter,
                            gridState,
                            selection,
                            onSource = {
                                if (source != it) {
                                    selection = emptyList()
                                    if (filter == "picked") filter = "all"
                                }
                                source = it
                                if (it == "camera" && camera.info != null) runtime.refreshCameraPhotos()
                            },
                            onFilter = { filter = it },
                            onSelect = ::togglePhoto,
                            onSelectionChange = { selection = it },
                            onOpen = { viewer = it.id },
                            onImport = {
                                picker.launch(arrayOf("image/*", "application/octet-stream"))
                            },
                            onConnect = { reconnect { runtime.refreshCameraPhotos() } },
                            onDownload = { format ->
                                runtime.enqueueDownload(selection, format)
                                selection = arrayListOf()
                                tab = Tab.TRANSFERS
                            },
                            onCompare = { comparingIds = selection.take(2); compare = true },
                            onSaveSelection = { savingSelection = true },
                            onRemove = { removing = it },
                            onUpload = {
                                sheet =
                                    if (runtime.cloud.signedIn) Sheet.PROJECT_PICKER
                                    else Sheet.CLOUD
                            },
                            onReview = { chosen ->
                                if (chosen.isNotEmpty()) newReviewPhotos = chosen.map { it.id }
                            },
                        )
                    Tab.PROJECTS ->
                        ProjectHub(
                            runtime,
                            projectId,
                            onProject = { projectId = it },
                            onCloud = { sheet = Sheet.CLOUD },
                            onBack = { projectId = null },
                            onReview = { localReview = it },
                            photos = photos,
                            onBatch = { savedBatchId = it },
                        )
                    Tab.TRANSFERS ->
                        TransferScreen(
                            tasks,
                            runtime,
                            onFtp = { ensureNetwork { sheet = Sheet.FTP } },
                            onFtpClient = { ensureNetwork { sheet = Sheet.FTP_CLIENT } },
                        )
                }
            }
        }
        when (sheet) {
            Sheet.CONNECT -> ConnectionSheet(runtime, connectionFailure, onClose = {
                sheet = null
                if (runtime.camera.value.info == null) { afterReconnect = null; reconnectAttempt++ }
            })
            Sheet.FTP -> FtpSheet(runtime, onClose = { sheet = null }, onPhotos = {
                sheet = null; source = "local"; filter = "all"; tab = Tab.GALLERY
            })
            Sheet.FTP_CLIENT ->
                FtpClientScreen(
                    runtime,
                    onClose = { sheet = null },
                    onTasks = {
                        sheet = null
                        tab = Tab.TRANSFERS
                    },
                )
            Sheet.BLUETOOTH -> BluetoothSheet(runtime, onClose = { sheet = null })
            Sheet.CLOUD -> CloudSheet(runtime, onClose = { sheet = null })
            Sheet.PROJECT_PICKER ->
                ProjectPicker(
                    runtime,
                    onClose = { sheet = null },
                    onSelect = {
                        runtime.uploadPreviews(selection, it)
                        selection = arrayListOf()
                        sheet = null
                        tab = Tab.TRANSFERS
                    },
                )
            Sheet.STORAGE ->
                SettingsSheet(
                    runtime,
                    onClose = { sheet = null },
                    onCloud = { sheet = Sheet.CLOUD },
                )
            null -> Unit
        }
        if (networkPermissionHelp) AlertDialog(onDismissRequest = { networkPermissionHelp = false },
            title = { Text("允许连接附近的相机") },
            text = { Text("请在系统应用权限中允许附近设备或局域网访问，之后即可使用相机连接和FTP传输。本机图库仍可使用。") },
            confirmButton = { TextButton(onClick = {
                networkPermissionHelp = false
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("打开权限设置") } },
            dismissButton = { TextButton(onClick = { networkPermissionHelp = false }) { Text("稍后") } })
        localReview?.let {
            LocalReviewScreen(runtime, it, photos, onClose = { localReview = null })
        }
        if (live)
            LiveScreen(
                runtime,
                onClose = { live = false },
                onPhoto = { viewer = it.id },
                paused = viewer != null,
                onGallery = { live = false; source = "camera"; tab = Tab.GALLERY; runtime.browseRecent() },
            )
        if (newReviewPhotos.isNotEmpty()) ReviewNameDialog(
            "现场选片 · " + SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date()), true,
            onClose = { newReviewPhotos = emptyList() },
            save = { name ->
                val created = runtime.reviews.create(name, newReviewPhotos)
                newReviewPhotos = emptyList(); localReview = created.id
            },
        )
        if (savingSelection) SaveSelectionDialog(runtime, selection,
            onClose = { savingSelection = false },
            onSaved = { savingSelection = false; savedBatchId = it.id; tab = Tab.PROJECTS },
        )
        savedBatchId?.let { id ->
            SavedSelectionScreen(runtime, id, photos,
                onClose = { savedBatchId = null }, onPhoto = { viewer = it.id },
                onUse = { ids ->
                    scope.launch {
                        val chosen = ids.chunked(500).flatMap { runtime.database.photos().byIds(it) }
                        runtime.database.photos().saveAll(chosen.filter { it.localHidden && it.imported }.map { it.copy(localHidden = false) })
                        source = if (chosen.any { !it.imported && it.source == PhotoSource.CAMERA.name }) "camera" else "local"
                        selection = ids; filter = "all"; tab = Tab.GALLERY; savedBatchId = null
                    }
                },
            )
        }
        photos
            .find { it.id == viewer }
            ?.let { photo ->
                PhotoViewer(
                    runtime,
                    photo,
                    onClose = { viewer = null },
                    onRemove = { removing = listOf(photo.id) },
                    selected = photo.id in selection,
                    cameraConnected = camera.info != null,
                    onSelect = { togglePhoto(photo.id) },
                    onMark = { mark ->
                        val previous = photo.mark
                        runtime.mark(photo, mark)
                        scope.launch {
                            if (
                                snackbar.showSnackbar("收藏已更新", "撤销", duration = SnackbarDuration.Short) ==
                                    SnackbarResult.ActionPerformed
                            )
                                runtime.mark(photo, PhotoMark.valueOf(previous))
                        }
                    },
                )
            }
        if (removing.isNotEmpty())
            PhotoRemovalDialog(
                runtime, removing,
                onClose = { removing = emptyList() },
                onRemoved = { ids ->
                    selection = selection.filterNot { it in ids }
                    if (viewer in ids) viewer = null
                },
            )
        if (compare)
            ComparePhotos(
                runtime,
                photos.filter { it.id in comparingIds }.take(2),
                selection = selection,
                onSelect = { togglePhoto(it.id) },
                onClose = { compare = false },
            )
    }
}

@Composable
internal fun Label(text: String) =
    Text(text, color = Muted, fontSize = 9.sp, letterSpacing = 1.6.sp)

@Composable
internal fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, fontSize = 11.sp) }
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier =
            modifier.shadow(
                3.dp,
                UiTokens.card,
                ambientColor = Brand.copy(alpha = .05f),
                spotColor = Brand.copy(alpha = .04f),
            ),
        shape = UiTokens.card,
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun Primary(
    text: String,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = UiTokens.control,
        contentPadding = PaddingValues(horizontal = 19.dp, vertical = 13.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 12.sp)
    }
}

@Composable
internal fun Info(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, lineHeight = 20.sp)
}

@Composable
private fun EmptyState(
    title: String,
    detail: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xffeaf0f5)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                null,
                tint = Color(0xff8ba1b7),
                modifier = Modifier.size(29.dp),
            )
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Info(detail)
        if (action != null) Primary(action, onClick = onAction)
    }
}

@Composable
private fun CameraHome(
    runtime: CameraRuntime,
    photos: List<Photo>,
    status: CameraStatus,
    onConnect: () -> Unit,
    onConnectionSettings: () -> Unit,
    onBrowse: () -> Unit,
    onFtp: () -> Unit,
    onFtpClient: () -> Unit,
    onImport: () -> Unit,
    onRemote: () -> Unit,
    onBluetooth: () -> Unit,
    onPhoto: (Photo) -> Unit,
) {
    val ftp by runtime.ftpStatus.collectAsStateWithLifecycle()
    val client by runtime.ftpClient.state.collectAsStateWithLifecycle()
    val imported = photos.filter { it.imported && !it.localHidden }
    LazyColumn(
        modifier = Modifier.testTag("camera-home"),
        contentPadding = PaddingValues(start = UiTokens.page, end = UiTokens.page, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(UiTokens.gap),
    ) {
        item {
            Surface(color = Color.White, shape = UiTokens.card) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onConnectionSettings) {
                        Icon(
                            Icons.Outlined.SettingsInputAntenna,
                            "相机连接设置",
                            tint = Brand,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            status.info?.displayName() ?: "连接你的相机",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                        )
                        Text(
                            if (status.connecting) "正在建立连接…"
                            else if (status.info != null) "已连接 · 固件 ${status.info.firmware}"
                            else "相机未连接",
                            fontSize = 11.sp,
                            color = if (status.info != null) Color(0xff447467) else Muted,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    TextButton(
                        onClick = if (status.info == null) onConnect else onBrowse,
                        enabled = !status.connecting,
                    ) {
                        Text(
                            if (status.connecting) "正在连接"
                            else if (status.info == null) "连接相机" else "浏览相机照片",
                            fontSize = 12.sp,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeAction(
                    "选片下载",
                    "浏览相机，按需导入",
                    Icons.Outlined.Collections,
                    Modifier.weight(1f),
                    true,
                    onBrowse,
                )
                HomeAction(
                    "取景拍摄",
                    "实时画面与遥控",
                    Icons.Outlined.CropFree,
                    Modifier.weight(1f),
                    false,
                    onRemote,
                )
            }
        }
        item {
            Surface(color = Color.White, shape = UiTokens.card) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        HomeTool("FTP 接收", "边拍边传", Icons.Outlined.Wifi, Modifier.weight(1f), onFtp)
                        HomeTool(
                            "FTP 客户端",
                            "双向文件传输",
                            Icons.Outlined.SwapHoriz,
                            Modifier.weight(1f),
                            onFtpClient,
                        )
                    }
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = Line.copy(alpha = .7f),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        HomeTool(
                            "快速快门",
                            "蓝牙遥控",
                            Icons.Outlined.Bluetooth,
                            Modifier.weight(1f),
                            onBluetooth,
                        )
                        HomeTool(
                            "本机导入",
                            "手机与读卡器",
                            Icons.Outlined.AddPhotoAlternate,
                            Modifier.weight(1f),
                            onImport,
                        )
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近照片", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("${imported.size} 张已导入", fontSize = 11.sp, color = Muted)
            }
        }
        if (imported.isEmpty())
            item {
                Surface(color = Color.White, shape = UiTokens.card) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.PhotoLibrary,
                            null,
                            tint = Muted,
                            modifier = Modifier.size(24.dp),
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("从照片开始", fontSize = 14.sp)
                            Text(
                                "导入后在这里继续浏览和选片",
                                fontSize = 11.sp,
                                color = Muted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        else
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(imported.take(12), key = { it.id }) { photo ->
                        Column(
                            Modifier.width(174.dp)
                                .clickable { onPhoto(photo) }
                                .semantics { contentDescription = "最近照片 ${photo.name}" }
                        ) {
                            PhotoThumbnail(
                                runtime,
                                photo,
                                Modifier.fillMaxWidth().aspectRatio(1.35f).clip(UiTokens.photo),
                            )
                            Text(
                                photo.name,
                                fontSize = 10.sp,
                                color = Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
                }
            }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "已选 ${imported.count { it.mark == PhotoMark.PICKED.name }}",
                    fontSize = 11.sp,
                    color = Muted,
                )
                Text(
                    "收藏 ${imported.count { it.mark == PhotoMark.FAVORITE.name }}",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
        }
        item {
            Text("FTP接收：$ftp", fontSize = 11.sp, color = Muted)
            if (client.connected)
                Text("FTP客户端：${client.profile?.label}", fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
private fun HomeAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier,
    emphasis: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            if (pressed) .98f else 1f,
            spring(dampingRatio = .88f, stiffness = 600f),
            label = "主要操作按压",
        )
    val color = if (emphasis) Color.White else Ink
    Surface(
        modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .heightIn(min = 86.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = UiTokens.card,
        color = if (emphasis) Color(0xff344f66) else Color.White,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    null,
                    tint = if (emphasis) Color(0xffdce7ee) else Brand,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    title,
                    color = color,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                subtitle,
                fontSize = 10.sp,
                color = if (emphasis) Color(0xffd6e1e8) else Muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HomeTool(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .heightIn(min = 66.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Brand, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier) {
    Panel(modifier) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Normal)
        Text(title, fontSize = 9.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
internal fun PhotoThumbnail(
    runtime: CameraRuntime,
    photo: Photo,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var model by remember(photo.id) { mutableStateOf(photo.displayUri) }
    var loading by remember(photo.id) { mutableStateOf(true) }
    var failed by remember(photo.id) { mutableStateOf(false) }
    LaunchedEffect(photo.id, photo.previewPath) {
        if (
            photo.displayUri.isBlank() ||
                photo.previewPath.isNotBlank() && !File(photo.previewPath).exists()
        )
            withContext(Dispatchers.IO) {
                runCatching { runtime.ensurePreview(photo) }
                    .onSuccess { model = it }
                    .onFailure {
                        loading = false
                        failed = true
                    }
            }
        else model = photo.displayUri
    }
    Box(
        modifier
            .semantics {
                stateDescription = if (loading) "预览加载中" else if (failed) "预览暂不可用" else "预览已显示"
            }
            .background(
                if (contentScale == ContentScale.Fit) Color(0xff15181d) else Color(0xffe6e9ed)
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (failed) Icons.Outlined.BrokenImage else Icons.Outlined.Image,
            if (failed) "预览暂不可用，原文件保留" else null,
            tint = Color(0xffbbc3cd),
        )
        if (model.isNotBlank())
            AsyncImage(
                model = model,
                contentDescription = photo.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onLoading = {
                    loading = true
                    failed = false
                },
                onSuccess = {
                    loading = false
                    failed = false
                },
                onError = {
                    loading = false
                    failed = true
                },
            )
        if (loading)
            LinearProgressIndicator(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                color = Brand,
            )
    }
}

@Composable
private fun Gallery(
    runtime: CameraRuntime,
    all: List<Photo>,
    camera: CameraStatus,
    source: String,
    filter: String,
    state: LazyGridState,
    selection: List<String>,
    onSource: (String) -> Unit,
    onFilter: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit,
    onOpen: (Photo) -> Unit,
    onImport: () -> Unit,
    onConnect: () -> Unit,
    onDownload: (DownloadFormat) -> Unit,
    onCompare: () -> Unit,
    onSaveSelection: () -> Unit,
    onRemove: (List<String>) -> Unit,
    onUpload: () -> Unit,
    onReview: (List<Photo>) -> Unit,
) {
    var download by remember { mutableStateOf(false) }
    var moreSelection by remember { mutableStateOf(false) }
    var bursts by rememberSaveable { mutableStateOf(false) }
    var layout by remember { mutableStateOf(GalleryLayout.from(runtime.settings.get("gallery.layout"))) }
    var choosingLayout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var bulkSelection by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val hasMore by runtime.galleryHasMore.collectAsStateWithLifecycle()
    val selectionSet = remember(selection) { selection.toSet() }
    var burst by remember { mutableStateOf<List<Photo>?>(null) }
    val cameraDay by runtime.galleryDay.collectAsStateWithLifecycle()
    val listedIds by runtime.currentCameraIds.collectAsStateWithLifecycle()
    val availableDays by runtime.captureDates.collectAsStateWithLifecycle()
    var localDay by rememberSaveable { mutableStateOf("") }
    val chosenDay = if (source == "camera") cameraDay else localDay
    val dragContext = "$source:$filter:$bursts:$chosenDay:${layout.key}:${if (source == "camera") camera.info?.id.orEmpty() else ""}"
    var dragGroups by remember(dragContext) { mutableStateOf<List<List<Photo>>?>(null) }
    var choosingDay by remember { mutableStateOf(false) }
    LaunchedEffect(dragContext) { bulkSelection?.cancel(); bulkSelection = null }
    var follow by rememberSaveable {
        mutableStateOf(runtime.settings.get("gallery.follow") != "false")
    }
    LaunchedEffect(all.firstOrNull()?.id) {
        if (follow && source == "local" && state.firstVisibleItemIndex == 0)
            state.animateScrollToItem(0)
    }
    fun matching(candidates: List<Photo>, cameraIds: Set<String> = listedIds): List<Photo> =
        candidates.filter { photo ->
            (if (source == "local") photo.inLibrary
            else
                camera.info != null && photo.source == PhotoSource.CAMERA.name && photo.id in cameraIds) &&
                (chosenDay.isBlank() || photoDay(photo) == chosenDay) &&
                when (filter) {
                    "picked" -> photo.id in selectionSet
                    "favorite" -> photo.mark == PhotoMark.FAVORITE.name
                    "new" -> photo.hasUnimportedFiles()
                    "raw" -> photo.files().any { it.raw }
                    else -> true
                }
        }
    val photos = matching(all)
    val allSelected = photos.isNotEmpty() && photos.all { it.id in selectionSet } && !(source == "camera" && hasMore)
    fun selectAll() {
        if (allSelected) {
            val visible = photos.map { it.id }.toSet()
            onSelectionChange(selection.filterNot { it in visible })
            return
        }
        if (source != "camera" || !hasMore) {
            onSelectionChange((selectionSet + photos.map { it.id }).toList())
            return
        }
        bulkSelection = scope.launch {
            try {
                runtime.readAllCameraPhotos()
                val ids = runtime.currentCameraIds.value
                val complete = runtime.database.photos().byIds(ids.toList())
                onSelectionChange((selectionSet + matching(complete, ids).map { it.id }).toList())
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { runtime.notice.value = error.message ?: "未能完成全选，请重试" }
            finally { bulkSelection = null }
        }
    }
    val groups =
        if (bursts) photos.groupBy { it.burstKey.ifBlank { it.id } }.values.toList()
        else photos.map { listOf(it) }
    val displayedGroups = dragGroups ?: groups
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = source == "local",
                        onClick = { onSource("local") },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) {
                        Text("已导入", fontSize = 12.sp)
                    }
                    SegmentedButton(
                        selected = source == "camera",
                        onClick = { onSource("camera") },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) {
                        Text("相机内", fontSize = 12.sp)
                    }
                }
                IconButton(
                    onClick = {
                        onReview(
                            if (selection.isEmpty()) photos else all.filter { it.id in selection }
                        )
                    },
                    enabled = photos.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Outlined.PeopleOutline,
                        "现场客户选片",
                        tint = Brand,
                        modifier = Modifier.size(UiTokens.icon),
                    )
                }
                IconButton(onClick = onImport) {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        "导入本机照片",
                        tint = Brand,
                        modifier = Modifier.size(UiTokens.icon),
                    )
                }
                Box {
                    IconButton(onClick={choosingLayout=true}) { Icon(if(layout==GalleryLayout.LIST) Icons.Outlined.ViewList else Icons.Outlined.GridView,"显示方式",tint=Brand,modifier=Modifier.size(UiTokens.icon)) }
                    DropdownMenu(choosingLayout,{choosingLayout=false}) {
                        GalleryLayout.entries.forEach { option -> DropdownMenuItem(text={Text(option.title)},leadingIcon={if(option==layout) Icon(Icons.Outlined.Check,null)},onClick={layout=option;runtime.settings.put("gallery.layout",option.key);choosingLayout=false}) }
                    }
                }
                if (source == "camera")
                    IconButton(
                        onClick = {
                            if (camera.info == null) onConnect() else runtime.refreshCameraPhotos()
                        }
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            "刷新相机照片",
                            tint = Brand,
                            modifier = Modifier.size(UiTokens.icon),
                        )
                    }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(
                    listOf(
                        "all" to "全部",
                        "picked" to "已选",
                        "favorite" to "收藏",
                        "new" to "未导入",
                        "raw" to "RAW",
                    )
                ) { (key, label) ->
                    FilterChip(filter == key, { onFilter(key) }, { Text(label, fontSize = 11.sp) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::selectAll, enabled = photos.isNotEmpty() && !camera.loadingPhotos && bulkSelection == null) {
                Text(if (allSelected) "取消全选" else "全选", fontSize = 11.sp)
            }
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    TextButton(
                        enabled = !camera.loadingPhotos && (source != "camera" || camera.info != null),
                        onClick = { choosingDay = true },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Icon(Icons.Outlined.CalendarToday, null, Modifier.size(15.dp))
                        Text(
                            chosenDay.ifBlank { "日期" },
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                item { FilterChip(bursts, {
                    bursts = !bursts
                    if (bursts) runtime.notice.value = "连拍照片会合并成一组，点开可逐张选择"
                }, { Text(if (bursts) "连拍分组 · 开" else "连拍分组", fontSize = 11.sp) }) }
                if (source == "local")
                    item {
                        FilterChip(
                            follow,
                            {
                                follow = !follow
                                runtime.settings.put("gallery.follow", follow.toString())
                            },
                            { Text("跟随最新", fontSize = 11.sp) },
                        )
                    }
                item {
                    Text(
                        if (source == "camera" && camera.info == null) "相机未连接"
                        else if (source == "camera") "已读取 ${photos.size} 张" else "${photos.size} 张",
                        fontSize = 11.sp,
                        color = Muted,
                    )
                }
                item { Text("长按滑动多选", fontSize = 10.sp, color = Muted) }
            }
            }
        }
        if (displayedGroups.isEmpty() && camera.loadingPhotos && source == "camera")
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                Text("正在读取相机照片", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
            }
        else if (displayedGroups.isEmpty())
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (source == "camera" && camera.info == null) "相机已断开"
                    else if (filter == "picked") "还没有选择照片"
                    else if (source == "camera") "在大屏上挑选照片" else "让喜欢的照片留在手机",
                    if (source == "camera" && camera.info == null) "重新连接后读取相机文件，已导入照片可在手机图库继续查看。"
                    else if (filter == "picked") "勾选照片，或长按后滑动批量选择；大图与图库使用同一份选择。"
                    else if (source == "camera") "连接后可浏览卡内照片，选择需要的格式下载。" else "导入照片后可以收藏、对比和加入客户项目。",
                    if (source == "camera" && camera.info == null) "重新连接相机"
                    else if (filter == "picked") "查看全部"
                    else if (source == "camera") "连接或刷新相机" else "导入照片",
                    if (source == "camera" && camera.info == null) onConnect
                    else if (filter == "picked") { { onFilter("all") } }
                    else if (source == "camera") onConnect else onImport,
                )
            }
        else
            LazyVerticalGrid(
                columns = GridCells.Fixed(layout.columns),
                state = state,
                userScrollEnabled = dragGroups == null,
                modifier = Modifier.weight(1f).testTag("gallery-grid")
                    .galleryDragSelection(state, dragContext, displayedGroups.map { group -> group.map { it.id } }, selection,
                        onSelectionChange,
                        onDragging = { dragging ->
                            dragGroups = if (dragging) groups else null
                            if (dragging) {
                                follow = false
                                runtime.settings.put("gallery.follow", "false")
                            }
                        }),
                contentPadding =
                    PaddingValues(
                        start = if (layout == GalleryLayout.SMALL) 2.dp else 8.dp,
                        end = if (layout == GalleryLayout.SMALL) 2.dp else 8.dp,
                        top = 4.dp,
                        bottom = if (selection.isEmpty()) 144.dp else 8.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (layout == GalleryLayout.SMALL) 2.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (layout == GalleryLayout.SMALL) 2.dp else 8.dp),
            ) {
                items(displayedGroups, key = { it.first().id }) { group ->
                    GalleryMediaItem(runtime, group, selectionSet, layout == GalleryLayout.LIST, layout == GalleryLayout.SMALL,
                        onOpen = { follow = false; runtime.settings.put("gallery.follow","false"); if(group.size>1) burst=group else onOpen(group.first()) },
                        onSelection = onSelectionChange)
                }
                if (source == "camera")
                    item {
                        TextButton(onClick = { runtime.loadPhotos() }) {
                            if (camera.loadingPhotos)
                                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Text("加载更多", fontSize = 11.sp)
                        }
                    }
            }
        if (selection.isNotEmpty())
            Surface(color = Color.White, shadowElevation = 5.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onSelectionChange(emptyList()) }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                        Text("已选 ${selection.size}", fontSize = 11.sp, color = Brand)
                        Icon(Icons.Outlined.Close, "清空本次选择", Modifier.padding(start = 4.dp).size(14.dp))
                    }
                    TextButton(onClick = onCompare, enabled = selection.size == 2) {
                        Text("对比", fontSize = 11.sp)
                    }
                    TextButton(onClick = onSaveSelection) {
                        Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(16.dp))
                        Text("保存", fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp))
                    }
                    Box {
                        IconButton(onClick = { moreSelection = true }) { Icon(Icons.Outlined.MoreHoriz, "更多选片操作", Modifier.size(19.dp)) }
                        DropdownMenu(moreSelection, { moreSelection = false }) {
                            DropdownMenuItem(text = { Text("加入云端项目") }, onClick = { moreSelection = false; onUpload() })
                        }
                    }
                    if (source == "local")
                        TextButton(onClick = { onRemove(selection) }) {
                            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(17.dp))
                            Text("移除", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    if (source == "camera")
                        Primary(
                            "下载 ${selection.size} 张",
                            camera.info?.canDownload == true,
                            Icons.Outlined.Download,
                        ) {
                            download = true
                        }
                }
            }
    }
    if (bulkSelection != null) AlertDialog(
        onDismissRequest = { bulkSelection?.cancel() }, title = { Text("正在读取全部照片") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("已读取 ${listedIds.size} 张，将按当前日期和筛选全选")
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { bulkSelection?.cancel() }) { Text("取消") } })
    if (download)
        AlertDialog(
            onDismissRequest = { download = false },
            title = { Text("选择下载格式") },
            text = {
                Column {
                    Info("RAW与JPEG属于同一次拍摄，可按需要分别导入。原片按原始字节保存。")
                    DownloadFormat.entries.forEach { format ->
                        TextButton(
                            onClick = {
                                download = false
                                onDownload(format)
                            }
                        ) {
                            Text(
                                when (format) {
                                    DownloadFormat.JPEG -> "JPEG / HEIF"
                                    DownloadFormat.RAW -> "RAW原片"
                                    DownloadFormat.ALL -> "全部文件"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { download = false }) { Text("取消") } },
        )
    if (choosingDay)
        AlertDialog(
            onDismissRequest = { choosingDay = false },
            title = { Text("按拍摄日期筛选") },
            text = {
                LazyColumn {
                    item {
                        TextButton(
                            onClick = {
                                choosingDay = false
                                if (source == "camera") runtime.selectCameraDay("")
                                else localDay = ""
                            }
                        ) {
                            Text("全部日期")
                        }
                    }
                    items(
                        (if (source == "camera") availableDays
                        else
                            all.filter { it.inLibrary }
                                .map { photoDay(it) }
                                .distinct()
                                .sortedDescending())
                    ) { day ->
                        TextButton(
                            onClick = {
                                choosingDay = false
                                if (source == "camera") runtime.selectCameraDay(day)
                                else localDay = day
                            }
                        ) {
                            Text(day)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingDay = false }) { Text("关闭") } },
        )
    burst?.let { group ->
        ModalSheet("连拍 ${group.size} 张", { burst = null }) {
            group.forEach { photo ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            burst = null
                            onOpen(photo)
                        }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhotoThumbnail(
                        runtime,
                        photo,
                        Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Text(
                        photo.name,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).padding(12.dp),
                    )
                    Checkbox(photo.id in selection, { onSelect(photo.id) })
                }
            }
        }
    }
}

@Composable
internal fun ModalSheet(
    title: String,
    onClose: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = CanvasColor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭") }
            }
            Column(
                (if (footer == null) Modifier else Modifier.weight(1f, fill = false)).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = if (footer == null) 30.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
            if (footer != null)
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp)) { footer() }
        }
    }
}

@Composable
private fun ConnectionSheet(runtime: CameraRuntime, initialFailure: String = "", onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val saved = remember { runtime.savedConnection() }
    var host by rememberSaveable { mutableStateOf(saved.host) }
    var user by rememberSaveable { mutableStateOf(saved.user) }
    var password by remember { mutableStateOf(saved.password) }
    var showPassword by remember { mutableStateOf(false) }
    var showPreparation by rememberSaveable { mutableStateOf(false) }
    var authenticate by rememberSaveable { mutableStateOf(saved.host.isBlank() || saved.user.isNotBlank()) }
    var submitted by remember { mutableStateOf(false) }
    val status by runtime.camera.collectAsStateWithLifecycle()
    val identityPrompt by runtime.cameraIdentityPrompt.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    fun closeConnection() {
        if (status.connecting || identityPrompt != null) runtime.disconnect()
        onClose()
    }
    LaunchedEffect(status.info, submitted) {
        if (submitted && status.info != null) onClose()
    }
    ModalSheet("连接相机", ::closeConnection, footer = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!submitted && initialFailure.isNotBlank()) Text("未能直接重连：$initialFailure", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            if (submitted && status.info == null && identityPrompt == null) {
                Text(status.message, color = if (status.connecting) Brand else MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Button(
                onClick = {
                    keyboard?.hide()
                    submitted = true
                    runtime.connect(ConnectionConfig(host = host.trim(), user = if (authenticate) user.trim() else "", password = if (authenticate) password else ""))
                },
                enabled = host.isNotBlank() && (!authenticate || (user.isNotBlank() && password.isNotBlank())) && !status.connecting && identityPrompt == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("camera-connect-action"),
                shape = UiTokens.control,
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
            ) {
                if (status.connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.Outlined.Link, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (status.connecting) "正在连接…" else "连接相机", fontSize = 15.sp)
            }
        }
    }) {
        Info("Sony 网络浏览与遥控 · 同一Wi-Fi或相机热点。其他具备FTP上传功能的相机可使用首页“FTP接收”。")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CameraAlt, null, tint = Brand, modifier = Modifier.size(22.dp))
                Text("开启电脑遥控与访问身份验证", Modifier.weight(1f).padding(start = 10.dp), fontSize = 13.sp)
                TextButton(onClick = { showPreparation = !showPreparation }) { Text(if (showPreparation) "收起" else "设置步骤", fontSize = 12.sp) }
            }
            androidx.compose.animation.AnimatedVisibility(showPreparation) {
                Info("① 网络 → 连接/电脑遥控 → 电脑遥控 → 开\n② 网络 → 网络选项 → 访问身份验证信息\n③ 首次填写相机地址、用户名和密码，指纹自动读取后确认\n连接前请断开其他控制App的相机会话。")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI
                            else Settings.ACTION_WIFI_SETTINGS
                        )
                    )
                }
            ) {
                Text("打开Wi-Fi设置", fontSize = 10.sp)
            }
            OutlinedButton(
                enabled = !scanning,
                onClick = {
                    scanning = true
                    scope.launch {
                        found =
                            withContext(Dispatchers.IO) {
                                runCatching { CameraDiscovery.discover(context) }
                                    .getOrDefault(emptyList())
                            }
                        scanning = false
                        if (found.isEmpty()) runtime.notice.value = "未发现相机，请确认Wi-Fi并手动填写机身地址"
                    }
                },
            ) {
                Text(if (scanning) "发现中…" else "发现相机", fontSize = 10.sp)
            }
        }
        found.forEach { address ->
            AssistChip(onClick = { host = address }, label = { Text(address) })
        }
        OutlinedTextField(
            host,
            { host = it },
            enabled = !status.connecting && identityPrompt == null,
            label = { Text("相机IP地址") },
            placeholder = { Text("例如 192.168.122.1") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("使用相机访问身份验证", Modifier.weight(1f), fontSize = 13.sp)
            Switch(
                checked = authenticate,
                onCheckedChange = { authenticate = it },
                enabled = !status.connecting && identityPrompt == null,
                modifier = Modifier.semantics { contentDescription = "使用相机访问身份验证" },
            )
        }
        if (authenticate) {
            OutlinedTextField(
                user,
                { user = it },
                label = { Text("访问认证账号") },
                enabled = !status.connecting && identityPrompt == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("访问认证密码") },
                enabled = !status.connecting && identityPrompt == null,
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPassword) "隐藏密码" else "显示密码")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Info(if (authenticate) "指纹自动读取，首次确认即可。认证信息加密保存在本机，下次无需重复输入。" else "仅在机身已关闭访问身份验证时使用此方式；首次连接可能需要在相机上配对。")
    }
    identityPrompt?.let { prompt ->
        val changed = prompt.previousFingerprint.isNotBlank()
        AlertDialog(
            onDismissRequest = { submitted = false; runtime.disconnect() },
            icon = { Icon(Icons.Outlined.VerifiedUser, null, tint = Brand) },
            shape = UiTokens.card,
            containerColor = Color.White,
            title = { Text(if (changed) "相机身份发生变化" else "确认相机身份") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("设备地址：${prompt.identity.host}")
                    Text(if (changed) "这次读取的指纹与之前不同。请在相机的访问身份验证信息中核对，再决定是否更新。" else "已自动读取指纹。请与相机的访问身份验证信息核对后确认，无需手动输入。")
                    if (changed) {
                        Text("之前的指纹", fontSize = 12.sp, color = Muted)
                        Text(prompt.previousFingerprint, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Text("${if (changed) "当前" else "SHA256"}指纹", fontSize = 12.sp, color = Muted)
                    Surface(color = CanvasColor, shape = UiTokens.control) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(prompt.identity.fingerprint, Modifier.padding(16.dp), fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                    Text("确认后才会发送认证信息。", fontSize = 12.sp, color = Muted)
                }
            },
            confirmButton = { Button(onClick = { runtime.confirmCameraIdentity(prompt.id) }, shape = UiTokens.control, colors = ButtonDefaults.buttonColors(containerColor = Brand)) { Text(if (changed) "确认更新并连接" else "确认并连接") } },
            dismissButton = { TextButton(onClick = { submitted = false; runtime.disconnect() }) { Text("取消") } },
        )
    }
}

@Composable
private fun FtpSheet(runtime: CameraRuntime, onClose: () -> Unit, onPhotos: () -> Unit) {
    FtpReceiverOptions(runtime, onClose, onPhotos)
}

@Composable
private fun BluetoothSheet(runtime: CameraRuntime, onClose: () -> Unit) {
    val status by runtime.bluetooth.state.collectAsStateWithLifecycle()
    val devices by runtime.bluetooth.devices.collectAsStateWithLifecycle()
    val ready by runtime.bluetooth.ready.collectAsStateWithLifecycle()
    ModalSheet("快速快门", onClose) {
        Info("在机身开启蓝牙遥控并进入配对。蓝牙模式提供轻量快门，不需要持续打开取景画面。")
        Text(status, fontSize = 13.sp, color = Brand)
        if (!ready && runtime.bluetooth.lastAddress.isNotBlank())
            OutlinedButton(
                onClick = {
                    runtime.startService()
                    runtime.bluetooth.connect(runtime.bluetooth.lastAddress)
                }
            ) {
                Text("连接上次的相机")
            }
        OutlinedButton(
            onClick = {
                runCatching { runtime.bluetooth.scan() }
                    .onFailure { runtime.notice.value = it.message.orEmpty() }
            }
        ) {
            Text("扫描蓝牙相机")
        }
        devices.forEach { device ->
            ListItem(
                headlineContent = { Text(device.name) },
                supportingContent = { Text(device.address, fontSize = 10.sp) },
                trailingContent = {
                    TextButton(
                        onClick = {
                            runtime.startService()
                            runtime.bluetooth.connect(device.address)
                        }
                    ) {
                        Text("连接")
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = ready, onClick = { runtime.bluetooth.focus() }) {
                Text("半按对焦")
            }
            Primary("快门", ready, Icons.Outlined.CameraAlt) { runtime.bluetooth.shutter() }
        }
        Info("快门遵循机身当前的单拍或连拍设置。指令发送后请确认机身拍摄结果。")
        if (ready) TextButton(onClick = { runtime.bluetooth.close() }) { Text("断开蓝牙") }
    }
}

@Composable
private fun TransferScreen(
    tasks: List<TransferTask>,
    runtime: CameraRuntime,
    onFtp: () -> Unit,
    onFtpClient: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportTask by remember { mutableStateOf<TransferTask?>(null) }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val task = exportTask
            if (uri != null && task != null)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                                FtpStorage.copyToUri(
                                    context,
                                    checkNotNull(FtpStorage.taskFile(context, task)) { "文件不存在" },
                                    uri,
                                )
                            }
                            .onSuccess { runtime.notice.value = "文件已另存" }
                            .onFailure { runtime.notice.value = it.message.orEmpty() }
                    }
                }
            exportTask = null
        }
    var filter by rememberSaveable { mutableStateOf("active") }
    val shown =
        tasks.filter {
            if (filter == "all") true
            else if (filter == "done") it.state == TransferState.COMPLETE.name
            else it.state !in setOf(TransferState.COMPLETE.name, TransferState.CANCELLED.name)
        }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                "文件与进度",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 10.dp),
            )
            Info("原片保存、预览生成与云端上传分别记录。")
            Row {
                TextButton(onClick = onFtp) { Text("FTP 接收") }
                TextButton(onClick = onFtpClient) { Text("FTP 客户端") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("active" to "进行中", "done" to "已完成", "all" to "全部").forEach { (key, name) ->
                    FilterChip(filter == key, { filter = key }, { Text(name, fontSize = 11.sp) })
                }
            }
        }
        if (shown.isEmpty())
            item { EmptyState("当前没有${if(filter=="done")"已完成"else"待处理"}任务", "下载和接收的照片会在这里保留记录。") }
        items(shown, key = { it.id }) { task ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (task.kind.startsWith("CLOUD")) Icons.Outlined.CloudUpload
                        else Icons.Outlined.Download,
                        null,
                        tint = Brand,
                        modifier = Modifier.size(21.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            task.title,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            taskState(task.state),
                            fontSize = 10.sp,
                            color = Muted,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                    if (task.state == TransferState.COMPLETE.name)
                        Icon(
                            Icons.Outlined.CheckCircle,
                            null,
                            tint = Color(0xff7d9a88),
                            modifier = Modifier.size(20.dp),
                        )
                }
                if (task.total > 0) {
                    LinearProgressIndicator(
                        progress = { (task.done.toFloat() / task.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        color = Brand,
                        trackColor = Line,
                    )
                    Text(
                        "${formatBytes(task.done)} / ${formatBytes(task.total)}",
                        fontSize = 9.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (task.error.isNotBlank()) Info(task.error)
                val savedFile = FtpStorage.taskFile(context, task)
                if (savedFile != null && task.state != TransferState.RUNNING.name) {
                    Row {
                        TextButton(
                            onClick = {
                                runCatching {
                                        val uri =
                                            FileProvider.getUriForFile(
                                                context,
                                                context.packageName + ".files",
                                                savedFile,
                                            )
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(
                                                        uri,
                                                        MediaFiles.mime(savedFile.name),
                                                    )
                                                    .addFlags(
                                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                    ),
                                                "打开文件",
                                            )
                                        )
                                    }
                                    .onFailure { runtime.notice.value = "手机没有可打开此格式的应用，可使用另存或分享" }
                            }
                        ) {
                            Text("打开")
                        }
                        TextButton(
                            onClick = {
                                val uri =
                                    FileProvider.getUriForFile(
                                        context,
                                        context.packageName + ".files",
                                        savedFile,
                                    )
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND)
                                            .setType(MediaFiles.mime(savedFile.name))
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "分享文件",
                                    )
                                )
                            }
                        ) {
                            Text("分享")
                        }
                        TextButton(
                            onClick = {
                                exportTask = task
                                export.launch(
                                    JSONObject(task.payload).optString("name", savedFile.name)
                                )
                            }
                        ) {
                            Text("另存")
                        }
                    }
                }
                val compressed =
                    runCatching { JSONObject(task.payload).optString("compressedUri") }
                        .getOrDefault("")
                if (compressed.isNotBlank())
                    TextButton(
                        onClick = {
                            runCatching {
                                    val raw = Uri.parse(compressed)
                                    val uri =
                                        if (raw.scheme == "file")
                                            FileProvider.getUriForFile(
                                                context,
                                                context.packageName + ".files",
                                                File(checkNotNull(raw.path)),
                                            )
                                        else raw
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND)
                                                .setType("image/jpeg")
                                                .putExtra(Intent.EXTRA_STREAM, uri)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                            "分享压缩副本",
                                        )
                                    )
                                }
                                .onFailure { runtime.notice.value = "副本暂不可分享，请检查文件权限" }
                        }
                    ) {
                        Text("分享压缩副本")
                    }
                if (
                    task.state in
                        setOf(
                            TransferState.FAILED.name,
                            TransferState.INTERRUPTED.name,
                            TransferState.WAITING_CONNECTION.name,
                            TransferState.WAITING_NETWORK.name,
                        )
                ) {
                    TextButton(
                        onClick = {
                            if (
                                task.kind == TransferKind.FTP_RECEIVE.name &&
                                    !runCatching { JSONObject(task.payload).optBoolean("ready") }
                                        .getOrDefault(false)
                            )
                                onFtp()
                            else runtime.retry(task)
                        }
                    ) {
                        Text(
                            if (
                                task.kind == TransferKind.FTP_RECEIVE.name &&
                                    !runCatching { JSONObject(task.payload).optBoolean("ready") }
                                        .getOrDefault(false)
                            )
                                "检查接收与相机重发"
                            else "重试",
                            fontSize = 11.sp,
                        )
                    }
                }
                if (
                    task.kind != TransferKind.FTP_RECEIVE.name &&
                        task.state !in
                            setOf(TransferState.COMPLETE.name, TransferState.CANCELLED.name)
                )
                    TextButton(onClick = { runtime.cancel(task) }) {
                        Text("取消任务", fontSize = 10.sp, color = Muted)
                    }
            }
        }
    }
}

private fun taskState(value: String) =
    when (TransferState.valueOf(value)) {
        TransferState.QUEUED -> "等待处理"
        TransferState.RUNNING -> "处理中"
        TransferState.WAITING_CONNECTION -> "等待连接"
        TransferState.WAITING_NETWORK -> "等待网络"
        TransferState.INTERRUPTED -> "上次运行中断"
        TransferState.COMPLETE -> "已完成"
        TransferState.FAILED -> "需要处理"
        TransferState.CANCELLED -> "已取消"
    }

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> "${bytes/1024} KB"
        else -> "$bytes B"
    }

@Composable
private fun CloudSheet(runtime: CameraRuntime, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(runtime.cloud.baseUrl) }
    var email by rememberSaveable { mutableStateOf(runtime.settings.get("cloud.email")) }
    var password by remember { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var mobile by rememberSaveable {
        mutableStateOf(runtime.settings.get("cloud.mobile") == "true")
    }
    var usage by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (runtime.cloud.signedIn)
            withContext(Dispatchers.IO) {
                runCatching { runtime.cloud.account() }
                    .onSuccess {
                        usage =
                            "已用 ${formatBytes(it.getLong("usedBytes"))} / ${formatBytes(it.getLong("quotaBytes"))}"
                    }
            }
    }
    ModalSheet("云端选片与交付", onClose) {
        Info("本地拍摄和导入无需登录。开启云端后，只上传你选定的预览照片，客户可以直接用网页挑选。")
        if (runtime.cloud.signedIn) {
            Panel {
                Text(email, fontSize = 14.sp)
                Info(usage.ifBlank { "已登录云端" })
            }
        }
        OutlinedTextField(
            address,
            { address = it },
            label = { Text("云服务地址") },
            placeholder = { Text("https://photos.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            email,
            { email = it },
            label = { Text("摄影师邮箱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!runtime.cloud.signedIn) {
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                invite,
                { invite = it },
                label = { Text("邀请码（注册时填写）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("允许移动网络上传", fontSize = 12.sp)
                Info("默认等待非计费网络，避免意外消耗流量")
            }
            Switch(
                mobile,
                {
                    mobile = it
                    runtime.settings.put("cloud.mobile", it.toString())
                    runtime.wakeNetwork()
                },
            )
        }
        if (error.isNotBlank())
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Primary(
            if (busy) "正在连接云端"
            else if (runtime.cloud.signedIn) "保存服务地址" else if (invite.isBlank()) "登录" else "邀请注册",
            !busy && address.isNotBlank(),
            Icons.Outlined.CloudQueue,
        ) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        runtime.cloud.configure(address)
                        if (!runtime.cloud.signedIn) runtime.cloud.login(email, password, invite)
                    }
                    runtime.wakeNetwork()
                    runtime.notice.value = "云服务已连接"
                    onClose()
                } catch (failure: Exception) {
                    error = failure.message.orEmpty()
                } finally {
                    busy = false
                }
            }
        }
        if (runtime.cloud.signedIn)
            TextButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runtime.cloud.logout() }
                        onClose()
                    }
                }
            ) {
                Text("退出云端账号")
            }
    }
}

@Composable
private fun ProjectPicker(runtime: CameraRuntime, onClose: () -> Unit, onSelect: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<CloudProject>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { runtime.cloud.projects() }
                .onSuccess { list = it }
                .onFailure { error = it.message.orEmpty() }
        }
    }
    ModalSheet("加入拍摄项目", onClose) {
        Info("仅生成并上传预览副本，原片继续由你保管。")
        if (error.isNotBlank()) Info(error)
        list.forEach { project ->
            ListItem(
                headlineContent = { Text(project.name, fontSize = 14.sp) },
                supportingContent = {
                    Text("第 ${project.roundNo} 轮 · ${cloudState(project.state)}", fontSize = 10.sp)
                },
                trailingContent = { TextButton(onClick = { onSelect(project.id) }) { Text("加入") } },
            )
        }
        OutlinedTextField(
            name,
            { name = it },
            label = { Text("或新建一个项目") },
            modifier = Modifier.fillMaxWidth(),
        )
        Primary("新建并加入", name.isNotBlank()) {
            scope.launch {
                try {
                    val project = withContext(Dispatchers.IO) { runtime.cloud.createProject(name) }
                    onSelect(project.id)
                } catch (failure: Exception) {
                    error = failure.message.orEmpty()
                }
            }
        }
    }
}

@Composable
internal fun ProjectsScreen(
    runtime: CameraRuntime,
    projectId: String?,
    onProject: (String) -> Unit,
    onCloud: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<CloudProject>>(emptyList()) }
    var detail by remember { mutableStateOf<CloudProject?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var sharing by remember { mutableStateOf(false) }
    var shareText by remember { mutableStateOf("") }
    var deliveryParent by remember { mutableStateOf("") }
    val context = LocalContext.current
    var synced by remember { mutableStateOf(0L) }
    suspend fun refresh() {
        if (!runtime.cloud.signedIn) return
        busy = true
        try {
            withContext(Dispatchers.IO) {
                if (projectId == null) list = runtime.cloud.projects()
                else detail = runtime.cloud.project(projectId)
            }
            error = ""
            synced = System.currentTimeMillis()
        } catch (failure: Exception) {
            error = failure.message.orEmpty()
        } finally {
            busy = false
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && projectId != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                runtime.uploadDelivery(uri, projectId, deliveryParent)
                runtime.notice.value = "成片已加入上传队列"
            }
        }
    LaunchedEffect(projectId, runtime.cloud.signedIn) { refresh() }
    if (!runtime.cloud.signedIn) {
        Column(Modifier.padding(24.dp)) {
            Text(
                "云端客户选片",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 11.dp),
            )
            Spacer(Modifier.height(16.dp))
            EmptyState("给客户一个专属选片空间", "客户免安装浏览照片、提交选择和留言。你来确认，再安心交付成片。", "启用云端选片", onCloud)
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            if (projectId != null)
                TextButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, null, Modifier.size(16.dp))
                    Text("所有项目", fontSize = 11.sp)
                }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    detail?.takeIf { projectId != null }?.name ?: "拍摄项目",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { scope.launch { refresh() } }) {
                    if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, "刷新项目", tint = Brand)
                }
                if (projectId == null)
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Outlined.Add, "新建拍摄项目", tint = Brand)
                    }
            }
            if (synced > 0)
                Info("最近同步 ${SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(synced))}")
        }
        if (error.isNotBlank())
            item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (projectId == null) {
            if (list.isEmpty() && !busy)
                item {
                    EmptyState("开始一个新的拍摄项目", "在图库选择照片后，可以加入项目并上传预览。", "新建项目", { creating = true })
                }
            items(list, key = { it.id }) { project ->
                Panel(Modifier.clickable { onProject(project.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xffe8eeed)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(project.name.take(1), color = Color(0xff90a79b), fontSize = 24.sp)
                        }
                        Column(Modifier.weight(1f).padding(start = 15.dp)) {
                            Text(project.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${project.mediaCount} 张预览 · 第 ${project.roundNo} 轮",
                                fontSize = 10.sp,
                                color = Muted,
                                modifier = Modifier.padding(top = 7.dp),
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
                    }
                    Spacer(Modifier.height(15.dp))
                    Text(cloudState(project.state), fontSize = 10.sp, color = Brand)
                }
            }
        } else
            detail?.let { project ->
                item {
                    Panel {
                        Text(
                            "第 ${project.roundNo} 轮 · ${cloudState(project.state)}",
                            fontSize = 13.sp,
                            color = Brand,
                        )
                        Info(
                            "${project.media.count{it.kind=="PREVIEW"}} 张预览 · ${project.media.count{it.kind=="PREVIEW"&&it.selected}} 张客户已选"
                        )
                        Spacer(Modifier.height(12.dp))
                        Primary("客户分享", project.media.isNotEmpty(), Icons.Outlined.Share) {
                            sharing = true
                        }
                        if (project.state == "SUBMITTED" && project.revisions.isNotEmpty())
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                runtime.cloud.confirm(
                                                    project.id,
                                                    project.revisions.first().id,
                                                )
                                            }
                                            refresh()
                                        } catch (failure: Exception) {
                                            error = failure.message.orEmpty()
                                        }
                                    }
                                }
                            ) {
                                Text("确认本轮客户选片")
                            }
                        if (project.state in setOf("SUBMITTED", "CONFIRMED", "DELIVERED"))
                            TextButton(onClick = { creating = true }) { Text("开放新一轮改选") }
                    }
                }
                items(project.media.filter { it.kind == "PREVIEW" }, key = { it.id }) { media ->
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model =
                                    ImageRequest.Builder(context)
                                        .data(
                                            runtime.cloud.baseUrl +
                                                "/api/projects/${project.id}/media/${media.id}/content"
                                        )
                                        .addHeader(
                                            "Authorization",
                                            "Bearer " + runtime.settings.get("cloud.token"),
                                        )
                                        .build(),
                                contentDescription = media.name,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(media.name, fontSize = 12.sp, maxLines = 2)
                                Text(
                                    if (media.selected) "客户已选" else "尚未选择",
                                    fontSize = 10.sp,
                                    color = if (media.selected) Brand else Muted,
                                    modifier = Modifier.padding(top = 7.dp),
                                )
                            }
                        }
                        if (media.commentText.isNotBlank()) Info(media.commentText)
                        if (media.selected && project.state in setOf("CONFIRMED", "DELIVERED"))
                            TextButton(
                                onClick = {
                                    deliveryParent = media.id
                                    picker.launch(arrayOf("image/*", "application/octet-stream"))
                                }
                            ) {
                                Icon(Icons.Outlined.Upload, null, Modifier.size(16.dp))
                                Text("上传对应成片", fontSize = 11.sp)
                            }
                    }
                }
            }
    }
    if (creating)
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(if (projectId == null) "新的拍摄项目" else "开放新一轮？") },
            text = {
                if (projectId == null)
                    OutlinedTextField(name, { name = it }, label = { Text("项目名称") })
                else Info("当前选择会作为新一轮草稿保留。已有提交历史不变，本轮成片暂时停止展示。")
            },
            confirmButton = {
                TextButton(
                    enabled = projectId != null || name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (projectId == null) runtime.cloud.createProject(name)
                                    else runtime.cloud.reopen(projectId)
                                }
                                creating = false
                                name = ""
                                refresh()
                            } catch (failure: Exception) {
                                error = failure.message.orEmpty()
                            }
                        }
                    },
                ) {
                    Text(if (projectId == null) "创建" else "开放新一轮")
                }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    if (sharing && projectId != null)
        ModalSheet("分享给客户", { sharing = false }) {
            Info("新链接会使旧链接失效，默认有效期7天。下载权限可以单独开启。")
            if (shareText.isNotBlank()) {
                Panel { Text(shareText, fontSize = 12.sp, lineHeight = 22.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            context
                                .getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("客户选片", shareText))
                            runtime.notice.value = "分享内容已复制"
                        }
                    ) {
                        Text("复制")
                    }
                    Primary("系统分享", icon = Icons.Outlined.Share) {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, shareText),
                                "分享客户选片链接",
                            )
                        )
                    }
                }
            }
            Primary("生成新的客户链接", !busy) {
                scope.launch {
                    busy = true
                    try {
                        val share =
                            withContext(Dispatchers.IO) {
                                JSONObject(runtime.cloud.share(projectId))
                            }
                        shareText =
                            "请查看「${detail?.name}」并选择喜欢的照片\n${share.getString("url")}\n访问码：${share.getString("accessCode")}"
                        refresh()
                    } catch (failure: Exception) {
                        error = failure.message.orEmpty()
                    } finally {
                        busy = false
                    }
                }
            }
            detail
                ?.shares
                ?.filter { !it.revoked }
                ?.forEach { share ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("允许客户下载已交付成片", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(
                            share.allowDownload,
                            { value ->
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            runtime.cloud.updateShare(
                                                projectId,
                                                share.id,
                                                value,
                                                false,
                                            )
                                        }
                                        refresh()
                                    } catch (failure: Exception) {
                                        error = failure.message.orEmpty()
                                    }
                                }
                            },
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        runtime.cloud.updateShare(projectId, share.id, null, true)
                                    }
                                    shareText = ""
                                    refresh()
                                } catch (failure: Exception) {
                                    error = failure.message.orEmpty()
                                }
                            }
                        }
                    ) {
                        Text("撤销这个分享链接")
                    }
                }
            if (error.isNotBlank()) Info(error)
        }
}

private fun cloudState(state: String) =
    when (state) {
        "DRAFT" -> "准备预览照片"
        "SHARED" -> "客户选片中"
        "SUBMITTED" -> "等待摄影师确认"
        "CONFIRMED" -> "选片已确认"
        "DELIVERED" -> "成片已交付"
        else -> "等待同步"
    }

@Composable
private fun SettingsSheet(runtime: CameraRuntime, onClose: () -> Unit, onCloud: () -> Unit) {
    val scope = rememberCoroutineScope()
    var storage by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val originals =
                File(runtime.context.filesDir, "media")
                    .walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() }
            val previews =
                File(runtime.context.cacheDir, "previews")
                    .walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() }
            storage = "App保存文件 ${formatBytes(originals)}\n预览缓存 ${formatBytes(previews)}"
        }
    }
    ModalSheet("设置与空间", onClose) {
        Panel {
            Text("光屿 FrameLink", fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Info("版本 ${BuildConfig.VERSION_NAME} · 开发测试版\n本地相机功能不要求云端账号")
        }
        SectionTitle("手机空间")
        Info(storage)
        Info("清理预览缓存会保留原片、系统相册和本机选片记录。已经存入系统相册的照片由系统相册管理。")
        OutlinedButton(onClick = { confirm = true }) { Text("清理预览缓存") }
        TextButton(onClick = onCloud) {
            Icon(Icons.Outlined.CloudQueue, null, Modifier.size(18.dp))
            Text("云端服务与流量设置", modifier = Modifier.padding(start = 9.dp))
        }
        TextButton(
            onClick = {
                runtime.stopAll()
                runtime.context.stopService(Intent(runtime.context, CaptureService::class.java))
                runtime.notice.value = "所有相机连接已停止"
            }
        ) {
            Text("停止所有相机连接")
        }
        Info("开源来源：Aenlly-FTP、alpha2go、α-Remote、SonnyLink\n原片按原始字节保存，只有用户主动选择的预览或成片会上传。")
    }
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清理预览缓存？") },
            text = { Info("原片和选片结果保留，需要时会重新生成或从相机获取预览。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        runtime.cleanupPreviews()
                        onClose()
                    }
                ) {
                    Text("清理缓存")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
}

@Composable
private fun PhotoViewer(
    runtime: CameraRuntime,
    photo: Photo,
    onClose: () -> Unit,
    onRemove: () -> Unit,
    selected: Boolean,
    cameraConnected: Boolean,
    onSelect: () -> Unit,
    onMark: (PhotoMark) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = photo.files().firstOrNull { !it.raw && it.savedUri.isNotBlank() }
    var model by remember(photo.id) { mutableStateOf(original?.savedUri ?: photo.displayUri) }
    var viewerLoading by remember(photo.id) { mutableStateOf(true) }
    var viewerFailure by remember(photo.id) { mutableStateOf("") }
    var filesInfo by remember { mutableStateOf(false) }
    var retryKey by remember(photo.id) { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var edited by remember { mutableStateOf<File?>(null) }
    val edit =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            val uri = result.data?.data ?: edited?.let { Uri.fromFile(it) }
            if (uri != null) runtime.importUris(listOf(uri))
        }
    fun share(editing: Boolean) {
        scope.launch {
            try {
                val source = original?.localUri ?: model
                check(source.isNotBlank()) { "请先下载原片或加载预览" }
                val raw = Uri.parse(source)
                val name = if (original != null) original.name else "preview.jpg"
                val file =
                    withContext(Dispatchers.IO) {
                        File(
                                context.filesDir,
                                "media/${if(editing)"edited"else"shared"}/${UUID.randomUUID()}_$name",
                            )
                            .apply {
                                parentFile!!.mkdirs()
                                val input =
                                    if (raw.scheme == "content" || raw.scheme == "file")
                                        context.contentResolver.openInputStream(raw)
                                    else File(source).inputStream()
                                input!!.use { stream ->
                                    outputStream().use { stream.copyTo(it, 65536) }
                                }
                            }
                    }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                val intent =
                    Intent(if (editing) Intent.ACTION_EDIT else Intent.ACTION_SEND)
                        .setDataAndType(uri, MediaFiles.mime(name))
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        .putExtra(Intent.EXTRA_STREAM, uri)
                if (editing) {
                    edited = file
                    edit.launch(intent)
                } else context.startActivity(Intent.createChooser(intent, "分享照片"))
            } catch (failure: Exception) {
                runtime.notice.value = failure.message.orEmpty()
            }
        }
    }
    LaunchedEffect(photo.id, retryKey) {
        if (original == null)
            withContext(Dispatchers.IO) {
                runCatching { runtime.ensurePreview(photo, true) }
                    .onSuccess { model = it }
                    .onFailure {
                        viewerLoading = false
                        viewerFailure = it.message ?: "预览加载失败，请重试"
                    }
            }
    }
    val glass = remember { HazeState() }
    DarkScene {
        FullScreenScene(onClose) {
            DarkDialogBars()
            if (filesInfo) PhotoFilesDialog(photo) { filesInfo = false }
            Box(Modifier.fillMaxSize().background(Color(0xff111318)).padding(dialogSafePadding())) {
                Box(
                    Modifier.fillMaxSize()
                        .clipToBounds()
                        .testTag("photo-viewer-image")
                        .semantics {
                            stateDescription =
                                if (viewerLoading) "照片加载中"
                                else if (viewerFailure.isNotBlank()) "照片暂不可用" else "照片已显示"
                        }
                        .hazeSource(glass)
                        .background(Color(0xff111318)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (model.isBlank() && viewerFailure.isBlank()) Info("等待预览")
                    else if (model.isNotBlank())
                        key(retryKey) {
                            AsyncImage(
                                model = model,
                                contentDescription = photo.name,
                                contentScale = ContentScale.Fit,
                                modifier =
                                    Modifier.fillMaxSize()
                                        .drawWithContent {
                                            withTransform({
                                                translate(pan.x, pan.y)
                                                scale(scale, scale, center)
                                            }) {
                                                this@drawWithContent.drawContent()
                                            }
                                        }
                                        .transformable(
                                            rememberTransformableState { zoom, offset, _ ->
                                                scale = (scale * zoom).coerceIn(1f, 6f)
                                                pan = if (scale <= 1f) Offset.Zero else pan + offset
                                            }
                                        )
                                        .pointerInput(photo.id) {
                                            detectTapGestures(
                                                onDoubleTap = {
                                                    scale = if (scale > 1f) 1f else 3f
                                                    pan = Offset.Zero
                                                }
                                            )
                                        },
                                onLoading = {
                                    viewerLoading = true
                                    viewerFailure = ""
                                },
                                onSuccess = {
                                    viewerLoading = false
                                    viewerFailure = ""
                                },
                                onError = {
                                    viewerLoading = false
                                    viewerFailure =
                                        if (photo.imported) "此格式暂不能显示，已保存的原文件可在传输页打开或另存。"
                                        else "预览暂不能显示，请检查相机连接后重试。"
                                },
                            )
                        }
                    if (viewerLoading)
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    if (viewerFailure.isNotBlank())
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(viewerFailure, color = Color(0xffd1d7df), fontSize = 13.sp)
                            TextButton(
                                onClick = {
                                    viewerFailure = ""
                                    viewerLoading = true
                                    retryKey++
                                }
                            ) {
                                Text("重试加载")
                            }
                        }
                }
                GlassSurface(
                    glass,
                    Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
                    radius = 20.dp,
                    dark = true,
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                photo.displayTitle(),
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (original != null) "已导入原片"
                                else if (photo.imported) "已导入RAW原片 · 当前显示预览"
                                else if (photo.source == PhotoSource.CAMERA.name && !cameraConnected) "离线预览 · 相机已断开"
                                else "相机预览 · 清晰度以原片为准",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = .8f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(photo.importTitle(),fontSize=10.sp,color=Color.White.copy(alpha=.75f),maxLines=2)
                        }
                        if (scale > 1.01f)
                            Text(
                                "%.1f×".format(Locale.ROOT, scale),
                                color = Color(0xffc8dceb),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        IconButton(onClick={filesInfo=true}) { Icon(Icons.Outlined.Info,"文件信息",tint=Color.White) }
                        if (photo.inLibrary)
                            IconButton(onClick = onRemove) {
                                Icon(Icons.Outlined.DeleteOutline, "移除照片", tint = Color.White)
                            }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Outlined.Close, "关闭照片", tint = Color.White)
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                    GlassSurface(glass, Modifier.fillMaxWidth(), radius = 22.dp, dark = true) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            MarkAction(
                                "收藏",
                                Icons.Outlined.FavoriteBorder,
                                photo.mark == PhotoMark.FAVORITE.name,
                            ) {
                                onMark(
                                    if (photo.mark == PhotoMark.FAVORITE.name) PhotoMark.NONE
                                    else PhotoMark.FAVORITE
                                )
                            }
                            PhotoSelectionAction(selected, onSelect)
                            MarkAction("修图", Icons.Outlined.Tune, false) { share(true) }
                            MarkAction("分享", Icons.Outlined.IosShare, false) { share(false) }
                        }
                    }
                    Text(
                        "双指缩放 · 双击查看细节 · 修图先创建副本",
                        color = Color.White.copy(alpha = .9f),
                        fontSize = 10.sp,
                        modifier =
                            Modifier.align(Alignment.CenterHorizontally)
                                .padding(top = 8.dp)
                                .background(Color.Black.copy(alpha = .7f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkAction(title: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 48.dp)
            .clip(UiTokens.control)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            null,
            tint = if (active) Color(0xffa5c5e4) else Color.White.copy(alpha = .92f),
            modifier = Modifier.size(22.dp),
        )
        Text(
            title,
            fontSize = 11.sp,
            color = if (active) Color(0xffa5c5e4) else Color.White.copy(alpha = .82f),
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun ComparePhotos(runtime: CameraRuntime, photos: List<Photo>, selection: List<String>, onSelect: (Photo) -> Unit, onClose: () -> Unit) {
    DarkScene {
        FullScreenScene(onClose) {
            DarkDialogBars()
            Column(
                Modifier.fillMaxSize()
                    .background(Color(0xff111318))
                    .padding(dialogSafePadding())
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "双图对比",
                        fontSize = 18.sp,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, "关闭对比", tint = Color.White)
                    }
                }
                photos.forEach { photo ->
                    Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                        PhotoThumbnail(
                            runtime,
                            photo,
                            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Fit,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                photo.name,
                                color = Color(0xffa0a8b5),
                                fontSize = 10.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onSelect(photo) }) {
                                Text(
                                    if (photo.id in selection) "已选择" else "选择这张",
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
                Info("对比画面可能为相机预览，原片细节请下载后查看。")
            }
        }
    }
}

@Composable
private fun PhotoSelectionAction(selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(UiTokens.control)
            .background(if (selected) SelectionAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(if (selected) "已选择" else "选择", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
    }
}


private fun photoDay(photo: Photo): String =
    photo.files().firstOrNull()?.cameraDay?.takeIf { it.isNotBlank() }
        ?: SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(photo.capturedAt))

@Composable
internal fun DarkDialogBars() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = false
            WindowInsetsControllerCompat(window, view).isAppearanceLightNavigationBars = false
        }
    }
}

/** 全屏对话框独立预留系统导航区域，避免继承主界面消费后的零insets */
@Composable
internal fun dialogSafePadding(): PaddingValues {
    val context = LocalContext.current
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val compose = WindowInsets.safeDrawing.asPaddingValues()
    val windowInsets = if (Build.VERSION.SDK_INT >= 30) {
        val nativeInsets = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        androidx.core.graphics.Insets.of(nativeInsets.left, nativeInsets.top, nativeInsets.right, nativeInsets.bottom)
    } else androidx.core.graphics.Insets.NONE
    val native =
        androidx.core.view.ViewCompat.getRootWindowInsets(view)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            ?.bottom ?: 0
    val resource = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    val fallback = if (resource == 0) 0 else context.resources.getDimensionPixelSize(resource)
    val bottom = with(density) { maxOf(native, fallback, windowInsets.bottom).toDp() } + 12.dp
    val top = with(density) { windowInsets.top.toDp() }
    val left = with(density) { windowInsets.left.toDp() }
    val right = with(density) { windowInsets.right.toDp() }
    return PaddingValues(
        start = maxOf(compose.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), left),
        top = maxOf(compose.calculateTopPadding(), top),
        end = maxOf(compose.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), right),
        bottom = maxOf(compose.calculateBottomPadding(), bottom),
    )
}
