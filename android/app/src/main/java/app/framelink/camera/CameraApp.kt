package app.framelink.camera

import android.app.Application
import androidx.room.Room

class CameraApp : Application() {
    lateinit var runtime: CameraRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        go.Seq.setContext(this)
        val database =
            Room.databaseBuilder(this, LocalDatabase::class.java, "framelink.db")
                .addMigrations(LocalDatabase.MIGRATION_1_2, LocalDatabase.MIGRATION_2_3)
                .build()
        runtime = CameraRuntime(this, database, SecureSettings(this))
    }
}
