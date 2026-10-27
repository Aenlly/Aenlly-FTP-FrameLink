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
) {
    var cloud by rememberSaveable { mutableStateOf(false) }
    val reviews by runtime.database.reviews().observe().collectAsStateWithLifecycle(emptyList())
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
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("PROJECTS & SELECTIONS", fontSize = 9.sp, color = Muted, letterSpacing = 1.6.sp)
            Text(
                "把选择，留在一起。",
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp),
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
        item { SectionTitle("本地现场选片") }
        if (reviews.isEmpty()) item { Info("在图库选择照片，点击人物图标进入现场客户模式。无需账号，记录与摄影师标记分开保存。") }
        items(reviews, key = { it.id }) { review ->
            Panel(Modifier.clickable { onReview(review.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PeopleOutline, null, tint = Brand)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(review.title, fontSize = 14.sp)
                        Info(
                            "${review.photoIds().size} 张照片 · ${review.choices().values.count{it.selected}} 张客户已选"
                        )
                    }
                    Text(if (review.locked) "已完成" else "选片中", fontSize = 10.sp, color = Muted)
                }
            }
        }
    }
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
    val ids = remember(review?.photoIdsJson) { review?.photoIds()?.toSet().orEmpty() }
    val visible = remember(photos, ids) { photos.filter { it.id in ids } }
    val choices = remember(review?.choicesJson) { review?.choices().orEmpty() }
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
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
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
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "退出现场选片") }
            }
            if (error.isNotBlank())
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
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
                                modifier =
                                    Modifier.align(Alignment.TopEnd).semantics {
                                        contentDescription = "客户选择 ${photo.name}"
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
                        TextButton(onClick = { scope.launch { runtime.reviews.lock(id, false) } }) {
                            Text("摄影师开放修改", fontSize = 10.sp)
                        }
                    else Info("选择只记录在本场次，不改变摄影师自己的收藏和标记。")
                }
            }
        }
    }
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
            Dialog(
                onDismissRequest = { viewing = null },
                properties =
                    DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
            ) {
                DarkDialogBars()
                Column(
                    Modifier.fillMaxSize()
                        .background(Color(0xff111318))
                        .padding(dialogSafePadding())
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
                            ),
                    )
                    Button(
                        onClick = { choose(photo, selected = !choice.selected) },
                        enabled = review?.locked == false,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(18.dp))
                        Text(if (choice.selected) "客户已选中" else "选择这张")
                    }
                }
            }
        }
}
