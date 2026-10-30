package app.framelink.camera

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** HyperOS限制测试进程从后台拉起Activity，已授权实机通过shell先启动本App */
internal class ForegroundAcceptanceRule:ExternalResource(){
    override fun before(){
        if(Build.MODEL !in setOf("2410CRP4CC","25113PN0EC"))return
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n app.framelink.camera/.MainActivity")).use{it.readBytes()}
    }
}
