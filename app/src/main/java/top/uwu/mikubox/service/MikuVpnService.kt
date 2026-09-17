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
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoConfigStore
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

    @Volatile
    private var tun: ParcelFileDescriptor? = null
    private var lastTunError: Throwable? = null

    /** Core startup (config parse + GeoSite loads) can take seconds; keep it off the main thread. */
    private val startExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        startExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        // System or another VPN app revoked our permission.
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn() {
        if (tun != null) return
        lastTunError = null
        // The foreground notification must appear promptly; the heavy work runs after it.
        startForegroundNotification()
        startExecutor.execute {
            if (tun != null) return@execute
            try {
                MikuProxyService.stop(this)
                val descriptor = establishTun() ?: run {
                    reportStartFailure(lastTunError?.message.orEmpty())
                    stopVpn()
                    return@execute
                }
                val config = MihomoConfigStore.activeConfig(this)
                val startResult = MihomoCore.start(
                    this,
                    config,
                    descriptor.fd,
                    MihomoDnsSettings.effectiveOverride(this, config),
                    MihomoCoreSettings.overridesJson(this, MihomoVpnSettings.mtu(this)),
                )
                if (startResult.isFailure) {
                    runCatching { descriptor.close() }
                    reportStartFailure(startResult.exceptionOrNull()?.message.orEmpty())
                    stopVpn()
                    return@execute
                }
                tun = descriptor
                MihomoTrafficStore.begin(MihomoProfileStore.selected(this))
                running = true
            } catch (error: Throwable) {
                reportStartFailure(error.message.orEmpty())
                stopVpn()
            }
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

    private fun stopVpn() {
        running = false
        runCatching { MihomoTrafficStore.finish(this) }
        runCatching { MihomoCore.stop() }
        runCatching { tun?.close() }
        tun = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val appMode = MihomoVpnSettings.appMode(this)
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MihomoVpnSettings.mtu(this))
            .addAddress(PRIVATE_VLAN4_CLIENT, PRIVATE_VLAN4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .allowBypass()
        if (MihomoCoreSettings.ipv6(this)) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, PRIVATE_VLAN6_PREFIX)
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
            builder.establish()
        } catch (t: Throwable) {
            lastTunError = t
            null
        }
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
            .setSmallIcon(R.mipmap.ic_launcher)
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
        const val ACTION_VPN_START_FAILED = "top.uwu.mikubox.action.VPN_START_FAILED"
        const val EXTRA_FAILURE_DETAIL = "failure_detail"

        private const val TAG = "MikuBox"

        private const val CHANNEL_ID = "miku_vpn_status"
        private const val NOTIFICATION_ID = 1

        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN4_PREFIX = 30
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN6_PREFIX = 126

        /** Coarse running flag for the UI/controller to reflect state. */
        @Volatile
        var running: Boolean = false
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
