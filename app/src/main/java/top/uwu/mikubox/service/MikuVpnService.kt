package top.uwu.mikubox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoConfigStore
import top.uwu.mikubox.core.CoreOverrides
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.core.MihomoDnsSettings
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoTrafficStore
import top.uwu.mikubox.ui.MainActivity

/**
 * The MikuBox VPN service.
 *
 * Full lifecycle: consent → foreground notification → TUN establishment →
 * hand the TUN file descriptor to the Mihomo core → stop. The TUN installs a
 * catch-all route (0.0.0.0/0 and ::/0) so all traffic is captured and routed
 * through the core, minus this app's own UID to avoid feeding Mihomo's own
 * sockets back into the tunnel.
 */
class MikuVpnService : VpnService() {

    /**
     * The tunnel descriptor, owned by the core once [MihomoCore.start] accepted
     * it. [MihomoCore.NO_TUN] while nothing is connected.
     */
    @Volatile
    private var tunFd: Int = MihomoCore.NO_TUN
    private var lastTunError: Throwable? = null

    /** Core startup (config parse + GeoSite loads) can take seconds; keep it off the main thread. */
    private val startExecutor = CoreServiceRuntime.executor
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var starting = false

    private val checkpointHandler = Handler(Looper.getMainLooper())

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val wakeLockLock = Any()

