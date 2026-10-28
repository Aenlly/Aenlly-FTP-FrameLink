@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.framelink.camera

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun FtpClientScreen(runtime: CameraRuntime, onClose: () -> Unit, onTasks: () -> Unit) {
    val state by runtime.ftpClient.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(!state.connected) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var search by rememberSaveable { mutableStateOf("") }
    var folderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var saveOptions by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val upload =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            if (uris.isNotEmpty()) {
                runtime.ftpClient.enqueueUploads(uris)
                onTasks()
            }
        }
    LaunchedEffect(state.path) {
        selected = emptySet()
        search = ""
    }
    LaunchedEffect(state.connected) { if (state.connected) editing = false }
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = CanvasColor) {
            Column(Modifier.fillMaxSize().padding(dialogSafePadding()).imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, "关闭FTP客户端") }
                    Column(Modifier.weight(1f)) {
                        Text("FTP 客户端", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (state.connected) state.profile?.label.orEmpty()
                            else "连接服务器，浏览并传输文件",
                            color = Muted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onTasks) { Text("任务") }
                    if (state.connected)
                        IconButton(onClick = { editing = !editing }) {
                            Icon(Icons.Outlined.Tune, "编辑FTP连接")
                        }
                }
                if (editing) {
                    FtpConnectionForm(
                        runtime,
                        state,
                        onConnected = { editing = false },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            enabled = state.path != "/" && !state.busy,
                            onClick = { runtime.ftpClient.browse(FtpPaths.parent(state.path)) },
                        ) {
                            Icon(Icons.Outlined.ArrowUpward, "上一级目录")
                        }
                        Text(
                            state.path,
                            Modifier.weight(1f).testTag("ftp-current-path"),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            enabled = !state.busy,
                            onClick = { runtime.ftpClient.browse(state.path) },
                        ) {
                            Icon(Icons.Outlined.Refresh, "刷新FTP目录")
                        }
                        IconButton(enabled = !state.busy, onClick = { folderDialog = true }) {
                            Icon(Icons.Outlined.CreateNewFolder, "新建远端文件夹")
                        }
                    }
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            enabled = !state.busy,
                            onClick = { upload.launch(arrayOf("*/*")) },
                        ) {
                            Icon(Icons.Outlined.Upload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("上传文件")
                        }
                        TextButton(onClick = { saveOptions = !saveOptions }) { Text("保存设置") }
                        TextButton(
                            onClick = {
                                runtime.ftpClient.disconnect()
                                editing = true
                            }
                        ) {
                            Text("断开")
                        }
                    }
                    if (saveOptions)
                        Column(
                            Modifier.heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp)
                        ) {
                            FtpSaveOptions(runtime)
                        }
                    OutlinedTextField(
                        search,
                        { search = it },
                        label = { Text("搜索当前目录") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(18.dp),
                    )
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.error.isNotBlank())
                        Text(
                            state.error,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    val entries =
                        state.entries.filter { it.name.contains(search, ignoreCase = true) }
                    LazyColumn(
                        Modifier.weight(1f).testTag("ftp-file-list"),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (entries.isEmpty() && !state.busy)
                            item {
                                Info(if (search.isBlank()) "此目录为空，可上传文件或新建文件夹。" else "当前目录没有匹配文件。")
                            }
                        items(entries, key = { it.path }) { entry ->
                            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = !state.busy) {
                                            if (entry.directory)
                                                runtime.ftpClient.browse(entry.path)
                                            else
                                                selected =
                                                    if (entry.path in selected)
                                                        selected - entry.path
                                                    else selected + entry.path
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (entry.directory) Icons.Outlined.FolderOpen
                                        else if (CameraFile(name = entry.name).image)
                                            Icons.Outlined.Image
                                        else Icons.Outlined.InsertDriveFile,
                                        null,
                                        tint = Brand,
                                    )
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(
                                            entry.name,
                                            fontSize = 13.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (entry.directory) "文件夹" else ftpBytes(entry.size),
                                            fontSize = 10.sp,
                                            color = Muted,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                    if (entry.directory)
                                        Icon(
                                            Icons.Outlined.ChevronRight,
                                            "打开 ${entry.name}",
                                            tint = Muted,
                                        )
                                    else
                                        Checkbox(
                                            entry.path in selected,
                                            onCheckedChange = {
                                                selected =
                                                    if (it) selected + entry.path
                                                    else selected - entry.path
                                            },
                                        )
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                val files = entries.filter { !it.directory }.map { it.path }.toSet()
                                selected =
                                    if (files.isNotEmpty() && selected.containsAll(files))
                                        selected - files
                                    else selected + files
                            }
                        ) {
                            Text("全选")
                        }
                        Spacer(Modifier.weight(1f))
                        Primary(
                            "下载 ${selected.size} 个文件",
                            selected.isNotEmpty() && !state.busy,
                            Icons.Outlined.Download,
                        ) {
                            runtime.ftpClient.enqueueDownloads(
                                state.entries.filter { it.path in selected }
                            )
                            selected = emptySet()
                            onTasks()
                        }
                    }
                }
            }
        }
    }
    if (folderDialog)
        AlertDialog(
            onDismissRequest = { folderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    folderName,
                    { folderName = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runtime.ftpClient.mkdir(folderName)
                        folderName = ""
                        folderDialog = false
                    },
                    enabled = folderName.isNotBlank(),
                ) {
                    Text("创建")
                }
            },
            dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("取消") } },
        )
}

