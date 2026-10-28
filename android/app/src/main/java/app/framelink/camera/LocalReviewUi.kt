@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.framelink.camera

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectHub(
    runtime: CameraRuntime,
    projectId: String?,
    onProject: (String) -> Unit,
    onBack: () -> Unit,
    onCloud: () -> Unit,
    onReview: (String) -> Unit,
    photos: List<Photo>,
    onBatch: (String) -> Unit,
) {
    var cloud by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<LocalReview?>(null) }
    var renaming by remember { mutableStateOf<LocalReview?>(null) }
    var deleting by remember { mutableStateOf<LocalReview?>(null) }
    val scope = rememberCoroutineScope()
    val reviews by runtime.database.reviews().observe().collectAsStateWithLifecycle(emptyList())
    val batches by runtime.database.batches().observe().collectAsStateWithLifecycle(emptyList())
    val photoMap = remember(photos) { photos.associateBy { it.id } }
    if (cloud || projectId != null) {
        Column(Modifier.fillMaxSize()) {
            if (projectId == null)
                TextButton(onClick = { cloud = false }) {
                    Icon(Icons.Outlined.ArrowBack, null, Modifier.size(16.dp))
                    Text("本地选片", fontSize = 11.sp)
                }
            Box(Modifier.weight(1f)) {
                ProjectsScreen(runtime, projectId, onProject, onCloud, onBack)
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(UiTokens.page),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "客户选片与交付",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudQueue, null, tint = Brand)
                    Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                        Text("云端客户选片", fontSize = 14.sp)
                        Info("客户免安装，远程挑选与接收成片")
                    }
                    TextButton(
                        onClick = { if (runtime.cloud.signedIn) cloud = true else onCloud() }
                    ) {
                        Text(if (runtime.cloud.signedIn) "云端项目" else "启用云端选片", fontSize = 10.sp)
                    }
                }
            }
        }
        item { SectionTitle("我的选片") }
        if (batches.isEmpty()) item { Info("在图库勾选照片后点击“保存”，为这一批选片命名。无需登录，可回看并分享图文选片单。") }
        items(batches, key = { "batch:${it.id}" }) { batch ->
            val ids = remember(batch.photoIdsJson) { batch.photoIds() }
            Panel(Modifier.clickable { onBatch(batch.id) }) {
                Text(batch.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Info("${ids.size} 张已选 · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(batch.createdAt)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    remember(batch.photosJson) { batch.snapshots().take(3) }.mapNotNull { saved -> photoMap[saved.id]?.takeIf { it.name == saved.name && it.capturedAt == saved.capturedAt } }.forEach { photo ->
                        PhotoThumbnail(runtime, photo, Modifier.weight(1f).aspectRatio(1.35f).clip(UiTokens.photo))
                    }
                }
            }
        }
        item { SectionTitle("本地现场选片") }
        if (reviews.isEmpty()) item { Info("在图库选择照片，点击人物图标进入现场客户模式。无需账号，记录与摄影师标记分开保存。") }
        items(reviews, key = { it.id }) { review ->
            Panel(Modifier.clickable { onReview(review.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PeopleOutline, null, tint = Brand)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(review.title, fontSize = 14.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Info(
                            "${review.photoIds().size} 张照片 · ${review.choices().values.count{it.selected}} 张客户已选"
                        )
                    }
                    Text(if (review.locked) "已完成" else "选片中", fontSize = 10.sp, color = Muted)
                    Box {
                        IconButton(onClick = { menu = review }) { Icon(Icons.Outlined.MoreHoriz, "管理现场选片 ${review.title}") }
                        DropdownMenu(menu?.id == review.id, { menu = null }) {
                            DropdownMenuItem(text = { Text("修改姓名或场次名称") }, onClick = { menu = null; renaming = review })
                            DropdownMenuItem(text = { Text("删除选片记录") }, onClick = { menu = null; deleting = review })
                        }
                    }
                }
            }
        }
    }
    renaming?.let { review -> ReviewNameDialog(review.title, false, { renaming = null }) { name -> runtime.reviews.rename(review.id, name); renaming = null } }
    deleting?.let { review -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("删除这次客户选片？") },
        text = { Text("删除“${review.title}”的选择和留言记录，照片原文件、其他场次和云端项目保留。") },
        confirmButton = { TextButton(onClick = { scope.launch { runtime.reviews.delete(review.id); deleting = null } }) { Text("删除记录") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
    ) }
}

