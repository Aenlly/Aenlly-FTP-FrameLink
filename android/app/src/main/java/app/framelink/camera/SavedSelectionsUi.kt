package app.framelink.camera

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun SaveSelectionDialog(runtime: CameraRuntime, ids: List<String>, onClose: () -> Unit, onSaved: (SelectionBatch) -> Unit) {
    var title by remember { mutableStateOf("选片 · " + SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() }, shape = UiTokens.card, containerColor = Color.White,
        title = { Text("保存这组选片") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${ids.size} 张照片，保存后可在项目中回看、恢复选择或分享图文选片单。", fontSize = 13.sp)
            OutlinedTextField(title, { title = it }, label = { Text("选片名称") }, singleLine = true, enabled = !saving)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } },
        confirmButton = { Button(onClick = {
            saving = true
            scope.launch { runCatching { runtime.batches.save(title, ids) }.onSuccess(onSaved).onFailure { error = it.message.orEmpty(); saving = false } }
        }, enabled = !saving && title.isNotBlank(), shape = UiTokens.control) { Text(if (saving) "正在保存…" else "保存选片") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text("取消") } },
    )
}

@Composable
internal fun SavedSelectionScreen(runtime: CameraRuntime, id: String, photos: List<Photo>, onClose: () -> Unit, onPhoto: (Photo) -> Unit, onUse: (List<String>) -> Unit) {
    val batches by runtime.database.batches().observe().collectAsStateWithLifecycle(emptyList())
    val batch = batches.firstOrNull { it.id == id }
    val camera by runtime.camera.collectAsStateWithLifecycle()
    val listed by runtime.currentCameraIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val snapshots = remember(batch?.photosJson) { batch?.snapshots().orEmpty() }
    val current = remember(photos) { photos.associateBy { it.id } }
    fun available(snapshot: Photo): Photo? = current[snapshot.id]?.takeIf { it.name == snapshot.name && it.capturedAt == snapshot.capturedAt }
    val candidates = snapshots.mapNotNull(::available).filter { it.inLibrary || it.imported || camera.info != null && it.id in listed }
    val hasRemote = candidates.any { !it.imported && it.source == PhotoSource.CAMERA.name }
    val eligible = if (hasRemote) candidates.filter { camera.info != null && it.id in listed } else candidates
    FullScreenScene(onClose) {
        Column(Modifier.fillMaxSize().background(CanvasColor).padding(dialogSafePadding())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(batch?.title ?: "选片记录", fontSize = 20.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("已保存 ${snapshots.size} 张 · " + (batch?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(it.createdAt)) } ?: ""), fontSize = 12.sp, color = Muted)
                }
                IconButton(onClick = { title = batch?.title.orEmpty(); rename = true }) { Icon(Icons.Outlined.Edit, "修改选片名称") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.DeleteOutline, "删除选片记录") }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭选片记录") }
            }
            Text("这是一组已保存的选片记录。查看大图时的选择与图库同步，记录内容保持不变。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = Muted)
            if (error.isNotBlank()) Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(snapshots, key = { it.id }) { snapshot ->
                    val photo = available(snapshot)
                    Column {
                        if (photo != null && (photo.imported || photo.previewPath.isNotBlank() || camera.info != null))
                            PhotoThumbnail(runtime, photo, Modifier.fillMaxWidth().aspectRatio(1f).clickable { onPhoto(photo) })
                        else Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xffe9ecf0)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.ImageNotSupported, "预览暂不可用", tint = Muted)
                        }
                        Text(snapshot.name, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
                        Text(if (photo?.imported == true && !photo.localHidden) "已导入手机" else if (camera.info == null) "离线记录 / 缓存预览" else "相机照片", fontSize = 10.sp, color = Muted)
                    }
                }
            }
            Surface(color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (eligible.size < snapshots.size) Text("当前可恢复 ${eligible.size}/${snapshots.size} 张；未导入照片需要连接相机并刷新对应列表。", fontSize = 12.sp, color = Muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = { onUse(eligible.map { it.id }) }, enabled = eligible.isNotEmpty()) { Text("恢复这组选择") }
                        Button(onClick = {
                            val record = batch ?: return@Button
                            exporting = true
                            scope.launch {
                                runCatching { runtime.batches.contactSheets(record) }.onSuccess { shareContactSheets(context, it) }
                                    .onFailure { error = it.message ?: "选片单生成失败" }
                                exporting = false
                            }
                        }, enabled = !exporting && batch != null) { Text(if (exporting) "生成中…" else "图文选片单") }
                    }
                }
            }
        }
    }
    if (rename) AlertDialog(
        onDismissRequest = { rename = false }, title = { Text("选片名称") },
        text = { OutlinedTextField(title, { title = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { runtime.batches.rename(id, title) }.onSuccess { rename = false }.onFailure { error = it.message.orEmpty() } } }) { Text("保存名称") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } },
    )
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, title = { Text("删除这条选片记录？") }, text = { Text("只删除本次记录，照片原文件与客户选片结果保留。") },
        confirmButton = { TextButton(onClick = { scope.launch { runtime.batches.delete(id); deleting = false; onClose() } }) { Text("删除记录") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
    )
}

internal fun shareContactSheets(context: Context, files: List<File>) {
    require(files.isNotEmpty()) { "请先选择照片" }
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) })
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.first()) else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    context.startActivity(Intent.createChooser(intent, "分享图文选片单"))
}
