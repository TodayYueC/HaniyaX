package dev.haniya.x.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.haniya.x.App
import dev.haniya.x.MainActivity
import dev.haniya.x.host.HostBridgeService
import dev.haniya.x.host.HostMode
import dev.haniya.x.R
import dev.haniya.x.services.ServiceState
import dev.haniya.x.services.ServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app process (and with it every pty session and managed background
 * process) alive while the user is away. It never stops itself while the process lives: leaving a page
 * must not end tasks. Holds an optional wake lock + Wi-Fi lock (settings.wakeLock, and while installing).
 */
class WorkspaceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var foreground = false

    /** Compact workspace summary so log-only updates during installation don't re-post the notification. */
    private data class WsBrief(val ready: Boolean, val installing: Boolean, val step: String?, val permille: Int?, val failed: Boolean) {
        companion object {
            val READY = WsBrief(true, false, null, null, false)
            fun of(ws: WorkspaceState) = when (ws) {
                WorkspaceState.Ready -> READY
                WorkspaceState.NotInstalled -> WsBrief(false, false, null, null, false)
                is WorkspaceState.Failed -> WsBrief(false, false, null, null, true)
                is WorkspaceState.Installing -> WsBrief(false, true, ws.step, ws.progress?.let { (it * 100).toInt() * 10 }, false)
            }
        }
    }

    private data class View(val counts: TaskCounts, val services: Int, val ws: WsBrief, val wake: Boolean)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground(buildNotification(View(TaskCounts(), 0, WsBrief.of(Runtime.workspace.state.value), false)))
        runCatching { startService(Intent(this, HostBridgeService::class.java)) }
            .onFailure { Log.w(TAG, "Cannot start the Android host bridge", it) }
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE_HOST) App.hostBridge.disable()
        // startForegroundService() requires startForeground() after every start request.
        if (!foreground) goForeground(buildNotification(View(TaskCounts(), 0, WsBrief.of(Runtime.workspace.state.value), false)))
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app from recents keeps tasks running; the notification remains the way back in.
    }

    override fun onDestroy() {
        scope.cancel()
        stopService(Intent(this, HostBridgeService::class.java))
        App.hostBridge.disable()
        releaseLocks()
        running = false
        super.onDestroy()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe() {
        val counts: Flow<TaskCounts> = (Runtime.tasks as? TaskManager)?.counts
            ?: Runtime.tasks.tasks.map(TaskRecords::counts)
        val services: Flow<Int> = servicesReady.flatMapLatest { ready ->
            if (!ready) flowOf(0)
            else App.services.services.map { list -> list.count(::isUp) }
        }
        val wake: Flow<Boolean> = App.settings.settings.map { it.wakeLock }
        scope.launch {
            val views = combine(counts, services, Runtime.workspace.state.map { WsBrief.of(it) }, wake) { c, s, ws, w ->
                View(c, s, ws, w)
            }.distinctUntilChanged()
            val hostMode = App.hostBridge.access.map { it.mode }.distinctUntilChanged()
            val pendingHost = App.hostBridge.approvals.map { it.isNotEmpty() }.distinctUntilChanged()
            combine(views, hostMode, pendingHost) { v, mode, pending -> Triple(v, mode, pending) }
                .collect { (v, mode, pending) ->
                    applyLocks(v.wake || v.ws.installing)
                    notify(buildNotification(v, mode, pending))
                }
        }
        // App.services is wired after Runtime in HaniyaApp.onCreate; it is ready by the time a service starts.
        servicesReady.value = runCatching { App.services }.isSuccess
    }

    private fun isUp(s: ServiceState) = s.status == ServiceStatus.Running || s.status == ServiceStatus.Starting ||
        s.status == ServiceStatus.Unhealthy

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, dev.haniya.relief.i18n.I18n.tr("工作区"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = "终端任务与后台服务运行时显示"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(
        v: View,
        hostMode: HostMode = HostMode.Disabled,
        hostPending: Boolean = false,
    ): Notification {
        val ws = v.ws
        val text = when {
            hostPending -> dev.haniya.relief.i18n.I18n.tr("Android 宿主请求等待确认")
            ws.ready -> dev.haniya.relief.i18n.I18n.tr("{0} 个任务运行中 · {1} 个服务", v.counts.running, v.services)
            ws.installing -> dev.haniya.relief.i18n.I18n.tr("正在创建工作区 · {0}", dev.haniya.relief.i18n.I18n.tr(ws.step.orEmpty()))
            ws.failed -> dev.haniya.relief.i18n.I18n.tr("工作区尚未就绪")
            else -> dev.haniya.relief.i18n.I18n.tr("工作区尚未创建")
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // With exactly one running task, tapping goes straight back to it.
            if (hostPending) action = ACTION_HOST_ACCESS
            else if (ws.ready && v.counts.running == 1) v.counts.latestRunningId?.let { putExtra(EXTRA_TASK_ID, it) }
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(dev.haniya.relief.i18n.I18n.tr("HaniyaX 工作区"))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (hostMode != HostMode.Disabled) {
            val disable = PendingIntent.getService(
                this, 1, Intent(this, WorkspaceService::class.java).setAction(ACTION_DISABLE_HOST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, dev.haniya.relief.i18n.I18n.tr("紧急关闭宿主访问"), disable)
        }
        if (ws.installing) {
            val p = ws.permille
            if (p == null) b.setProgress(0, 0, true) else b.setProgress(1000, p, false)
        }
        return b.build()
    }

    private fun goForeground(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            foreground = true
            running = true
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun notify(n: Notification) {
        if (!foreground) { goForeground(n); return }
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n) }
    }

    @SuppressLint("WakelockTimeout")
    private fun applyLocks(hold: Boolean) {
        if (hold) {
            if (wakeLock?.isHeld != true) {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HaniyaX:workspace").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            if (wifiLock?.isHeld != true) {
                @Suppress("DEPRECATION")
                wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HaniyaX:workspace")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        } else {
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "HaniyaX.Service"
        private const val CHANNEL = "workspace"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_HOST_ACCESS = "dev.haniya.x.REVIEW_HOST_ACCESS"
        private const val ACTION_DISABLE_HOST = "dev.haniya.x.DISABLE_HOST_ACCESS"
        const val EXTRA_TASK_ID = "dev.haniya.x.TASK_ID"

        @Volatile private var running = false
        private val servicesReady = MutableStateFlow(false)

        /** Starts the foreground service if it isn't running. Safe to call repeatedly from anywhere. */
        fun ensureStarted(context: Context) {
            if (running) return
            val app = context.applicationContext
            try {
                val i = Intent(app, WorkspaceService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
            } catch (t: Throwable) {
                // e.g. background-start restrictions; the next call from the foreground UI retries.
                Log.w(TAG, "cannot start workspace service", t)
            }
        }
    }
}
