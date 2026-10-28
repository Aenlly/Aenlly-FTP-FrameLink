package app.framelink.camera

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/** 竖屏采用系统相机的拍摄层级；横屏保持完整窗口，控制作为可收起的玻璃浮层 */
@Composable
internal fun LiveScreen(runtime:CameraRuntime,onClose:()->Unit,onPhoto:(Photo)->Unit,paused:Boolean,onGallery:()->Unit) {
    val frame by runtime.frame.collectAsStateWithLifecycle()
    val camera by runtime.camera.collectAsStateWithLifecycle()
    val properties by runtime.properties.collectAsStateWithLifecycle()
    val updating by runtime.parameterUpdating.collectAsStateWithLifecycle()
    val countdown by runtime.captureCountdown.collectAsStateWithLifecycle()
    val latest by runtime.lastCapture.collectAsStateWithLifecycle()
    val notice by runtime.notice.collectAsStateWithLifecycle()
    val landscape=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    val scope=rememberCoroutineScope()
    val glass=remember{HazeState()}
    var controls by rememberSaveable{mutableStateOf(!landscape)}
    var tools by remember{mutableStateOf(false)}
    var assistants by rememberSaveable{mutableStateOf(false)}
    var showHistogram by rememberSaveable{mutableStateOf(false)}
    var info by remember{mutableStateOf(false)}
    var grid by rememberSaveable{mutableStateOf(false)}
    var zebra by rememberSaveable{mutableStateOf(false)}
    var peak by rememberSaveable{mutableStateOf(false)}
    var timer by rememberSaveable{mutableIntStateOf(0)}
    var shooting by remember{mutableStateOf(false)}
    var histogram by remember{mutableStateOf(IntArray(64))}
    var dimensions by remember{mutableStateOf(IntSize.Zero)}
    var rendererError by remember{mutableStateOf("")}
    var presented by remember{mutableStateOf(false)}
    var rendererKey by remember{mutableIntStateOf(0)}
    var property by remember{mutableStateOf<CameraProperty?>(null)}
    LaunchedEffect(paused){if(paused){runtime.cancelCountdown();runtime.stopLive()}else runtime.startLive()}
    DisposableEffect(Unit){onDispose{runtime.cancelCountdown();runtime.stopLive()}}
    fun hide(){controls=false;tools=false}
    fun retry(){rendererError="";presented=false;rendererKey++;runtime.stopLive();runtime.startLive()}
    fun shoot(){
        if(countdown>0){runtime.cancelCountdown();shooting=false}
        else if(!shooting){shooting=true;scope.launch{try{runtime.capture(timer).join()}finally{shooting=false}}}
    }
    fun cycleTimer(){timer=when(timer){0->3;3->10;else->0}}
    @Composable fun Quick(modifier:Modifier=Modifier){
        LazyRow(modifier.testTag("live-quick-properties"),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            items(CameraPropertyText.quick(properties),key={it.code}){item->
                Column(Modifier.clip(UiTokens.control).clickable(enabled=updating==null&&CameraPropertyText.editable(item)){property=item}.padding(horizontal=12.dp,vertical=8.dp)){
                    Text(CameraPropertyText.name(item),color=Color.White.copy(alpha=.6f),fontSize=10.sp)
                    Text(if(updating==item.code)"应用中…" else CameraPropertyText.compact(item),color=Color.White,fontSize=13.sp)
                }
            }
        }
    }
    DarkScene{
        FullScreenScene(onClose){
            DarkDialogBars()
            LiveSystemBars()
            val safe=dialogSafePadding()
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xff080a0d))){
                val panelHeight=if(landscape) maxHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()-16.dp else minOf(360.dp,maxHeight*.44f)
                val panelWidth=if(landscape) (maxWidth*.28f).coerceIn(220.dp,260.dp) else minOf(maxWidth-24.dp,480.dp)
                val railHeight=maxHeight-132.dp
                val portraitFrame=if(!landscape&&controls) PaddingValues(top=safe.calculateTopPadding()+56.dp,bottom=safe.calculateBottomPadding()+if(tools) panelHeight+12.dp else 194.dp) else PaddingValues(0.dp)
                Box(Modifier.fillMaxSize().then(if(controls)Modifier.hazeSource(glass) else Modifier)){
                    Box(Modifier.fillMaxSize().padding(portraitFrame).testTag("live-preview")
                        .semantics{
                            contentDescription=if(controls)"取景画面，点按隐藏控制" else "显示取景控制"
                            stateDescription=if(rendererError.isNotBlank())rendererError else if(presented)"取景画面已显示" else "等待取景画面"
                            onClick{controls=!controls;if(!controls)tools=false;true}
                        }.pointerInput(Unit){detectTapGestures(onTap={controls=!controls;if(!controls)tools=false})},
                        contentAlignment=Alignment.Center
                    ){
                        key(rendererKey){
                            AndroidView(factory={LiveViewSurface(it)},update={view->
                                view.frameSize={width,height->val next=IntSize(width,height);if(dimensions!=next)dimensions=next}
                                view.failure={rendererError=it}
                                view.framePresented={if(!presented)presented=true}
                                view.histogram=if(controls&&showHistogram){bins->histogram=bins}else null
                                view.zebra=zebra;view.peaking=peak
                                frame?.let(view::submit)
                            },modifier=Modifier.fillMaxSize(),onRelease={it.onPause()})
                        }
                        if(grid&&dimensions.width>0)Canvas(Modifier.fillMaxSize()){
                            val scale=minOf(size.width/dimensions.width,size.height/dimensions.height)
                            val width=dimensions.width*scale;val height=dimensions.height*scale
                            val left=(size.width-width)/2;val top=(size.height-height)/2
                            for(i in 1..2){
                                drawLine(Color.White.copy(alpha=.18f),Offset(left+width*i/3,top),Offset(left+width*i/3,top+height),1f)
                                drawLine(Color.White.copy(alpha=.18f),Offset(left,top+height*i/3),Offset(left+width,top+height*i/3),1f)
                            }
                        }
                        if(frame==null||rendererError.isNotBlank())Column(horizontalAlignment=Alignment.CenterHorizontally){
                            Text(rendererError.ifBlank{"等待相机取景画面"},color=Color.White,fontSize=12.sp)
                            if(rendererError.isNotBlank())TextButton(onClick=::retry){Text("重新打开取景")}
                        }
                        if(countdown>0)Text(countdown.toString(),color=Color.White,fontSize=64.sp)
                        if(controls&&showHistogram)Canvas(Modifier.align(Alignment.BottomStart).padding(12.dp).size(104.dp,52.dp).testTag("live-histogram")
                            .semantics{contentDescription="预览亮度直方图"}.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha=.48f)).padding(6.dp)){
                            val highest=(histogram.maxOrNull()?:1).coerceAtLeast(1)
                            histogram.forEachIndexed{index,value->val x=index*size.width/histogram.size;drawLine(Color.White.copy(alpha=.85f),Offset(x,size.height),Offset(x,size.height-value.toFloat()/highest*size.height),size.width/histogram.size)}
                            drawLine(Color.White.copy(alpha=.28f),Offset(0f,size.height),Offset(size.width,size.height),1f)
                        }
                    }
                }
                if(controls&&!landscape&&!tools){
                    Column(Modifier.fillMaxSize().padding(safe).padding(horizontal=16.dp)){
                        Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically){
                            IconButton(onClick=onClose){Icon(Icons.Outlined.ArrowBack,"返回相机",tint=Color.White)}
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick=::cycleTimer){Icon(Icons.Outlined.Timer,if(timer==0)"无延时" else "$timer 秒延时",tint=if(timer>0)Color(0xffb8d7f0)else Color.White)}
                            IconButton(onClick={grid=!grid}){Icon(Icons.Outlined.Grid3x3,"构图网格",tint=if(grid)Color(0xffb8d7f0)else Color.White)}
                            IconButton(onClick=::hide){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White)}
                            IconButton(onClick={info=true}){Icon(Icons.Outlined.MoreHoriz,"连接信息",tint=Color.White)}
                        }
                        Spacer(Modifier.weight(1f))
                        Column(Modifier.fillMaxWidth().height(194.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                            GlassSurface(glass,Modifier.fillMaxWidth(),radius=24.dp,dark=true){Quick(Modifier.fillMaxWidth())}
                            Row(horizontalArrangement=Arrangement.spacedBy(28.dp),verticalAlignment=Alignment.CenterVertically){
                                Text("拍照",fontSize=14.sp,color=Color(0xffb8d7f0))
                                TextButton(onClick={tools=true}){Text("专业工具",color=Color.White.copy(alpha=.75f),fontSize=13.sp)}
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick={if(latest!=null)onPhoto(latest!!)else onGallery()}),contentAlignment=Alignment.Center){
                                    if(latest!=null)AsyncImage(latest!!.displayUri,"查看拍后照片",Modifier.fillMaxSize())
                                    else Icon(Icons.Outlined.PhotoLibrary,"查看相机照片",tint=Color.White,modifier=Modifier.size(28.dp))
                                }
                                Shutter(76,shooting,countdown,camera.info?.canCapture==true,::shoot)
                                IconButton(onClick=::retry,modifier=Modifier.size(64.dp).background(Color.White.copy(alpha=.09f),CircleShape)){
                                    Icon(Icons.Outlined.Refresh,"重新打开取景",tint=Color.White,modifier=Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
                if(controls&&landscape&&!tools){
                    Box(Modifier.fillMaxSize().padding(safe).padding(horizontal=12.dp,vertical=8.dp)){
                        GlassSurface(glass,Modifier.align(Alignment.TopStart),radius=24.dp,dark=true){
                            IconButton(onClick=onClose){Icon(Icons.Outlined.ArrowBack,"返回相机",tint=Color.White)}
                        }
                        GlassSurface(glass,Modifier.align(Alignment.CenterStart).width(108.dp).heightIn(max=railHeight),radius=22.dp,dark=true){
                            LazyColumn(Modifier.fillMaxWidth().testTag("live-quick-properties"),verticalArrangement=Arrangement.spacedBy(2.dp)){
                                items(CameraPropertyText.quick(properties),key={it.code}){item->
                                    Column(Modifier.fillMaxWidth().clickable(enabled=updating==null&&CameraPropertyText.editable(item)){property=item}.padding(horizontal=12.dp,vertical=8.dp)){
                                        Text(CameraPropertyText.name(item),fontSize=10.sp,color=Color.White.copy(alpha=.65f))
                                        Text(if(updating==item.code)"应用中…" else CameraPropertyText.compact(item),fontSize=13.sp,color=Color.White)
                                    }
                                }
                            }
                        }
                        Column(Modifier.align(Alignment.CenterEnd).width(112.dp).fillMaxHeight(),horizontalAlignment=Alignment.CenterHorizontally){
                            GlassSurface(glass,radius=24.dp,dark=true){
                                Row{
                                    IconButton(onClick={info=true}){Icon(Icons.Outlined.MoreHoriz,"连接信息",tint=Color.White)}
                                    IconButton(onClick=::hide){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White)}
                                }
                            }
                            TextButton(onClick={tools=true}){Text("专业工具",fontSize=12.sp,color=Color.White)}
                            Spacer(Modifier.weight(1f))
                            GlassSurface(glass,radius=30.dp,dark=true){
                                Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                                    TextButton(onClick=::cycleTimer){Text(if(timer==0)"无延时" else "$timer 秒",fontSize=11.sp,color=Color.White)}
                                    Shutter(64,shooting,countdown,camera.info?.canCapture==true,::shoot)
                                    IconButton(onClick={if(latest!=null)onPhoto(latest!!)else onGallery()}){
                                        Icon(Icons.Outlined.PhotoLibrary,"查看相机照片",tint=Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
                if(controls&&tools){
                    GlassSurface(glass,Modifier.align(if(landscape) Alignment.CenterEnd else Alignment.BottomCenter).padding(safe).padding(horizontal=12.dp,vertical=8.dp)
                        .width(panelWidth).height(panelHeight).testTag("live-tools-panel").pointerInput(Unit){detectTapGestures(onTap={})},radius=24.dp,dark=true){
                        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text("专业工具",Modifier.weight(1f),fontSize=14.sp,color=Color.White)
                                IconButton(onClick=::hide,modifier=Modifier.size(44.dp)){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White,modifier=Modifier.size(20.dp))}
                                IconButton(onClick={tools=false},modifier=Modifier.size(44.dp)){Icon(Icons.Outlined.Close,"收起工具",tint=Color.White,modifier=Modifier.size(20.dp))}
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                FilterChip(selected=!assistants,onClick={assistants=false},label={Text("机身参数",fontSize=11.sp)})
                                FilterChip(selected=assistants,onClick={assistants=true},label={Text("预览辅助",fontSize=11.sp)})
                            }
                            LazyColumn(Modifier.weight(1f).testTag("live-properties"),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                if(assistants){
                                    item{LiveAssistToggle("构图网格",grid){grid=it}}
                                    item{LiveAssistToggle("峰值对焦",peak){peak=it}}
                                    item{LiveAssistToggle("过曝提示",zebra){zebra=it}}
                                    item{LiveAssistToggle("直方图",showHistogram){showHistogram=it}}
                                    item{Text("辅助分析基于取景预览",fontSize=10.sp,color=Color.White.copy(alpha=.6f),modifier=Modifier.padding(vertical=8.dp))}
                                }else{
                                    items(CameraPropertyText.controls(properties),key={it.code}){item->
                                        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clip(UiTokens.control).clickable(enabled=updating==null&&CameraPropertyText.editable(item)){property=item}.padding(horizontal=6.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                                            Text(CameraPropertyText.name(item),Modifier.weight(1f),fontSize=12.sp,color=Color.White)
                                            Text(if(updating==item.code)"应用中…" else CameraPropertyText.compact(item),fontSize=12.sp,color=Color.White.copy(alpha=.8f),maxLines=2)
                                            Icon(Icons.Outlined.ChevronRight,null,tint=Color.White.copy(alpha=.5f),modifier=Modifier.size(16.dp))
                                        }
                                    }
                                    item{TextButton(onClick={info=true}){Text("连接信息与预览规格",fontSize=11.sp)}}
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                IconButton(onClick={if(latest!=null)onPhoto(latest!!)else onGallery()}){Icon(Icons.Outlined.PhotoLibrary,"查看相机照片",tint=Color.White,modifier=Modifier.size(22.dp))}
                                Shutter(48,shooting,countdown,camera.info?.canCapture==true,::shoot)
                                TextButton(onClick=::cycleTimer){Text(if(timer==0)"无延时" else "$timer 秒",fontSize=11.sp,color=Color.White)}
                            }
                        }
                    }
                }
                if(controls&&notice.isNotBlank()&&!tools)Text(notice,color=Color.White,fontSize=11.sp,maxLines=2,modifier=Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.6f),UiTokens.control).padding(12.dp))
            }
        }
        if(info)AlertDialog(onDismissRequest={info=false},title={Text("连接信息")},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(camera.info?.displayName()?:"相机未连接")
                Text("协议型号：${camera.info?.model.orEmpty()}",fontSize=12.sp)
                Text("固件：${camera.info?.firmware.orEmpty()}",fontSize=12.sp)
                Text(if(dimensions.width>0)"实收预览：${dimensions.width}×${dimensions.height}" else "正在等候预览",fontSize=12.sp)
                Text("预览尺寸由当前连接通道提供，不代表照片原片分辨率。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },confirmButton={TextButton(onClick={info=false}){Text("完成")}})
        property?.let{item->
            val current=properties.firstOrNull{it.code==item.code}?:item
            AlertDialog(onDismissRequest={property=null},shape=UiTokens.card,title={Text(CameraPropertyText.name(current))},text={
                LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    items(CameraPropertyText.choices(current)){choice->
                        Row(Modifier.fillMaxWidth().clip(UiTokens.control).background(if(choice.value==current.value)Brand.copy(alpha=.25f)else Color.Transparent).clickable{runtime.setProperty(current,choice.value);property=null}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                            RadioButton(choice.value==current.value,null)
                            Text(choice.label,fontSize=14.sp,modifier=Modifier.padding(end=12.dp))
                        }
                    }
                    if(CameraPropertyText.choices(current).size<current.choices.size)item{Info("其他机身选项暂未适配，请在相机上调整。")}
                }
            },confirmButton={TextButton(onClick={property=null}){Text("关闭")}})
        }
    }
}

@Composable
private fun LiveAssistToggle(title:String,enabled:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clickable{onChange(!enabled)}.padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,Modifier.weight(1f),fontSize=12.sp,color=Color.White)
        Switch(checked=enabled,onCheckedChange=onChange,modifier=Modifier.semantics{contentDescription=title})
    }
}

@Composable
private fun Shutter(size:Int,shooting:Boolean,countdown:Int,enabled:Boolean,onClick:()->Unit){
    Box(Modifier.size(size.dp).semantics{contentDescription="拍摄"}.border(2.dp,Color.White,CircleShape).padding(6.dp).clip(CircleShape).background(if(shooting)Color(0xff9aabb8)else Color.White).clickable(enabled=enabled&&(!shooting||countdown>0),onClick=onClick),contentAlignment=Alignment.Center){
        if(countdown>0)Text(countdown.toString(),color=Brand,fontSize=24.sp)
        else if(shooting)CircularProgressIndicator(Modifier.size(22.dp),color=Brand,strokeWidth=2.dp)
    }
}

@Composable
private fun LiveSystemBars(){
    val view=LocalView.current
    DisposableEffect(view){
        val window=(view.parent as?DialogWindowProvider)?.window
        val controller=window?.let{WindowInsetsControllerCompat(it,view)}
        controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose{controller?.show(WindowInsetsCompat.Type.systemBars())}
    }
}
