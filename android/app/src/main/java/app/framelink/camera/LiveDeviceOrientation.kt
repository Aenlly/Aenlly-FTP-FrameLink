package app.framelink.camera

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

/** 手机取景保持固定布局，仅将内容转正；方向边界留出滞回，平放时保持上一方向 */
@Stable
internal class LiveDeviceOrientation{
    var rotation by mutableIntStateOf(0)
        private set
    fun update(degrees:Int){
        if(degrees !in 0..359)return
        val current=(((-rotation)%360)+360)%360
        val distance=abs(degrees-current).let{minOf(it,360-it)}
        if(distance<60)return
        val target=((degrees+45)/90*90)%360
        var delta=-target-rotation
        while(delta>180)delta-=360
        while(delta< -180)delta+=360
        rotation+=delta
    }
}

private fun Context.liveActivity():Activity?{
    var current=this
    while(current is ContextWrapper){
        if(current is Activity)return current
        current=current.baseContext
    }
    return current as? Activity
}

@Composable
internal fun rememberLiveDeviceOrientation(paused:Boolean):LiveDeviceOrientation{
    val context=LocalContext.current
    val activity=context.liveActivity()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    // 平板、分屏继续服从实际窗口尺寸，不能锁住整个多窗口工作区
    val phone=LocalConfiguration.current.smallestScreenWidthDp<600&&activity?.isInMultiWindowMode!=true
    val orientation=remember{LiveDeviceOrientation()}
    DisposableEffect(activity,lifecycle,phone,paused){
        if(activity==null||!phone||paused)onDispose{}
        else{
            val previous=activity.requestedOrientation
            val listener=object:OrientationEventListener(context){
                override fun onOrientationChanged(degrees:Int){orientation.update(degrees)}
            }
            val supported=listener.canDetectOrientation()
            if(supported)activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val observer=LifecycleEventObserver{_,event->
                if(event==Lifecycle.Event.ON_RESUME&&listener.canDetectOrientation())listener.enable()
                if(event==Lifecycle.Event.ON_PAUSE)listener.disable()
            }
            lifecycle.addObserver(observer)
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)&&listener.canDetectOrientation())listener.enable()
            onDispose{
                listener.disable();lifecycle.removeObserver(observer)
                if(supported)activity.requestedOrientation=previous
            }
        }
    }
    return orientation
}
