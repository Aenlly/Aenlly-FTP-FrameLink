package app.framelink.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 连接与拍摄集中在设备卡片，FTP、蓝牙和本机导入保留独立入口 */
@Composable
internal fun CameraHome(runtime:CameraRuntime,photos:List<Photo>,status:CameraStatus,onConnect:()->Unit,onConnectionSettings:()->Unit,onBrowse:()->Unit,onLibrary:()->Unit,onFtp:()->Unit,onFtpClient:()->Unit,onImport:()->Unit,onRemote:()->Unit,onBluetooth:()->Unit,onSettings:()->Unit,onCloud:()->Unit,onPhoto:(Photo)->Unit){
    val receiving by runtime.ftpRunning.collectAsStateWithLifecycle()
    val client by runtime.ftpClient.state.collectAsStateWithLifecycle()
    val imported=remember(photos){photos.filter{it.inLibrary}.take(8)}
    var more by remember{mutableStateOf(false)}
    val connected=status.info!=null
    @Composable fun DeviceCard(){
Surface(color=Color.White,shape=RoundedCornerShape(24.dp)){
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Brand.copy(alpha=.09f)),contentAlignment=Alignment.Center){Icon(Icons.Outlined.CameraAlt,null,tint=Brand,modifier=Modifier.size(27.dp))}
                            Column(Modifier.weight(1f).padding(horizontal=12.dp)){
                                Text(status.info?.displayName()?:"连接你的相机",fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=Ink,maxLines=2)
                                Text(if(status.connecting)"正在建立连接…"else if(connected)if(status.info?.connectionKind==CameraConnectionKinds.USB_READER)"已连接 · USB读卡"else"已连接 · Wi-Fi"else"未连接",fontSize=12.sp,color=if(connected)Color(0xff348261)else Muted,modifier=Modifier.padding(top=4.dp))
                            }
                            TextButton(onClick=if(connected)onConnectionSettings else onConnect,enabled=!status.connecting,modifier=Modifier.semantics{contentDescription=if(connected)"相机连接设置"else"快速连接相机"}){Text(if(connected)"连接设置"else"连接",fontSize=12.sp)}
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            Button(onClick=if(!connected)onConnect else if(status.info?.canLiveView==true)onRemote else onBrowse,enabled=!status.connecting,shape=RoundedCornerShape(22.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("camera-primary")){
                                Icon(Icons.Outlined.CameraAlt,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(status.connecting)"正在连接"else if(!connected)"连接相机"else if(status.info?.canLiveView==true)"取景拍摄"else"浏览相机",fontSize=13.sp)
                            }
                            OutlinedButton(onClick=if(connected)onBrowse else onLibrary,shape=RoundedCornerShape(22.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("camera-browse")){
                                Icon(Icons.Outlined.Collections,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(connected)"相机照片"else"手机照片",fontSize=13.sp)
                            }
                        }
                    }
                }
    }
    @Composable fun ToolsCard(compact:Boolean=false){
Surface(color=Color.White,shape=RoundedCornerShape(22.dp)){
                    Column{
                        WorkspaceTool("FTP 接收","让相机主动把照片传到手机",Icons.Outlined.Wifi,if(receiving)"已开启"else"",onFtp,compact=compact)
                        HorizontalDivider(Modifier.padding(horizontal=16.dp),color=Line)
                        WorkspaceTool("FTP 客户端","浏览服务器，上传或下载文件",Icons.Outlined.SwapHoriz,if(client.connected)"已连接"else"",onFtpClient,compact=compact)
                        HorizontalDivider(Modifier.padding(horizontal=16.dp),color=Line)
                        WorkspaceTool("快速快门","通过蓝牙遥控相机",Icons.Outlined.Bluetooth,onClick=onBluetooth,compact=compact)
                        HorizontalDivider(Modifier.padding(horizontal=16.dp),color=Line)
                        WorkspaceTool("本机导入","从手机照片、文件或读卡器导入",Icons.Outlined.AddPhotoAlternate,onClick=onImport,compact=compact)
                    }
                }
    }
    Column(Modifier.fillMaxSize()){
        WorkspaceHeader("相机",if(connected)if(status.info?.canCapture==true)"相机已连接，随时继续拍摄"else"相机已连接，浏览并导入照片"else"连接相机，或先浏览手机照片"){
            Box{
                IconButton(onClick={more=true}){Icon(Icons.Outlined.MoreHoriz,"相机更多操作",tint=Ink)}
                DropdownMenu(more,{more=false}){
                    DropdownMenuItem(text={Text("相机连接设置")},onClick={more=false;onConnectionSettings()})
                    DropdownMenuItem(text={Text("云端设置")},onClick={more=false;onCloud()})
                    DropdownMenuItem(text={Text("设置与空间")},onClick={more=false;onSettings()})
                }
            }
        }
        LazyColumn(Modifier.weight(1f).widthIn(max=1200.dp).fillMaxWidth().align(Alignment.CenterHorizontally).testTag("camera-home"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            item{
                BoxWithConstraints(Modifier.fillMaxWidth()){
                    if(maxWidth>=720.dp)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                        Column(Modifier.weight(1f)){DeviceCard()}
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){WorkspaceSection("拍摄工具","按需使用");ToolsCard(true)}
                    }else Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)){
                        DeviceCard();WorkspaceSection("拍摄工具","按需使用");ToolsCard()
                    }
                }
            }
            item{WorkspaceSection("最近导入",action={TextButton(onClick=onLibrary){Text("查看全部",fontSize=12.sp)}})}
            if(imported.isEmpty())item{Text("导入的照片会显示在这里。",fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=8.dp))}
            else item{
                LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    items(imported,key={it.id}){photo->
                        Column(Modifier.width(116.dp).clickable{onPhoto(photo)}.semantics{contentDescription="最近照片 ${photo.name}"}){
                            PhotoThumbnail(runtime,photo,Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                            Text(photo.name,fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
                        }
                    }
                }
            }
        }
    }
}
