package app.framelink.camera

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoRemovalDialog(
    runtime: CameraRuntime,
    ids: List<String>,
    onClose: () -> Unit,
    onRemoved: (List<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var plan by remember(ids) { mutableStateOf<PhotoRemovalPlan?>(null) }
    var error by remember { mutableStateOf("") }
    var deleteFiles by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    var resume by rememberSaveable { mutableStateOf(false) }
    val approval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        waiting = false
        if (result.resultCode == Activity.RESULT_OK) resume = true
        else error = "系统未授权，删除尚未完成，照片记录已保留。可重试或仅从图库移除。"
    }
    LaunchedEffect(ids) {
        runCatching { withContext(Dispatchers.IO) { runtime.photoRemoval.plan(ids) } }
            .onSuccess { plan = it }
            .onFailure { error = it.message ?: "无法读取照片，请关闭后重试" }
    }
    fun execute() {
        if (busy || waiting) return
        val confirmed = plan ?: return
        busy = true
        error = ""
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runtime.removePhotos(confirmed, deleteFiles) }
                onRemoved(result.removedIds)
                if (result.failures.isEmpty()) {
                    runtime.notice.value = "已${if (deleteFiles) "删除本机文件并移除" else "从图库移除"} ${result.removedIds.size} 张照片" + result.warnings.joinToString("；", prefix = if (result.warnings.isEmpty()) "" else "；")
                    onClose()
                } else {
                    error = "已完成 ${result.removedIds.size} 张，其余未完成：\n" + result.failures.joinToString("\n")
                    plan = withContext(Dispatchers.IO) { runtime.photoRemoval.plan(ids - result.removedIds.toSet()) }
                }
            } catch (permission: PhotoRemovalPermission) {
                waiting = true
                runCatching { approval.launch(IntentSenderRequest.Builder(permission.request).build()) }
                    .onFailure { waiting = false; error = "无法打开系统授权，请重试或仅从图库移除" }
            } catch (failure: Exception) {
                error = failure.message ?: "未完成删除，照片记录已保留"
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(resume, plan) {
        if (resume && plan != null) {
            resume = false
            execute()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy && !waiting) onClose() },
        shape = UiTokens.card,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        title = { Text("移除${plan?.photos?.size ?: ids.size}张照片") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (plan == null && error.isBlank()) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                listOf(false to "仅从图库移除", true to "删除本机文件并移除").forEach { (removeFiles, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy && !waiting && plan != null) { deleteFiles = removeFiles },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = deleteFiles == removeFiles, onClick = null)
                        Text(label, Modifier.padding(start = 10.dp), fontSize = 14.sp)
                    }
                }
                Text(
                    if (deleteFiles) "删除App保存的原片、预览及生成的相册/导出副本，无法撤销。最初导入的来源文件、相机存储卡和云端照片保留。"
                    else "本机文件保留，照片从本App图库隐藏；再次导入或从相机下载可恢复显示。",
                    fontSize = 13.sp,
                )
                plan?.let { current ->
                    Text("包含RAW与JPEG时，按整组处理。", fontSize = 12.sp, color = Muted)
                    if (current.reviewCount > 0) Text("涉及 ${current.reviewCount} 个现场选片记录。已保存的选择和留言保留；删除文件后，本机可能无法继续查看这些照片。", fontSize = 12.sp, color = Muted)
                    if (current.retainedShared > 0) Text("${current.retainedShared} 个文件仍被其他照片记录引用，将保留这些共享文件。", fontSize = 12.sp, color = Muted)
                }
                if (busy || waiting) Text(if (waiting) "等待系统授权…" else "正在处理照片…", fontSize = 13.sp, color = Brand)
                if (error.isNotBlank()) Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = ::execute,
                enabled = plan != null && !busy && !waiting,
                shape = UiTokens.control,
                colors = ButtonDefaults.buttonColors(containerColor = if (deleteFiles) MaterialTheme.colorScheme.error else Brand),
            ) {
                Text(if (deleteFiles) "确认删除文件" else "确认移除")
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy && !waiting) { Text("取消") } },
    )
}
