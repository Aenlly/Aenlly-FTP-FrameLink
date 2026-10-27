package app.framelink.camera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** 用户主动启动的相机连接与接收服务，停止后保留数据库和原片 */
class CaptureService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("camera", "相机连接与传输", NotificationManager.IMPORTANCE_LOW)
            )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, CaptureService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val shutter =
            PendingIntent.getService(
                this,
                2,
                Intent(this, CaptureService::class.java).setAction("SHUTTER"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, "camera")
                .setSmallIcon(R.drawable.ic_aperture)
                .setContentTitle("光屿 · 相机连接运行中")
                .setContentText("照片与任务会保留，可打开App查看真实连接状态")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "蓝牙快门", shutter)
                .addAction(0, "停止连接", stop)
                .build()
        ServiceCompat.startForeground(
            this,
            1001,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = (application as CameraApp).runtime
        when (intent?.action) {
            "STOP" -> {
                runtime.stopAll()
                stopSelf()
            }
            "SHUTTER" -> runtime.bluetooth.shutter()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as CameraApp).runtime.stopAll()
        stopSelf()
    }

    override fun onDestroy() {
        (application as CameraApp).runtime.stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
