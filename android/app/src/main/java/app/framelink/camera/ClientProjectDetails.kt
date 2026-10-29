package app.framelink.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 摄影师先查看场次记录，进入客户模式后才允许修改客户选择 */
@Composable
internal fun ClientProjectDetails(runtime:CameraRuntime,review:LocalReview,photos:List<Photo>,onBack:()->Unit,onClient:()->Unit,onPhoto:(Photo,List<String>,List<String>)->Unit){
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    var more by remember{mutableStateOf(false)}
    var renaming by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf(false)}
    var sharing by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val ids=remember(review.photoIdsJson){review.photoIds()}
    val choices=remember(review.choicesJson){review.choices()}
    val map=remember(photos){photos.associateBy{it.id}}
    val selected=remember(ids,choices){ids.filter{choices[it]?.selected==true}}
    Column(Modifier.fillMaxSize()){
        WorkspaceHeader("客户场次",java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.ROOT).format(java.util.Date(review.createdAt))){
            IconButton(onClick=onBack){Icon(Icons.Outlined.ArrowBack,"返回全部项目")}
            Box{
                IconButton(onClick={more=true}){Icon(Icons.Outlined.MoreHoriz,"管理客户场次")}
                DropdownMenu(more,{more=false}){
                    DropdownMenuItem(text={Text("修改姓名或场次名称")},onClick={more=false;renaming=true})
                    if(review.locked)DropdownMenuItem(text={Text("开放本轮修改")},onClick={more=false;scope.launch{runtime.reviews.lock(review.id,false)}})
                    DropdownMenuItem(text={Text("删除选片记录")},onClick={more=false;deleting=true})
                }
            }
        }
        Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
            Text(review.title,fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            Text("客户的选择和留言只属于本场次。",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical=14.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)){
                Text("${ids.size} 张照片",fontSize=13.sp)
                Text("${selected.size} 张客户已选",fontSize=13.sp,color=Brand)
                Text(if(review.locked)"本轮已完成"else"选片进行中",fontSize=12.sp,color=Muted)
            }
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        }
        LazyVerticalGrid(columns=GridCells.Adaptive(128.dp),modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            items(ids,key={it}){id->
                val photo=map[id]
                Column{
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("client-detail-photo-$id").clip(RoundedCornerShape(12.dp)).clickable(enabled=photo!=null){photo?.let{onPhoto(it,ids,selected)}}){
                        if(photo!=null)PhotoThumbnail(runtime,photo,Modifier.fillMaxSize())else Icon(Icons.Outlined.ImageNotSupported,"照片记录暂不可用",modifier=Modifier.align(Alignment.Center))
                        if(id in selected)Icon(Icons.Outlined.CheckCircle,"客户已选",tint=Brand,modifier=Modifier.align(Alignment.BottomEnd).padding(6.dp))
                    }
                    Text(photo?.name?:"照片记录暂不可用",fontSize=10.sp,color=Muted,maxLines=1,modifier=Modifier.padding(top=4.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Button(onClick=onClient,modifier=Modifier.weight(1f),shape=RoundedCornerShape(22.dp)){Text(if(review.locked)"查看客户结果"else"进入客户选片")}
            OutlinedButton(enabled=selected.isNotEmpty()&&!sharing,onClick={
                sharing=true
                scope.launch{
                    runCatching{runtime.batches.contactSheets(SelectionBatch(review.id,review.title,Json.encode(selected),Json.encode(selected.mapNotNull(map::get)),review.createdAt,review.updatedAt))}
                        .onSuccess{shareContactSheets(context,it)}.onFailure{error=it.message?:"选片单生成失败"}
                    sharing=false
                }
            },shape=RoundedCornerShape(22.dp)){Text(if(sharing)"生成中…"else"选片单")}
        }
    }
    if(renaming)ReviewNameDialog(review.title,false,{renaming=false}){title->runtime.reviews.rename(review.id,title);renaming=false}
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除这次客户选片？")},text={Text("删除选择和留言记录，照片原文件与其他场次保留。")},confirmButton={TextButton(onClick={scope.launch{runtime.reviews.delete(review.id);deleting=false;onBack()}}){Text("删除记录")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}
