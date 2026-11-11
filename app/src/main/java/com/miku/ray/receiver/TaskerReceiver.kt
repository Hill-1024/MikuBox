package com.miku.ray.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import com.miku.ray.AppConfig
import com.miku.ray.core.LauncherManager
import com.miku.ray.util.LogUtil

class TaskerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            // The manifest filter already selects this action; checking it here
            // too keeps a malformed or re-targeted intent from reaching the
            // toggle path.
            if (intent?.action != ACTION_FIRE_SETTING) return
            val bundle = intent.getBundleExtra(AppConfig.TASKER_EXTRA_BUNDLE) ?: return
            // A bundle without the switch key is not a command this plugin
            // sent or accepted. getBoolean() alone would read that absence as
            // false and turn an empty broadcast into a disconnect.
            if (!bundle.containsKey(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH)) return
            val switch = bundle.getBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, false)
            val guid = bundle.getString(AppConfig.TASKER_EXTRA_BUNDLE_GUID).orEmpty()

            if (TextUtils.isEmpty(guid)) {
                return
            } else if (switch) {
                if (guid == AppConfig.TASKER_DEFAULT_GUID) {
                    LauncherManager.startServiceFromToggle(context)
                } else {
                    LauncherManager.startService(context, guid)
                }
            } else {
                LauncherManager.stopService(context)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing Tasker broadcast", e)
        }
    }

    private companion object {
        const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    }
}
