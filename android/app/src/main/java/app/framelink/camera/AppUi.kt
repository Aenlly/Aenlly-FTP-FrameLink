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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.flow.collect
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
    val photoIndex=remember(photos){photos.associateBy{it.id}}
    val tasks by runtime.tasks.collectAsStateWithLifecycle(emptyList())
    val camera by runtime.camera.collectAsStateWithLifecycle()
    val cameraIds by runtime.currentCameraIds.collectAsStateWithLifecycle()
    val cameraHasMore by runtime.galleryHasMore.collectAsStateWithLifecycle()
    val message by runtime.notice.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.CAMERA) }
    val windowDensity=androidx.compose.ui.platform.LocalDensity.current
    val configuredWidth=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    var windowPixels by remember{mutableIntStateOf(0)}
    val windowWidth=if(windowPixels>0)with(windowDensity){windowPixels.toDp().value.toInt()}else configuredWidth
    val galleryWindow=galleryWindowLayout(windowWidth)
    val railGallery=tab==Tab.GALLERY&&galleryWindow.rail
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var connectionFailure by remember { mutableStateOf("") }
    var reconnectAttempt by remember { mutableIntStateOf(0) }
    var afterReconnect by remember { mutableStateOf<(() -> Unit)?>(null) }
    var source by rememberSaveable { mutableStateOf("local") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var galleryLocalDay by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var selectionKeys by rememberSaveable { mutableStateOf<Map<String,String>>(emptyMap()) }
    val hasRemoteDraft=remember(photos,selection){val ids=selection.toSet();photos.any{it.id in ids&&it.source==PhotoSource.CAMERA.name}}
    var gallerySelecting by rememberSaveable { mutableStateOf(false) }
    var galleryOverlayOpen by remember{mutableStateOf(false)}
    var selectionNeedsCheck by rememberSaveable { mutableStateOf(false) }
    var checkingSelection by remember { mutableStateOf(false) }
    var sharingSelection by remember { mutableStateOf(false) }
    var checkingSelectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraEpoch by rememberSaveable { mutableIntStateOf(0) }
    var viewer by rememberSaveable { mutableStateOf<String?>(null) }
    val splitGallery=tab==Tab.GALLERY&&galleryWindow.preview&&viewer!=null
    var viewerIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var viewerAutoLoad by rememberSaveable{mutableStateOf(false)}
    var viewerContext by rememberSaveable{mutableStateOf("")}
    var removing by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var savingSelection by rememberSaveable { mutableStateOf(false) }
    var savedBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var localReview by rememberSaveable { mutableStateOf<String?>(null) }
    var newReviewPhotos by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var compare by rememberSaveable { mutableStateOf(false) }
    var comparingIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var live by rememberSaveable { mutableStateOf(false) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val tabStates=rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    fun galleryContext()="$source:$filter:${if(source=="camera")runtime.galleryDay.value else galleryLocalDay}:${camera.info?.id.orEmpty()}:$cameraEpoch"
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
    fun changeSelection(ids:List<String>){
        val lookup=photoIndex
        selection=ids.distinct()
        selectionKeys=selection.mapNotNull { id -> (selectionKeys[id]?:lookup[id]?.selectionResourceKey())?.let { id to it } }.toMap()
        if(selection.none{lookup[it]?.source==PhotoSource.CAMERA.name})selectionNeedsCheck=false
    }
    fun togglePhoto(id: String) {
        changeSelection(selection.toMutableSet().apply { if (!remove(id)) add(id) }.toList())
    }
    fun validSelection():Boolean{
        val lookup=photoIndex
        val valid=selection.filter { id -> lookup[id]?.selectionResourceKey()?.let { it==selectionKeys[id] }==true }
        if(valid.size==selection.size)return true
        changeSelection(valid)
        runtime.notice.value="部分照片的资源信息已变化，已移出本次选择，请重新核对"
        return false
    }
    fun continueSelection(){
        if(source!="camera"||!selectionNeedsCheck){gallerySelecting=true;return}
        val connected=camera.info?.id
        if(connected==null){reconnect{continueSelection()};return}
        if(checkingSelection)return
        checkingSelection=true
        checkingSelectionJob=scope.launch{
            try{
                runtime.selectCameraDay("").join()
                runtime.readAllCameraPhotos()
                check(runtime.camera.value.info?.id==connected){"相机连接已变化，请重新核对"}
                val ids=runtime.currentCameraIds.value
                val lookup=selection.chunked(500).flatMap{runtime.database.photos().byIds(it)}.associateBy{it.id}
                val valid=selection.filter { id -> lookup[id]?.let { p -> p.selectionResourceKey()==selectionKeys[id]&&(p.source!=PhotoSource.CAMERA.name||id in ids) }==true }
                val removed=selection.size-valid.size
                changeSelection(valid)
                selectionNeedsCheck=false
                gallerySelecting=true
                filter="all"
                if(removed>0)runtime.notice.value="有 $removed 张相机资源不再匹配，已移出本次选择"
            }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception){runtime.notice.value=error.message?:"选片核对未完成，草稿保留"}
            finally{checkingSelection=false}
        }
    }
    BackHandler(tab==Tab.GALLERY&&gallerySelecting&&viewer==null){gallerySelecting=false}
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
            viewer=null
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
        if(camera.info==null){
            if(lastCameraId!=null)cameraEpoch++
            if(hasRemoteDraft)selectionNeedsCheck=true
        }
        camera.info?.id?.let { currentId ->
            if(lastCameraId!=null&&lastCameraId!=currentId&&selection.isNotEmpty())selectionNeedsCheck=true
            lastCameraId=currentId
        }
    }
    LaunchedEffect(photos,selection,camera.info?.id){
        if(camera.info==null&&hasRemoteDraft)selectionNeedsCheck=true
    }
    val currentPhotoViewer: @Composable (Boolean)->Unit = { embedded ->
            photos
            .find { it.id == viewer }
            ?.let { photo ->
                PhotoViewer(
                    runtime,
                    photo,
                    embedded=embedded,
                    onClose = { viewer = null },
                    onRemove = { removing = listOf(photo.id) },
                    selected = photo.id in selection,
                    cameraConnected = camera.info != null,
                    resourceConfirmed=photo.source!=PhotoSource.CAMERA.name||photo.id in cameraIds,
                    canDownload=camera.info?.canDownload==true,
                    neighbors=viewerIds.mapNotNull(photoIndex::get).takeIf{list->list.any{it.id==photo.id}}?:listOf(photo),
                    onNavigate={viewer=it},
                    canLoadMore=viewerAutoLoad&&viewerContext==galleryContext()&&source=="camera"&&filter!="picked"&&cameraHasMore,
                    loadingMore=camera.loadingPhotos,
                    onNeedMore={if(runtime.galleryError.value.isBlank())runtime.loadPhotos()},
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
            modifier=Modifier.onSizeChanged{windowPixels=it.width},
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
                if(!railGallery&&(tab!=Tab.GALLERY||!gallerySelecting))Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    GlassNavigation(glass, Tab.entries.map { NavigationEntry(it.title, it.icon, "tab-${it.name.lowercase(Locale.ROOT)}") }, tab.ordinal) { tab = Tab.entries[it] }
                }
            },
        ) { padding ->
            val workspacePadding=PaddingValues(
                top=padding.calculateTopPadding(),
                bottom=if(tab==Tab.GALLERY&&gallerySelecting&&!railGallery)0.dp else padding.calculateBottomPadding(),
            )
            Row(
                Modifier.fillMaxSize()
                    .hazeSource(glass)
                    .background(CanvasColor)
                    .padding(workspacePadding)
                    .consumeWindowInsets(workspacePadding)
            ) {
                if(railGallery)GalleryNavigationRail(Tab.entries.map{NavigationEntry(it.title,it.icon,"tab-${it.name.lowercase(Locale.ROOT)}")},tab.ordinal,{viewer=null;tab=Tab.entries[it]},{sheet=Sheet.STORAGE})
                Column(Modifier.weight(1f).fillMaxHeight()){
                if(tab!=Tab.GALLERY)Row(
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
                tabStates.SaveableStateProvider(tab.name){when (tab) {
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
                            onPhoto = { viewerAutoLoad=false;viewerIds=photos.filter{p->p.inLibrary}.map{p->p.id};viewer = it.id },
                        )
                    Tab.GALLERY ->Row(Modifier.fillMaxSize()){
                        Box(Modifier.weight(1f).fillMaxHeight()){
                        Gallery(
                            runtime,
                            photos,
                            camera,
                            source,
                            filter,
                            localDay=galleryLocalDay,
                            onLocalDay={galleryLocalDay=it},
                            state=gridState,
                            selection=selection,
                            previewId=if(splitGallery)viewer else null,
                            onOverlayChanged={galleryOverlayOpen=it},
                            selecting=gallerySelecting,
                            needsCheck=selectionNeedsCheck,
                            onSelecting={gallerySelecting=it},
                            onRecheck=::continueSelection,
                            onSource = {
                                viewer=null
                                gallerySelecting=false
                                source = it
                                if(it=="camera"&&selection.any{id->photoIndex[id]?.source==PhotoSource.CAMERA.name&&id !in cameraIds})selectionNeedsCheck=true
                                if (it == "camera" && camera.info != null) runtime.refreshCameraPhotos()
                            },
                            onFilter = { filter = it },
                            onSelect = ::togglePhoto,
                            onSelectionChange = ::changeSelection,
                            onOpen = { viewer = it.id },
                            onViewerScope={ids,automatic->
                                val contextKey=galleryContext()
                                if(viewer==null||!automatic){viewerIds=ids;viewerAutoLoad=automatic;viewerContext=contextKey}
                                else if(viewerAutoLoad&&viewerContext==contextKey)viewerIds=(viewerIds+ids).distinct()
                            },
                            onImport = {
                                picker.launch(arrayOf("image/*", "application/octet-stream"))
                            },
                            onConnect = { reconnect { runtime.refreshCameraPhotos() } },
                            onDownload = { format ->
                                if(selectionNeedsCheck||selection.any{id->photoIndex[id]?.source==PhotoSource.CAMERA.name&&id !in cameraIds}){
                                    selectionNeedsCheck=true
                                    continueSelection()
                                }
                                else if(validSelection()){
                                    runtime.enqueueDownload(selection.filter { id -> photoIndex[id]?.source==PhotoSource.CAMERA.name }, format)
                                    gallerySelecting=false
                                    viewer=null
                                    tab=Tab.TRANSFERS
                                }
                            },
                            onCompare = { if(validSelection()){comparingIds = selection.take(2); compare = true} },
                            onSaveSelection = { if(validSelection())savingSelection = true },
                            onShareSelection = {
                                if(!sharingSelection&&validSelection())scope.launch{
                                    sharingSelection=true
                                    runtime.notice.value="正在生成图文选片单"
                                    try{
                                        val chosen=selection.mapNotNull(photoIndex::get)
                                        val batch=SelectionBatch(UUID.randomUUID().toString(),"本次选片",Json.encode(selection),Json.encode(chosen))
                                        shareContactSheets(context,runtime.batches.contactSheets(batch))
                                    }catch(error:Exception){runtime.notice.value=error.message?:"图文选片单未能生成"}
                                    finally{sharingSelection=false}
                                }
                            },
                            onRemove = { if(validSelection())removing = it },
                            onUpload = {
                                if(validSelection())sheet =
                                    if (runtime.cloud.signedIn) Sheet.PROJECT_PICKER
                                    else Sheet.CLOUD
                            },
                            onReview = { chosen ->
                                if (chosen.isNotEmpty()&&(selection.isEmpty()||validSelection())) newReviewPhotos = chosen.map { it.id }
                            },
                        )
                        }
                        if(splitGallery)Box(Modifier.weight(1f).fillMaxHeight().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xff111318)).testTag("gallery-preview-pane")){
                            if(viewer!=null)currentPhotoViewer(true)
                            else Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                Icon(Icons.Outlined.Collections,null,tint=Color.White.copy(alpha=.6f),modifier=Modifier.size(42.dp))
                                Text("点选照片查看大图",color=Color.White.copy(alpha=.8f),modifier=Modifier.padding(top=16.dp))
                            }
                        }
                    }
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
                }}
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
                        viewer=null
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
            onSaved = { savingSelection = false; gallerySelecting=false;viewer=null; savedBatchId = it.id; tab = Tab.PROJECTS },
        )
        savedBatchId?.let { id ->
            SavedSelectionScreen(runtime, id, photos,
                onClose = { savedBatchId = null }, onPhoto = { target -> scope.launch{viewerAutoLoad=false;viewerIds=runtime.database.batches().find(id)?.photoIds().orEmpty();viewer=target.id} },
                onUse = { ids ->
                    scope.launch {
                        val chosen = ids.chunked(500).flatMap { runtime.database.photos().byIds(it) }
                        runtime.database.photos().saveAll(chosen.filter { it.localHidden && it.imported }.map { it.copy(localHidden = false) })
                        source = if (chosen.any { !it.imported && it.source == PhotoSource.CAMERA.name }) "camera" else "local"
                        changeSelection(ids)
                        runtime.database.batches().find(id)?.snapshots()?.takeIf{it.isNotEmpty()}?.let{snapshots->selectionKeys=snapshots.filter{it.id in ids}.associate{it.id to it.selectionResourceKey()}}
                        selectionNeedsCheck=source=="camera"; gallerySelecting=false; galleryLocalDay=""; filter = "all"; tab = Tab.GALLERY; savedBatchId = null
                    }
                },
            )
        }
        val galleryBlockingOverlay=tab==Tab.GALLERY&&(savingSelection||compare||galleryOverlayOpen||sheet!=null||localReview!=null||newReviewPhotos.isNotEmpty())
        if(!splitGallery&&!galleryBlockingOverlay)currentPhotoViewer(false)
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
        if(checkingSelection)AlertDialog(onDismissRequest={checkingSelectionJob?.cancel()},title={Text("正在核对选片")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){LinearProgressIndicator(Modifier.fillMaxWidth());Text("重新读取相机资源，确认草稿仍对应原来的照片。不会拍照或删除文件。")}},confirmButton={},dismissButton={TextButton(onClick={checkingSelectionJob?.cancel()}){Text("取消")}})
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
    localDay:String,
    onLocalDay:(String)->Unit,
    state: LazyGridState,
    selection: List<String>,
    previewId:String?=null,
    onOverlayChanged:(Boolean)->Unit={},
    selecting: Boolean,
    needsCheck: Boolean,
    onSelecting: (Boolean) -> Unit,
    onRecheck: () -> Unit,
    onSource: (String) -> Unit,
    onFilter: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSelectionChange: (List<String>) -> Unit,
    onOpen: (Photo) -> Unit,
    onViewerScope: (List<String>,Boolean) -> Unit,
    onImport: () -> Unit,
    onConnect: () -> Unit,
    onDownload: (DownloadFormat) -> Unit,
    onCompare: () -> Unit,
    onSaveSelection: () -> Unit,
    onShareSelection: () -> Unit,
    onRemove: (List<String>) -> Unit,
    onUpload: () -> Unit,
    onReview: (List<Photo>) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()){
    var download by remember { mutableStateOf(false) }
    var moreSelection by remember { mutableStateOf(false) }
    var bursts by rememberSaveable { mutableStateOf(false) }
    var layout by remember { mutableStateOf(GalleryLayout.from(runtime.settings.get("gallery.layout"))) }
    var choosingLayout by remember { mutableStateOf(false) }
    var choosingFilter by remember { mutableStateOf(false) }
    var libraryMore by remember { mutableStateOf(false) }
    var clearSelection by remember { mutableStateOf(false) }
    val configuredWidth=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val paneWidth=maxWidth.value.toInt()
    val columns=galleryColumnCount(paneWidth,layout,adaptive=configuredWidth>=600)
    val scope = rememberCoroutineScope()
    var bulkSelection by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val hasMore by runtime.galleryHasMore.collectAsStateWithLifecycle()
    val pageError by runtime.galleryError.collectAsStateWithLifecycle()
    val selectionSet = remember(selection) { selection.toSet() }
    var burst by remember { mutableStateOf<List<Photo>?>(null) }
    val cameraDay by runtime.galleryDay.collectAsStateWithLifecycle()
    val listedIds by runtime.currentCameraIds.collectAsStateWithLifecycle()
    val availableDays by runtime.captureDates.collectAsStateWithLifecycle()
    val chosenDay = if (source == "camera") cameraDay else localDay
    val dragContext = "$source:$filter:$bursts:$chosenDay:${layout.key}:$columns:${if (source == "camera") camera.info?.id.orEmpty() else ""}"
    var dragGroups by remember(dragContext) { mutableStateOf<List<List<Photo>>?>(null) }
    var dragHadScopeNote by remember { mutableStateOf(false) }
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
    var scopedBurstViewer by remember{mutableStateOf(false)}
    LaunchedEffect(photos.map{it.id}){if(!scopedBurstViewer)onViewerScope(photos.map{it.id},true)}
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
    SideEffect{onOverlayChanged(download||moreSelection||choosingLayout||choosingFilter||libraryMore||clearSelection||choosingDay||bulkSelection!=null||burst!=null)}
    DisposableEffect(Unit){onDispose{onOverlayChanged(false)}}
    val scrollContext="$source:$chosenDay:$filter:${camera.info?.id.orEmpty()}"
    var appliedScrollContext by rememberSaveable{mutableStateOf(scrollContext)}
    LaunchedEffect(scrollContext) { if(appliedScrollContext!=scrollContext){state.scrollToItem(0);appliedScrollContext=scrollContext} }
    LaunchedEffect(source, camera.info?.id, chosenDay, filter, layout, columns, hasMore, camera.loadingPhotos, photos.size, bulkSelection, dragGroups != null, pageError) {
        if (source == "camera" && camera.info != null && filter != "picked" && hasMore &&
            !camera.loadingPhotos && bulkSelection == null && dragGroups == null && pageError.isBlank()) {
            if (photos.isEmpty()) runtime.loadPhotos().join()
            else snapshotFlow {
                val info = state.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= 0 && last >= info.totalItemsCount - columns * 2 - 1
            }.collect { nearEnd -> if (nearEnd) runtime.loadPhotos().join() }
        }
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.padding(horizontal=16.dp)){
            Row(Modifier.fillMaxWidth().heightIn(min=70.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(if(selecting)"选择照片" else "图库",fontSize=26.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                    Text(if(selecting)"本次已选 "+selection.size+" 张 · "+photos.count{it.id in selectionSet}+" 张在当前范围"
                        else if(source=="local")"原片保存在手机" else camera.info?.displayName()?.plus(" · 已连接")?:"相机未连接",
                        modifier=Modifier.testTag("gallery-selection-count"),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                TextButton(onClick={onSelecting(!selecting)},modifier=Modifier.semantics{contentDescription=if(selecting)"完成选择" else "选择照片"}){
                    Text(if(selecting)"完成" else if(selection.isEmpty())"选择" else "选择 ("+selection.size+")",fontSize=14.sp)
                }
                Box{
                    IconButton(onClick={libraryMore=true}){Icon(Icons.Outlined.MoreHoriz,"图库更多操作",tint=Ink)}
                    DropdownMenu(libraryMore,{libraryMore=false}){
                        DropdownMenuItem(text={Text("导入本机照片")},leadingIcon={Icon(Icons.Outlined.AddPhotoAlternate,null)},onClick={libraryMore=false;onImport()})
                        DropdownMenuItem(text={Text("现场客户选片")},leadingIcon={Icon(Icons.Outlined.PeopleOutline,null)},enabled=photos.isNotEmpty(),onClick={
                            libraryMore=false;onReview(if(selection.isEmpty())photos else all.filter{it.id in selectionSet})
                        },modifier=Modifier.semantics{contentDescription="现场客户选片"})
                        if(source=="camera")DropdownMenuItem(text={Text(if(camera.info==null)"重新连接相机" else "刷新相机照片")},onClick={
                            libraryMore=false
                            if(camera.info==null)onConnect()else runtime.refreshCameraPhotos()
                        },modifier=Modifier.semantics{contentDescription="刷新相机照片"})
                        if(source=="local")DropdownMenuItem(text={Text(if(follow)"关闭跟随最新" else "跟随最新照片")},onClick={follow=!follow;runtime.settings.put("gallery.follow",follow.toString());libraryMore=false})
                    }
                }
            }
            Row(Modifier.padding(vertical=6.dp).clip(RoundedCornerShape(22.dp)).background(CanvasColor).padding(4.dp)){
                listOf("camera" to "相机内","local" to "已导入").forEach{(key,title)->
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if(source==key)Color.White else Color.Transparent)
                        .clickable{onSource(key)}.padding(horizontal=22.dp,vertical=9.dp),contentAlignment=Alignment.Center){
                        Text(title,fontSize=13.sp,color=if(source==key)Ink else Muted,fontWeight=if(source==key)FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={choosingDay=true},enabled=source!="camera"||camera.info!=null,contentPadding=PaddingValues(horizontal=0.dp)){
                    Text(chosenDay.ifBlank{"全部日期"},fontSize=13.sp,color=Ink)
                    Icon(Icons.Outlined.ExpandMore,null,Modifier.padding(start=3.dp).size(17.dp),tint=Muted)
                }
                Text(photos.size.toString()+" 张"+if(filter!="all")" · "+when(filter){"picked"->"本次已选";"favorite"->"收藏";"new"->"未导入";else->"RAW"}else"",
                    modifier=Modifier.weight(1f).padding(start=12.dp),fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                IconButton(onClick={choosingFilter=true}){Icon(Icons.Outlined.FilterList,"筛选照片",tint=if(filter=="all")Muted else SelectionAccent,modifier=Modifier.size(22.dp))}
                IconButton(onClick={choosingLayout=true}){Icon(if(layout==GalleryLayout.LIST)Icons.Outlined.ViewList else Icons.Outlined.GridView,"显示方式",tint=Muted,modifier=Modifier.size(22.dp))}
            }
            if(selecting&&(dragGroups==null||dragHadScopeNote))Row(Modifier.fillMaxWidth().padding(bottom=6.dp),verticalAlignment=Alignment.CenterVertically){
                Text(if(needsCheck&&source=="camera")"重连后需核对相机资源" else "全选作用于当前日期和筛选",fontSize=11.sp,color=Muted,modifier=Modifier.weight(1f))
                TextButton(onClick=::selectAll,enabled=photos.isNotEmpty()&&!camera.loadingPhotos&&bulkSelection==null){
                    Text(if(allSelected)"取消全选" else "全选",fontSize=12.sp)
                }
            }
        }
        if (displayedGroups.isEmpty() && (camera.loadingPhotos || (hasMore && pageError.isBlank() && filter != "picked")) && source == "camera")
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                Text("正在读取相机照片", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
            }
        else if (displayedGroups.isEmpty() && source == "camera" && pageError.isNotBlank())
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState("照片读取未完成", pageError, "重试读取", { runtime.refreshCameraPhotos() })
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
                columns = GridCells.Fixed(columns),
                state = state,
                userScrollEnabled = dragGroups == null,
                modifier = Modifier.weight(1f).testTag("gallery-grid")
                    .galleryDensityGesture(!selecting&&layout!=GalleryLayout.LIST){larger->
                        val options=listOf(GalleryLayout.SMALL,GalleryLayout.MEDIUM,GalleryLayout.LARGE)
                        layout=options[(options.indexOf(layout)+if(larger)1 else -1).coerceIn(0,options.lastIndex)]
                        runtime.settings.put("gallery.layout",layout.key)
                    }
                    .galleryDragSelection(state, dragContext, displayedGroups.map { group -> group.map { it.id } }, selection,
                        onSelectionChange,
                        onDragging = { dragging ->
                            if(dragging)dragHadScopeNote=selecting
                            dragGroups = if (dragging) groups else null
                            if (dragging) {
                                onSelecting(true)
                                follow = false
                                runtime.settings.put("gallery.follow", "false")
                            }
                        }),
                contentPadding =
                    PaddingValues(
                        start = if (layout == GalleryLayout.LIST) 12.dp else 2.dp,
                        end = if (layout == GalleryLayout.LIST) 12.dp else 2.dp,
                        top = 4.dp,
                        bottom = 8.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if(layout==GalleryLayout.LIST)8.dp else 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(displayedGroups, key = { it.first().id }) { group ->
                    GalleryMediaItem(runtime, group, selectionSet, layout == GalleryLayout.LIST, layout == GalleryLayout.SMALL, selecting=selecting,previewing=group.any{it.id==previewId},
                        onOpen = { follow = false; runtime.settings.put("gallery.follow","false"); if(selecting){val ids=group.map{it.id}.toSet();onSelectionChange((if(group.all{it.id in selectionSet})selectionSet-ids else selectionSet+ids).toList())} else if(group.size>1) burst=group else {scopedBurstViewer=false;onViewerScope(photos.map{it.id},true);onOpen(group.first())} },
                        onSelection = onSelectionChange)
                }
                if (source == "camera")
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            if (camera.loadingPhotos) {
                                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                Text("正在读取照片", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(start = 8.dp))
                            } else if (pageError.isNotBlank()) TextButton(onClick = { runtime.loadPhotos() }) { Text("读取失败，点此重试", fontSize = 11.sp) }
                            else Text(if (filter == "picked") "已显示本次选择" else if (hasMore) "向下滑动继续加载" else "已加载全部", fontSize = 11.sp, color = Muted)
                        }
                    }
            }
        if(selecting)Surface(color=Color.White,shadowElevation=2.dp){
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                TextButton(onClick={clearSelection=true},enabled=selection.isNotEmpty(),contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.semantics{contentDescription="清空本次选择"}){Text("清空",fontSize=12.sp)}
                TextButton(onClick=onCompare,enabled=selection.size==2,contentPadding=PaddingValues(horizontal=4.dp)){Text("对比",fontSize=12.sp)}
                TextButton(onClick=onSaveSelection,enabled=selection.isNotEmpty(),contentPadding=PaddingValues(horizontal=4.dp)){Text("存选片",fontSize=12.sp)}
                Box{
                    IconButton(onClick={moreSelection=true},enabled=selection.isNotEmpty()){Icon(Icons.Outlined.MoreHoriz,"更多选片操作",Modifier.size(21.dp))}
                    DropdownMenu(moreSelection,{moreSelection=false}){
                        if(source=="local")DropdownMenuItem(text={Text("移除所选照片")},leadingIcon={Icon(Icons.Outlined.DeleteOutline,null)},onClick={moreSelection=false;onRemove(selection)})
                        DropdownMenuItem(text={Text("现场客户选片")},onClick={moreSelection=false;onReview(all.filter{it.id in selectionSet})})
                        DropdownMenuItem(text={Text("加入云端项目")},onClick={moreSelection=false;onUpload()})
                    }
                }
                if(source=="camera")Button(
                    enabled=all.any{it.id in selectionSet&&it.source==PhotoSource.CAMERA.name}&&camera.info!=null,
                    onClick={if(needsCheck)onRecheck()else download=true},
                    shape=RoundedCornerShape(22.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)){
                    Text(if(needsCheck)"核对选片" else "导入 "+all.count{it.id in selectionSet&&it.source==PhotoSource.CAMERA.name}+" 张",fontSize=13.sp)
                }else Button(onClick=onShareSelection,enabled=selection.isNotEmpty(),shape=RoundedCornerShape(22.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)){
                    Text("分享选片单",fontSize=13.sp)
                }
            }
        }else if(selection.isNotEmpty())Box(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),contentAlignment=Alignment.Center){
            Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xffeaf2ff)).clickable{onSelecting(true)}.padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                Text("本次已选 "+selection.size+" 张",fontSize=12.sp,color=SelectionAccent)
                Text(if(needsCheck&&source=="camera")" · 待核对" else " · 继续处理",fontSize=12.sp,color=SelectionAccent,modifier=Modifier.padding(start=8.dp))
                Icon(Icons.Outlined.ChevronRight,null,Modifier.size(16.dp),tint=SelectionAccent)
            }
        }
    }
    if(clearSelection)AlertDialog(onDismissRequest={clearSelection=false},title={Text("清空本次选择？")},text={Text("只清空当前选择，收藏、选片记录和原片保留。")},
        confirmButton={TextButton(onClick={onSelectionChange(emptyList());clearSelection=false}){Text("清空选择")}},dismissButton={TextButton(onClick={clearSelection=false}){Text("取消")}})
    if(choosingFilter)ModalSheet("筛选照片",{choosingFilter=false}){
        listOf("all" to "全部","picked" to "本次已选","favorite" to "收藏","new" to "未导入","raw" to "RAW").forEach{(key,title)->
            Row(Modifier.fillMaxWidth().clickable{onFilter(key);choosingFilter=false}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                Text(title,Modifier.weight(1f),fontSize=15.sp)
                if(filter==key)Icon(Icons.Outlined.Check,null,tint=SelectionAccent)
            }
        }
    }
    if(choosingLayout)ModalSheet("显示方式",{choosingLayout=false}){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            GalleryLayout.entries.forEach{option->FilterChip(option==layout,{layout=option;runtime.settings.put("gallery.layout",option.key);choosingLayout=false},{Text(option.title,fontSize=12.sp)})}
        }
        Row(Modifier.fillMaxWidth().clickable{bursts=!bursts}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("连拍分组",fontSize=15.sp);Text("合并相邻连拍，点开仍可逐张选择",fontSize=12.sp,color=Muted)}
            Switch(bursts,{bursts=it},modifier=Modifier.semantics{contentDescription="连拍分组"})
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
                        val files=all.filter{it.id in selectionSet&&it.source==PhotoSource.CAMERA.name}.flatMap{it.downloadFiles(format)}
                        val pending=files.filter{it.savedUri.isBlank()}
                        TextButton(
                            enabled=pending.isNotEmpty()&&camera.info?.canDownload==true,
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
                            Text(if(files.isEmpty())" · 无此格式" else if(pending.isEmpty())" · 已导入" else " · "+pending.size+" 个文件 · "+android.text.format.Formatter.formatShortFileSize(LocalContext.current,pending.sumOf{it.size}),fontSize=11.sp,color=Muted)
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
                                else onLocalDay("")
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
                                else onLocalDay(day)
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
                            scopedBurstViewer=true;onViewerScope(group.map{it.id},false)
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
    embedded:Boolean=false,
    onClose: () -> Unit,
    onRemove: () -> Unit,
    selected: Boolean,
    cameraConnected: Boolean,
    resourceConfirmed:Boolean,
    canDownload:Boolean,
    neighbors:List<Photo>,
    onNavigate:(String)->Unit,
    canLoadMore:Boolean,
    loadingMore:Boolean,
    onNeedMore:()->Unit,
    onSelect: () -> Unit,
    onMark: (PhotoMark) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = photo.files().firstOrNull { !it.raw && it.savedUri.isNotBlank() }
    var model by remember(photo.selectionResourceKey(),original?.savedUri) { mutableStateOf(original?.savedUri ?: photo.displayUri) }
    var viewerLoading by remember(photo.id) { mutableStateOf(true) }
    var viewerFailure by remember(photo.id) { mutableStateOf("") }
    var filesInfo by remember { mutableStateOf(false) }
    var retryKey by remember(photo.id) { mutableIntStateOf(0) }
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var pan by remember(photo.id) { mutableStateOf(Offset.Zero) }
    var imageBounds by remember(photo.id){mutableStateOf(IntSize.Zero)}
    val decodedSizes=remember{mutableStateMapOf<String,IntSize>()}
    val readyModels=remember{mutableStateMapOf<String,String>()}
    var imageViewport by remember{mutableStateOf(IntSize.Zero)}
    fun boundedPan(position:Offset,zoom:Float):Offset{
        val bounds=if(imageBounds.width>0)imageBounds else decodedSizes[photo.id]?:IntSize.Zero
        if(bounds.width<=0||imageViewport.width<=0)return Offset.Zero
        val fit=minOf(imageViewport.width.toFloat()/bounds.width,imageViewport.height.toFloat()/bounds.height)
        val x=((bounds.width*fit*zoom-imageViewport.width)/2).coerceAtLeast(0f)
        val y=((bounds.height*fit*zoom-imageViewport.height)/2).coerceAtLeast(0f)
        return Offset(position.x.coerceIn(-x,x),position.y.coerceIn(-y,y))
    }
    var viewerControls by remember { mutableStateOf(true) }
    var viewerMore by remember { mutableStateOf(false) }
    var importingCurrent by remember { mutableStateOf(false) }
    val pager=rememberPagerState(initialPage=neighbors.indexOfFirst{it.id==photo.id}.coerceAtLeast(0),pageCount={neighbors.size})
    val strip=rememberLazyListState()
    LaunchedEffect(pager.settledPage,neighbors.size,canLoadMore,loadingMore){
        if(canLoadMore&&!loadingMore&&pager.settledPage>=neighbors.lastIndex-2)onNeedMore()
    }
    LaunchedEffect(photo.id,model){if(model.isNotBlank()&&readyModels[photo.id]==model){viewerLoading=false;viewerFailure=""}}
    LaunchedEffect(photo.id){
        val index=neighbors.indexOfFirst{it.id==photo.id}.coerceAtLeast(0)
        if(!pager.isScrollInProgress&&pager.settledPage!=index)pager.scrollToPage(index)
        strip.animateScrollToItem(index)
    }
    LaunchedEffect(pager,neighbors.map{it.id},photo.id){
        snapshotFlow{pager.settledPage}.collect { index -> neighbors.getOrNull(index)?.takeIf{it.id!=photo.id}?.let{onNavigate(it.id)} }
    }
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
                val source = original?.savedUri ?: model
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
    LaunchedEffect(photo.selectionResourceKey(), retryKey) {
        if (original == null)
            withContext(Dispatchers.IO) {
                runCatching { runtime.ensurePreview(photo, true) }
                    .onSuccess { model = it }
                    .onFailure {
                        viewerLoading = false
                        viewerFailure = if(model.isBlank())it.message ?: "预览加载失败，请重试" else ""
                    }
            }
    }
    val glass = remember { HazeState() }
    DarkScene {
        PhotoViewerScene(embedded,onClose) {
            if(!embedded)DarkDialogBars()
            if (filesInfo) PhotoFilesDialog(photo) { filesInfo = false }
            if(importingCurrent)AlertDialog(onDismissRequest={importingCurrent=false},title={Text("导入当前照片")},text={Column{
                DownloadFormat.entries.forEach{format->TextButton(enabled=photo.downloadFiles(format).any{it.savedUri.isBlank()},onClick={runtime.enqueueDownload(listOf(photo.id),format);runtime.notice.value="已加入导入队列，可在传输页查看";importingCurrent=false;onClose()}){Text(when(format){DownloadFormat.JPEG->"JPEG / HEIF";DownloadFormat.RAW->"RAW原片";DownloadFormat.ALL->"全部文件"})}}
            }},confirmButton={},dismissButton={TextButton(onClick={importingCurrent=false}){Text("取消")}})
            Box(Modifier.fillMaxSize().background(Color(0xff111318)).padding(if(embedded)PaddingValues(0.dp)else dialogSafePadding())) {
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
                        key(retryKey){
                            HorizontalPager(state=pager,userScrollEnabled=scale<=1.01f,modifier=Modifier.fillMaxSize().testTag("photo-viewer-pager")){page->
                                val pagePhoto=neighbors[page]
                                val active=pagePhoto.id==photo.id
                                AsyncImage(model=if(active)model else pagePhoto.displayUri,contentDescription=pagePhoto.name,contentScale=ContentScale.Fit,
                                    modifier=Modifier.fillMaxSize().onSizeChanged{if(active)imageViewport=it}
                                        .drawWithContent{
                                            withTransform({translate(if(active)pan.x else 0f,if(active)pan.y else 0f);scale(if(active)scale else 1f,if(active)scale else 1f,center)}){
                                                this@drawWithContent.drawContent()
                                            }
                                        }.transformable(state=rememberTransformableState{zoom,offset,_->
                                            if(active){
                                                scale=(scale*zoom).coerceIn(1f,6f)
                                                pan=if(scale<=1f)Offset.Zero else boundedPan(pan+offset,scale)
                                            }
                                        },canPan={scale>1f},enabled=active)
                                        .pointerInput(pagePhoto.id){
                                            detectTapGestures(onTap={viewerControls=!viewerControls},onDoubleTap={point->
                                                if(active){
                                                    scale=if(scale>1f)1f else 3f
                                                    pan=if(scale<=1f)Offset.Zero else boundedPan(Offset((imageViewport.width/2f-point.x)*2,(imageViewport.height/2f-point.y)*2),scale)
                                                }
                                            })
                                        },
                                    onLoading={if(active){viewerLoading=true;viewerFailure=""}},
                                    onSuccess={
                                        val bounds=IntSize(it.result.drawable.intrinsicWidth,it.result.drawable.intrinsicHeight)
                                        decodedSizes[pagePhoto.id]=bounds
                                        readyModels[pagePhoto.id]=if(active)model else pagePhoto.displayUri
                                        if(active){viewerLoading=false;viewerFailure="";imageBounds=bounds}
                                    },
                                    onError={if(active){viewerLoading=false;viewerFailure=if(photo.imported)"此格式暂不能显示，原文件仍可在文件信息中查看。" else "预览暂不能显示，请检查连接后重试。"}})
                            }
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
                if(viewerControls){
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp).background(Color.Black.copy(alpha=.42f),RoundedCornerShape(24.dp)).padding(horizontal=4.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically){
                        IconButton(onClick=onClose){Icon(Icons.Outlined.ArrowBack,"关闭照片",tint=Color.White)}
                        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){
                            Text(SimpleDateFormat("M月d日 · HH:mm",Locale.CHINA).format(Date(photo.capturedAt)),color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Medium)
                            Text(if(original!=null)"已导入原片 · "+photo.formatTitle() else if(photo.imported)"已导入RAW · 当前为预览" else if(!cameraConnected&&photo.source==PhotoSource.CAMERA.name)"离线缓存 · 相机已断开" else if(!resourceConfirmed)"缓存预览 · 需重新核对相机" else "相机预览 · "+photo.formatTitle(),
                                fontSize=11.sp,color=Color.White.copy(alpha=.72f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        PhotoSelectionAction(selected,onSelect,enabled=!pager.isScrollInProgress)
                    }
                    if(scale>1.01f)TextButton(onClick={scale=1f;pan=Offset.Zero},modifier=Modifier.align(Alignment.TopEnd).padding(top=80.dp,end=16.dp).background(Color.Black.copy(alpha=.5f),CircleShape)){Text("还原",color=Color.White,fontSize=12.sp)}
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                        LazyRow(state=strip,modifier=Modifier.fillMaxWidth().height(54.dp).testTag("viewer-filmstrip"),horizontalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(horizontal=8.dp)){
                            items(neighbors,key={it.id}){item->
                                Box(Modifier.size(50.dp).clip(RoundedCornerShape(7.dp)).border(if(item.id==photo.id)2.dp else 0.dp,if(item.id==photo.id)Color.White else Color.Transparent,RoundedCornerShape(7.dp))
                                    .clickable{scope.launch{pager.animateScrollToPage(neighbors.indexOf(item))}}.semantics{contentDescription="缩略图 "+item.name}){
                                    PhotoThumbnail(runtime,item,Modifier.fillMaxSize().padding(if(item.id==photo.id)3.dp else 0.dp).graphicsLayer{alpha=if(item.id==photo.id)1f else .6f})
                                }
                            }
                        }
                        GlassSurface(glass,Modifier.widthIn(max=520.dp).fillMaxWidth(),radius=26.dp,dark=true){
                            Row(Modifier.fillMaxWidth().padding(vertical=2.dp),horizontalArrangement=Arrangement.SpaceEvenly){
                                IconButton(onClick={share(false)},enabled=!pager.isScrollInProgress){Icon(Icons.Outlined.IosShare,"分享照片",tint=Color.White)}
                                IconButton(onClick={onMark(if(photo.mark==PhotoMark.FAVORITE.name)PhotoMark.NONE else PhotoMark.FAVORITE)},enabled=!pager.isScrollInProgress){
                                    Icon(if(photo.mark==PhotoMark.FAVORITE.name)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,"收藏照片",tint=if(photo.mark==PhotoMark.FAVORITE.name)SelectionAccent else Color.White)
                                }
                                IconButton(onClick={filesInfo=true},enabled=!pager.isScrollInProgress){Icon(Icons.Outlined.Info,"文件信息",tint=Color.White)}
                                Box{
                                    IconButton(onClick={viewerMore=true},enabled=!pager.isScrollInProgress){Icon(Icons.Outlined.MoreHoriz,"照片更多操作",tint=Color.White)}
                                    DropdownMenu(viewerMore,{viewerMore=false}){
                                        DropdownMenuItem(text={Text("系统修图（创建副本）")},onClick={viewerMore=false;share(true)})
                                        if(photo.source==PhotoSource.CAMERA.name&&photo.hasUnimportedFiles())DropdownMenuItem(text={Text("导入当前照片")},enabled=canDownload&&resourceConfirmed,onClick={viewerMore=false;importingCurrent=true})
                                        if(photo.inLibrary)DropdownMenuItem(text={Text("移除照片")},onClick={viewerMore=false;onRemove()},modifier=Modifier.semantics{contentDescription="移除照片"})
                                    }
                                }
                            }
                        }
                        Text((neighbors.indexOfFirst{it.id==photo.id}+1).toString()+" / "+neighbors.size+" · "+photo.displayTitle(),fontSize=11.sp,color=Color.White.copy(alpha=.68f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
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
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                    if(maxWidth>=600.dp&&maxWidth>maxHeight)Row(Modifier.fillMaxSize()){
                        photos.forEach{photo->ComparisonPhoto(runtime,photo,photo.id in selection,{onSelect(photo)},Modifier.weight(1f).fillMaxHeight())}
                    }else Column(Modifier.fillMaxSize()){
                        photos.forEach{photo->ComparisonPhoto(runtime,photo,photo.id in selection,{onSelect(photo)},Modifier.weight(1f).fillMaxWidth())}
                    }
                }
                Info("对比画面可能为相机预览，原片细节请下载后查看。")
            }
        }
    }
}

@Composable
private fun PhotoSelectionAction(selected: Boolean, onClick: () -> Unit,enabled:Boolean=true) {
    Box(Modifier.size(44.dp).clip(CircleShape).toggleable(selected,enabled=enabled,role=androidx.compose.ui.semantics.Role.Checkbox,onValueChange={onClick()}).semantics{
        contentDescription="选择当前照片"
        stateDescription=if(selected)"已选择" else "未选择"
    },contentAlignment=Alignment.Center){
        Box(Modifier.size(27.dp).background(if(selected)SelectionAccent else Color.Black.copy(alpha=.18f),CircleShape).border(2.dp,Color.White,CircleShape),contentAlignment=Alignment.Center){
            if(selected)Icon(Icons.Outlined.Check,null,tint=Color.White,modifier=Modifier.size(19.dp))
        }
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
