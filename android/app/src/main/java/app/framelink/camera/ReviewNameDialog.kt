package app.framelink.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 姓名、手机号或自定义备注作为场次名称，不改变该场次的客户选择 */
@Composable
internal fun ReviewNameDialog(initial: String, creating: Boolean, onClose: () -> Unit, save: suspend (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, shape = UiTokens.card, containerColor = Color.White,
        title = { Text(if (creating) "为这次选片命名" else "修改选片名称") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { if (it.length <= 160) name = it }, label = { Text("客户姓名 / 手机号 / 场次名称") }, singleLine = true, enabled = !busy)
            Text("名称只用于记录这是谁、哪次拍摄的选片。", fontSize = 12.sp, color = Muted)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } },
        confirmButton = { Button(onClick = {
            busy = true
            scope.launch { runCatching { save(name) }.onFailure { error = it.message.orEmpty(); busy = false } }
        }, enabled = name.isNotBlank() && !busy) { Text(if (busy) "保存中…" else if (creating) "开始选片" else "保存名称") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } },
    )
}