@Composable
private fun FtpConnectionForm(
    runtime: CameraRuntime,
    state: FtpBrowserState,
    onConnected: () -> Unit,
    modifier: Modifier,
) {
    val saved = remember { mutableStateOf(runtime.ftpClient.profiles()) }
    val initial = state.profile ?: saved.value.firstOrNull() ?: FtpProfile()
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var tls by remember { mutableStateOf(initial.tls) }
    var encoding by remember { mutableStateOf(initial.encoding) }
    var directory by remember { mutableStateOf(initial.directory) }
    var error by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (saved.value.isNotEmpty()) {
            TextButton(onClick = { showSaved = !showSaved }) {
                Text(if (showSaved) "收起最近连接" else "最近连接（${saved.value.size}）")
            }
            if (showSaved)
                saved.value.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                host = profile.host
                                port = profile.port.toString()
                                user = profile.username
                                password = profile.password
                                tls = profile.tls
                                encoding = profile.encoding
                                directory = profile.directory
                            }
                        ) {
                            Text(
                                "${profile.label} · ${profile.username}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, false),
                            )
                        }
                        IconButton(
                            onClick = {
                                runtime.ftpClient.forget(profile.id)
                                saved.value = runtime.ftpClient.profiles()
                            }
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                "移除 ${profile.host} 保存的账号",
                                Modifier.size(16.dp),
                            )
                        }
                    }
                }
        }
        OutlinedTextField(
            host,
            { host = it },
            label = { Text("服务器地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                port,
                { port = it },
                label = { Text("端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Switch(tls, { tls = it })
                Text("FTPS", fontSize = 12.sp)
            }
        }
        OutlinedTextField(
            user,
            { user = it },
            label = { Text("登录账号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("登录密码") },
            singleLine = true,
            visualTransformation =
                if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                        "显示或隐藏密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            directory,
            { directory = it },
            label = { Text("起始目录") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("文件名编码", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("UTF-8", "GBK", "ISO-8859-1").forEach { value ->
                FilterChip(
                    encoding == value,
                    { encoding = value },
                    { Text(value, fontSize = 10.sp) },
                )
            }
        }
        Info(
            if (tls) "使用显式FTPS，验证服务器证书。账号加密保存在本机。"
            else "普通FTP不加密，请在可信网络使用；服务器支持时可开启FTPS。匿名登录请填写anonymous。"
        )
        if ((error + state.error).isNotBlank())
            Text(
                error.ifBlank { state.error },
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        Primary(if (state.busy) "正在连接服务器" else "连接并浏览文件", !state.busy, Icons.Outlined.Link) {
            runCatching {
                    val input =
                        FtpProfile(
                            host.trim(),
                            port.toIntOrNull() ?: 0,
                            user.trim(),
                            password,
                            tls,
                            encoding,
                            directory,
                        )
                    input.validate()
                    runtime.ftpClient.connect(input)
                }
                .onFailure { error = it.message.orEmpty() }
        }
        if (state.connected) TextButton(onClick = onConnected) { Text("返回文件列表") }
    }
}

@Composable
internal fun FtpReceiverOptions(runtime: CameraRuntime, onClose: () -> Unit, onPhotos: () -> Unit = {}) {
    val context = LocalContext.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val status by runtime.ftpStatus.collectAsStateWithLifecycle()
    var addresses by remember { mutableStateOf(FtpReceiver.addresses()) }
    var address by rememberSaveable {
        mutableStateOf(
            runtime.settings.get("ftp.address").takeIf { it in addresses }
                ?: addresses.firstOrNull().orEmpty()
        )
    }
    var user by rememberSaveable {
        mutableStateOf(runtime.settings.get("ftp.user").ifBlank { "camera" })
    }
    var password by remember {
        mutableStateOf(
            runtime.settings.get("ftp.password").ifBlank {
                java.util.UUID.randomUUID().toString().take(12)
            }
        )
    }
    var port by rememberSaveable {
        mutableStateOf(runtime.settings.get("ftp.port").ifBlank { "2121" })
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val running by runtime.ftpRunning.collectAsStateWithLifecycle()
    ModalSheet("FTP 接收 · 边拍边传", onClose, footer = {
        if (!running) Primary("启动照片接收", address.isNotBlank() && user.isNotBlank() && password.length >= 8 && (port.toIntOrNull() ?: 0) in 1024..65535, Icons.Outlined.Wifi) {
            runtime.settings.put("ftp.address", address)
            runtime.settings.put("ftp.user", user)
            runtime.settings.put("ftp.password", password)
            runtime.settings.put("ftp.port", port)
            runtime.startFtp(address, port.toInt(), user, password)
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { runtime.stopFtp() }) { Text("停止接收") }
            Button(onClick = onPhotos, modifier = Modifier.weight(1f)) { Text("查看已接收照片") }
        }
    }) {
        Text(if (running) "接收服务在线" else "让相机把照片传到手机", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(status, color = Brand, fontSize = 12.sp)
        Info("同一Wi-Fi或手机热点 → 启动接收 → 在相机中填写下方配置。下次会保留账号和端口。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = address.isNotBlank(), onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("相机FTP配置", "服务器：$address\n端口：$port\n账号：$user\n远程目录：/\n传输模式：被动（PASV）"))
                copied = true
            }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Text(if (copied) "已复制连接信息" else "复制连接信息", fontSize = 12.sp) }
            TextButton(onClick = {
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("FTP接收密码", password))
            }) { Text("复制密码", fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("手机接收地址", Modifier.weight(1f), fontSize = 13.sp)
            TextButton(
                enabled = !running,
                onClick = {
                    addresses = FtpReceiver.addresses()
                    if (address !in addresses) address = addresses.firstOrNull().orEmpty()
                },
            ) {
                Text("刷新地址")
            }
        }
        addresses.forEach { ip ->
            FilterChip(address == ip, { address = ip }, { Text(ip) }, enabled = !running)
        }
        if (addresses.isEmpty()) Info("未找到局域网地址，请先连接Wi-Fi或打开热点，再刷新地址。")
        OutlinedTextField(
            user,
            { user = it },
            label = { Text("接收账号") },
            enabled = !running,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("接收密码") },
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示或隐藏接收密码")
            } },
            enabled = !running,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            port,
            { port = it },
            label = { Text("接收端口") },
            enabled = !running,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Info("在相机FTP设置中填写所选手机地址、端口、账号与密码；使用被动模式和UTF-8文件名。")
        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "收起保存与压缩设置" else "保存位置与压缩副本")
        }
        if (advanced) FtpSaveOptions(runtime)
    }
}

@Composable
private fun FtpSaveOptions(runtime: CameraRuntime) {
    var compress by remember { mutableStateOf(runtime.settings.get("ftp.compress") == "true") }
    var edge by remember {
        mutableStateOf(runtime.settings.get("ftp.compress.edge").ifBlank { "2560" })
    }
    var quality by remember {
        mutableStateOf(runtime.settings.get("ftp.compress.quality").ifBlank { "85" })
    }
    var formats by remember {
        mutableStateOf(
            runtime.settings.get("ftp.compress.formats").ifBlank { "JPG,JPEG,PNG,HIF,HEIF,HEIC" }
        )
    }
    var album by remember { mutableStateOf(runtime.settings.get("ftp.compress.album") != "false") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("保存与压缩", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        FtpDirectoryPicker(runtime, "ftp.save.tree", "原文件额外保存到", "App内原文件，照片同步系统相册")
        Info("FTP接收和客户端下载共用此设置。相册不可识别的文件保留在App，可从传输页面打开、分享或另存。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("另存压缩副本", fontSize = 13.sp)
                Info("保留原文件，额外生成JPEG分享图")
            }
            Switch(
                compress,
                {
                    compress = it
                    runtime.settings.put("ftp.compress", it.toString())
                },
            )
        }
        if (compress) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1280", "2560").forEach { value ->
                    FilterChip(
                        edge == value,
                        {
                            edge = value
                            runtime.settings.put("ftp.compress.edge", value)
                        },
                        { Text("最长边 ≤ $value", fontSize = 10.sp) },
                    )
                }
            }
            Text("JPEG质量 $quality", fontSize = 12.sp)
            Slider(
                value = quality.toFloatOrNull() ?: 85f,
                onValueChange = { quality = it.toInt().toString() },
                onValueChangeFinished = { runtime.settings.put("ftp.compress.quality", quality) },
                valueRange = 50f..100f,
            )
            OutlinedTextField(
                formats,
                {
                    formats = it
                    runtime.settings.put("ftp.compress.formats", it)
                },
                label = { Text("生成副本的格式，逗号分隔") },
                modifier = Modifier.fillMaxWidth(),
            )
            FtpDirectoryPicker(runtime, "ftp.compress.tree", "压缩副本保存到", "系统相册 / FrameLink / 分享副本")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("压缩副本同步到系统相册", Modifier.weight(1f), fontSize = 12.sp)
                Switch(
                    album,
                    {
                        album = it
                        runtime.settings.put("ftp.compress.album", it.toString())
                    },
                )
            }
            Info("支持手机可解码的照片格式；RAW原文件保留。生成失败会保留原片，并在任务中提示重试。")
        }
    }
}