@Composable
internal fun LocalReviewScreen(
    runtime: CameraRuntime,
    id: String,
    photos: List<Photo>,
    onClose: () -> Unit,
) {
    val review by
        remember(id) { runtime.database.reviews().observe(id) }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var exportingSheet by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val ids = remember(review?.photoIdsJson) { review?.photoIds()?.toSet().orEmpty() }
    val choices = remember(review?.choicesJson) { review?.choices().orEmpty() }
    val visible = remember(photos, ids, choices, selectedOnly) { photos.filter { it.id in ids && (!selectedOnly || choices[it.id]?.selected == true) } }
    fun choose(photo: Photo, selected: Boolean? = null, comment: String? = null) {
        runtime.scope.launch {
            saving = true
            try {
                runtime.reviews.choose(id, photo.id, selected, comment)
            } catch (failure: Exception) {
                error = failure.message.orEmpty()
            } finally {
                saving = false
            }
        }
    }
    FullScreenScene(onClose) {
        Column(Modifier.fillMaxSize().background(CanvasColor).padding(dialogSafePadding())) {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        review?.title ?: "现场客户选片",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Info("客户模式 · 无需登录")
                }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, "修改现场选片名称") }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "退出现场选片") }
            }
            if (error.isNotBlank())
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!selectedOnly, { selectedOnly = false }, { Text("全部 ${ids.size}") })
                FilterChip(selectedOnly, { selectedOnly = true }, { Text("客户已选 ${choices.values.count { it.selected }}") })
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(145.dp),
                modifier = Modifier.weight(1f).testTag("review-grid"),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(visible, key = { it.id }) { photo ->
                    val choice = choices[photo.id] ?: LocalChoice()
                    Column {
                        Box(
                            Modifier.clip(RoundedCornerShape(15.dp)).clickable {
                                viewing = photo.id
                            }
                        ) {
                            PhotoThumbnail(runtime, photo, Modifier.fillMaxWidth().aspectRatio(1f))
                            Checkbox(
                                choice.selected,
                                { choose(photo, selected = it) },
                                enabled = review?.locked == false,
                                colors = CheckboxDefaults.colors(checkedColor = Brand, uncheckedColor = Color.White, checkmarkColor = Color.White),
                                modifier =
                                    Modifier.align(Alignment.TopEnd).semantics {
                                        contentDescription = "客户选择 ${photo.name}"
                                    }.drawBehind {
                                        val edge = 22.dp.toPx()
                                        drawRoundRect(Color.Black.copy(alpha = .35f),
                                            topLeft = androidx.compose.ui.geometry.Offset(center.x - edge / 2, center.y - edge / 2),
                                            size = androidx.compose.ui.geometry.Size(edge, edge),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                                    },
                            )
                        }
                        Text(
                            photo.name,
                            fontSize = 10.sp,
                            color = Muted,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                        if (choice.comment.isNotBlank()) Info(choice.comment)
                    }
                }
            }
            Surface(color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${choices.values.count{it.selected}} 张客户已选",
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (review?.locked == false)
                            Primary(if (saving) "保存中" else "完成选择", !saving) {
                                scope.launch { runtime.reviews.lock(id, true) }
                            }
                        else
                            Primary("导出清单", icon = Icons.Outlined.IosShare) {
                                scope.launch {
                                    try {
                                        val file = runtime.reviews.export(id)
                                        val uri =
                                            FileProvider.getUriForFile(
                                                context,
                                                context.packageName + ".files",
                                                file,
                                            )
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND)
                                                    .setType("text/csv")
                                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                                    .addFlags(
                                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                    ),
                                                "导出客户选片清单",
                                            )
                                        )
                                    } catch (failure: Exception) {
                                        error = failure.message.orEmpty()
                                    }
                                }
                            }
                    }
                    if (review?.locked == true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { scope.launch { runtime.reviews.lock(id, false) } }) { Text("摄影师开放修改", fontSize = 11.sp) }
                            TextButton(onClick = {
                                val record = review ?: return@TextButton
                                val selected = record.photoIds().filter { choices[it]?.selected == true }
                                if (selected.isEmpty()) { error = "客户还没有选择照片"; return@TextButton }
                                exportingSheet = true
                                scope.launch {
                                    val byId = photos.associateBy { it.id }
                                    runCatching {
                                        runtime.batches.contactSheets(SelectionBatch(record.id, record.title, Json.encode(selected), Json.encode(selected.mapNotNull { byId[it]?.copy() }), record.createdAt, record.updatedAt))
                                    }.onSuccess { shareContactSheets(context, it) }.onFailure { error = it.message ?: "选片单生成失败" }
                                    exportingSheet = false
                                }
                            }, enabled = !exportingSheet) { Text(if (exportingSheet) "生成中…" else "图文选片单", fontSize = 11.sp) }
                        }
                    else Info("选择只记录在本场次，不改变摄影师自己的收藏和标记。")
                }
            }
        }
    }
    if (renaming) ReviewNameDialog(review?.title.orEmpty(), false, { renaming = false }) { name -> runtime.reviews.rename(id, name); renaming = false }
    visible
        .find { it.id == viewing }
        ?.let { photo ->
            val choice = choices[photo.id] ?: LocalChoice()
            var model by remember(photo.id) { mutableStateOf(photo.displayUri) }
            var commentDraft by remember(photo.id) { mutableStateOf(choice.comment) }
            LaunchedEffect(photo.id) {
                withContext(Dispatchers.IO) {
                    runCatching { runtime.ensurePreview(photo, true) }.onSuccess { model = it }
                }
            }
            FullScreenScene({ viewing = null }) {
                DarkDialogBars()
                Column(
                    Modifier.fillMaxSize()
                        .background(Color(0xff111318))
                        .padding(dialogSafePadding())
                        .imePadding()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            photo.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewing = null }) {
                            Icon(Icons.Outlined.Close, "关闭客户预览", tint = Color.White)
                        }
                    }
                    AsyncImage(
                        model = model,
                        contentDescription = photo.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    OutlinedTextField(
                        commentDraft,
                        {
                            if (it.length <= 2000) {
                                commentDraft = it
                                choose(photo, comment = it)
                            }
                        },
                        enabled = review?.locked == false,
                        label = { Text("给摄影师的留言") },
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                disabledTextColor = Color.White.copy(alpha = .7f),
                                focusedLabelColor = Color(0xffa5c5e4),
                                unfocusedLabelColor = Color(0xffa4afbe),
                            ),
                    )
                    Button(
                        onClick = { choose(photo, selected = !choice.selected) },
                        enabled = review?.locked == false,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(18.dp))
                        Text(if (choice.selected) "客户已选中" else "客户选择这张")
                    }
                }
            }
        }
}
