package top.uwu.mikubox.core

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** App-wide UI preferences (theme, effects) equivalent to UwU's DataStore flags. */
object AppSettings {

    private const val PREFS = "miku_app_settings"
    private const val KEY_NIGHT_MODE = "night_mode"
    private const val KEY_PARTICLES = "particles"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_QUICK_ACTIONS = "quick_actions"
    private const val KEY_FAB_EXTENDED = "fab_extended"
    private const val KEY_SORT_ORDER = "server_sort_order"
    private const val KEY_COMPACT_ACTIONS = "compact_list_actions"
    private const val KEY_THEME_FAMILY = "theme_family"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_DYNAMIC_FROM_BANNER = "dynamic_color_banner"
    private const val KEY_USE_CUSTOM_COLOR = "use_custom_color"
    private const val KEY_CUSTOM_COLOR = "custom_color"
    private const val KEY_BANNER_COLOR = "banner_color"
    private const val KEY_TRUE_BLACK = "true_black"
    private const val KEY_FONT_FAMILY = "font_family"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_BOLD_TEXT = "bold_text"
    private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"

    /** Night mode values; [NIGHT_AUTO] follows the clock (day 6:00-18:00). */
    const val NIGHT_SYSTEM = "system"
    const val NIGHT_LIGHT = "light"
    const val NIGHT_DARK = "dark"
    const val NIGHT_AUTO = "auto"

    const val FONT_SCALE_DEFAULT = 100
    const val FONT_SCALE_MIN = 85
    const val FONT_SCALE_MAX = 130

    /** Palette family keys, in swatch order. */
    val themeFamilies = listOf(
        "teal", "cyan", "light_blue", "blue", "indigo", "deep_purple", "purple",
        "magenta", "pink", "red", "deep_orange", "orange", "amber", "yellow",
        "light_green", "green", "blue_grey",
    )

    /** Home list ordering, mirroring the release build's Order section. */
    const val SORT_ORIGIN = 0
    const val SORT_NAME = 1
    const val SORT_UPDATED = 2

    const val LANGUAGE_SYSTEM = ""
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_TRADITIONAL_CHINESE = "zh-TW"
    const val LANGUAGE_SIMPLIFIED_CHINESE = "zh-CN"
    const val LANGUAGE_FRENCH = "fr"
    const val LANGUAGE_INDONESIAN = "in"
    const val LANGUAGE_RUSSIAN = "ru"

    val supportedLanguages = listOf(
        LANGUAGE_SYSTEM, LANGUAGE_ENGLISH, LANGUAGE_TRADITIONAL_CHINESE,
        LANGUAGE_SIMPLIFIED_CHINESE, LANGUAGE_FRENCH, LANGUAGE_INDONESIAN,
        LANGUAGE_RUSSIAN,
    )

    /** One of [NIGHT_SYSTEM] / [NIGHT_LIGHT] / [NIGHT_DARK] / [NIGHT_AUTO]. */
    fun nightMode(context: Context): String =
        prefs(context).getString(KEY_NIGHT_MODE, NIGHT_SYSTEM).orEmpty()
            .takeIf { it in nightModes } ?: NIGHT_SYSTEM

