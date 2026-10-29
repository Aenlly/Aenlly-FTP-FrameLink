package app.framelink.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** 双栏直接复用大图内容，窄窗口仍使用完整照片窗口 */
@Composable
internal fun PhotoViewerScene(embedded:Boolean,onClose:()->Unit,content:@Composable ()->Unit){
    if(embedded){
        BackHandler(onBack=onClose)
        Box(Modifier.fillMaxSize().testTag("embedded-photo-viewer")){content()}
    }
    else FullScreenScene(onClose,content)
}