@Composable
private fun FtpDirectoryPicker(
    runtime: CameraRuntime,
    key: String,
    title: String,
    fallback: String,
) {
    var value by remember { mutableStateOf(runtime.settings.get(key)) }
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null)
                runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        value = uri.toString()
                        runtime.settings.put(key, value)
                    }
                    .onFailure { runtime.notice.value = "目录未授权，请重新选择可写目录" }
        }
    Text(title, fontSize = 12.sp)
    Text(
        if (value.isBlank()) fallback else Uri.decode(value.substringAfterLast('/')),
        fontSize = 10.sp,
        color = Muted,
    )
    Row {
        TextButton(onClick = { picker.launch(value.takeIf { it.isNotBlank() }?.let(Uri::parse)) }) {
            Text("选择文件夹")
        }
        if (value.isNotBlank())
            TextButton(
                onClick = {
                    value = ""
                    runtime.settings.remove(key)
                }
            ) {
                Text("恢复默认")
            }
    }
}

internal fun ftpBytes(value: Long): String =
    when {
        value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value.toDouble() / (1024L * 1024 * 1024))
        value >= 1024 * 1024 -> "%.1f MB".format(value.toDouble() / (1024 * 1024))
        value >= 1024 -> "${value / 1024} KB"
        else -> "$value B"
    }
