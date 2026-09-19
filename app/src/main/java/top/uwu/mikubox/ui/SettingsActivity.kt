package top.uwu.mikubox.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.core.BackupManager
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.core.ThemeManager
import top.uwu.mikubox.core.RoutingMode
import top.uwu.mikubox.databinding.ActivitySettingsBinding
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoSubscriptionUpdater
import top.uwu.mikubox.service.MihomoVpnSettings
import top.uwu.mikubox.service.MihomoVpnSettings.AppMode
import top.uwu.mikubox.service.VpnController

/**
 * Settings, laid out as the release design's stacked rows: grouped sections
 * with a headline, and every row a card whose trailing widget is a switch, a
 * current value or a navigation arrow.
 */
class SettingsActivity : EdgeToEdgeActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val exportBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeBackup(it) } }

    private val importBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readBackup(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        setupTabs()
        setupRows()
    }

    /**
     * Two pinned categories. Every row stays one tap away — the tab only decides
     * which half of the page is shown, so there is no second navigation level.
     */
    private fun setupTabs() {
        binding.settingsTab.addTab(
            binding.settingsTab.newTab().setText(R.string.settings_tab_appearance)
        )
        binding.settingsTab.addTab(
            binding.settingsTab.newTab().setText(R.string.settings_tab_network)
        )
        binding.settingsTab.addOnTabSelectedListener(
            object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                    showSection(tab.position == 0)
                }

                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit

                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                    binding.settingsScroll.smoothScrollTo(0, 0)
                }
            }
        )
    }

    private fun showSection(appearance: Boolean) {
        binding.sectionAppearance.visibility =
            if (appearance) android.view.View.VISIBLE else android.view.View.GONE
        binding.sectionNetwork.visibility =
            if (appearance) android.view.View.GONE else android.view.View.VISIBLE
        binding.settingsScroll.smoothScrollTo(0, 0)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun setupRows() {
        UwuRow.bind(
            binding.rowThemeColor,
            UwuRow.Slot.TOP,
            R.drawable.ic_palette_24,
            getString(R.string.settings_theme_color),
            getString(R.string.settings_theme_color_summary),
        ) { ThemeDialogs.showThemeColor(this) { render() } }
        UwuRow.bind(
            binding.rowDynamicColor,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_color_lens_24,
            getString(R.string.settings_dynamic_color),
            if (ThemeManager.supportsDynamicColor()) {
                getString(R.string.settings_dynamic_color_summary)
            } else {
                getString(R.string.settings_dynamic_unavailable)
            },
            switchState = AppSettings.dynamicColor(this) && ThemeManager.supportsDynamicColor(),
        ) { toggleDynamicColor() }
        UwuRow.bind(
            binding.rowDynamicBanner,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_image_24dp,
            getString(R.string.settings_dynamic_banner),
            if (ThemeManager.supportsDynamicColor()) {
                getString(R.string.settings_dynamic_banner_summary)
            } else {
                getString(R.string.settings_dynamic_unavailable)
            },
            switchState = AppSettings.dynamicColorFromBanner(this) &&
                ThemeManager.supportsDynamicColor(),
        ) { toggleDynamicBanner() }
        UwuRow.bind(
            binding.rowDarkMode,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_blur_on_24,
            getString(R.string.settings_dark_mode),
        ) { pickDarkMode() }
        UwuRow.bind(
            binding.rowTrueBlack,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_circle,
            getString(R.string.settings_true_black),
            if (ThemeManager.isDarkMode(this)) {
                getString(R.string.settings_true_black_summary)
            } else {
                getString(R.string.settings_true_black_night_only)
            },
            switchState = AppSettings.trueBlack(this) && ThemeManager.isDarkMode(this),
        ) { toggleTrueBlack() }

        UwuRow.bind(
            binding.rowFont,
            UwuRow.Slot.TOP,
            R.drawable.ic_format_font,
            getString(R.string.settings_font),
            getString(R.string.settings_font_summary),
        ) { FontPickerBottomSheet.show(supportFragmentManager) }
        UwuRow.bind(
            binding.rowFontSize,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_format_font,
            getString(R.string.settings_font_size),
        ) { editFontScale() }
        UwuRow.bind(
            binding.rowIconShape,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_image_24dp,
            getString(R.string.settings_icon_shape),
            getString(R.string.settings_icon_shape_summary),
        ) { ThemeDialogs.showIconShape(this) { render() } }
        UwuRow.bind(
            binding.rowBannerShape,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_image_24dp,
            getString(R.string.settings_banner_shape),
            getString(R.string.settings_banner_shape_summary),
        ) { ThemeDialogs.showBannerShape(this) { render() } }
        UwuRow.bind(
            binding.rowBoldText,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_edit_24dp,
            getString(R.string.settings_bold_text),
            getString(R.string.settings_bold_text_summary),
            switchState = AppSettings.boldText(this),
        ) { toggleBoldText() }

        UwuRow.bind(
            binding.rowLanguage,
            UwuRow.Slot.TOP,
            R.drawable.ic_translate,
            getString(R.string.settings_language),
        ) { pickLanguage() }
        UwuRow.bind(
            binding.rowProfileName,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_account_edit,
            getString(R.string.settings_profile_name),
            getString(R.string.settings_profile_name_summary),
        ) { editProfileName() }
        UwuRow.bind(
            binding.rowParticles,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_sparkles_24dp,
            getString(R.string.settings_particles),
            getString(R.string.settings_particles_summary),
            switchState = AppSettings.particlesEnabled(this),
        ) { toggleParticles() }
        UwuRow.bind(
            binding.rowQuickActions,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_speed_24,
            getString(R.string.settings_quick_actions),
            getString(R.string.settings_quick_actions_summary),
            switchState = AppSettings.quickActions(this),
        ) { toggleQuickActions() }
        UwuRow.bind(
            binding.rowFabExtended,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_clock,
            getString(R.string.settings_fab_extended),
            getString(R.string.settings_fab_extended_summary),
            switchState = AppSettings.fabExtended(this),
        ) { toggleFabExtended() }
        UwuRow.bind(
            binding.rowCompactActions,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_action_note_add,
            getString(R.string.settings_compact_actions),
            getString(R.string.settings_compact_actions_summary),
            switchState = AppSettings.compactListActions(this),
        ) { toggleCompactActions() }

        UwuRow.bind(
            binding.rowApps,
            UwuRow.Slot.TOP,
            R.drawable.ic_per_apps_24dp,
            getString(R.string.settings_per_app),
            getString(R.string.desc_apps),
            arrow = true,
        ) { startActivity(Intent(this, AppListActivity::class.java)) }
        UwuRow.bind(
            binding.rowDns,
            UwuRow.Slot.TOP,
            R.drawable.ic_dns,
            getString(R.string.settings_dns),
            getString(R.string.settings_dns_summary),
            arrow = true,
        ) { startActivity(Intent(this, DnsActivity::class.java)) }
        UwuRow.bind(
            binding.rowBoot,
            UwuRow.Slot.TOP,
            R.drawable.ic_access_point_network,
            getString(R.string.settings_boot),
            getString(R.string.settings_boot_summary),
            switchState = MihomoProfileStore.autoStart(this),
        ) { toggleBoot() }
        UwuRow.bind(
            binding.rowAutoconnect,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_flash_on,
            getString(R.string.settings_autoconnect),
            getString(R.string.settings_autoconnect_summary),
            switchState = MihomoCoreSettings.autoConnectOnStart(this),
        ) { toggleAutoconnect() }
        UwuRow.bind(
            binding.rowAllowLan,
            UwuRow.Slot.SINGLE,
            R.drawable.ic_lan,
            getString(R.string.settings_allow_lan),
            getString(R.string.settings_allow_lan_summary),
            switchState = MihomoCoreSettings.allowLan(this),
        ) { toggleAllowLan() }
        UwuRow.bind(
            binding.rowIpv6,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_wan,
            getString(R.string.settings_ipv6),
            getString(R.string.settings_ipv6_summary),
            switchState = MihomoCoreSettings.ipv6(this),
        ) { toggleIpv6() }
        UwuRow.bind(
            binding.rowMtu,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_router,
            getString(R.string.settings_mtu),
        ) { editMtu() }

        UwuRow.bind(
            binding.rowMode,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_transform_24,
            getString(R.string.settings_mode),
        ) { pickMode() }
        UwuRow.bind(
            binding.rowLog,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_math_log,
            getString(R.string.settings_log),
        ) { pickLog() }
        UwuRow.bind(
            binding.rowTunStack,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_connection,
            getString(R.string.settings_tun_stack),
        ) { pickStack() }
        UwuRow.bind(
            binding.rowUnifiedDelay,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_speed_24,
            getString(R.string.settings_unified_delay),
            getString(R.string.settings_unified_delay_summary),
            switchState = MihomoCoreSettings.unifiedDelay(this),
        ) { toggleUnifiedDelay() }
        UwuRow.bind(
            binding.rowTcpConcurrent,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_crosshairs,
            getString(R.string.settings_tcp_concurrent),
            getString(R.string.settings_tcp_concurrent_summary),
            switchState = MihomoCoreSettings.tcpConcurrent(this),
        ) { toggleTcpConcurrent() }
        UwuRow.bind(
            binding.rowTestUrl,
            UwuRow.Slot.TOP,
            R.drawable.ic_web_24dp,
            getString(R.string.settings_test_url),
        ) { editTestUrl() }
        UwuRow.bind(
            binding.rowTestTimeout,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_history,
            getString(R.string.settings_test_timeout),
        ) { editTestTimeout() }
        UwuRow.bind(
            binding.rowAdvanced,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_settings_24dp,
            getString(R.string.advanced_title),
            getString(R.string.advanced_summary),
            arrow = true,
        ) { startActivity(Intent(this, AdvancedActivity::class.java)) }

        UwuRow.bind(
            binding.rowExport,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_backup_24dp,
            getString(R.string.settings_export),
            getString(R.string.settings_export_summary),
            arrow = true,
        ) { exportBackup.launch("mikubox-backup.json") }
        UwuRow.bind(
            binding.rowImport,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_restore_24dp,
            getString(R.string.settings_import),
            getString(R.string.settings_import_summary),
            arrow = true,
        ) { importBackup.launch(arrayOf("application/json", "text/*")) }
    }

    private fun render() {
        // Rows are re-bound on every resume: a preference can also change from
        // the home screen, a sheet or the boot receiver, and a switch still
        // showing the old state is worse than no switch at all.
        setupRows()
        setValue(binding.rowThemeColor, themeColorLabel())
        setValue(binding.rowDarkMode, getString(darkModeLabel(AppSettings.nightMode(this))))
        setValue(binding.rowFont, ThemeDialogs.fontLabel(this, AppSettings.fontFamily(this)))
        setValue(
            binding.rowFontSize,
            getString(R.string.font_size_value, AppSettings.fontScale(this)),
        )
        setValue(binding.rowIconShape, ThemeDialogs.iconShapeLabel(AppSettings.iconShape(this)))
        setValue(binding.rowBannerShape, ThemeDialogs.iconShapeLabel(AppSettings.bannerShape(this)))
        setValue(binding.rowLanguage, getString(languageLabel(AppSettings.language(this))))
        setValue(
            binding.rowProfileName,
            AppSettings.profileName(this).ifBlank { getString(R.string.uwu_profile_banner_title) },
        )
        setValue(binding.rowMtu, MihomoVpnSettings.mtu(this).toString())
        setValue(binding.rowApps, getString(appModeLabel(MihomoVpnSettings.appMode(this))))
        setValue(binding.rowMode, getString(modeLabel(MihomoCoreSettings.mode(this))))
        setValue(binding.rowLog, getString(logLabel(MihomoCoreSettings.logLevel(this))))
        setValue(binding.rowTunStack, getString(stackLabel(MihomoCoreSettings.tunStack(this))))
        setValue(binding.rowTestUrl, MihomoCoreSettings.testUrl(this))
        setValue(
            binding.rowTestTimeout,
            getString(R.string.settings_test_timeout_value, MihomoCoreSettings.testTimeout(this)),
        )
    }

    /** "Dynamic", "Custom" or the active family name. */
    private fun themeColorLabel(): CharSequence = when {
        ThemeManager.isDynamicActive(this) -> getString(R.string.settings_theme_dynamic_label)
        AppSettings.useCustomColor(this) -> getString(R.string.theme_custom_color)
        else -> ThemeManager.familyDisplayName(AppSettings.themeFamily(this))
    }

    private fun setValue(row: top.uwu.mikubox.databinding.UwuRowBinding, value: CharSequence) {
        row.rowValue.text = value
        row.rowValue.visibility = android.view.View.VISIBLE
        row.rowValue.setTextColor(
            MaterialColors.getColor(
                row.rowValue,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        )
    }

    // ------------------------------------------------------------- toggles

    private fun toggleParticles() {
        val enabled = !AppSettings.particlesEnabled(this)
        AppSettings.setParticlesEnabled(this, enabled)
        UwuRow.bind(
            binding.rowParticles,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_sparkles_24dp,
            getString(R.string.settings_particles),
            getString(R.string.settings_particles_summary),
            switchState = enabled,
        ) { toggleParticles() }
    }

    private fun toggleQuickActions() {
        val enabled = !AppSettings.quickActions(this)
        AppSettings.setQuickActions(this, enabled)
        UwuRow.bind(
            binding.rowQuickActions,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_speed_24,
            getString(R.string.settings_quick_actions),
            getString(R.string.settings_quick_actions_summary),
            switchState = enabled,
        ) { toggleQuickActions() }
    }

    private fun toggleFabExtended() {
        val enabled = !AppSettings.fabExtended(this)
        AppSettings.setFabExtended(this, enabled)
        UwuRow.bind(
            binding.rowFabExtended,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_clock,
            getString(R.string.settings_fab_extended),
            getString(R.string.settings_fab_extended_summary),
            switchState = enabled,
        ) { toggleFabExtended() }
    }

    private fun toggleCompactActions() {
        val enabled = !AppSettings.compactListActions(this)
        AppSettings.setCompactListActions(this, enabled)
        UwuRow.bind(
            binding.rowCompactActions,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_action_note_add,
            getString(R.string.settings_compact_actions),
            getString(R.string.settings_compact_actions_summary),
            switchState = enabled,
        ) { toggleCompactActions() }
    }

    private fun toggleBoot() {
        val enabled = !MihomoProfileStore.autoStart(this)
        MihomoProfileStore.setAutoStart(this, enabled)
        UwuRow.bind(
            binding.rowBoot,
            UwuRow.Slot.TOP,
            R.drawable.ic_access_point_network,
            getString(R.string.settings_boot),
            getString(R.string.settings_boot_summary),
            switchState = enabled,
        ) { toggleBoot() }
    }

    private fun toggleAutoconnect() {
        val enabled = !MihomoCoreSettings.autoConnectOnStart(this)
        MihomoCoreSettings.setAutoConnectOnStart(this, enabled)
        UwuRow.bind(
            binding.rowAutoconnect,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_flash_on,
            getString(R.string.settings_autoconnect),
            getString(R.string.settings_autoconnect_summary),
            switchState = enabled,
        ) { toggleAutoconnect() }
    }

    private fun toggleAllowLan() {
        val enabled = !MihomoCoreSettings.allowLan(this)
        MihomoCoreSettings.setAllowLan(this, enabled)
        applyCoreChange()
        UwuRow.bind(
            binding.rowAllowLan,
            UwuRow.Slot.SINGLE,
            R.drawable.ic_lan,
            getString(R.string.settings_allow_lan),
            getString(R.string.settings_allow_lan_summary),
            switchState = enabled,
        ) { toggleAllowLan() }
    }

    private fun toggleIpv6() {
        val enabled = !MihomoCoreSettings.ipv6(this)
        MihomoCoreSettings.setIpv6(this, enabled)
        applyCoreChange()
        UwuRow.bind(
            binding.rowIpv6,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_wan,
            getString(R.string.settings_ipv6),
            getString(R.string.settings_ipv6_summary),
            switchState = enabled,
        ) { toggleIpv6() }
    }

    private fun toggleUnifiedDelay() {
        val enabled = !MihomoCoreSettings.unifiedDelay(this)
        MihomoCoreSettings.setUnifiedDelay(this, enabled)
        applyCoreChange()
        UwuRow.bind(
            binding.rowUnifiedDelay,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_speed_24,
            getString(R.string.settings_unified_delay),
            getString(R.string.settings_unified_delay_summary),
            switchState = enabled,
        ) { toggleUnifiedDelay() }
    }

    private fun toggleTcpConcurrent() {
        val enabled = !MihomoCoreSettings.tcpConcurrent(this)
        MihomoCoreSettings.setTcpConcurrent(this, enabled)
        applyCoreChange()
        UwuRow.bind(
            binding.rowTcpConcurrent,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_crosshairs,
            getString(R.string.settings_tcp_concurrent),
            getString(R.string.settings_tcp_concurrent_summary),
            switchState = enabled,
        ) { toggleTcpConcurrent() }
    }

    /**
     * The core reads these settings when it starts, so a running tunnel is
     * reloaded to apply them; while disconnected there is nothing to do.
     */
    private fun applyCoreChange() {
        val running = VpnController.isRunning
        VpnController.restart(this)
        if (running) UwuSnackbar.info(this, getString(R.string.advanced_restarting))
    }

    // ------------------------------------------------------------- pickers

    private fun modeLabel(mode: MihomoCoreSettings.ProxyMode): Int = when (mode) {
        MihomoCoreSettings.ProxyMode.RULE -> R.string.mode_rule
        MihomoCoreSettings.ProxyMode.GLOBAL -> R.string.mode_global
        MihomoCoreSettings.ProxyMode.DIRECT -> R.string.mode_direct
        else -> R.string.mode_follow
    }

    private fun logLabel(level: MihomoCoreSettings.LogLevel): Int = when (level) {
        MihomoCoreSettings.LogLevel.SILENT -> R.string.log_silent
        MihomoCoreSettings.LogLevel.WARNING -> R.string.log_warning
        MihomoCoreSettings.LogLevel.DEBUG -> R.string.log_debug
        else -> R.string.log_info
    }

    private fun stackLabel(stack: MihomoCoreSettings.TunStack): Int = when (stack) {
        MihomoCoreSettings.TunStack.SYSTEM -> R.string.stack_system
        MihomoCoreSettings.TunStack.GVISOR -> R.string.stack_gvisor
        MihomoCoreSettings.TunStack.MIXED -> R.string.stack_mixed
        MihomoCoreSettings.TunStack.FOLLOW -> R.string.stack_follow
    }

    private fun pickMode() {
        val modes = MihomoCoreSettings.ProxyMode.values()
        val labels = modes.map { getString(modeLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_mode)
            .setSingleChoiceItems(labels, modes.indexOf(MihomoCoreSettings.mode(this))) { dialog, which ->
                // A running core switches over immediately, exactly like the home switch.
                RoutingMode.select(this, modes[which])
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickLog() {
        val levels = MihomoCoreSettings.LogLevel.values()
        val labels = levels.map { getString(logLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_log)
            .setSingleChoiceItems(labels, levels.indexOf(MihomoCoreSettings.logLevel(this))) { dialog, which ->
                MihomoCoreSettings.setLogLevel(this, levels[which])
                dialog.dismiss()
                render()
                applyCoreChange()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickStack() {
        val stacks = MihomoCoreSettings.TunStack.values()
        val labels = stacks.map { getString(stackLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_tun_stack)
            .setSingleChoiceItems(labels, stacks.indexOf(MihomoCoreSettings.tunStack(this))) { dialog, which ->
                MihomoCoreSettings.setTunStack(this, stacks[which])
                dialog.dismiss()
                render()
                applyCoreChange()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editProfileName() {
        UwuDialogs.input(
            context = this,
            title = getString(R.string.settings_profile_name),
            message = getString(R.string.uwu_profile_name_dialog),
            initial = AppSettings.profileName(this),
        ) { value ->
            AppSettings.setProfileName(this, value)
            render()
        }
    }

    private fun editTestUrl() {
        UwuDialogs.input(
            context = this,
            title = getString(R.string.settings_test_url),
            initial = MihomoCoreSettings.testUrl(this),
            hint = getString(R.string.settings_test_url),
        ) { value ->
            if (value.isNotBlank()) {
                MihomoCoreSettings.setTestUrl(this, value)
                render()
            }
        }
    }

    private fun editTestTimeout() {
        UwuDialogs.input(
            context = this,
            title = getString(R.string.settings_test_timeout),
            initial = MihomoCoreSettings.testTimeout(this).toString(),
            hint = getString(R.string.settings_test_timeout),
        ) { value ->
            value.toIntOrNull()?.let {
                MihomoCoreSettings.setTestTimeout(this, it)
                render()
            }
        }
    }

    private fun editMtu() {
        UwuDialogs.input(
            context = this,
            title = getString(R.string.settings_mtu),
            initial = MihomoVpnSettings.mtu(this).toString(),
            hint = getString(R.string.settings_mtu),
        ) { value ->
            value.toIntOrNull()?.let {
                MihomoVpnSettings.setMtu(this, it)
                render()
                applyCoreChange()
            }
        }
    }

    private fun appModeLabel(mode: AppMode): Int = when (mode) {
        AppMode.ALLOW_LIST -> R.string.per_app_mode_allow
        AppMode.DISALLOW_LIST -> R.string.per_app_mode_disallow
        else -> R.string.per_app_mode_all
    }

    private fun darkModeLabel(mode: String): Int = when (mode) {
        AppSettings.NIGHT_LIGHT -> R.string.settings_theme_light
        AppSettings.NIGHT_DARK -> R.string.settings_theme_dark
        AppSettings.NIGHT_AUTO -> R.string.settings_theme_auto
        else -> R.string.settings_theme_system
    }

    private fun pickDarkMode() {
        val modes = AppSettings.nightModes
        val labels = modes.map { getString(darkModeLabel(it)) }.toTypedArray()
        val current = modes.indexOf(AppSettings.nightMode(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_dark_mode)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                AppSettings.setNightMode(this, modes[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleDynamicColor() {
        if (!ThemeManager.supportsDynamicColor()) {
            UwuSnackbar.error(this, getString(R.string.settings_dynamic_unavailable))
            return
        }
        val enabled = !AppSettings.dynamicColor(this)
        AppSettings.setDynamicColor(this, enabled)
        if (enabled) AppSettings.setDynamicColorFromBanner(this, false)
        recreate()
    }

    private fun toggleDynamicBanner() {
        if (!ThemeManager.supportsDynamicColor()) {
            UwuSnackbar.error(this, getString(R.string.settings_dynamic_unavailable))
            return
        }
        val enabled = !AppSettings.dynamicColorFromBanner(this)
        AppSettings.setDynamicColorFromBanner(this, enabled)
        if (enabled) AppSettings.setDynamicColor(this, false)
        recreate()
    }

    private fun toggleTrueBlack() {
        if (!ThemeManager.isDarkMode(this)) {
            UwuSnackbar.info(this, getString(R.string.settings_true_black_night_only))
            return
        }
        AppSettings.setTrueBlack(this, !AppSettings.trueBlack(this))
        recreate()
    }

    private fun toggleBoldText() {
        AppSettings.setBoldText(this, !AppSettings.boldText(this))
        recreate()
    }

    private fun editFontScale() {
        ThemeDialogs.showSlider(
            activity = this,
            titleRes = R.string.settings_font_size,
            value = AppSettings.fontScale(this),
            min = AppSettings.FONT_SCALE_MIN,
            max = AppSettings.FONT_SCALE_MAX,
            step = 5,
        ) { percent -> AppSettings.setFontScale(this, percent) }
    }

    private fun languageLabel(language: String): Int = when (language) {
        AppSettings.LANGUAGE_ENGLISH -> R.string.language_english
        AppSettings.LANGUAGE_TRADITIONAL_CHINESE -> R.string.language_traditional_chinese
        AppSettings.LANGUAGE_SIMPLIFIED_CHINESE -> R.string.language_simplified_chinese
        AppSettings.LANGUAGE_FRENCH -> R.string.language_french
        AppSettings.LANGUAGE_INDONESIAN -> R.string.language_indonesian
        AppSettings.LANGUAGE_RUSSIAN -> R.string.language_russian
        else -> R.string.language_system
    }

    private fun pickLanguage() {
        val languages = AppSettings.supportedLanguages
        val labels = languages.map { getString(languageLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, languages.indexOf(AppSettings.language(this))) { dialog, which ->
                val language = languages[which]
                AppSettings.setLanguage(this, language)
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeBackup(uri: Uri) {
        lifecycleScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(BackupManager.export(this@SettingsActivity).toByteArray())
                    } ?: error("no stream")
                }
            }.isSuccess
            if (ok) {
                UwuSnackbar.success(this@SettingsActivity, getString(R.string.toast_backup_exported))
            } else {
                UwuSnackbar.error(this@SettingsActivity, getString(R.string.toast_backup_failed))
            }
        }
    }

    private fun readBackup(uri: Uri) {
        lifecycleScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    val json = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("no stream")
                    BackupManager.import(this@SettingsActivity, json)
                    // A backup can carry subscriptions this install did not
                    // have; the periodic update work has to be rebuilt around
                    // the restored set.
                    MihomoSubscriptionUpdater.reconfigure(this@SettingsActivity)
                }
            }.isSuccess
            if (ok) {
                AppSettings.applyNightMode(this@SettingsActivity)
                render()
                UwuSnackbar.success(this@SettingsActivity, getString(R.string.toast_backup_imported))
            } else {
                UwuSnackbar.error(this@SettingsActivity, getString(R.string.toast_backup_failed))
            }
        }
    }
}
