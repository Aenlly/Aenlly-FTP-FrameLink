package app.framelink.camera

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/** 画面始终按完整窗口显示，控制只作为可收起的玻璃浮层，不参与预览尺寸计算 */
@Composable
internal fun LiveScreen(runtime: CameraRuntime, onClose: () -> Unit, onPhoto: (Photo) -> Unit, paused: Boolean) {
    val frame by runtime.frame.collectAsStateWithLifecycle()
    val camera by runtime.camera.collectAsStateWithLifecycle()
    val properties by runtime.properties.collectAsStateWithLifecycle()
    val updating by runtime.parameterUpdating.collectAsStateWithLifecycle()
    val countdown by runtime.captureCountdown.collectAsStateWithLifecycle()
    val latest by runtime.lastCapture.collectAsStateWithLifecycle()
    val notice by runtime.notice.collectAsStateWithLifecycle()
    val orientation = LocalConfiguration.current.orientation
    val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val glass = remember { HazeState() }
    var controls by rememberSaveable { mutableStateOf(!landscape) }
    var pro by remember { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var zebra by rememberSaveable { mutableStateOf(false) }
    var peak by rememberSaveable { mutableStateOf(false) }
    var timer by rememberSaveable { mutableIntStateOf(0) }
    var shooting by remember { mutableStateOf(false) }
    var histogram by remember { mutableStateOf(IntArray(64)) }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    var rendererError by remember { mutableStateOf("") }
    var presented by remember { mutableStateOf(false) }
    var rendererKey by remember { mutableIntStateOf(0) }
    var property by remember { mutableStateOf<CameraProperty?>(null) }
    LaunchedEffect(orientation) { controls = !landscape; presented = false }
    LaunchedEffect(paused) { if (paused) { runtime.cancelCountdown(); runtime.stopLive() } else runtime.startLive() }
    DisposableEffect(Unit) { onDispose { runtime.cancelCountdown(); runtime.stopLive() } }
    fun retry() { rendererError = ""; presented = false; rendererKey++; runtime.stopLive(); runtime.startLive() }
    fun shoot() {
        if (countdown > 0) { runtime.cancelCountdown(); shooting = false }
        else if (!shooting) {
            shooting = true
            scope.launch { try { runtime.capture(timer).join() } finally { shooting = false } }
        }
    }
    DarkScene {
        FullScreenScene(onClose) {
            DarkDialogBars()
            LiveSystemBars()
            Box(Modifier.fillMaxSize().background(Color(0xff0e1015))) {
                Box(
                    Modifier.fillMaxSize().testTag("live-preview").then(if (controls) Modifier.hazeSource(glass) else Modifier)
                        .semantics {
                            contentDescription = if (controls) "取景画面，点按隐藏控制" else "显示取景控制"
                            stateDescription = if (rendererError.isNotBlank()) rendererError else if (presented) "取景画面已显示" else "等待取景画面"
                            onClick { controls = !controls; true }
                        }
                        .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) },
                    contentAlignment = Alignment.Center,
                ) {
                    key(rendererKey) {
                        AndroidView(
                            factory = { LiveViewSurface(it) },
                            update = { view ->
                                view.frameSize = { width, height -> val next = IntSize(width, height); if (dimensions != next) dimensions = next }
                                view.failure = { rendererError = it }
                                view.framePresented = { if (!presented) presented = true }
                                view.histogram = if (controls && pro) { bins -> histogram = bins } else null
                                view.zebra = zebra
                                view.peaking = peak
                                frame?.let(view::submit)
                            },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { it.onPause() },
                        )
                    }
                    if (grid && dimensions.width > 0) Canvas(Modifier.fillMaxSize()) {
                        val scale = minOf(size.width / dimensions.width, size.height / dimensions.height)
                        val width = dimensions.width * scale; val height = dimensions.height * scale
                        val left = (size.width - width) / 2; val top = (size.height - height) / 2
                        for (i in 1..2) {
                            drawLine(Color.White.copy(alpha = .18f), Offset(left + width * i / 3, top), Offset(left + width * i / 3, top + height), 1f)
                            drawLine(Color.White.copy(alpha = .18f), Offset(left, top + height * i / 3), Offset(left + width, top + height * i / 3), 1f)
                        }
                    }
                    if (frame == null || rendererError.isNotBlank()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (rendererError.isNotBlank()) rendererError else "等待相机取景画面", color = Color.White, fontSize = 12.sp)
                            if (rendererError.isNotBlank()) TextButton(onClick = ::retry) { Text("重新打开取景") }
                        }
                    }
                    if (countdown > 0) Text(countdown.toString(), color = Color.White, fontSize = 64.sp)
                }
                if (controls) {
                    Box(Modifier.fillMaxSize().padding(dialogSafePadding()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        GlassSurface(glass, Modifier.align(Alignment.TopStart).widthIn(max = if (landscape) 250.dp else (LocalConfiguration.current.screenWidthDp - 172).coerceAtLeast(130).dp), radius = 24.dp, dark = true) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                                IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, "返回相机", tint = Color.White, modifier = Modifier.size(19.dp)) }
                                Column(Modifier.weight(1f, false)) {
                                    Text("取景拍摄", fontSize = 12.sp, color = Color.White)
                                    Text(camera.info?.model.orEmpty() + if (dimensions.width > 0) " · ${dimensions.width}×${dimensions.height}" else "", color = Color.White.copy(alpha = .72f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        GlassSurface(glass, Modifier.align(Alignment.TopEnd), radius = 24.dp, dark = true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { controls = false }) { Icon(Icons.Outlined.Fullscreen, "沉浸取景", tint = Color.White, modifier = Modifier.size(20.dp)) }
                                TextButton(onClick = { pro = !pro }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (pro) "简洁布局" else "专业工具", fontSize = 11.sp, color = Color.White) }
                            }
                        }
                        Column(
                            Modifier.align(Alignment.BottomCenter).widthIn(max = if (landscape) 520.dp else 440.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (notice.isNotBlank()) GlassSurface(glass, radius = 16.dp, dark = true) { Text(notice, color = Color.White, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(10.dp)) }
                            GlassSurface(glass, Modifier.fillMaxWidth(), radius = 22.dp, dark = true) {
                                Column {
                                    if (pro) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                            TextButton(onClick = { grid = !grid }) { Text("网格 ${if (grid) "开" else "关"}", fontSize = 10.sp, color = Color.White) }
                                            TextButton(onClick = { peak = !peak }) { Text("峰值对焦 ${if (peak) "开" else "关"}", fontSize = 10.sp, color = Color.White) }
                                            TextButton(onClick = { zebra = !zebra }) { Text("过曝提示 ${if (zebra) "开" else "关"}", fontSize = 10.sp, color = Color.White) }
                                        }
                                        Canvas(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 18.dp)) {
                                            val max = (histogram.maxOrNull() ?: 1).coerceAtLeast(1)
                                            histogram.forEachIndexed { index, value -> val x = index * size.width / 64; drawLine(Color.White.copy(alpha = .65f), Offset(x, size.height), Offset(x, size.height - value.toFloat() / max * size.height), size.width / 70) }
                                        }
                                    }
                                    LazyRow(Modifier.fillMaxWidth().testTag("live-properties").padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(if (pro) properties.filter { CameraPropertyText.recognized(it) && CameraPropertyText.label(it) != null } else CameraPropertyText.quick(properties), key = { it.code }) { item ->
                                            Column(Modifier.clip(UiTokens.control).clickable(enabled = updating == null && CameraPropertyText.editable(item)) { property = item }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                                Text(CameraPropertyText.name(item), color = Color.White.copy(alpha = .65f), fontSize = 9.sp)
                                                Text(if (updating == item.code) "应用中…" else CameraPropertyText.compact(item), color = Color.White, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            if (!landscape) LiveShutterDock(glass, timer, shooting, countdown, camera.info?.canCapture == true, latest, { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }, ::shoot, ::retry, onPhoto)
                        }
                        if (landscape) Box(Modifier.align(Alignment.CenterEnd)) {
                            LiveShutterDock(glass, timer, shooting, countdown, camera.info?.canCapture == true, latest, { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }, ::shoot, ::retry, onPhoto, vertical = true)
                        }
                    }
                }
            }
        }
        property?.let { item ->
            val current = properties.firstOrNull { it.code == item.code } ?: item
            AlertDialog(
                onDismissRequest = { property = null }, shape = UiTokens.card,
                title = { Text(CameraPropertyText.name(current)) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(CameraPropertyText.choices(current)) { choice ->
                            Row(Modifier.fillMaxWidth().clip(UiTokens.control).background(if (choice.value == current.value) Brand.copy(alpha = .25f) else Color.Transparent).clickable { runtime.setProperty(current, choice.value); property = null }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(choice.value == current.value, null)
                                Text(choice.label, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                            }
                        }
                        if (CameraPropertyText.choices(current).size < current.choices.size) item { Info("其他机身选项暂未适配，请在相机上调整。") }
                    }
                },
                confirmButton = { TextButton(onClick = { property = null }) { Text("关闭") } },
            )
        }
    }
}

