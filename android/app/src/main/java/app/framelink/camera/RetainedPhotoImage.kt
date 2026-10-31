package app.framelink.camera

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest

/** 同一资源升级清晰度时保留已解码画面，切换资源后不复用上一张照片 */
@Composable
internal fun RetainedPhotoImage(
    resourceKey:String,
    model:String,
    fullSize:Boolean,
    description:String,
    modifier:Modifier=Modifier,
    imageLoader:ImageLoader=LocalContext.current.imageLoader,
    onLoading:(Boolean)->Unit={},
    onSuccess:(AsyncImagePainter.State.Success)->Unit={},
    onError:(Boolean)->Unit={},
){
    val context=LocalContext.current
    var retained by remember(resourceKey){mutableStateOf<Painter?>(null)}
    // 进入过当前页后不降回小图，避免翻页结束时重复解码与清晰度跳变
    var highResolution by remember(resourceKey){mutableStateOf(fullSize)}
    SideEffect{if(fullSize)highResolution=true}
    val edge=if(fullSize||highResolution)4096 else 1280
    val request=remember(context,model,edge){
        ImageRequest.Builder(context).data(model).size(edge,edge)
            .precision(coil.size.Precision.INEXACT).crossfade(false).build()
    }
    AsyncImage(
        model=request,imageLoader=imageLoader,contentDescription=description,
        contentScale=ContentScale.Fit,modifier=modifier,
        transform={state->when(state){
            is AsyncImagePainter.State.Loading->state.copy(painter=retained?:state.painter)
            is AsyncImagePainter.State.Error->state.copy(painter=retained?:state.painter)
            else->state
        }},
        onState={state->when(state){
            is AsyncImagePainter.State.Loading->onLoading(retained!=null)
            is AsyncImagePainter.State.Success->{retained=state.painter;onSuccess(state)}
            is AsyncImagePainter.State.Error->onError(retained!=null)
            else->Unit
        }},
    )
}
