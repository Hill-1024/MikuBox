package com.mikubox.mihomo.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.miku.ray.AppConfig
import com.miku.ray.handler.MmkvManager
import com.miku.ray.util.LogUtil

/**
 * Brings the tunnel back when the process is killed with it up.
 *
 * START_STICKY asks Android to recreate a killed service; framework-managed
 * sticky foreground-service restarts are exempt from background-start limits.
 * This alarm is a best-effort fallback for devices that delay that restart.
 * Its explicit start is still subject to background restrictions, and Doze may
 * defer the alarm, so it cannot promise a restart deadline or defeat force-stop.
 * The persisted expectation is cleared on manual disconnect and VPN revocation.
 */
object TunnelGuard {

    /**
     * How often the guard checks. Half a minute keeps the gap after a kill
     * short (observed end to end: the alarm fires, the service comes back and
     * the tunnel is up within the next check), while the check itself is a
     * broadcast that only reads a flag unless something is wrong. In Doze the
     * platform stretches allow-while-idle alarms to its own cadence, which is
     * not a guaranteed recovery interval.
     */
    private const val CHECK_INTERVAL_MS = 30_000L

    /** True while the tunnel is supposed to be up. */
    fun isExpected(context: Context): Boolean =
        runCatching { MmkvManager.decodeSettingsBool(AppConfig.PREF_TUNNEL_EXPECTED) }
            .getOrDefault(false)

    /**
     * Records whether a tunnel should be running, arming or disarming the
     * check with it. Called when the service reaches connected state and when
     * it tears down, so a deliberate disconnect never resurrects.
     */
    fun expectRunning(context: Context, expected: Boolean) {
        runCatching { MmkvManager.encodeSettings(AppConfig.PREF_TUNNEL_EXPECTED, expected) }
            .onFailure { LogUtil.w(message = "Could not record the tunnel expectation", throwable = it) }
        if (expected) schedule(context) else cancel(context)
    }

    /** Arms the next check; the receiver re-arms it after every firing. */
    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS
        runCatching {
            // Allow-while-idle needs no exact-alarm permission; the platform
            // may defer it in Doze, which is the documented behaviour.
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
        }.onFailure { LogUtil.w(message = "Could not arm the tunnel guard", throwable = it) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(pending(context)) }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        // Explicit component: matches the manifest entry without needing an
        // action filter, so nothing else can trigger the guard.
        Intent(context, TunnelGuardReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * The alarm's landing point. Runs even when the app's process is gone, because
 * the platform starts a fresh one for the broadcast.
 */
class TunnelGuardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!TunnelGuard.isExpected(context)) {
            // Disconnected while the alarm was in flight; let it lapse.
            TunnelGuard.cancel(context)
            return
        }
        if (!MikuVpnService.running) {
            LogUtil.w(message = "Tunnel expected but not running; restarting it")
            // The start is refused outright when the platform does not allow a
            // background foreground-service start (no battery-optimization
            // exemption, device in Doze); say so instead of throwing out of a
            // broadcast, and keep the alarm armed so the next window retries.
            runCatching { MikuVpnService.start(context) }
                .onFailure { LogUtil.w(message = "Tunnel guard could not restart the service", throwable = it) }
        }
        TunnelGuard.schedule(context)
    }
}