    /**
     * Keeps the CPU awake while the tunnel runs, when the user asked for it: a
     * suspended device stops forwarding traffic until something wakes it again.
     * Both accessors take the same lock because start runs on the executor while
     * settings changes call in from the main thread.
     */
    private fun acquireWakeLock() {
        synchronized(wakeLockLock) {
            if (CoreOverrides.wakeLock(this) != CoreOverrides.ON || wakeLock != null) return
            val manager = getSystemService(android.os.PowerManager::class.java) ?: return
            wakeLock = runCatching {
                manager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:vpn",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockLock) {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                restartVpn()
                return START_STICKY
            }

            ACTION_REFRESH -> {
                // Settings the running service can adopt without touching the core.
                applyRuntimeSettings()
                return START_STICKY
            }
        }
        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        // System or another VPN app revoked our permission.
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn() {
        if (starting || tunFd != MihomoCore.NO_TUN) return
        starting = true
        val request = generation.incrementAndGet()
        MikuProxyService.stop(this)
        lastTunError = null
        // The foreground notification must appear promptly; the heavy work runs after it.
        startForegroundNotification()
        startExecutor.execute {
            if (generation.get() != request) return@execute
            // Tracked so a failure between establish() and the core taking over
            // can still close the detached descriptor instead of leaking it.
            var fd: Int = MihomoCore.NO_TUN
            var coreAccepted = false
            try {
                fd = establishTun() ?: run {
                    reportStartFailure(lastTunError?.message.orEmpty())
                    checkpointHandler.post { if (generation.get() == request) stopVpn() }
                    return@execute
                }
                val profile = MihomoProfileStore.selected(this)
                val config = profile?.config ?: MihomoConfigStore.activeConfig(this)
                val startResult = CoreServiceRuntime.start(this, { MihomoTrafficStore.finish(this) }) { MihomoCore.start(
                    this,
                    config,
                    fd,
                    MihomoDnsSettings.effectiveOverride(this, config),
                    MihomoCoreSettings.overridesJson(this, MihomoVpnSettings.mtu(this)),
                ) }
                if (startResult.isFailure) {
                    closeDetachedTun(fd)
                    val failure = startResult.exceptionOrNull()
                    // The message alone is not enough to place a Kotlin failure;
                    // keep the trace in logcat for the diagnostics export.
                    runCatching { Log.e(TAG, "core start threw", failure) }
                    reportStartFailure(failure?.message.orEmpty())
                    checkpointHandler.post { if (generation.get() == request) stopVpn() }
                    return@execute
                }
                coreAccepted = true
                // Native startup cannot be interrupted. Its result may already be obsolete.
                if (generation.get() != request) {
                    CoreServiceRuntime.stop(this)
                    return@execute
                }
                MihomoTrafficStore.begin(profile)
                checkpointHandler.post {
                    if (generation.get() != request) return@post
                    starting = false
                    tunFd = fd
                    running = true
                    startedAtMillis = System.currentTimeMillis()
                    checkpointHandler.post(trafficCheckpoint)
                    acquireWakeLock()
                }
            } catch (error: Throwable) {
                if (!coreAccepted && fd != MihomoCore.NO_TUN) closeDetachedTun(fd)
                reportStartFailure(error.message.orEmpty())
                checkpointHandler.post { if (generation.get() == request) stopVpn() }
            }
        }
    }

    /**
     * Folds the traffic measured so far into the profile's stored totals while
     * the tunnel runs. Without it a session that ends abruptly — the process
     * being killed, or an update while connected — would lose everything it
     * moved, because only a clean stop used to write the numbers out.
     */
    private val trafficCheckpoint = object : Runnable {
        override fun run() {
            if (!running) return
            // JNI reads plus a prefs commit stay off the main thread; the
            // executor only runs start/stop around these while the tunnel is up,
            // so checkpoints and the final fold still happen in order.
            runCatching {
                val request = generation.get()
                startExecutor.execute {
                    if (generation.get() == request) MihomoTrafficStore.checkpoint(this@MikuVpnService)
                }
            }
            checkpointHandler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }

    private fun reportStartFailure(detail: String) {
        val message = detail.ifBlank { getString(R.string.mihomo_start_failed) }
        Log.e(TAG, "VPN start failed: $message")
        sendBroadcast(
            Intent(ACTION_VPN_START_FAILED)
                .setPackage(packageName)
                .putExtra(EXTRA_FAILURE_DETAIL, message),
        )
    }

    /**
     * Applies changed core settings by restarting the tunnel in place.
     *
     * The core reads its configuration at start, so a switch that changes it only
     * takes effect after a reload. The interface is recreated and the foreground
     * notification stays up, so the user sees a short interruption instead of
     * having to reconnect by hand.
     */
    private fun restartVpn() {
        if (tunFd == MihomoCore.NO_TUN) return
        Log.i(TAG, "restarting the core for changed settings")
        stopCore()
        // Both calls run on the same executor, so the start can never overtake the
        // stop and the old core is always shut down first.
        startVpn()
    }

    /** Re-reads the settings the tunnel can honour while it runs. */
    private fun applyRuntimeSettings() {
        if (tunFd == MihomoCore.NO_TUN) return
        releaseWakeLock()
        acquireWakeLock()
    }

    /** Tears the tunnel and the core down, leaving the service itself alive. */
    private fun stopCore() {
        generation.incrementAndGet()
        starting = false
        running = false
        startedAtMillis = 0L
        checkpointHandler.removeCallbacks(trafficCheckpoint)
        releaseWakeLock()
        // The core owns the tunnel descriptor and closes it while shutting down,
        // so this only drops the reference. Folding the traffic has to happen
        // before the core stops (it reads the live counters).
        tunFd = MihomoCore.NO_TUN
        // The process-wide queue outlives services; ownership prevents a late
        // onDestroy from shutting down a newer service's core.
        runCatching {
            startExecutor.execute {
                runCatching { CoreServiceRuntime.stop(this) }
            }
        }
    }

    private fun stopVpn() {
        stopCore()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * Establishes the tunnel and hands its descriptor to the core.
     *
     * [ParcelFileDescriptor.detachFd] is what makes the handover safe: the core
     * wraps the descriptor in a Go file object and closes it when the tunnel
     * stops, and Go closes it with a raw syscall. Keeping Java as an owner as
     * well meant both sides closed the same descriptor; the second close landed
     * on an fd number the GPU driver had already been given, and bionic aborted
     * the process with an fdsan error right when the user disconnected.
     */
    private fun establishTun(): Int? {
        val appMode = MihomoVpnSettings.appMode(this)
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MihomoVpnSettings.mtu(this))
            .addAddress(PRIVATE_VLAN4_CLIENT, PRIVATE_VLAN4_PREFIX)
            // Without a resolver of its own Android answers from the underlying
            // network (or refuses to answer at all, depending on the vendor), so
            // point every app at an address inside the tunnel. The route below
            // carries it into the core, which hijacks port 53.
            .addDnsServer(PRIVATE_VLAN4_DNS)
            .addRoute("0.0.0.0", 0)
            .allowBypass()
        if (MihomoCoreSettings.ipv6(this)) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, PRIVATE_VLAN6_PREFIX)
            builder.addDnsServer(PRIVATE_VLAN6_DNS)
            builder.addRoute("::", 0)
        }
        // The core shares this application's UID. Excluding it prevents
        // Mihomo's own sockets from being fed back into the VPN TUN. In
        // allow-list mode it is implicitly excluded by not being allowed.
        if (appMode != MihomoVpnSettings.AppMode.ALLOW_LIST) {
            builder.addDisallowedApplication(packageName)
        }
        when (appMode) {
            MihomoVpnSettings.AppMode.ALL -> Unit
            MihomoVpnSettings.AppMode.ALLOW_LIST -> MihomoVpnSettings.packages(this).forEach { packageName ->
                runCatching { builder.addAllowedApplication(packageName) }
            }
            MihomoVpnSettings.AppMode.DISALLOW_LIST -> MihomoVpnSettings.packages(this).forEach { packageName ->
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        }
        builder.setConfigureIntent(configurePendingIntent())
        return try {
            builder.establish()?.detachFd()
        } catch (t: Throwable) {
            lastTunError = t
            null
        }
    }

    /**
     * Closes a descriptor the core never took over. [ParcelFileDescriptor.adoptFd]
     * re-attaches ownership so the close is clean: a detached fd has no owner as
     * far as fdsan is concerned.
     */
    private fun closeDetachedTun(fd: Int) {
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    // region notification

    private fun startForegroundNotification() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MikuVpnService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // The monochrome silhouette renders cleanly in the status bar; the
            // full-colour launcher icon becomes an opaque blob there.
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_notification_running))
            .setContentIntent(configurePendingIntent())
            .addAction(0, getString(R.string.vpn_action_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun configurePendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        pendingFlags(),
    )

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    // endregion

    companion object {
        const val ACTION_STOP = "top.uwu.mikubox.action.STOP_VPN"

        /** Reloads the core in place so changed settings apply immediately. */
        const val ACTION_RESTART = "top.uwu.mikubox.action.RESTART_VPN"

        /** Picks up settings the running tunnel can adopt as is. */
        const val ACTION_REFRESH = "top.uwu.mikubox.action.REFRESH_VPN"

        const val ACTION_VPN_START_FAILED = "top.uwu.mikubox.action.VPN_START_FAILED"
        const val EXTRA_FAILURE_DETAIL = "failure_detail"

        private const val TAG = "MikuBox"

        private const val CHANNEL_ID = "miku_vpn_status"
        private const val NOTIFICATION_ID = 1

        /** How often the running session's traffic is written into the profile's totals. */
        private const val CHECKPOINT_INTERVAL_MS = 30_000L

        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN4_PREFIX = 30
        private const val PRIVATE_VLAN4_DNS = "172.19.0.2"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN6_PREFIX = 126
        private const val PRIVATE_VLAN6_DNS = "fdfe:dcba:9876::2"

        /** Coarse running flag for the UI/controller to reflect state. */
        @Volatile
        var running: Boolean = false
            private set

        /** Wall-clock start of the current session, 0 while stopped. */
        @Volatile
        var startedAtMillis: Long = 0L
            private set

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, MikuVpnService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MikuVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
