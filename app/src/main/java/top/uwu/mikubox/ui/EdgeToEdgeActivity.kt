package top.uwu.mikubox.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.core.ThemeManager
import java.util.Locale

/**
 * Shared MikuRay-style system-bar handling for secondary screens, plus the
 * theme/typography bootstrap: the palette has to be applied before the first
 * view inflates and the font scale has to be baked into the configuration.
 */
abstract class EdgeToEdgeActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Copy first: the theme helper hands back the live configuration when the
        // font scale is untouched, and the locale must not leak into the process.
        val config = Configuration(ThemeManager.scaledConfiguration(newBase.resources.configuration, newBase))
        val language = AppSettings.language(newBase)
        if (language.isNotEmpty()) config.setLocale(Locale.forLanguageTag(language))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /** Theme revision this screen was built with, see [ThemeManager.version]. */
    private var appliedThemeVersion = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        // Below API 29 the application callback runs too late to set the theme.
        appliedThemeVersion = ThemeManager.version()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onResume() {
        super.onResume()
        // The palette or the typography changed while this screen sat in the back
        // stack: rebuild it so every activity follows a colour switch.
        if (appliedThemeVersion != ThemeManager.version()) {
            recreate()
            return
        }
        // The automatic night mode follows the clock, so re-resolve on resume and
        // let the delegate recreate the activity when the window changed.
        if (AppSettings.nightMode(this) == AppSettings.NIGHT_AUTO) {
            val current = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val wanted = when (AppSettings.resolveNightMode(this)) {
                AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
                AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
                else -> current
            }
            if (wanted != current) AppSettings.applyNightMode(this)
        }
    }

    protected fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            // The app bar owns the status-bar strip (it is colourPrimary and
            // has fitsSystemWindows), so the root only takes the side and
            // bottom insets -- padding it here would paint colourBg up there.
            view.updatePadding(
                left = maxOf(bars.left, cutout.left),
                right = maxOf(bars.right, cutout.right),
                bottom = maxOf(bars.bottom, cutout.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