@Composable
private fun LiveShutterDock(glass: HazeState, timer: Int, shooting: Boolean, countdown: Int, enabled: Boolean, latest: Photo?, onTimer: () -> Unit, onShoot: () -> Unit, onRetry: () -> Unit, onPhoto: (Photo) -> Unit, vertical: Boolean = false) {
    GlassSurface(glass, radius = 34.dp, dark = true) {
        val content: @Composable () -> Unit = {
            TextButton(onClick = onTimer, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (timer == 0) "无延时" else "$timer 秒", color = Color.White, fontSize = 11.sp) }
            Box(Modifier.size(58.dp).semantics { contentDescription = "拍摄" }.border(2.dp, Color.White, CircleShape).padding(5.dp).clip(CircleShape).background(if (shooting) Color(0xff9aabb8) else Color.White).clickable(enabled = enabled && (!shooting || countdown > 0), onClick = onShoot), contentAlignment = Alignment.Center) {
                if (countdown > 0) Text(countdown.toString(), color = Brand, fontSize = 24.sp)
                else if (shooting) CircularProgressIndicator(Modifier.size(22.dp), color = Brand, strokeWidth = 2.dp)
            }
            if (latest != null) AsyncImage(model = latest.displayUri, contentDescription = "查看拍后照片", modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onPhoto(latest) })
            else IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, "重新打开取景", tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
        if (vertical) Column(Modifier.padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        else Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

@Composable
private fun LiveSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
