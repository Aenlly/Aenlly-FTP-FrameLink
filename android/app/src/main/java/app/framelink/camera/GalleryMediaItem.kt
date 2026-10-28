package app.framelink.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun GalleryMediaItem(runtime: CameraRuntime, group: List<Photo>, selected: Set<String>, list: Boolean, onOpen: () -> Unit, onSelection: (List<String>) -> Unit) {
    val photo=group.first()
    val complete=group.all{it.id in selected}
    val partial=!complete && group.any{it.id in selected}
    val shape=RoundedCornerShape(if(list) 16.dp else 10.dp)
    val base=Modifier.testTag("photo-${photo.id}").clickable(onClick=onOpen).semantics{contentDescription="查看 ${photo.name}"}
    @Composable fun Select(modifier:Modifier=Modifier) {
        TriStateCheckbox(state=if(complete) ToggleableState.On else if(partial) ToggleableState.Indeterminate else ToggleableState.Off,
            onClick={val ids=group.map{it.id}.toSet();onSelection((if(complete) selected-ids else selected+ids).toList())},
            colors=CheckboxDefaults.colors(checkedColor=Brand,uncheckedColor=if(list) Muted else Color.White,checkmarkColor=Color.White),
            modifier=modifier.semantics{contentDescription="选择 ${photo.name}"})
    }
    @Composable fun Preview(modifier:Modifier) {
        Box(modifier.clip(shape).then(if(complete||partial) Modifier.border(2.dp,Brand,shape) else Modifier)) {
            PhotoThumbnail(runtime,photo,Modifier.fillMaxSize())
            if(complete||partial) Box(Modifier.matchParentSize().background(Brand.copy(alpha=.12f)))
            if(photo.mark==PhotoMark.FAVORITE.name) Icon(Icons.Outlined.Favorite,"已收藏",tint=Color.White,modifier=Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha=.4f),RoundedCornerShape(7.dp)).padding(4.dp).size(15.dp))
            Surface(Modifier.align(Alignment.BottomStart).padding(5.dp),color=Color.Black.copy(alpha=.52f),shape=RoundedCornerShape(5.dp)) {
                Text(if(group.size>1) "${photo.formatTitle()} · 连拍 ${group.size}" else photo.formatTitle(),color=Color.White,fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=5.dp,vertical=3.dp))
            }
            if(!list) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).background(Color.Black.copy(alpha=.24f),RoundedCornerShape(8.dp))) {Select()}
        }
    }
    if(list) {
        Row(base.fillMaxWidth().background(Color.White,shape).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Preview(Modifier.size(84.dp))
            Column(Modifier.weight(1f).padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(photo.displayTitle(),fontSize=14.sp,color=Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(photo.formatTitle()+" · "+android.text.format.Formatter.formatShortFileSize(LocalContext.current,photo.files().sumOf{it.size}),fontSize=11.sp,color=Brand)
                Text(photo.importTitle(),fontSize=11.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(photo.files().joinToString(" / "){it.name},fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Select()
        }
    } else {
        Column(base,verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Preview(Modifier.fillMaxWidth().aspectRatio(1f))
            Text(photo.displayTitle(),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp,color=Ink,modifier=Modifier.padding(horizontal=3.dp))
            Text(photo.importTitle(),fontSize=9.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=3.dp))
        }
    }
}

@Composable
internal fun PhotoFilesDialog(photo:Photo,onClose:()->Unit) {
    AlertDialog(onDismissRequest=onClose,title={Text("原始文件")},text={
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("${photo.formatTitle()} · 同一次拍摄的文件分别保存",fontSize=12.sp)
            photo.files().forEach { file -> Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(file.name,fontSize=14.sp)
                Text(file.formatTitle()+" · "+android.text.format.Formatter.formatShortFileSize(LocalContext.current,file.size)+" · "+if(file.savedUri.isNotBlank()) "已导入" else "未导入",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    },confirmButton={TextButton(onClick=onClose){Text("完成")}})
}
