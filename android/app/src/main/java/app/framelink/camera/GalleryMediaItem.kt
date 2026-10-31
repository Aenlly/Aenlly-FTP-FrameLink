package app.framelink.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun GalleryMediaItem(runtime: CameraRuntime, group: List<Photo>, selected: Set<String>, list: Boolean, compact: Boolean, selecting:Boolean=true, previewing:Boolean=false, onOpen: () -> Unit, onSelection: (List<String>) -> Unit) {
    val photo=group.first()
    val complete=group.all{it.id in selected}
    val partial=!complete && group.any{it.id in selected}
    val shape=RoundedCornerShape(if(list) 12.dp else 4.dp)
    val base=Modifier.testTag("photo-${photo.id}").clickable(onClick=onOpen).semantics{contentDescription="查看 ${photo.name}";if(previewing)stateDescription="正在预览"}
    @Composable fun Select(modifier:Modifier=Modifier) {
        Box(modifier.size(if(compact&&!list) 32.dp else 40.dp).triStateToggleable(state=if(complete) ToggleableState.On else if(partial) ToggleableState.Indeterminate else ToggleableState.Off,role=androidx.compose.ui.semantics.Role.Checkbox,
            onClick={val ids=group.map{it.id}.toSet();onSelection((if(complete) selected-ids else selected+ids).toList())})
            .semantics{contentDescription="选择 ${photo.name}"},contentAlignment=Alignment.Center) {
            Box(Modifier.size(25.dp).background(if(complete||partial) SelectionAccent else Color.Black.copy(alpha=.18f),CircleShape).border(2.dp,if(list&&!complete&&!partial) Muted else Color.White,CircleShape),contentAlignment=Alignment.Center) {
                if(complete||partial) Icon(if(partial) Icons.Outlined.Remove else Icons.Outlined.Check,null,tint=Color.White,modifier=Modifier.size(18.dp))
            }
        }
    }
    @Composable fun Preview(modifier:Modifier) {
        Box(modifier.clip(shape)) {
            PhotoThumbnail(runtime,photo,Modifier.fillMaxSize())
            if(photo.mark==PhotoMark.FAVORITE.name) Icon(Icons.Outlined.Favorite,"已收藏",tint=Color.White,modifier=Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha=.4f),RoundedCornerShape(7.dp)).padding(4.dp).size(15.dp))
            if(group.size>1||photo.files().any{it.raw})Surface(Modifier.align(Alignment.TopStart).padding(5.dp),color=Color.Black.copy(alpha=.52f),shape=RoundedCornerShape(5.dp)) {
                Text(if(group.size>1) "${photo.formatTitle()} · 连拍 ${group.size}" else photo.formatTitle(),color=Color.White,fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=5.dp,vertical=3.dp))
            }
            if(!list&&(selecting||complete||partial)) Select(Modifier.align(Alignment.BottomEnd))
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
            if(selecting||complete||partial)Select()
        }
    } else {
        Box(base.background(if(selecting&&complete)SelectionAccent.copy(alpha=.15f)else Color.Transparent,shape).then(if(previewing&&!selecting)Modifier.border(2.dp,SelectionAccent,shape)else Modifier)) {
            Preview(Modifier.fillMaxWidth().aspectRatio(1f).padding(if(selecting&&complete)3.dp else 0.dp))
        }
    }
}

private data class PhotoExifReadState(val information:PhotoExif?=null,val loading:Boolean=true)

