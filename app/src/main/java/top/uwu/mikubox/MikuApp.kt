package top.uwu.mikubox

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.core.ThemeManager

/**
 * Applies the persisted theme before any activity is created: the palette
 * (family / dynamic / custom seed), the AMOLED overlay and the typography are
 * all resolved here so a screen is themed before it inflates its first view.
 */
class MikuApp : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        AppSettings.applyNightMode(this)
        applyLanguage(AppSettings.language(this))
        registerActivityLifecycleCallbacks(this)
    }

    /**
     * The framework owns the per-app locale from API 33 on, and it wins over
     * [AppCompatDelegate.setApplicationLocales] when the two disagree during
     * startup — a restored preference would be ignored until the picker ran
     * again. Writing the framework value first keeps the stored choice.
     */
    private fun applyLanguage(tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = LocaleList.forLanguageTags(tag)
            getSystemService(LocaleManager::class.java)?.let { manager ->
                if (manager.applicationLocales.toLanguageTags() != locales.toLanguageTags()) {
                    manager.applicationLocales = locales
                }
            }
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        ThemeManager.apply(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // onActivityPreCreated needs API 29; older releases theme the activity
        // from the activity itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) ThemeManager.apply(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
