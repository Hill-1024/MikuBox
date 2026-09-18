package top.uwu.mikubox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoSubscriptionUpdater

/** Restores UwU's non-UI boot behaviour: scheduled updates and optional VPN restart. */
class MikuBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val action = intent.action
        val pending = goAsync()
        Thread {
            try {
                if (
                    action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
                    MihomoProfileStore.autoStart(appContext) &&
                    MihomoProfileStore.selected(appContext) != null
                ) {
                    // Android forbids a background receiver from showing the
                    // consent activity. Reconnect only when consent survived.
                    if (VpnService.prepare(appContext) == null) MikuVpnService.start(appContext)
                }
            } finally {
                pending.finish()
            }
            // Scheduling the updater opens WorkManager's database on first use,
            // which can outlast the broadcast timeout and showed up as an ANR
            // on boot and right after an app update. It is idempotent, so it
            // runs once the broadcast has already been answered.
            runCatching { MihomoSubscriptionUpdater.reconfigure(appContext) }
        }.start()
    }
}
