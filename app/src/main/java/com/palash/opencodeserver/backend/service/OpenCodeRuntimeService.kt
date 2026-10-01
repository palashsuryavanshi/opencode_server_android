package com.palash.opencodeserver.backend.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.palash.opencodeserver.backend.RealLocalBackend
import com.palash.opencodeserver.backend.core.ServerProcessState
import com.palash.opencodeserver.backend.core.StartPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Spec 36/75/87 - the foreground service that owns server lifetime.
 *
 * Why this exists: the spec requires that the server survives the Activity, and spec 86 says the
 * lifecycle must not depend on an Activity. A foreground service with a persistent notification is
 * the mechanism Android actually honours, so the service is the real owner and the Activity is a
 * client of it.
 *
 * ```text
 * Android App → OpenCodeRuntimeService → RuntimeManager → OpenCode process
 * ```
 *
 * Two deliberate behaviours:
 *  - The notification always shows the real bound endpoint, never a hard-coded port (spec 36).
 *  - Battery impact is disclosed once, in the notification text, rather than hidden (spec 75).
 */
class OpenCodeRuntimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var backend: RealLocalBackend

    override fun onCreate() {
        super.onCreate()
        backend = BackendHolder.get(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    // Spec 89 - explicit stop: disconnect the stream, then the server.
                    backend.stopServer()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification(
                    title = "OpenCode Server",
                    text = "Starting the local OpenCode server..."
                ))
                observeServerState()
            }
        }

        // START_STICKY lets the system restart the service after a kill, which combined with server
        // discovery in the backend means the user still finds their server running.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Spec 37/75 - the user swiped the app away. Stop unless "keep running" was chosen.
        val policy = backend.observeSettings().value.startPolicy
        if (policy != StartPolicy.KEEP_RUNNING) {
            scope.launch { backend.stopServer() }
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /** Spec 36 - the notification text reflects the actual state, not an optimistic one. */
    private fun observeServerState() {
        scope.launch {
            backend.observeServerState().collect { info ->
                when (info.state) {
                    ServerProcessState.RUNNING -> updateNotification(
                        title = "OpenCode Server",
                        text = "Running · ${info.baseUrl ?: "starting"}"
                    )
                    ServerProcessState.STARTING -> updateNotification(
                        title = "OpenCode Server",
                        text = "Starting…"
                    )
                    ServerProcessState.CRASHED -> {
                        updateNotification(
                            title = "OpenCode Server",
                            text = "Stopped unexpectedly · tap to view diagnostics"
                        )
                        // Spec 40 - the service stays alive so diagnostics remain reachable, but the
                        // backend decides whether to restart, and only within its attempt cap.
                    }
                    ServerProcessState.STOPPED, ServerProcessState.STOPPING -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    else -> updateNotification("OpenCode Server", info.state.name.lowercase())
                }
            }
        }
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPending = openIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, OpenCodeRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Spec 36 - Tap to open.
            .setContentIntent(contentPending)
            // Spec 36 - Stop Server action.
            .addAction(0, "Stop Server", stopPending)
            // Spec 75 - battery impact disclosed, not hidden.
            .setSubText("Uses battery while running")
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenCode server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the local OpenCode server status"
            setShowBadge(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "opencode_server"
        const val NOTIFICATION_ID = 4096
        const val ACTION_STOP = "com.palash.opencodeserver.opencode.action.STOP_SERVER"

        /** Spec 36 - start the service as foreground so the process is not killed. */
        fun start(context: Context) {
            val intent = Intent(context, OpenCodeRuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OpenCodeRuntimeService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

/**
 * Spec 87 - the process-wide backend reference.
 *
 * A plain holder rather than a DI framework: there is exactly one runtime per process, and the
 * service, the Activity and any worker all need the same instance.
 */
object BackendHolder {

    @Volatile
    private var instance: RealLocalBackend? = null

    fun get(context: Context): RealLocalBackend = instance ?: synchronized(this) {
        instance ?: RealLocalBackend(context.applicationContext).also { instance = it }
    }

    fun set(backend: RealLocalBackend?) {
        instance = backend
    }
}

/**
 * Spec 37/75 - explain Android's background limits honestly.
 *
 * The spec is explicit: do not promise indefinite background execution. This reports what is
 * actually configured so the UI can qualify the promise rather than overstating it.
 */
object BackgroundExecutionAdvisor {

    data class Status(
        val foregroundServiceAllowed: Boolean,
        val batteryOptimised: Boolean,
        val summary: String,
        val guidance: String?
    )

    fun evaluate(context: Context): Status {
        val powerManager = context.getSystemService(android.os.PowerManager::class.java)
        val batteryAvailable = context.getSystemService(android.os.BatteryManager::class.java) != null

        // A foreground service has always been permitted; the Q+ restriction is on starting one
        // from the background, which the app never does (it starts from an Activity or a Service).
        val foregroundAllowed = true

        // True when the app is NOT exempt from battery optimisation, i.e. Android may kill it.
        val exemptFromBatteryOptimisation =
            runCatching { powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true }
                .getOrDefault(false)

        val guidance = when {
            !exemptFromBatteryOptimisation ->
                "Android may stop the background server to save battery. " +
                    "For the server to keep running, allow unrestricted battery use for this app."
            else ->
                "The server can keep running in the background. It will still be stopped if you " +
                    "force-stop the app or reboot the device."
        }

        return Status(
            foregroundServiceAllowed = foregroundAllowed,
            // Named for what it reports: true means Android may still kill the server.
            batteryOptimised = !exemptFromBatteryOptimisation,
            summary = if (batteryAvailable) {
                "Battery service available"
            } else {
                "Battery service unavailable"
            },
            guidance = guidance
        )
    }
}