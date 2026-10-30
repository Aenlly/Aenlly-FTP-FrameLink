package app.framelink.camera

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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

private data class LiveToolItem(val title:String,val value:String,val selected:Boolean=false,val enabled:Boolean=true,val onClick:()->Unit)

@Composable
private fun LiveToolCell(item:LiveToolItem,modifier:Modifier){
    val caption=if(item.title=="对焦模式")item.value.replace("单次自动对焦","单次对焦").replace("连续自动对焦","连续对焦")else item.value
    Column(modifier.testTag("live-tool-cell").heightIn(min=52.dp)
        .clickable(enabled=item.enabled,onClick=item.onClick).semantics{contentDescription=item.title;stateDescription=item.value}
        .padding(horizontal=2.dp,vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(item.title,fontSize=11.sp,lineHeight=14.sp,color=if(item.selected)LiveAccent else Color.White,maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        Text(caption,fontSize=10.sp,lineHeight=13.sp,color=Color.White.copy(alpha=.68f),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** 小控件保留原玻璃透亮度，文字菜单只加轻底色，不改变其他页面材质 */
@Composable
private fun LiveGlass(state:HazeState,modifier:Modifier=Modifier,light:Boolean=false,menu:Boolean=false,radius:androidx.compose.ui.unit.Dp=22.dp,content:@Composable BoxScope.()->Unit){
    GlassSurface(state,modifier,radius=radius,dark=!light){
        if(menu)Box(Modifier.matchParentSize().background(Color(0xff14191f).copy(alpha=.20f)))
        content()
    }
}

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
    val libraryPhotos by runtime.photos.collectAsStateWithLifecycle(emptyList())
    val albumImage=latest?.displayUri?.takeIf{it.isNotBlank()}?:libraryPhotos.firstOrNull{it.displayUri.isNotBlank()}?.displayUri
    val notice by runtime.notice.collectAsStateWithLifecycle()
    val landscape=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    val scope=rememberCoroutineScope()
    val glass=remember{HazeState()}
    var controls by rememberSaveable{mutableStateOf(true)}
    var tools by rememberSaveable{mutableStateOf(false)}
    var assistants by rememberSaveable{mutableStateOf(false)}
    var professional by rememberSaveable{mutableStateOf(false)}
    var showHistogram by rememberSaveable{mutableStateOf(false)}
    var headerLight by remember{mutableStateOf(false)}
    var info by rememberSaveable{mutableStateOf(false)}
    var grid by rememberSaveable{mutableStateOf(false)}
    var zebra by rememberSaveable{mutableStateOf(false)}
    var peak by rememberSaveable{mutableStateOf(false)}
    var timer by rememberSaveable{mutableIntStateOf(0)}
    var shootingSettings by rememberSaveable{mutableStateOf(false)}
    var lookUnavailable by remember{mutableStateOf(false)}
    var lookLoading by remember{mutableStateOf(false)}
    var lookStatus by remember{mutableStateOf("")}
    var autoImport by remember(camera.info?.id){mutableStateOf(camera.info?.canAutoImport!=false&&runtime.settings.get("capture.auto_import")=="true")}
    var singleShot by remember{mutableStateOf(runtime.settings.get("capture.single")!="false")}
    var openingGallery by remember{mutableStateOf(false)}
    var viewport by remember{mutableStateOf(IntSize.Zero)}
    var focusPoint by remember{mutableStateOf<Offset?>(null)}
    var histogram by remember{mutableStateOf(IntArray(64))}
    var dimensions by remember{mutableStateOf(IntSize.Zero)}
    var rendererError by remember{mutableStateOf("")}
    var presented by remember{mutableStateOf(false)}
    var rendererKey by remember{mutableIntStateOf(0)}
    var propertyCode by rememberSaveable{mutableStateOf<Int?>(null)}
    val property=properties.firstOrNull{it.code==propertyCode}
    LaunchedEffect(paused){if(paused){runtime.cancelCountdown();runtime.stopLive()}else runtime.startLive()}
    DisposableEffect(Unit){onDispose{runtime.cancelCountdown();runtime.stopLive()}}
    fun hide(){controls=false;tools=false;propertyCode=null}
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
        if(property!=null){propertyCode=null;return}
        focus(point)
    }
    LaunchedEffect(focusPoint,focusing){if(focusPoint!=null&&!focusing){kotlinx.coroutines.delay(1600);focusPoint=null}}
    fun cycleTimer(){timer=when(timer){0->3;3->10;else->0}}
    suspend fun applyParameter(item:CameraProperty,value:Long):Long{
        runtime.setProperty(item,value).join()
        return runtime.properties.value.firstOrNull{it.code==item.code}?.value?:item.value
    }
    @Composable fun Album(modifier:Modifier=Modifier,size:Int=48){
        Box(modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=.08f)).clickable(onClick=::openAlbum),contentAlignment=Alignment.Center){
            if(albumImage!=null)AsyncImage(albumImage,"查看相机照片",Modifier.fillMaxSize().testTag("live-album-thumbnail"),contentScale=ContentScale.Crop)
            else Icon(Icons.Outlined.PhotoLibrary,"查看相机照片",tint=Color.White,modifier=Modifier.size(26.dp))
        }
    }
    @Composable fun Modes(modifier:Modifier=Modifier){
            Row(modifier.padding(4.dp),horizontalArrangement=Arrangement.Center){
                listOf(false to "拍照",true to "专业").forEach{(pro,title)->
                    Column(Modifier.width(56.dp).heightIn(min=44.dp)
                        .clickable{professional=pro;propertyCode=null}.semantics{contentDescription=title+"模式"},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                        Text(title,fontSize=12.sp,color=if(professional==pro)LiveAccent else Color.White.copy(alpha=.7f))
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.width(20.dp).height(1.dp).background(if(professional==pro)LiveAccent else Color.Transparent))
                    }
                }
            }
    }
    @Composable fun Quick(vertical:Boolean,modifier:Modifier=Modifier){
        @Composable fun Item(item:CameraProperty,modifier:Modifier){
            Column(modifier.clip(RoundedCornerShape(12.dp)).background(if(property?.code==item.code)Color.White.copy(alpha=.12f)else Color.Transparent).clickable(enabled=updating==null&&CameraPropertyText.editable(item)){propertyCode=item.code}.semantics{contentDescription="调整"+CameraPropertyText.name(item)}
                .padding(horizontal=4.dp,vertical=if(vertical)2.dp else 4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                Text(if(updating==item.code)"应用中…" else CameraPropertyText.quickValue(item),Modifier.testTag("live-quick-value"),fontSize=13.sp,lineHeight=16.sp,maxLines=1,color=if(property?.code==item.code)LiveAccent else Color.White)
                Text(CameraPropertyText.quickCode(item),fontSize=10.sp,lineHeight=12.sp,color=Color.White.copy(alpha=.65f),maxLines=1)
            }
        }
        GlassSurface(glass,modifier,radius=22.dp,dark=true){
        if(vertical)Column(Modifier.fillMaxSize().testTag("live-quick-properties"),horizontalAlignment=Alignment.CenterHorizontally){
            CameraPropertyText.quick(properties).chunked(3).forEach{row->Row(Modifier.weight(1f).fillMaxWidth()){
                row.forEach{Item(it,Modifier.weight(1f).fillMaxHeight())}
                repeat(3-row.size){Spacer(Modifier.weight(1f))}
            }}
        }else Row(Modifier.fillMaxSize().testTag("live-quick-properties")){
            CameraPropertyText.quick(properties).forEach{Item(it,Modifier.weight(1f).fillMaxHeight())}
        }
        }
    }
    @Composable fun Parameter(vertical:Boolean,modifier:Modifier=Modifier){
        property?.let{item->
            val current=properties.firstOrNull{it.code==item.code}?:item
            val adjustable=updating==null&&CameraPropertyText.editable(current)
            var draftLabel by remember(current.code){mutableStateOf<String?>(null)}
            val numeric=current.code in CameraPropertyText.exposureOrder
            val modernLook=current.code==CameraPropertyText.CREATIVE_LOOK
            val choiceColumns=if(modernLook&&LocalDensity.current.fontScale<=1.3f)4 else 2
            LiveGlass(glass,modifier.testTag("live-property-picker").pointerInput(Unit){detectTapGestures(onTap={})},radius=22.dp,menu=modernLook){
                if(numeric&&vertical)Column(Modifier.fillMaxSize().padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text(CameraPropertyText.name(current),fontSize=10.sp,color=Color.White.copy(alpha=.7f))
                    Text((draftLabel?:CameraPropertyText.quickValue(current)).removePrefix("ISO ").removeSuffix(" 秒").removeSuffix(" EV"),fontSize=12.sp,maxLines=1,color=LiveAccent,modifier=Modifier.padding(vertical=4.dp))
                    CameraParameterRuler(current,true,adjustable,{applyParameter(current,it)},Modifier.weight(1f).fillMaxWidth(),onPreview={draftLabel=it})
                    IconButton(onClick={propertyCode=null}){Icon(Icons.Outlined.Close,"关闭参数",tint=Color.White,modifier=Modifier.size(18.dp))}
                }else if(numeric)Row(Modifier.fillMaxSize().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(CameraPropertyText.name(current),fontSize=11.sp,color=Color.White.copy(alpha=.8f),maxLines=1,modifier=Modifier.width(48.dp))
                    CameraParameterRuler(current,false,adjustable,{applyParameter(current,it)},Modifier.weight(1f).height(44.dp),onPreview={draftLabel=it})
                    Text((draftLabel?:CameraPropertyText.quickValue(current)).removePrefix("ISO ").removeSuffix(" 秒").removeSuffix(" EV"),fontSize=13.sp,color=LiveAccent,maxLines=1,modifier=Modifier.width(65.dp))
                    IconButton(onClick={propertyCode=null},modifier=Modifier.size(40.dp)){Icon(Icons.Outlined.Close,"关闭参数",tint=Color.White,modifier=Modifier.size(18.dp))}
                }else Column(Modifier.fillMaxSize().padding(10.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text(CameraPropertyText.name(current)+(if(modernLook)" · "+(CameraPropertyText.label(current)?:"机身设置")else""),color=Color.White,fontSize=14.sp,modifier=Modifier.weight(1f))
                        IconButton(onClick={propertyCode=null}){Icon(Icons.Outlined.Close,"关闭参数",tint=Color.White)}
                    }
                    if(modernLook&&!current.writable)Text("当前连接仅可查看",fontSize=11.sp,color=Color.White.copy(alpha=.8f))
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        items(CameraPropertyText.choices(current).chunked(choiceColumns)){row->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                row.forEach{choice->TextButton(enabled=adjustable,onClick={scope.launch{applyParameter(current,choice.value)}},modifier=Modifier.weight(1f).heightIn(min=44.dp).background(if(choice.value==current.value)Color.White.copy(alpha=.15f)else if(modernLook)Color.Transparent else Color.Black.copy(alpha=.12f),RoundedCornerShape(12.dp))){
                                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)){
                                        Text(choice.label,color=if(choice.value==current.value)LiveAccent else Color.White,fontSize=12.sp,lineHeight=16.sp)
                                        if(modernLook)CameraPropertyText.creativeLookName(choice.value)?.let{name->
                                            Text(name,color=if(choice.value==current.value)LiveAccent.copy(alpha=.85f)else Color.White.copy(alpha=.78f),fontSize=10.sp,lineHeight=13.sp,maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                    }
                                }}
                                repeat(choiceColumns-row.size){Spacer(Modifier.weight(1f))}
                            }
                        }
                        if(CameraPropertyText.choices(current).size<current.choices.size)item{Text("其他机身选项暂未适配，请在相机调整。",fontSize=11.sp,color=Color.White.copy(alpha=.65f))}
                    }
                }
            }
        }
    }
    fun openLook(){
        if(lookLoading)return
        tools=false
        lookUnavailable=false
        lookLoading=true
        scope.launch{
            try{
                runtime.refreshCameraProperties().join()
                val actual=runtime.properties.value.firstOrNull{it.code==CameraPropertyText.CREATIVE_LOOK&&CameraPropertyText.label(it)!=null}
                if(actual!=null){lookUnavailable=false;propertyCode=actual.code}
                else{
                    val result=org.json.JSONObject(runtime.native.creativeLookDiagnostics())
                    val descriptors=result.optJSONArray("candidateDescriptors")
                    val modern=descriptors?.let{rows->(0 until rows.length()).map{rows.getJSONObject(it)}.firstOrNull{it.optInt("code")==CameraPropertyText.CREATIVE_LOOK}}
                    lookStatus=if(modern!=null&&modern.optInt("enabled")!=1)"机身当前将创意外观标为不可调。图片配置文件非关闭或Log拍摄等模式可能限制此功能，请在机身检查；本次未发送设置。"
                        else if(descriptors==null||descriptors.length()==0)"相机已连接，但应用暂未取得创意外观选项。可以重新读取；如仍无选项，请先在机身调整。"
                        else "已读取机身设置，但暂未识别当前风格。本次未作更改，可以重新读取。"
                    lookUnavailable=true
                }
            }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception){lookStatus="创意外观设置未读取完成，请检查相机连接后重试。";lookUnavailable=true}
            finally{lookLoading=false}
        }
    }
    @Composable fun Look(size:Int=48){
        val actual=properties.firstOrNull{it.code==CameraPropertyText.CREATIVE_LOOK}
        val style=actual?.let{CameraPropertyText.label(it)}
        IconButton(onClick=::openLook,enabled=!lookLoading,modifier=Modifier.size(size.dp).background(Color.Black.copy(alpha=.35f),CircleShape).semantics{contentDescription="创意外观";stateDescription=style?:"读取机身设置"}){
            if(lookLoading)CircularProgressIndicator(Modifier.size(18.dp),color=Color.White,strokeWidth=1.5.dp)
            else if(style!=null)Text(style,color=Color.White,fontSize=13.sp)
            else Icon(Icons.Outlined.Palette,"创意外观",tint=Color.White,modifier=Modifier.size(if(size<40)20.dp else 25.dp))
        }
    }
    @Composable fun HeaderActions(modifier:Modifier=Modifier){
        LiveGlass(glass,modifier.testTag("live-header-actions"),radius=22.dp){
            Row{
                IconButton(onClick=::cycleTimer,modifier=Modifier.size(44.dp)){
                    if(timer==0)Icon(Icons.Outlined.Timer,"无延时",tint=Color.White,modifier=Modifier.size(19.dp))
                    else Text(timer.toString(),fontSize=14.sp,color=LiveAccent,modifier=Modifier.semantics{contentDescription="$timer 秒延时"})
                }
                IconButton(onClick={tools=!tools;propertyCode=null},modifier=Modifier.size(44.dp)){CameraMoreIcon()}
            }
        }
    }
    @Composable fun Header(modifier:Modifier=Modifier,actions:Boolean=true,light:Boolean=false){
        val ink=if(light)Color(0xff151a20)else Color.White
        Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            LiveGlass(glass,light=light){IconButton(onClick=onClose,modifier=Modifier.size(44.dp)){Icon(Icons.Outlined.ArrowBack,"返回相机",tint=ink,modifier=Modifier.size(21.dp))}}
            LiveGlass(glass,Modifier.testTag("live-status-group"),light=light){Row(verticalAlignment=Alignment.CenterVertically){
            properties.firstOrNull{it.code==0x500E&&CameraPropertyText.label(it)!=null}?.let{mode->
                Box(Modifier.size(44.dp).clickable(enabled=CameraPropertyText.editable(mode)){propertyCode=mode.code}.semantics{contentDescription="曝光模式"},contentAlignment=Alignment.Center){
                    Text(when(mode.value){1L->"M";2L->"P";3L->"A";4L->"S";else->CameraPropertyText.compact(mode)},color=ink,fontSize=12.sp)
                }
                VerticalDivider(Modifier.height(16.dp),color=ink.copy(alpha=.2f))
            }
            Text(camera.info?.displayName()?:"相机",fontSize=11.sp,lineHeight=14.sp,color=ink,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=112.dp).heightIn(min=44.dp).padding(horizontal=10.dp,vertical=14.dp))
            }}
            if(actions){Spacer(Modifier.weight(1f));HeaderActions()}
        }
    }
    DarkScene{
        FullScreenScene({when{tools->tools=false;property!=null->propertyCode=null;else->onClose()}}){
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
                val railWidth=minOf(196.dp,cameraSpace-16.dp).coerceAtLeast(96.dp)
                val wideRail=railWidth>=164.dp
                val clusterHeight=if(wideRail)148.dp else 246.dp
                val quickHeight=((sceneHeight-clusterHeight)/2+24.dp-82.dp).coerceIn(0.dp,132.dp)
                val quickMinimumHeight=56.dp*LocalDensity.current.fontScale+8.dp
                val dockHeight=if(professional)220.dp else 162.dp
                val panelTop=if(maxHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()<240.dp)12.dp else 66.dp
                val panelEnd=if(landscape)cameraSpace+12.dp else 14.dp
                val panelHeight=(maxHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()-panelTop-14.dp).coerceAtLeast(120.dp)
                val panelWidth=minOf(280.dp,maxWidth-panelEnd-14.dp-safe.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)-safe.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
                // 普通字号保持四列；大字或窄窗口增加单项宽度，保留至少44dp触控区域
                val toolColumns=((panelWidth-20.dp)/(52.dp*LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1,4)
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
                                view.histogram=if(controls&&showHistogram&&!tools){bins->histogram=bins}else null
                                // 深浅控件带滞回，避免随画面轻微变化闪烁
                                view.headerBrightness=if(controls&&landscape){mean->if(mean>.64f)headerLight=true else if(mean<.52f)headerLight=false}else null
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
                    }
                }
                // HUD不进入hazeSource，菜单展开期间暂时隐藏，保留用户的直方图开关
                if(controls&&showHistogram&&!tools)Box(Modifier.fillMaxSize().padding(portraitFrame)){
                    Canvas(Modifier.align(if(landscape)Alignment.BottomStart else Alignment.TopEnd).padding(if(landscape)safe else PaddingValues(top=safe.calculateTopPadding()+64.dp)).padding(12.dp).size(96.dp,44.dp).testTag("live-histogram")
                        .semantics{contentDescription="预览亮度直方图"}.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha=.48f)).padding(6.dp)){
                        val highest=(histogram.maxOrNull()?:1).coerceAtLeast(1)
                        histogram.forEachIndexed{index,value->val x=index*size.width/histogram.size;drawLine(Color.White.copy(alpha=.85f),Offset(x,size.height),Offset(x,size.height-value.toFloat()/highest*size.height),size.width/histogram.size)}
                        drawLine(Color.White.copy(alpha=.28f),Offset(0f,size.height),Offset(size.width,size.height),1f)
                    }
                }
                if(!controls)IconButton(onClick={controls=true},modifier=Modifier.align(Alignment.TopEnd).padding(safe).padding(12.dp).background(Color.Black.copy(alpha=.3f),CircleShape)
                    .semantics{stateDescription=if(presented)"取景画面已显示" else "等待取景画面"}){
                    Icon(Icons.Outlined.Tune,"显示取景控制",tint=Color.White)
                }
                val inlineParameter=property?.code in CameraPropertyText.exposureOrder
                if(controls&&!landscape){
                    Header(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(safe).padding(horizontal=14.dp,vertical=10.dp))
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(safe).padding(horizontal=16.dp,vertical=12.dp).testTag("live-portrait-dock"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                        if(professional)Quick(false,Modifier.fillMaxWidth().height(54.dp))
                        Row(Modifier.fillMaxWidth().height(82.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                            Album()
                            Shutter(76,shooting,countdown,camera.info?.canCapture==true&&!receiving,::shoot)
                            Look()
                        }
                        Modes()
                    }
                    if(property!=null&&!tools)Parameter(false,Modifier.align(Alignment.BottomCenter).padding(safe).padding(horizontal=14.dp).padding(bottom=dockHeight+12.dp).widthIn(max=360.dp).fillMaxWidth().height(if(inlineParameter)56.dp else 220.dp))
                }
                if(controls&&landscape){
                    Header(Modifier.align(Alignment.TopStart).padding(safe).padding(start=12.dp,top=10.dp,end=cameraSpace+12.dp),actions=false,light=headerLight)
                    Box(Modifier.align(Alignment.CenterEnd).padding(safe).padding(horizontal=8.dp).width(railWidth).fillMaxHeight().testTag("live-shutter-rail")){
                        HeaderActions(Modifier.align(Alignment.TopCenter).padding(top=10.dp))
                        if(professional&&quickHeight>=quickMinimumHeight)Quick(true,Modifier.align(Alignment.TopCenter).padding(top=70.dp).fillMaxWidth().height(quickHeight))
                        Column(Modifier.align(Alignment.Center).offset(y=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
                            if(wideRail)Row(Modifier.fillMaxWidth().height(88.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){
                                Album(size=44)
                                Shutter(68,shooting,countdown,camera.info?.canCapture==true&&!receiving,::shoot)
                                Look(44)
                            }else Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                                Album(size=44);Shutter(64,shooting,countdown,camera.info?.canCapture==true&&!receiving,::shoot);Look(44)
                            }
                            Modes()
                        }
                    }
                    Box(Modifier.fillMaxSize().testTag("live-landscape-controls")){
                        if(property!=null&&!tools){
                            if(inlineParameter&&cameraSpace-railWidth>=72.dp)Parameter(true,Modifier.align(Alignment.CenterEnd).padding(safe).padding(top=77.dp,bottom=24.dp,end=cameraSpace-64.dp).width(64.dp).fillMaxHeight())
                            else Parameter(false,Modifier.align(if(inlineParameter)Alignment.BottomStart else Alignment.TopEnd).padding(safe).padding(top=if(inlineParameter)0.dp else 66.dp,end=if(inlineParameter)12.dp else cameraSpace+12.dp,bottom=12.dp,start=if(inlineParameter)12.dp else 0.dp).width(if(inlineParameter)280.dp else 235.dp).heightIn(max=sceneHeight-safe.calculateTopPadding()-safe.calculateBottomPadding()-82.dp).height(if(inlineParameter)56.dp else 240.dp))
                        }
                    }
                }
                AnimatedVisibility(visible=controls&&tools,
                    modifier=Modifier.align(Alignment.TopEnd).padding(safe).padding(start=14.dp,end=panelEnd,top=panelTop,bottom=14.dp),
                    enter=fadeIn(tween(160))+scaleIn(tween(180),initialScale=.96f),exit=fadeOut(tween(120))+scaleOut(tween(120),targetScale=.98f)){
                    LiveGlass(glass,Modifier.width(panelWidth).heightIn(max=panelHeight).testTag("live-tools-panel").pointerInput(Unit){detectTapGestures(onTap={})},menu=true,radius=23.dp){
                        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp)){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text("取景设置",Modifier.weight(1f),fontSize=13.sp,color=Color.White)
                                IconButton(onClick=::hide,modifier=Modifier.size(44.dp)){Icon(Icons.Outlined.Fullscreen,"沉浸取景",tint=Color.White,modifier=Modifier.size(18.dp))}
                                IconButton(onClick={tools=false},modifier=Modifier.size(44.dp)){Icon(Icons.Outlined.Close,"收起工具",tint=Color.White,modifier=Modifier.size(18.dp))}
                            }
                            Row(Modifier.fillMaxWidth().padding(bottom=4.dp)){
                                listOf(false to "机身参数",true to "预览辅助").forEach{(tab,title)->
                                    Column(Modifier.weight(1f).heightIn(min=44.dp)
                                        .clickable{assistants=tab}.padding(horizontal=4.dp,vertical=8.dp).semantics{stateDescription=if(assistants==tab)"已选中"else"未选中"},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                                        Text(title,color=if(assistants==tab)LiveAccent else Color.White.copy(alpha=.65f),fontSize=11.sp)
                                        Spacer(Modifier.height(6.dp))
                                        Box(Modifier.width(24.dp).height(1.dp).background(if(assistants==tab)LiveAccent else Color.Transparent))
                                    }
                                }
                            }
                            val toolItems=if(assistants)listOf(
                                LiveToolItem("构图网格",if(grid)"开"else"关",selected=grid){grid=!grid},
                                LiveToolItem("直方图",if(showHistogram)"开"else"关",selected=showHistogram){showHistogram=!showHistogram},
                                LiveToolItem("峰值对焦",if(peak)"开"else"关",selected=peak){peak=!peak},
                                LiveToolItem("过曝提示",if(zebra)"开"else"关",selected=zebra){zebra=!zebra},
                                LiveToolItem("延时快门",if(timer>0)"$timer 秒"else"关闭",selected=timer>0){cycleTimer()},
                                LiveToolItem("拍摄设置","打开"){tools=false;shootingSettings=true}
                            )else CameraPropertyText.controls(properties).map{item->
                                LiveToolItem(CameraPropertyText.name(item).let{if(it=="照片保存位置")"保存位置"else it},if(updating==item.code)"应用中…"else CameraPropertyText.compact(item),enabled=updating==null&&CameraPropertyText.editable(item)){
                                    propertyCode=item.code;tools=false
                                }
                            }+listOfNotNull(if(properties.none{it.code==CameraPropertyText.CREATIVE_LOOK&&CameraPropertyText.label(it)!=null})LiveToolItem("创意外观","读取设置",enabled=!lookLoading){openLook()}else null,
                                LiveToolItem("连接信息","查看"){tools=false;info=true})
                            HorizontalDivider(color=Color.White.copy(alpha=.12f))
                            LazyColumn(Modifier.heightIn(max=(panelHeight-110.dp).coerceAtLeast(44.dp)).testTag("live-properties")){
                                items(toolItems.chunked(toolColumns)){row->
                                    Column{
                                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                                            row.forEach{item->LiveToolCell(item,Modifier.weight(1f))}
                                            repeat(toolColumns-row.size){Spacer(Modifier.weight(1f))}
                                        }
                                        if(row.last()!=toolItems.last())HorizontalDivider(color=Color.White.copy(alpha=.10f))
                                    }
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
                    Column(Modifier.weight(1f)){Text("拍后自动导入");Text(if(camera.info?.canAutoImport==false)"此连接不提供拍后队列，请从相机图库选择导入"else"关闭时仅存相机；开启时同步相机与手机。机身拒绝更改时请先在相机调整",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Switch(autoImport,{next->scope.launch{if(runtime.setCaptureImport(next))autoImport=next}},modifier=Modifier.semantics{contentDescription="拍后自动导入"},enabled=camera.info?.canAutoImport!=false&&!shooting&&!receiving)
                }
                TextButton(onClick={shootingSettings=false;runtime.receivePendingCaptures()},enabled=camera.info?.canAutoImport!=false&&!shooting&&!receiving){Text("接收机身待传文件（不拍照）")}
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
        if(lookUnavailable)AlertDialog(onDismissRequest={lookUnavailable=false},title={Text("创意外观")},text={Text(lookStatus)},
            confirmButton={TextButton(onClick=::openLook,enabled=!lookLoading){Text(if(lookLoading)"正在读取"else"重新读取")}},
            dismissButton={TextButton(onClick={lookUnavailable=false}){Text("完成")}})
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