@Composable
internal fun PhotoFilesDialog(runtime:CameraRuntime,photo:Photo,onPreview:(String)->Unit={},onClose:()->Unit) {
    val context=LocalContext.current
    val camera by runtime.camera.collectAsState()
    val cameraIds by runtime.currentCameraIds.collectAsState()
    val scope=rememberCoroutineScope()
    val cached=runtime.cachedOriginalPreview(photo)
    var fetching by remember(photo.selectionResourceKey()){mutableStateOf(false)}
    var failure by remember(photo.selectionResourceKey()){mutableStateOf("")}
    var revision by remember(photo.selectionResourceKey()){mutableIntStateOf(0)}
    var more by remember(photo.selectionResourceKey()){mutableStateOf(false)}
    val readState by produceState(PhotoExifReadState(),photo.selectionResourceKey(),photo.filesJson,cached,revision){
        value=PhotoExifReadState()
        if(BuildConfig.DEBUG)android.util.Log.d("FrameLinkPhotoInfo","Reading metadata")
        val information=withContext(Dispatchers.IO){PhotoExifReader.read(runtime.context,photo,cached)}
        value=PhotoExifReadState(information,false)
        if(BuildConfig.DEBUG)android.util.Log.d("FrameLinkPhotoInfo","Metadata ready: ${information!=null}")
    }
    val exif=readState.information
    val loading=readState.loading
    AlertDialog(onDismissRequest=onClose,title={Text("原始文件")},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(photo.displayTitle(),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("拍摄参数",fontSize=14.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("ISO" to exif?.iso,"快门速度" to exif?.shutter,"光圈" to exif?.aperture).forEach{(label,value)->
                    Column(Modifier.weight(1f).semantics{contentDescription="$label：${value?:"未提供"}"}){
                        Text(label,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value?:"未提供",fontSize=16.sp,modifier=Modifier.padding(top=5.dp))
                    }
                }
            }
            Text(if(loading)"正在读取照片信息"else exif?.source?.let{"来自$it"}?:"暂未读取到EXIF拍摄信息",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("焦距" to exif?.focalLength,"曝光补偿" to exif?.exposureBias,"相机" to exif?.camera,"镜头" to exif?.lens,"拍摄时间" to exif?.takenAt,"像素尺寸" to exif?.dimensions).forEach{(label,value)->
                if(value!=null)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Text(label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.width(60.dp))
                    Text(value,fontSize=12.sp,modifier=Modifier.weight(1f))
                }
            }
            val location=exif?.location
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Text("位置",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.width(60.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text(location?.coordinates?:if(loading||exif==null)"尚未读取"else if(exif?.source=="预览副本")"预览未携带位置"else"未记录",fontSize=12.sp)
                    location?.altitude?.let{Text("海拔 ${PhotoExifText.decimal(it)} m",fontSize=11.sp)}
                    location?.datum?.let{Text("坐标基准 $it",fontSize=11.sp)}
                    if(location?.canOpenMap==true)TextButton(onClick={
                        try{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(location.geoUri())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}
                        catch(_:android.content.ActivityNotFoundException){runtime.notice.value="没有可打开照片坐标的地图应用"}
                        catch(_:SecurityException){runtime.notice.value="系统未允许打开地图，可直接查看照片坐标"}
                    }){Text("在地图中查看")}
                }
            }
            val extra=listOf("曝光模式" to exif?.exposureMode,"白平衡" to exif?.whiteBalance,"测光" to exif?.metering,"闪光灯" to exif?.flash,"等效焦距" to exif?.equivalentFocalLength,"处理软件" to exif?.software,"作者" to exif?.artist,"版权" to exif?.copyright).filter{it.second!=null}
            if(extra.isNotEmpty()){
                TextButton(onClick={more=!more}){Text(if(more)"收起更多信息"else"更多拍摄信息")}
                if(more)extra.forEach{(label,value)->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Text(label,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.width(60.dp))
                    Text(value.orEmpty(),fontSize=12.sp,modifier=Modifier.weight(1f))
                }}
            }
            if(!loading&&(exif?.hasExposure!=true||exif?.source=="预览副本")&&cached==null&&photo.files().any{it.formatTitle()=="JPEG"&&it.savedUri.isBlank()}&&camera.info?.canDownload==true&&photo.id in cameraIds){
                TextButton(enabled=!fetching,onClick={
                    fetching=true;failure=""
                    scope.launch{
                        try{val path=runtime.ensureOriginalPreview(photo);onPreview(path);revision++}
                        catch(cancelled:CancellationException){throw cancelled}
                        catch(error:Exception){failure=error.message?:"拍摄信息读取失败，请重试"}
                        finally{fetching=false}
                    }
                }){Text(if(fetching)"正在读取JPEG…"else"读取JPEG完整信息")}
            }
            if(failure.isNotBlank())Text(failure,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
            HorizontalDivider()
            Text("${photo.formatTitle()} · 同一次拍摄的文件分别保存",fontSize=12.sp)
            photo.files().forEach { file -> Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(file.name,fontSize=14.sp)
                Text(file.formatTitle()+" · "+android.text.format.Formatter.formatShortFileSize(LocalContext.current,file.size)+" · "+if(file.savedUri.isNotBlank()) "已导入" else "未导入",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    },confirmButton={TextButton(onClick=onClose){Text("完成")}})
}
