package app.framelink.camera

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Android17将相机连接、FTP收发等局域网访问纳入同一运行时权限 */
internal object LocalNetworkAccess {
    const val permission = "android.permission.ACCESS_LOCAL_NETWORK"
    fun granted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