    fun setNightMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_NIGHT_MODE, mode).commit()
        applyNightMode(context)
    }

    /** Resolves the stored mode to the delegate constant the app should run in. */
    fun resolveNightMode(context: Context): Int = when (nightMode(context)) {
        NIGHT_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        NIGHT_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        NIGHT_AUTO -> if (isDaytime()) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }

        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(resolveNightMode(context))
    }

    /** The clock window (06:00-18:00) the automatic mode treats as daytime. */
    fun isDaytime(): Boolean =
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 6 until 18

    val nightModes = listOf(NIGHT_SYSTEM, NIGHT_LIGHT, NIGHT_DARK, NIGHT_AUTO)

    fun particlesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PARTICLES, true)

    fun setParticlesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PARTICLES, enabled).commit()
    }

    /** Keep the app's task out of the recent-apps list. */
    fun hideFromRecents(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_FROM_RECENTS, false)

    fun setHideFromRecents(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_FROM_RECENTS, enabled).commit()
    }

    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM).orEmpty()

    fun setLanguage(context: Context, languageTag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, languageTag).commit()
    }

    /** Name shown next to the home greeting; blank falls back to the default. */
    fun profileName(context: Context): String =
        prefs(context).getString(KEY_PROFILE_NAME, "").orEmpty()

    fun setProfileName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_PROFILE_NAME, name.trim()).commit()
    }

    fun quickActions(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QUICK_ACTIONS, false)

    fun setQuickActions(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUICK_ACTIONS, enabled).commit()
    }

    fun fabExtended(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FAB_EXTENDED, false)

    fun setFabExtended(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAB_EXTENDED, enabled).commit()
    }

    /** One of [SORT_ORIGIN] / [SORT_NAME] / [SORT_UPDATED]. */
    fun sortOrder(context: Context): Int = prefs(context).getInt(KEY_SORT_ORDER, SORT_ORIGIN)

    fun setSortOrder(context: Context, order: Int) {
        prefs(context).edit().putInt(KEY_SORT_ORDER, order).commit()
    }

    /** Collapse the per-row actions of the home list into an overflow menu. */
    fun compactListActions(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPACT_ACTIONS, false)

    fun setCompactListActions(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPACT_ACTIONS, enabled).commit()
    }

    // ------------------------------------------------------------- theming

    /** Palette family key, one of [themeFamilies]. */
    fun themeFamily(context: Context): String =
        prefs(context).getString(KEY_THEME_FAMILY, themeFamilies.first()).orEmpty()
            .takeIf { it in themeFamilies } ?: themeFamilies.first()

    fun setThemeFamily(context: Context, family: String) {
        prefs(context).edit().putString(KEY_THEME_FAMILY, family).commit()
        ThemeManager.notifyThemeChanged()
    }

    /** Material You wallpaper colours (Android 12+). */
    fun dynamicColor(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DYNAMIC_COLOR, false)

    fun setDynamicColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).commit()
        ThemeManager.notifyThemeChanged()
    }

    /** Derive the palette from the home banner artwork (Android 12+). */
    fun dynamicColorFromBanner(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DYNAMIC_FROM_BANNER, false)

    fun setDynamicColorFromBanner(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_FROM_BANNER, enabled).commit()
        ThemeManager.notifyThemeChanged()
    }

    fun useCustomColor(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_CUSTOM_COLOR, false)

    fun customColor(context: Context): Int = prefs(context).getInt(KEY_CUSTOM_COLOR, 0)

    fun setCustomColor(context: Context, color: Int) {
        prefs(context).edit()
            .putBoolean(KEY_USE_CUSTOM_COLOR, true)
            .putInt(KEY_CUSTOM_COLOR, color)
            .apply()
        ThemeManager.notifyThemeChanged()
    }

    fun clearCustomColor(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_USE_CUSTOM_COLOR, false)
            .putInt(KEY_CUSTOM_COLOR, 0)
            .apply()
        ThemeManager.notifyThemeChanged()
    }

    /** Seed colour extracted from the banner artwork, 0 when none. */
    fun bannerColor(context: Context): Int = prefs(context).getInt(KEY_BANNER_COLOR, 0)

    fun setBannerColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_BANNER_COLOR, color).apply()
    }

    /** AMOLED black surfaces, only meaningful in the dark theme. */
    fun trueBlack(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRUE_BLACK, false)

    fun setTrueBlack(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRUE_BLACK, enabled).commit()
        ThemeManager.notifyThemeChanged()
    }

    // ---------------------------------------------------------- typography

    /** Bundled font key, "default" for the system face. */
    fun fontFamily(context: Context): String =
        prefs(context).getString(KEY_FONT_FAMILY, "default").orEmpty()

    fun setFontFamily(context: Context, key: String) {
        prefs(context).edit().putString(KEY_FONT_FAMILY, key).commit()
        ThemeManager.notifyThemeChanged()
    }

    /** Percentage, 100 = system scale. */
    fun fontScale(context: Context): Int = prefs(context)
        .getInt(KEY_FONT_SCALE, FONT_SCALE_DEFAULT)
        .coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

    fun setFontScale(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(KEY_FONT_SCALE, percent.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX))
            .commit()
        ThemeManager.notifyThemeChanged()
    }

    fun boldText(context: Context): Boolean = prefs(context).getBoolean(KEY_BOLD_TEXT, false)

    fun setBoldText(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOLD_TEXT, enabled).commit()
        ThemeManager.notifyThemeChanged()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
