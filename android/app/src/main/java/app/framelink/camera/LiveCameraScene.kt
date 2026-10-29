package app.framelink.camera

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.scale
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

/** 拍照界面保持简洁，专业参数按需展开；两种方向共享完整取景与真实机身状态 */
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
    var assistants by rememberSaveable{mutableStateOf(true)}
    var professional by rememberSaveable{mutableStateOf(false)}
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
    fun tapPreview(point:Offset){
        if(tools){tools=false;return}
        property=null
        focus(point)
    }
    LaunchedEffect(focusPoint,focusing){if(focusPoint!=null&&!focusing){kotlinx.coroutines.delay(1600);focusPoint=null}}
    fun cycleTimer(){timer=when(timer){0->3;3->10;else->0}}
    suspend fun applyParameter(item:CameraProperty,value:Long):Long{
        runtime.setProperty(item,value).join()
        return runtime.properties.value.firstOrNull{it.code==item.code}?.value?:item.value
    }
    @Composable fun Album(modifier:Modifier=Modifier){
        Box(modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=.08f)).clickable(onClick=::openAlbum),contentAlignment=Alignment.Center){
            if(latest!=null)AsyncImage(latest!!.displayUri,"查看相机照片",Modifier.fillMaxSize())
            else Icon(Icons.Outlined.PhotoLibrary,"查看相机照片",tint=Color.White,modifier=Modifier.size(26.dp))
        }
    }
    @Composable fun Modes(modifier:Modifier=Modifier){
        GlassSurface(glass,modifier,radius=26.dp,dark=true){
            Row(Modifier.padding(4.dp),horizontalArrangement=Arrangement.Center){
                listOf(false to "拍照",true to "专业").forEach{(pro,title)->
                    val tint by animateColorAsState(if(professional==pro)Color.White.copy(alpha=.12f)else Color.Transparent,tween(140),label="拍摄模式")
                    Box(Modifier.width(48.dp).height(40.dp).clip(CircleShape).background(tint)
                        .clickable{professional=pro;property=null}.semantics{contentDescription=title+"模式"},contentAlignment=Alignment.Center){
                        Text(title,fontSize=12.sp,color=if(professional==pro)LiveAccent else Color.White.copy(alpha=.7f))
                    }
                }
            }
        }
    }
    @Composable fun Quick(vertical:Boolean,modifier:Modifier=Modifier){
        @Composable fun Item(item:CameraProperty,modifier:Modifier){
            Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled=updating==null&&CameraPropertyText.editable(item)){property=item}
                .padding(horizontal=4.dp,vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                Text(if(updating==item.code)"应用中…" else CameraPropertyText.compact(item).removePrefix("ISO ").removeSuffix(" 秒").removeSuffix(" EV"),Modifier.testTag("live-quick-value"),fontSize=14.sp,maxLines=1,color=if(property?.code==item.code)LiveAccent else Color.White)
                Text(CameraPropertyText.name(item),fontSize=11.sp,color=Color.White.copy(alpha=.72f),maxLines=1)
            }
        }
        if(vertical)Column(modifier.testTag("live-quick-properties"),horizontalAlignment=Alignment.CenterHorizontally){
            CameraPropertyText.quick(properties).forEach{Item(it,Modifier.weight(1f).fillMaxWidth())}
        }else Row(modifier.testTag("live-quick-properties")){
            CameraPropertyText.quick(properties).forEach{Item(it,Modifier.weight(1f).fillMaxHeight())}
        }
    }
    @Composable fun Parameter(vertical:Boolean,modifier:Modifier=Modifier){
        property?.let{item->
            val current=properties.firstOrNull{it.code==item.code}?:item
            val adjustable=updating==null&&CameraPropertyText.editable(current)
            var draftLabel by remember(current.code){mutableStateOf<String?>(null)}
            GlassSurface(glass,modifier.testTag("live-property-picker").pointerInput(Unit){detectTapGestures(onTap={})},radius=24.dp,dark=true){
                if(vertical)Column(Modifier.fillMaxSize().padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text(CameraPropertyText.name(current),fontSize=10.sp,color=Color.White.copy(alpha=.65f),modifier=Modifier.padding(top=8.dp))
                    Text((draftLabel?:CameraPropertyText.compact(current)).removePrefix("ISO ").removeSuffix(" 秒").removeSuffix(" EV"),fontSize=12.sp,maxLines=1,color=LiveAccent)
                    CameraParameterRuler(current,true,adjustable,{applyParameter(current,it)},Modifier.weight(1f).fillMaxWidth(),onPreview={draftLabel=it})
                    IconButton(onClick={property=null}){Icon(Icons.Outlined.Close,"关闭参数",tint=Color.White,modifier=Modifier.size(18.dp))}
                }else Column(Modifier.fillMaxSize().padding(horizontal=12.dp)){
                    Row(Modifier.height(44.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(CameraPropertyText.name(current),fontSize=12.sp,color=Color.White.copy(alpha=.65f))
                        Spacer(Modifier.weight(1f))
                        Text(if(updating==current.code)"应用中…" else draftLabel?:CameraPropertyText.compact(current),fontSize=18.sp,color=LiveAccent)
                        IconButton(onClick={property=null}){Icon(Icons.Outlined.Close,"关闭参数",tint=Color.White,modifier=Modifier.size(18.dp))}
                    }
                    CameraParameterRuler(current,false,adjustable,{applyParameter(current,it)},Modifier.fillMaxWidth().weight(1f),onPreview={draftLabel=it})
                }
            }
        }
    }
    DarkScene{
        FullScreenScene({when{tools->tools=false;property!=null->property=null;else->onClose()}}){
            DarkDialogBars()
            LiveSystemBars()
            val windowSafe=dialogSafePadding()
            // 沉浸取景只保留实际手势和切口区域，不叠加通用Dialog的导航栏回退高度
            val gestureBottom=WindowInsets.mandatorySystemGestures.asPaddingValues().calculateBottomPadding()
            val cutoutBottom=WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
            val safe=PaddingValues(start=windowSafe.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),top=windowSafe.calculateTopPadding(),end=windowSafe.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),bottom=maxOf(gestureBottom,cutoutBottom))
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xff080a0d))){
                val sceneHeight=maxHeight
                // 横屏原比例取景后，将剩余宽度集中用于相机控制，模式切换不改变对焦坐标
                val imageRatio=if(dimensions.height>0)dimensions.width.toFloat()/dimensions.height else 1.5f
                val cameraSpace=(maxWidth-maxHeight*imageRatio).coerceIn(112.dp,320.dp)
                val wideControls=cameraSpace>=safe.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)+240.dp
                val panelHeight=if(assistants)minOf(300.dp,maxHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()-24.dp) else if(landscape) minOf(332.dp,maxHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()-24.dp) else minOf(380.dp,maxHeight*.52f)
                val panelWidth=if(landscape) minOf(320.dp,maxWidth*.36f) else minOf(maxWidth-24.dp,420.dp)
                val portraitFrame=PaddingValues(end=if(landscape)cameraSpace else 0.dp)
                Box(Modifier.fillMaxSize().then(if(controls)Modifier.hazeSource(glass) else Modifier)){
                    Box(Modifier.fillMaxSize().padding(portraitFrame).testTag("live-preview")
                        .onSizeChanged{viewport=it}
                        .semantics{
                            contentDescription="取景画面，点按对焦"
                            stateDescription=if(rendererError.isNotBlank())rendererError else if(presented)"取景画面已显示" else "等待取景画面"
                            onClick{tapPreview(Offset(viewport.width/2f,viewport.height/2f));true}
                        }.pointerInput(viewport,dimensions,camera.info?.canFocus){detectTapGestures(onTap={tapPreview(it)})},
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
                        if(controls&&showHistogram)Canvas(Modifier.align(if(landscape)Alignment.BottomStart else Alignment.TopEnd).padding(if(landscape)safe else PaddingValues(top=safe.calculateTopPadding()+64.dp)).padding(12.dp).size(96.dp,44.dp).testTag("live-histogram")
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
                val inlineParameter=property?.code in CameraPropertyText.exposureOrder
                if(controls&&!tools&&!landscape){
                    Column(Modifier.fillMaxSize().padding(safe).padding(horizontal=20.dp).padding(top=12.dp,bottom=8.dp)){
                        Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
                            IconButton(onClick=onClose){Icon(Icons.Outlined.ArrowBack,"返回相机",tint=Color.White)}
                            Spacer(Modifier.weight(1f))
                            properties.firstOrNull{it.code==0x5010&&CameraPropertyText.editable(it)}?.let{exposure->
                                IconButton(onClick={property=exposure}){Icon(Icons.Outlined.Exposure,"调整曝光",tint=if(property?.code==exposure.code)LiveAccent else Color.White)}
                            }
                            IconButton(onClick=::cycleTimer){Icon(Icons.Outlined.Timer,if(timer==0)"无延时" else "$timer 秒延时",tint=if(timer>0)LiveAccent else Color.White)}
                            IconButton(onClick=::hide){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White)}
                        }
                        Spacer(Modifier.weight(1f))
                        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                            if(professional)Quick(false,Modifier.fillMaxWidth().height(52.dp).background(Color.Black.copy(alpha=.68f),RoundedCornerShape(24.dp)))
                            Row(Modifier.fillMaxWidth().height(92.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                Album()
                                Shutter(80,shooting,countdown,camera.info?.canCapture==true&&!receiving,::shoot)
                                IconButton(onClick={tools=true;assistants=true},modifier=Modifier.size(52.dp).background(Color.White.copy(alpha=.08f),CircleShape)){
                                    CameraMoreIcon()
                                }
                            }
                            if(inlineParameter)Parameter(false,Modifier.widthIn(max=420.dp).fillMaxWidth().height(104.dp))
                            else Modes()
                        }
                    }
                }
                if(controls&&!tools&&landscape){
                    Box(Modifier.fillMaxSize().padding(safe).padding(12.dp).testTag("live-landscape-controls")){
                        IconButton(onClick=onClose,modifier=Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha=.5f),CircleShape)){
                            Icon(Icons.Outlined.ArrowBack,"返回相机",tint=Color.White)
                        }
                        Row(Modifier.align(Alignment.TopStart).padding(start=52.dp).background(Color.Black.copy(alpha=.5f),CircleShape)){
                            IconButton(onClick=::cycleTimer){Icon(Icons.Outlined.Timer,if(timer==0)"无延时" else "$timer 秒延时",tint=if(timer>0)LiveAccent else Color.White)}
                            IconButton(onClick={grid=!grid}){Icon(Icons.Outlined.Grid3x3,"构图网格",tint=if(grid)LiveAccent else Color.White)}
                        }
                        Column(Modifier.align(Alignment.CenterEnd).width(108.dp).fillMaxHeight().testTag("live-shutter-rail"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween){
                            Row{
                                IconButton(onClick={tools=true;assistants=true}){CameraMoreIcon()}
                                IconButton(onClick=::hide){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White)}
                            }
                            Shutter(80,shooting,countdown,camera.info?.canCapture==true&&!receiving,::shoot)
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Album()
                                Modes()
                            }
                        }
                        if(professional&&wideControls)Quick(true,Modifier.align(Alignment.CenterEnd).padding(end=112.dp).width(60.dp).heightIn(max=sceneHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()-48.dp).fillMaxHeight())
                        if(!professional&&wideControls&&!inlineParameter)properties.firstOrNull{it.code==0x5010&&CameraPropertyText.editable(it)}?.let{exposure->
                            Column(Modifier.align(Alignment.CenterEnd).padding(end=132.dp).clip(RoundedCornerShape(20.dp)).clickable{property=exposure}.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                Icon(Icons.Outlined.Exposure,"调整曝光",tint=Color.White,modifier=Modifier.size(22.dp))
                                Text(CameraPropertyText.compact(exposure),fontSize=11.sp,color=Color.White.copy(alpha=.6f))
                            }
                        }
                        if(professional&&!wideControls&&!inlineParameter)Quick(false,Modifier.align(Alignment.BottomStart).padding(end=128.dp).fillMaxWidth().height(52.dp).background(Color.Black.copy(alpha=.35f),RoundedCornerShape(20.dp)))
                        if(inlineParameter){
                            if(wideControls)Parameter(true,Modifier.align(Alignment.CenterEnd).padding(end=176.dp).width(52.dp).fillMaxHeight().padding(vertical=32.dp))
                            else Parameter(false,Modifier.align(Alignment.BottomStart).padding(end=124.dp).fillMaxWidth().height(104.dp))
                        }
                    }
                }
                AnimatedVisibility(visible=controls&&tools,
                    modifier=Modifier.align(if(landscape)Alignment.Center else Alignment.BottomCenter).offset(x=if(landscape)-cameraSpace/2 else 0.dp).padding(safe).padding(12.dp),
                    enter=fadeIn(tween(160))+scaleIn(tween(180),initialScale=.96f),exit=fadeOut(tween(120))+scaleOut(tween(120),targetScale=.98f)){
                    GlassSurface(glass,Modifier.width(panelWidth).height(panelHeight).testTag("live-tools-panel").pointerInput(Unit){detectTapGestures(onTap={})},radius=32.dp,dark=true){
                        Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text("拍摄控制",Modifier.weight(1f),fontSize=15.sp,color=Color.White)
                                IconButton(onClick=::hide){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White,modifier=Modifier.size(20.dp))}
                                IconButton(onClick={tools=false}){Icon(Icons.Outlined.Close,"收起工具",tint=Color.White,modifier=Modifier.size(20.dp))}
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){
                                TextButton(onClick={assistants=true}){Text("预览辅助",color=if(assistants)LiveAccent else Color.White.copy(alpha=.55f),fontSize=12.sp)}
                                TextButton(onClick={assistants=false}){Text("机身参数",color=if(!assistants)LiveAccent else Color.White.copy(alpha=.55f),fontSize=12.sp)}
                            }
                            LazyColumn(Modifier.weight(1f).testTag("live-properties"),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                if(assistants){
                                    val aids=listOf(
                                        Triple("构图网格",Icons.Outlined.Grid3x3,grid),Triple("直方图",Icons.Outlined.BarChart,showHistogram),
                                        Triple("峰值对焦",Icons.Outlined.CenterFocusStrong,peak),Triple("过曝提示",Icons.Outlined.Contrast,zebra),
                                        Triple("延时快门",Icons.Outlined.Timer,timer>0),Triple("拍摄设置",Icons.Outlined.Tune,false))
                                    items(aids.chunked(3)){row->
                                        Row(Modifier.fillMaxWidth()){
                                            row.forEach{(title,icon,selected)->
                                                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable{
                                                    when(title){
                                                        "构图网格"->grid=!grid
                                                        "直方图"->showHistogram=!showHistogram
                                                        "峰值对焦"->peak=!peak
                                                        "过曝提示"->zebra=!zebra
                                                        "延时快门"->cycleTimer()
                                                        else->{tools=false;shootingSettings=true}
                                                    }
                                                }.padding(vertical=8.dp).semantics{contentDescription=title},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){
                                                    Box(Modifier.size(46.dp).background(if(selected)LiveAccent.copy(alpha=.18f)else Color.Black.copy(alpha=.25f),CircleShape),contentAlignment=Alignment.Center){
                                                        Icon(icon,null,tint=if(selected)LiveAccent else Color.White,modifier=Modifier.size(24.dp))
                                                    }
                                                    Text(if(title=="延时快门"&&timer>0)"$timer 秒" else title,fontSize=11.sp,color=Color.White.copy(alpha=.85f))
                                                }
                                            }
                                        }
                                    }
                                }else{
                                    items(CameraPropertyText.controls(properties),key={it.code}){item->
                                        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clip(RoundedCornerShape(12.dp))
                                            .clickable(enabled=updating==null&&CameraPropertyText.editable(item)){property=item;if(item.code in CameraPropertyText.exposureOrder)tools=false}
                                            .padding(horizontal=6.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                                            Text(CameraPropertyText.name(item),Modifier.weight(1f),fontSize=12.sp,color=Color.White)
                                            Text(if(updating==item.code)"应用中…" else CameraPropertyText.compact(item),fontSize=12.sp,color=Color.White.copy(alpha=.75f),maxLines=2)
                                            Icon(Icons.Outlined.ChevronRight,null,tint=Color.White.copy(alpha=.4f),modifier=Modifier.size(16.dp))
                                        }
                                    }
                                    item{TextButton(onClick={tools=false;info=true}){Text("连接信息与预览规格",fontSize=11.sp)}}
                                }
                            }
                        }
                    }
                }
                if(controls&&notice.isNotBlank()&&!tools)Text(notice,color=Color.White,fontSize=12.sp,maxLines=2,modifier=Modifier.align(if(landscape)Alignment.BottomCenter else Alignment.TopCenter).padding(safe).padding(top=if(landscape)0.dp else 60.dp,bottom=if(landscape)8.dp else 0.dp).widthIn(max=420.dp).background(Color.Black.copy(alpha=.7f),UiTokens.control).padding(10.dp))
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
                Text("点按画面选定位置并对焦，机身能力或当前模式不支持时会给出提示。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
        property?.takeUnless{it.code in CameraPropertyText.exposureOrder}?.let{item->
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
private fun CameraMoreIcon(){
    Canvas(Modifier.size(24.dp).semantics{contentDescription="更多控制"}){
        for(row in 0..1)for(column in 0..2)drawCircle(Color.White,1.7.dp.toPx(),Offset((5+column*7).dp.toPx(),(8+row*8).dp.toPx()))
    }
}

@Composable
private fun Shutter(size:Int,shooting:Boolean,countdown:Int,enabled:Boolean,onClick:()->Unit){
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val innerScale by animateFloatAsState(if(pressed).92f else 1f,tween(90),label="快门按压")
    Box(Modifier.size(size.dp).semantics{contentDescription="拍摄"}.border(2.dp,Color.White,CircleShape).padding(6.dp).scale(innerScale).clip(CircleShape).background(if(shooting)Color(0xff9aabb8)else Color.White).clickable(interactionSource=interaction,indication=LocalIndication.current,enabled=enabled&&(!shooting||countdown>0),onClick=onClick),contentAlignment=Alignment.Center){
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
