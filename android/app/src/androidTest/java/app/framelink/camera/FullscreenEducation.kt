package app.framelink.camera

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** 仅确认系统首次全屏教学，不操作其他应用或权限提示 */
internal fun dismissFullscreenEducation() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val info = automation.serviceInfo
    info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    automation.serviceInfo = info
    val end = SystemClock.elapsedRealtime() + 2500
    while (SystemClock.elapsedRealtime() < end) {
        val roots = automation.windows.mapNotNull { it.root }.filter { it.packageName == "com.android.systemui" }
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            val all = mutableListOf<AccessibilityNodeInfo>()
            while (queue.isNotEmpty()) { val node = queue.removeFirst(); all += node; for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add) }
            if (all.none { it.text?.toString()?.contains("full screen", true) == true || it.text?.toString()?.contains("全屏") == true }) continue
            val button = all.firstOrNull { it.text?.toString() in listOf("Got it", "知道了", "我知道了") } ?: continue
            var target: AccessibilityNodeInfo? = button
            while (target != null && !target.isClickable) target = target.parent
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        SystemClock.sleep(100)
    }
}
