package app.framelink.camera

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** 旋转时更新同一个全屏窗口，保留取景状态并避免返回底层页面的闪烁 */
@Composable
internal fun FullScreenScene(onClose: () -> Unit, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    // Compose1.7关闭平台宽度时会按screenHeightDp固定窗口，旧系统会因此漏掉系统栏区域
    // 保持平台测量，再由窗口明确设置MATCH_PARENT，避免覆盖后又被内容尺寸改回
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view, configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val resize = Runnable { window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                it.setGravity(Gravity.TOP or Gravity.START)
                if (Build.VERSION.SDK_INT >= 28) {
                    it.attributes = it.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    // 某些系统仍先按状态栏/切口移动Dialog窗口；内容层会自行处理安全区域
                    it.attributes = it.attributes.apply { setFitInsetsTypes(0) }
                }
            } }
            view.post(resize)
            onDispose { view.removeCallbacks(resize) }
        }
        Box(Modifier.fillMaxSize().testTag("fullscreen-scene")) { content() }
    }
}
