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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.Stroke
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
internal fun LiveScreen(runtime:CameraRuntime,onClose:()->Unit,paused:Boolean,onGallery:()->Unit) {
    val frame by runtime.frame.collectAsStateWithLifecycle()
    val camera by runtime.camera.collectAsStateWithLifecycle()
    val properties by runtime.properties.collectAsStateWithLifecycle()
    val updating by runtime.parameterUpdating.collectAsStateWithLifecycle()
    val countdown by runtime.captureCountdown.collectAsStateWithLifecycle()
    val shooting by runtime.captureBusy.collectAsStateWithLifecycle()
    val receiving by runtime.postCaptureBusy.collectAsStateWithLifecycle()
    val focusing by runtime.focusBusy.collectAsStateWithLifecycle()
    val latest by runtime.lastCapture.collectAsStateWithLifecycle()
    val notice by runtime.notice.collectAsStateWithLifecycle()
    val landscape=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    val scope=rememberCoroutineScope()
    val glass=remember{HazeState()}
    var controls by rememberSaveable{mutableStateOf(true)}
    var tools by remember{mutableStateOf(false)}
    var assistants by rememberSaveable{mutableStateOf(false)}
    var showHistogram by rememberSaveable{mutableStateOf(false)}
    var info by remember{mutableStateOf(false)}
    var grid by rememberSaveable{mutableStateOf(false)}
    var zebra by rememberSaveable{mutableStateOf(false)}
    var peak by rememberSaveable{mutableStateOf(false)}
    var timer by rememberSaveable{mutableIntStateOf(0)}
    var shootingSettings by remember{mutableStateOf(false)}
    var autoImport by remember{mutableStateOf(runtime.settings.get("capture.auto_import")=="true")}
    var singleShot by remember{mutableStateOf(runtime.settings.get("capture.single")!="false")}
    var openingGallery by remember{mutableStateOf(false)}
    var viewport by remember{mutableStateOf(IntSize.Zero)}
    var focusPoint by remember{mutableStateOf<Offset?>(null)}
    var histogram by remember{mutableStateOf(IntArray(64))}
    var dimensions by remember{mutableStateOf(IntSize.Zero)}
    var rendererError by remember{mutableStateOf("")}
    var presented by remember{mutableStateOf(false)}
    var rendererKey by remember{mutableIntStateOf(0)}
    var property by remember{mutableStateOf<CameraProperty?>(null)}
    LaunchedEffect(paused){if(paused){runtime.cancelCountdown();runtime.stopLive()}else runtime.startLive()}
    DisposableEffect(Unit){onDispose{runtime.cancelCountdown();runtime.stopLive()}}
    fun hide(){controls=false;tools=false;property=null}
    fun retry(){rendererError="";presented=false;rendererKey++;runtime.stopLive();runtime.startLive()}
    fun shoot(){
        if(countdown>0)runtime.cancelCountdown()
        else if(!shooting&&!receiving)runtime.capture(timer)
    }
    fun openAlbum(){
        if(openingGallery)return
        runtime.cancelCountdown()
        openingGallery=true
        scope.launch{try{runtime.awaitPhotoWork();runtime.stopLive();onGallery()}finally{openingGallery=false}}
    }
    fun focus(point:Offset){
        val normalized=liveFocusPoint(point,viewport,dimensions)?:return
        if(camera.info?.canFocus!=true){runtime.notice.value="当前相机未开放点按对焦，请在机身调整";return}
        focusPoint=point
        runtime.focusAt(normalized.x,normalized.y)
    }
    LaunchedEffect(focusPoint,focusing){if(focusPoint!=null&&!focusing){kotlinx.coroutines.delay(1600);focusPoint=null}}
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
                // 横屏原比例取景的留边优先承载操作，展开参数也不改变图像尺寸与对焦坐标
                val imageRatio=if(dimensions.height>0)dimensions.width.toFloat()/dimensions.height else 1.5f
                val sideSpace=((maxWidth-maxHeight*imageRatio)/2).coerceAtLeast(0.dp)
                val railWidth=(sideSpace-16.dp).coerceIn(88.dp,132.dp)
                val panelHeight=if(landscape) (maxHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()-if(showHistogram)156.dp else 112.dp).coerceAtLeast(120.dp) else minOf(360.dp,maxHeight*.44f)
                val panelWidth=if(landscape) (sideSpace-8.dp).coerceIn(136.dp,168.dp) else minOf(maxWidth-24.dp,480.dp)
                val railHeight=(maxHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()-if(showHistogram)156.dp else 112.dp).coerceAtLeast(80.dp)
                val portraitFrame=if(!landscape&&controls) PaddingValues(top=safe.calculateTopPadding()+56.dp,bottom=safe.calculateBottomPadding()+if(tools) panelHeight+12.dp else 194.dp) else PaddingValues(0.dp)
                Box(Modifier.fillMaxSize().then(if(controls)Modifier.hazeSource(glass) else Modifier)){
                    Box(Modifier.fillMaxSize().padding(portraitFrame).testTag("live-preview")
                        .onSizeChanged{viewport=it}
                        .semantics{
                            contentDescription="取景画面，点按对焦"
                            stateDescription=if(rendererError.isNotBlank())rendererError else if(presented)"取景画面已显示" else "等待取景画面"
                            onClick{focus(Offset(viewport.width/2f,viewport.height/2f));true}
                        }.pointerInput(viewport,dimensions,camera.info?.canFocus){detectTapGestures(onTap={property=null;focus(it)})},
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
                        focusPoint?.let{point->Canvas(Modifier.fillMaxSize()){
                            drawRect(Color(0xffffd66b),topLeft=point-Offset(24.dp.toPx(),24.dp.toPx()),size=androidx.compose.ui.geometry.Size(48.dp.toPx(),48.dp.toPx()),style=Stroke(1.5.dp.toPx()))
                        }}
                        if(controls&&showHistogram)Canvas(Modifier.align(Alignment.BottomStart).padding(if(landscape)safe else PaddingValues(0.dp)).padding(12.dp).size(if(landscape)minOf(104.dp,railWidth)else 104.dp,48.dp).testTag("live-histogram")
                            .semantics{contentDescription="预览亮度直方图"}.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha=.48f)).padding(6.dp)){
                            val highest=(histogram.maxOrNull()?:1).coerceAtLeast(1)
                            histogram.forEachIndexed{index,value->val x=index*size.width/histogram.size;drawLine(Color.White.copy(alpha=.85f),Offset(x,size.height),Offset(x,size.height-value.toFloat()/highest*size.height),size.width/histogram.size)}
                            drawLine(Color.White.copy(alpha=.28f),Offset(0f,size.height),Offset(size.width,size.height),1f)
                        }
                    }
                }
                if(!controls)IconButton(onClick={controls=true},modifier=Modifier.align(Alignment.TopEnd).padding(safe).padding(12.dp).background(Color.Black.copy(alpha=.3f),CircleShape)
                    .semantics{stateDescription=if(presented)"取景画面已显示" else "等待取景画面"}){
                    Icon(Icons.Outlined.Tune,"显示取景控制",tint=Color.White)
                }
                if(controls&&!landscape&&!tools){
                    Column(Modifier.fillMaxSize().padding(safe).padding(horizontal=16.dp)){
                        Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically){
                            IconButton(onClick=onClose){Icon(Icons.Outlined.ArrowBack,"返回相机",tint=Color.White)}
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick=::cycleTimer){Icon(Icons.Outlined.Timer,if(timer==0)"无延时" else "$timer 秒延时",tint=if(timer>0)Color(0xffb8d7f0)else Color.White)}
                            IconButton(onClick={grid=!grid}){Icon(Icons.Outlined.Grid3x3,"构图网格",tint=if(grid)Color(0xffb8d7f0)else Color.White)}
                            IconButton(onClick=::hide){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White)}
                            IconButton(onClick={shootingSettings=true}){Icon(Icons.Outlined.MoreHoriz,"拍摄设置",tint=Color.White)}
                        }
                        Spacer(Modifier.weight(1f))
                        Column(Modifier.fillMaxWidth().height(194.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                            GlassSurface(glass,Modifier.fillMaxWidth(),radius=24.dp,dark=true){Quick(Modifier.fillMaxWidth())}
                            Row(horizontalArrangement=Arrangement.spacedBy(28.dp),verticalAlignment=Alignment.CenterVertically){
                                Text("拍照",fontSize=14.sp,color=Color(0xffb8d7f0))
                                TextButton(onClick={tools=true}){Text("专业工具",color=Color.White.copy(alpha=.75f),fontSize=13.sp)}
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick=::openAlbum),contentAlignment=Alignment.Center){
                                    if(latest!=null)AsyncImage(latest!!.displayUri,"查看相机照片",Modifier.fillMaxSize())
                                    else Icon(Icons.Outlined.PhotoLibrary,"查看相机照片",tint=Color.White,modifier=Modifier.size(28.dp))
                                }
                                Shutter(76,shooting,countdown,camera.info?.canCapture==true&&!receiving,::shoot)
                                IconButton(onClick=::retry,modifier=Modifier.size(64.dp).background(Color.White.copy(alpha=.09f),CircleShape)){
                                    Icon(Icons.Outlined.Refresh,"重新打开取景",tint=Color.White,modifier=Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
                if(controls&&landscape){
                    Box(Modifier.fillMaxSize().padding(safe).padding(horizontal=8.dp,vertical=8.dp).testTag("live-landscape-controls")){
                        Box(Modifier.align(Alignment.TopStart).width(railWidth),contentAlignment=Alignment.Center){
                            IconButton(onClick=onClose){Icon(Icons.Outlined.ArrowBack,"返回相机",tint=Color.White)}
                        }
                        if(!tools)Column(Modifier.align(Alignment.CenterStart).width(railWidth).height(minOf(railHeight,216.dp)).testTag("live-quick-properties"),horizontalAlignment=Alignment.CenterHorizontally){
                                CameraPropertyText.quick(properties).forEach{item->
                                    Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled=updating==null&&CameraPropertyText.editable(item)){property=item}.padding(horizontal=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                                        Text(CameraPropertyText.name(item),fontSize=10.sp,color=Color.White.copy(alpha=.55f))
                                        Text(if(updating==item.code)"应用中…" else CameraPropertyText.compact(item),modifier=Modifier.testTag("live-quick-value"),fontSize=14.sp,color=Color.White,maxLines=1)
                                    }
                                }
                        }
                        Column(Modifier.align(Alignment.CenterEnd).width(railWidth).fillMaxHeight().testTag("live-shutter-rail"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween){
                                Row{
                                    IconButton(onClick={shootingSettings=true}){Icon(Icons.Outlined.MoreHoriz,"拍摄设置",tint=Color.White)}
                                    IconButton(onClick=::hide){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White)}
                                }
                                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                                    TextButton(onClick=::cycleTimer){Text(if(timer==0)"无延时" else "$timer 秒",fontSize=11.sp,color=Color.White)}
                                    Shutter(72,shooting,countdown,camera.info?.canCapture==true&&!receiving,::shoot)
                                }
                                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
                                    GlassSurface(glass,radius=22.dp,dark=true){
                                        TextButton(onClick={tools=!tools;assistants=false}){Text(if(tools)"拍照" else "专业工具",fontSize=11.sp,color=if(tools)Color(0xffffd66b)else Color.White)}
                                    }
                                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=.08f)).clickable(onClick=::openAlbum),contentAlignment=Alignment.Center){
                                        if(latest!=null)AsyncImage(latest!!.displayUri,"查看相机照片",Modifier.fillMaxSize())
                                        else Icon(Icons.Outlined.PhotoLibrary,"查看相机照片",tint=Color.White,modifier=Modifier.size(24.dp))
                                    }
                                }
                        }
                    }
                }
                if(controls&&tools){
                    GlassSurface(glass,Modifier.align(if(landscape) Alignment.CenterStart else Alignment.BottomCenter).padding(safe).padding(horizontal=8.dp,vertical=8.dp)
                        .width(panelWidth).height(panelHeight).testTag("live-tools-panel").pointerInput(Unit){detectTapGestures(onTap={})},radius=24.dp,dark=true){
                        Column(Modifier.padding(horizontal=if(landscape)8.dp else 12.dp,vertical=8.dp)){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text("专业工具",Modifier.weight(1f),fontSize=14.sp,color=Color.White)
                                if(!landscape)IconButton(onClick=::hide,modifier=Modifier.size(44.dp)){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White,modifier=Modifier.size(20.dp))}
                                IconButton(onClick={tools=false},modifier=Modifier.size(44.dp)){Icon(Icons.Outlined.Close,"收起工具",tint=Color.White,modifier=Modifier.size(20.dp))}
                            }
                            if(landscape){
                                TextButton(onClick={assistants=!assistants},modifier=Modifier.fillMaxWidth()){
                                    Text(if(assistants)"机身参数" else "预览辅助",fontSize=11.sp,color=Color(0xffb8d7f0))
                                }
                            }else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
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
                                        if(landscape)Column(Modifier.fillMaxWidth().heightIn(min=48.dp).clip(UiTokens.control).clickable(enabled=updating==null&&CameraPropertyText.editable(item)){property=item}.padding(horizontal=6.dp,vertical=6.dp)){
                                            Text(CameraPropertyText.name(item),fontSize=10.sp,color=Color.White.copy(alpha=.55f))
                                            Text(if(updating==item.code)"应用中…" else CameraPropertyText.compact(item),fontSize=13.sp,color=Color.White,maxLines=2)
                                        }else
                                        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clip(UiTokens.control).clickable(enabled=updating==null&&CameraPropertyText.editable(item)){property=item}.padding(horizontal=6.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                                            Text(CameraPropertyText.name(item),Modifier.weight(1f),fontSize=12.sp,color=Color.White)
                                            Text(if(updating==item.code)"应用中…" else CameraPropertyText.compact(item),fontSize=12.sp,color=Color.White.copy(alpha=.8f),maxLines=2)
                                            Icon(Icons.Outlined.ChevronRight,null,tint=Color.White.copy(alpha=.5f),modifier=Modifier.size(16.dp))
                                        }
                                    }
                                    item{TextButton(onClick={info=true}){Text("连接信息与预览规格",fontSize=11.sp)}}
                                }
                            }
                            if(!landscape)Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                IconButton(onClick=::openAlbum){Icon(Icons.Outlined.PhotoLibrary,"查看相机照片",tint=Color.White,modifier=Modifier.size(22.dp))}
                                Shutter(48,shooting,countdown,camera.info?.canCapture==true&&!receiving,::shoot)
                                TextButton(onClick=::cycleTimer){Text(if(timer==0)"无延时" else "$timer 秒",fontSize=11.sp,color=Color.White)}
                            }
                        }
                    }
                }
                if(controls&&landscape)property?.let{item->
                    val current=properties.firstOrNull{it.code==item.code}?:item
                    val choices=CameraPropertyText.choices(current)
                    val list=rememberLazyListState()
                    LaunchedEffect(item.code){list.scrollToItem((choices.indexOfFirst{it.value==current.value}-1).coerceAtLeast(0))}
                    GlassSurface(glass,Modifier.align(Alignment.BottomCenter).padding(safe).padding(bottom=12.dp)
                        .width(minOf(460.dp,maxWidth-railWidth*2-32.dp)).testTag("live-property-picker"),radius=22.dp,dark=true){
                        Row(Modifier.height(64.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                            Text(CameraPropertyText.name(current),Modifier.width(64.dp),fontSize=11.sp,color=Color.White.copy(alpha=.7f),maxLines=2)
                            LazyRow(Modifier.weight(1f).testTag("live-property-choices"),state=list,verticalAlignment=Alignment.CenterVertically){
                                items(choices,key={it.value}){choice->
                                    Column(Modifier.widthIn(min=64.dp,max=180.dp).height(56.dp).clip(RoundedCornerShape(14.dp))
                                        .clickable(enabled=updating==null){runtime.setProperty(current,choice.value)}.padding(horizontal=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                                        Text(choice.label,fontSize=13.sp,color=if(choice.value==current.value)Color(0xffffd66b)else Color.White.copy(alpha=.75f),maxLines=2)
                                        Spacer(Modifier.height(6.dp))
                                        Box(Modifier.size(14.dp,2.dp).background(if(choice.value==current.value)Color(0xffffd66b)else Color.White.copy(alpha=.2f),CircleShape))
                                    }
                                }
                            }
                            IconButton(onClick={property=null}){Icon(Icons.Outlined.Close,"关闭参数",tint=Color.White,modifier=Modifier.size(18.dp))}
                        }
                    }
                }
                if(controls&&notice.isNotBlank()&&!tools)Text(notice,color=Color.White,fontSize=11.sp,maxLines=2,modifier=Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.6f),UiTokens.control).padding(12.dp))
                if(receiving||openingGallery)Text(if(openingGallery)"正在完成拍摄操作，稍后打开图库" else "正在接收拍后照片",color=Color.White,fontSize=11.sp,modifier=Modifier.align(Alignment.TopCenter).padding(safe).padding(top=64.dp).background(Color.Black.copy(alpha=.5f),UiTokens.control).padding(8.dp))
            }
        }
        if(shootingSettings)AlertDialog(onDismissRequest={shootingSettings=false},title={Text("拍摄设置")},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text("单张拍摄");Text(if(singleShot)"拍摄时切换单张，完成后恢复机身模式" else "跟随机身模式，连拍设置可能拍摄多张",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Switch(singleShot,{singleShot=it;runtime.settings.put("capture.single",it.toString())},modifier=Modifier.semantics{contentDescription="单张拍摄"},enabled=!shooting&&!receiving)
                }
                Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text("拍后自动导入");Text("关闭时仅存相机；开启时同步相机与手机。机身拒绝更改时请先在相机调整",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Switch(autoImport,{next->scope.launch{if(runtime.setCaptureImport(next))autoImport=next}},modifier=Modifier.semantics{contentDescription="拍后自动导入"},enabled=!shooting&&!receiving)
                }
                TextButton(onClick={shootingSettings=false;runtime.receivePendingCaptures()},enabled=!shooting&&!receiving){Text("接收机身待传文件（不拍照）")}
                Text("点按画面选择对焦位置。机身能力或当前对焦模式不支持时会给出提示。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={shootingSettings=false;info=true}){Text("连接信息")}
            }
        },confirmButton={TextButton(onClick={shootingSettings=false}){Text("完成")}})
        if(info)AlertDialog(onDismissRequest={info=false},title={Text("连接信息")},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(camera.info?.displayName()?:"相机未连接")
                Text("协议型号：${camera.info?.model.orEmpty()}",fontSize=12.sp)
                Text("固件：${camera.info?.firmware.orEmpty()}",fontSize=12.sp)
                Text(if(dimensions.width>0)"实收预览：${dimensions.width}×${dimensions.height}" else "正在等候预览",fontSize=12.sp)
                Text("预览尺寸由当前连接通道提供，不代表照片原片分辨率。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },confirmButton={TextButton(onClick={info=false}){Text("完成")}})
        if(!landscape)property?.let{item->
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
