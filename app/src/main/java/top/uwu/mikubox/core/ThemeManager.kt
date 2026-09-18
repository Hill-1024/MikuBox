package top.uwu.mikubox.core

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import top.uwu.mikubox.R

/**
 * Resolves and applies the app theme, following the release design's
 * precedence:
 *
 * 1. a palette derived from the home banner artwork (Android 12+),
 * 2. Material You wallpaper colours (Android 12+),
 * 3. a custom seed colour (Android 12+),
 * 4. one of the preset palette families,
 *
 * with the AMOLED overlay layered on top whenever the app is in the dark theme.
 * Typography (font family, bold) is applied in the same pass so a screen is
 * themed before any view is inflated.
 */
object ThemeManager {

    /**
     * Bumped whenever the palette or typography changes. Screens capture it when
     * they are created and recreate themselves when it moves, so a colour change
     * reaches the activities already sitting in the back stack.
     */
    @Volatile
    private var version: Long = 0L

    fun version(): Long = version

    fun notifyThemeChanged() {
        version++
    }

    /** Theme style for a family key; unknown keys fall back to the default. */
    @StyleRes
    fun styleFor(family: String): Int = when (family) {
        "cyan" -> R.style.Theme_MikuBoxCla_Cyan
        "light_blue" -> R.style.Theme_MikuBoxCla_LightBlue
        "blue" -> R.style.Theme_MikuBoxCla_Blue
        "indigo" -> R.style.Theme_MikuBoxCla_Indigo
        "deep_purple" -> R.style.Theme_MikuBoxCla_DeepPurple
        "purple" -> R.style.Theme_MikuBoxCla_Purple
        "magenta" -> R.style.Theme_MikuBoxCla_Magenta
        "pink" -> R.style.Theme_MikuBoxCla_Pink
        "red" -> R.style.Theme_MikuBoxCla_Red
        "deep_orange" -> R.style.Theme_MikuBoxCla_DeepOrange
        "orange" -> R.style.Theme_MikuBoxCla_Orange
        "amber" -> R.style.Theme_MikuBoxCla_Amber
        "yellow" -> R.style.Theme_MikuBoxCla_Yellow
        "light_green" -> R.style.Theme_MikuBoxCla_LightGreen
        "green" -> R.style.Theme_MikuBoxCla_Green
        "blue_grey" -> R.style.Theme_MikuBoxCla_BlueGrey
        else -> R.style.Theme_MikuBoxCla_Teal
    }

    /** The family a theme overlay should be resolved against for previews. */
    fun familyDisplayName(family: String): String = family
        .split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    fun isDarkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** True when the palette is not coming from a preset family. */
    fun isDynamicActive(context: Context): Boolean = supportsDynamicColor() && when {
        AppSettings.dynamicColorFromBanner(context) && AppSettings.bannerColor(context) != 0 -> true
        AppSettings.dynamicColor(context) -> true
        AppSettings.useCustomColor(context) && AppSettings.customColor(context) != 0 -> true
        else -> false
    }

    /**
     * Applies the theme + typography. Must run before the activity inflates any
     * view, which is why it is called from onActivityPreCreated (API 29+) and
     * again at the top of onCreate on older releases.
     */
    fun apply(activity: Activity) {
        val trueBlack = AppSettings.trueBlack(activity) && isDarkMode(activity)
        val bannerSeed = AppSettings.bannerColor(activity)
        val customSeed = AppSettings.customColor(activity)

        val seed = when {
            !supportsDynamicColor() -> null
            AppSettings.dynamicColorFromBanner(activity) && bannerSeed != 0 -> bannerSeed
            AppSettings.dynamicColor(activity) -> null
            AppSettings.useCustomColor(activity) && customSeed != 0 -> customSeed
            else -> null
        }
        val useDynamic = supportsDynamicColor() && (
            seed != null ||
                AppSettings.dynamicColor(activity) ||
                (AppSettings.useCustomColor(activity) && customSeed != 0)
            )

        if (useDynamic) {
            val options = DynamicColorsOptions.Builder()
            if (seed != null) options.setContentBasedSource(seed)
            if (trueBlack) options.setThemeOverlay(R.style.ThemeOverlay_Miku_TrueBlack)
            DynamicColors.applyToActivityIfAvailable(activity, options.build())
        } else {
            activity.setTheme(styleFor(AppSettings.themeFamily(activity)))
            if (trueBlack) {
                activity.theme.applyStyle(R.style.ThemeOverlay_Miku_TrueBlack, true)
            }
        }
        applyTypography(activity)
    }

    /** Applies the selected font family and bold variant as theme overlays. */
    fun applyTypography(activity: Activity) {
        val family = AppSettings.fontFamily(activity)
        fontOverlayFor(family)?.let { activity.theme.applyStyle(it, true) }
        if (AppSettings.boldText(activity)) {
            activity.theme.applyStyle(R.style.Miku_BoldText, true)
        }
    }

    /**
     * Font key to overlay style. Keys are the bundled font resource names, so a
     * new font only needs an entry in the picker arrays and here.
     */
    @StyleRes
    fun fontOverlayFor(key: String): Int? = when (key) {
        "uwu_font_title" -> R.style.Miku_Font_UwuFontTitle
        "uwu_font_summary" -> R.style.Miku_Font_UwuFontSummary
        "uwu_font_typography" -> R.style.Miku_Font_UwuFontTypography
        "googlesansregular" -> R.style.Miku_Font_Googlesansregular
        "robotoregular" -> R.style.Miku_Font_Robotoregular
        "poppinsregular" -> R.style.Miku_Font_Poppinsregular
        "sfprodisplay" -> R.style.Miku_Font_Sfprodisplay
        "oneui" -> R.style.Miku_Font_Oneui
        "rine" -> R.style.Miku_Font_Rine
        "chococookyregular" -> R.style.Miku_Font_Chococookyregular
        "simpleday" -> R.style.Miku_Font_Simpleday
        "fucek" -> R.style.Miku_Font_Fucek
        "dancingscript" -> R.style.Miku_Font_Dancingscript
        "cream" -> R.style.Miku_Font_Cream
        "emilyscandy" -> R.style.Miku_Font_Emilyscandy
        "summerdream" -> R.style.Miku_Font_Summerdream
        "incosolata" -> R.style.Miku_Font_Incosolata
        "jetbrains_mono" -> R.style.Miku_Font_JetbrainsMono
        else -> null
    }

    /** Configuration with the user's font scale baked in. */
    fun scaledConfiguration(base: Configuration, context: Context): Configuration {
        val percent = AppSettings.fontScale(context)
        if (percent == AppSettings.FONT_SCALE_DEFAULT) return base
        val updated = Configuration(base)
        updated.fontScale = base.fontScale * percent / 100f
        return updated
    }
}
