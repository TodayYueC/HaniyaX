package dev.haniya.x.host

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.haniya.x.App

/** Private Android service, started alongside the foreground workspace service. */
class HostBridgeService : Service() {
    override fun onCreate() {
        super.onCreate()
        App.hostBridge.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        App.hostBridge.stop()
        super.onDestroy()
    }
}
