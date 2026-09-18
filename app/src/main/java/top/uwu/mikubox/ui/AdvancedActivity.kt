package top.uwu.mikubox.ui

import android.os.Bundle
import android.widget.LinearLayout
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.core.CoreOverrides
import top.uwu.mikubox.core.DnsOverrides
import top.uwu.mikubox.databinding.ActivityAdvancedBinding
import top.uwu.mikubox.service.VpnController

/**
 * The structured half of the settings: the DNS panel the desktop clients put
 * behind "DNS overwrite" and the core/TUN knobs they group under "core" and
 * "advanced". Everything here is an override on top of the active profile and
 * takes effect on the next connection.
 */
class AdvancedActivity : EdgeToEdgeActivity() {

    /** How a changed setting is put into effect. */
    private enum class Apply {
        /** The core reads it at start, so the tunnel has to reload. */
        CORE,

        /** The running tunnel can adopt it as is. */
        RUNTIME,
    }

    private lateinit var binding: ActivityAdvancedBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        renderDns()
        renderCore()
        renderNetwork()
    }

    /**
     * Hands a changed setting to the running tunnel: a reload for anything the
     * core reads at start, an in-place refresh for the rest. Disconnected, both
     * are no-ops and the next connection simply picks the setting up.
     */
    private fun applyChange(apply: Apply) {
        if (apply == Apply.RUNTIME) {
            VpnController.refresh(this)
            return
        }
        val running = VpnController.isRunning
        VpnController.restart(this)
        if (running) UwuSnackbar.info(this, getString(R.string.advanced_restarting))
    }

    // ------------------------------------------------------------------ DNS

    private fun renderDns() {
        val entries = listOf(
            SettingsForm.Entry(
                title = R.string.dns_enhanced_mode,
                summary = R.string.dns_enhanced_mode_summary,
                value = { getString(DnsOverrides.enhancedMode(this).label()) },
            ) { pickEnhancedMode() },
            textEntry(
                R.string.dns_fake_ip_range,
                R.string.dns_fake_ip_range_summary,
                { DnsOverrides.fakeIpRange(this) },
                { DnsOverrides.setFakeIpRange(this, it) },
            ),
            textEntry(
                R.string.dns_fake_ip_filter,
                R.string.dns_fake_ip_filter_summary,
                { DnsOverrides.fakeIpFilter(this) },
                { DnsOverrides.setFakeIpFilter(this, it) },
            ),
            textEntry(
                R.string.dns_nameserver,
                R.string.dns_nameserver_summary,
                { DnsOverrides.nameserver(this) },
                { DnsOverrides.setNameserver(this, it) },
            ),
            textEntry(
                R.string.dns_default_nameserver,
                R.string.dns_default_nameserver_summary,
                { DnsOverrides.defaultNameserver(this) },
                { DnsOverrides.setDefaultNameserver(this, it) },
            ),
            textEntry(
                R.string.dns_proxy_nameserver,
                R.string.dns_proxy_nameserver_summary,
                { DnsOverrides.proxyServerNameserver(this) },
                { DnsOverrides.setProxyServerNameserver(this, it) },
            ),
            textEntry(
                R.string.dns_direct_nameserver,
                R.string.dns_direct_nameserver_summary,
                { DnsOverrides.directNameserver(this) },
                { DnsOverrides.setDirectNameserver(this, it) },
            ),
            textEntry(
                R.string.dns_fallback,
                R.string.dns_fallback_summary,
                { DnsOverrides.fallback(this) },
                { DnsOverrides.setFallback(this, it) },
            ),
            textEntry(
                R.string.dns_fallback_cidr,
                R.string.dns_fallback_cidr_summary,
                { DnsOverrides.fallbackIpCidr(this) },
                { DnsOverrides.setFallbackIpCidr(this, it) },
            ),
            triEntry(
                R.string.dns_fallback_geoip,
                R.string.dns_fallback_geoip_summary,
                { DnsOverrides.fallbackGeoIp(this) },
                { DnsOverrides.setFallbackGeoIp(this, it) },
            ),
            triEntry(
                R.string.dns_respect_rules,
                R.string.dns_respect_rules_summary,
                { DnsOverrides.respectRules(this) },
                { DnsOverrides.setRespectRules(this, it) },
            ),
            triEntry(
                R.string.dns_use_hosts,
                R.string.dns_use_hosts_summary,
                { DnsOverrides.useHosts(this) },
                { DnsOverrides.setUseHosts(this, it) },
            ),
            triEntry(
                R.string.dns_use_system_hosts,
                R.string.dns_use_system_hosts_summary,
                { DnsOverrides.useSystemHosts(this) },
                { DnsOverrides.setUseSystemHosts(this, it) },
            ),
            triEntry(
                R.string.dns_prefer_h3,
                R.string.dns_prefer_h3_summary,
                { DnsOverrides.preferH3(this) },
                { DnsOverrides.setPreferH3(this, it) },
            ),
            triEntry(
                R.string.dns_ipv6,
                R.string.dns_ipv6_summary,
                { DnsOverrides.ipv6(this) },
                { DnsOverrides.setIpv6(this, it) },
            ),
            triEntry(
                R.string.dns_direct_policy,
                R.string.dns_direct_policy_summary,
                { DnsOverrides.directFollowPolicy(this) },
                { DnsOverrides.setDirectFollowPolicy(this, it) },
            ),
            SettingsForm.Entry(
                title = R.string.dns_cache_algorithm,
                summary = R.string.dns_cache_algorithm_summary,
                value = { getString(DnsOverrides.cacheAlgorithm(this).label()) },
            ) { pickCacheAlgorithm() },
        )
        SettingsForm.render(binding.listDns, entries)
    }

    private fun pickEnhancedMode() {
        val values = DnsOverrides.EnhancedMode.entries
        UwuDialogs.choose(
            context = this,
            title = getString(R.string.dns_enhanced_mode),
            items = values.map { getString(it.label()) }.toTypedArray(),
            selected = values.indexOf(DnsOverrides.enhancedMode(this)).coerceAtLeast(0),
        ) { index ->
            DnsOverrides.setEnhancedMode(this, values[index])
            renderDns()
            applyChange(Apply.CORE)
        }
    }

    private fun pickCacheAlgorithm() {
        val values = DnsOverrides.CacheAlgorithm.entries
        UwuDialogs.choose(
            context = this,
            title = getString(R.string.dns_cache_algorithm),
            items = values.map { getString(it.label()) }.toTypedArray(),
            selected = values.indexOf(DnsOverrides.cacheAlgorithm(this)).coerceAtLeast(0),
        ) { index ->
            DnsOverrides.setCacheAlgorithm(this, values[index])
            renderDns()
            applyChange(Apply.CORE)
        }
    }

    private fun DnsOverrides.EnhancedMode.label(): Int = when (this) {
        DnsOverrides.EnhancedMode.FAKE_IP -> R.string.dns_mode_fake_ip
        DnsOverrides.EnhancedMode.REDIR_HOST -> R.string.dns_mode_redir_host
        DnsOverrides.EnhancedMode.NORMAL -> R.string.dns_mode_normal
        DnsOverrides.EnhancedMode.FOLLOW -> R.string.opt_follow
    }

    private fun DnsOverrides.CacheAlgorithm.label(): Int = when (this) {
        DnsOverrides.CacheAlgorithm.ARC -> R.string.dns_cache_arc
        DnsOverrides.CacheAlgorithm.LRU -> R.string.dns_cache_lru
        DnsOverrides.CacheAlgorithm.FOLLOW -> R.string.opt_follow
    }

    // ----------------------------------------------------------------- core

    private fun renderCore() {
        val entries = listOf(
            SettingsForm.Entry(
                title = R.string.core_find_process,
                summary = R.string.core_find_process_summary,
                value = { getString(CoreOverrides.findProcess(this).label()) },
            ) { pickFindProcess() },
            SettingsForm.Entry(
                title = R.string.core_geodata_loader,
                summary = R.string.core_geodata_loader_summary,
                value = { getString(CoreOverrides.geodataLoader(this).label()) },
            ) { pickGeodataLoader() },
            triEntry(
                R.string.core_geodata_mode,
                R.string.core_geodata_mode_summary,
                { CoreOverrides.geodataMode(this) },
                { CoreOverrides.setGeodataMode(this, it) },
            ),
            SettingsForm.Entry(
                title = R.string.core_fingerprint,
                summary = R.string.core_fingerprint_summary,
                value = { getString(CoreOverrides.clientFingerprint(this).label()) },
            ) { pickFingerprint() },
            numberEntry(
                R.string.core_keep_alive,
                R.string.core_keep_alive_summary,
                { CoreOverrides.keepAliveInterval(this) },
                { CoreOverrides.setKeepAliveInterval(this, it) },
            ),
            triEntry(
                R.string.core_disable_keep_alive,
                R.string.core_disable_keep_alive_summary,
                { CoreOverrides.disableKeepAlive(this) },
                { CoreOverrides.setDisableKeepAlive(this, it) },
            ),
            numberEntry(
                R.string.core_mixed_port,
                R.string.core_mixed_port_summary,
                { CoreOverrides.mixedPort(this) },
                { CoreOverrides.setMixedPort(this, it) },
            ),
            numberEntry(
                R.string.tun_udp_timeout,
                R.string.tun_udp_timeout_summary,
                { CoreOverrides.udpTimeout(this) },
                { CoreOverrides.setUdpTimeout(this, it) },
            ),
            numberEntry(
                R.string.tun_icmp_timeout,
                R.string.tun_icmp_timeout_summary,
                { CoreOverrides.icmpTimeout(this) },
                { CoreOverrides.setIcmpTimeout(this, it) },
            ),
            triEntry(
                R.string.tun_endpoint_nat,
                R.string.tun_endpoint_nat_summary,
                { CoreOverrides.endpointIndependentNat(this) },
                { CoreOverrides.setEndpointIndependentNat(this, it) },
            ),
            triEntry(
                R.string.tun_disable_icmp,
                R.string.tun_disable_icmp_summary,
                { CoreOverrides.disableIcmpForwarding(this) },
                { CoreOverrides.setDisableIcmpForwarding(this, it) },
            ),
        )
        SettingsForm.render(binding.listCore, entries)
    }

    /** App-side network behaviour: sniffing recovery and keeping the CPU awake. */
    private fun renderNetwork() {
        val entries = listOf(
            triEntry(
                R.string.core_sniffing,
                R.string.core_sniffing_summary,
                { CoreOverrides.sniffing(this) },
                { CoreOverrides.setSniffing(this, it) },
            ),
            triEntry(
                R.string.core_sniff_override,
                R.string.core_sniff_override_summary,
                { CoreOverrides.sniffOverrideDestination(this) },
                { CoreOverrides.setSniffOverrideDestination(this, it) },
            ),
            triEntry(
                R.string.core_wake_lock,
                R.string.core_wake_lock_summary,
                { CoreOverrides.wakeLock(this) },
                apply = Apply.RUNTIME,
                set = { CoreOverrides.setWakeLock(this, it) },
            ),
            SettingsForm.Entry(
                title = R.string.controller_title,
                summary = R.string.controller_summary,
                value = { getString(controllerLabel()) },
                switch = { CoreOverrides.controller(this) == CoreOverrides.ON },
            ) { toggleController() },
            SettingsForm.Entry(
                title = R.string.controller_address,
                summary = R.string.controller_address_summary,
                value = { CoreOverrides.controllerAddress(this) },
            ) { editControllerAddress() },
            SettingsForm.Entry(
                title = R.string.controller_secret,
                summary = R.string.controller_secret_summary,
                value = { maskSecret(CoreOverrides.controllerSecret(this)) },
            ) { editControllerSecret() },
            SettingsForm.Entry(
                title = R.string.controller_secret_new,
                summary = R.string.controller_secret_new_summary,
                value = { "" },
            ) {
                CoreOverrides.regenerateControllerSecret(this)
                UwuSnackbar.info(this, getString(R.string.controller_secret_regenerated))
                renderNetwork()
                applyChange(Apply.CORE)
            },
            SettingsForm.Entry(
                title = R.string.recents_title,
                summary = R.string.recents_summary,
                value = { getString(if (AppSettings.hideFromRecents(this)) R.string.opt_on else R.string.opt_off) },
                switch = { AppSettings.hideFromRecents(this) },
            ) {
                AppSettings.setHideFromRecents(this, !AppSettings.hideFromRecents(this))
                renderNetwork()
            },
        )
        SettingsForm.render(binding.listNetwork, entries)
    }

    /** The controller's state as the switch and its value label read it. */
    private fun controllerLabel(): Int = when (CoreOverrides.controller(this)) {
        CoreOverrides.ON -> R.string.opt_on
        CoreOverrides.OFF -> R.string.opt_off
        else -> R.string.opt_follow
    }

    private fun toggleController() {
        val next = if (CoreOverrides.controller(this) == CoreOverrides.ON) {
            CoreOverrides.OFF
        } else {
            CoreOverrides.ON
        }
        CoreOverrides.setController(this, next)
        renderNetwork()
        applyChange(Apply.CORE)
    }

    private fun editControllerAddress() {
        UwuDialogs.input(
            context = this,
            title = getString(R.string.controller_address),
            message = getString(R.string.controller_address_summary),
            initial = CoreOverrides.controllerAddress(this),
            validate = { value ->
                val host = value.substringBeforeLast(':', "")
                val port = value.substringAfterLast(':', "")
                if (host.isNotBlank() && port.toIntOrNull() != null) null else R.string.controller_address_invalid
            },
        ) { value ->
            CoreOverrides.setControllerAddress(this, value)
            renderNetwork()
            applyChange(Apply.CORE)
        }
    }

    /** The secret is only shown while it is being copied or replaced. */
    private fun editControllerSecret() {
        UwuDialogs.input(
            context = this,
            title = getString(R.string.controller_secret),
            message = getString(R.string.controller_secret_summary),
            initial = CoreOverrides.controllerSecret(this),
        ) { value ->
            CoreOverrides.setControllerSecret(this, value)
            renderNetwork()
            applyChange(Apply.CORE)
        }
    }

    private fun maskSecret(secret: String): String =
        if (secret.isBlank()) getString(R.string.opt_follow) else secret.take(6) + "…"

    private fun pickFindProcess() {
        val values = CoreOverrides.FindProcess.entries
        UwuDialogs.choose(
            context = this,
            title = getString(R.string.core_find_process),
            items = values.map { getString(it.label()) }.toTypedArray(),
            selected = values.indexOf(CoreOverrides.findProcess(this)).coerceAtLeast(0),
        ) { index ->
            CoreOverrides.setFindProcess(this, values[index])
            renderCore()
            applyChange(Apply.CORE)
        }
    }

    private fun pickGeodataLoader() {
        val values = CoreOverrides.GeodataLoader.entries
        UwuDialogs.choose(
            context = this,
            title = getString(R.string.core_geodata_loader),
            items = values.map { getString(it.label()) }.toTypedArray(),
            selected = values.indexOf(CoreOverrides.geodataLoader(this)).coerceAtLeast(0),
        ) { index ->
            CoreOverrides.setGeodataLoader(this, values[index])
            renderCore()
            applyChange(Apply.CORE)
        }
    }

    private fun pickFingerprint() {
        val values = CoreOverrides.ClientFingerprint.entries
        UwuDialogs.choose(
            context = this,
            title = getString(R.string.core_fingerprint),
            items = values.map { getString(it.label()) }.toTypedArray(),
            selected = values.indexOf(CoreOverrides.clientFingerprint(this)).coerceAtLeast(0),
        ) { index ->
            CoreOverrides.setClientFingerprint(this, values[index])
            renderCore()
            applyChange(Apply.CORE)
        }
    }

    private fun CoreOverrides.FindProcess.label(): Int = when (this) {
        CoreOverrides.FindProcess.ALWAYS -> R.string.core_find_process_always
        CoreOverrides.FindProcess.STRICT -> R.string.core_find_process_strict
        CoreOverrides.FindProcess.OFF_MODE -> R.string.core_find_process_off
        CoreOverrides.FindProcess.FOLLOW -> R.string.opt_follow
    }

    private fun CoreOverrides.GeodataLoader.label(): Int = when (this) {
        CoreOverrides.GeodataLoader.STANDARD -> R.string.core_geodata_standard
        CoreOverrides.GeodataLoader.MEMORY -> R.string.core_geodata_memory
        CoreOverrides.GeodataLoader.FOLLOW -> R.string.opt_follow
    }

    private fun CoreOverrides.ClientFingerprint.label(): Int = when (this) {
        CoreOverrides.ClientFingerprint.CHROME -> R.string.fp_chrome
        CoreOverrides.ClientFingerprint.FIREFOX -> R.string.fp_firefox
        CoreOverrides.ClientFingerprint.SAFARI -> R.string.fp_safari
        CoreOverrides.ClientFingerprint.IOS -> R.string.fp_ios
        CoreOverrides.ClientFingerprint.ANDROID -> R.string.fp_android
        CoreOverrides.ClientFingerprint.RANDOM -> R.string.fp_random
        CoreOverrides.ClientFingerprint.NONE -> R.string.fp_none
        CoreOverrides.ClientFingerprint.FOLLOW -> R.string.opt_follow
    }

    // --------------------------------------------------------------- entries

    /**
     * Three-way switch: keep the profile's value, force it on, force it off.
     * Options that also exist in the profile are editable this way rather than
     * silently losing to whatever the subscription shipped.
     */
    private fun triEntry(
        title: Int,
        summary: Int,
        state: () -> Int,
        set: (Int) -> Unit,
        apply: Apply = Apply.CORE,
    ): SettingsForm.Entry {
        val states = listOf(DnsOverrides.UNSET, DnsOverrides.ON, DnsOverrides.OFF)
        return SettingsForm.Entry(
            title = title,
            summary = summary,
            value = {
                getString(
                    when (state()) {
                        DnsOverrides.ON -> R.string.opt_on
                        DnsOverrides.OFF -> R.string.opt_off
                        else -> R.string.opt_follow
                    },
                )
            },
            onPick = {
                UwuDialogs.choose(
                    context = this,
                    title = getString(title),
                    items = arrayOf(
                        getString(R.string.opt_follow),
                        getString(R.string.opt_on),
                        getString(R.string.opt_off),
                    ),
                    selected = states.indexOf(state()).coerceAtLeast(0),
                ) { index ->
                    set(states[index])
                    renderDns()
                    renderCore()
                    renderNetwork()
                    applyChange(apply)
                }
            },
        )
    }

    private fun textEntry(
        title: Int,
        summary: Int,
        current: () -> String,
        set: (String) -> Unit,
        apply: Apply = Apply.CORE,
    ) = SettingsForm.Entry(
        title = title,
        summary = summary,
        value = { current().ifBlank { getString(R.string.opt_follow) } },
        onPick = {
            UwuDialogs.input(
                context = this,
                title = getString(title),
                message = getString(summary),
                initial = current(),
            ) { value ->
                set(value)
                renderDns()
                renderCore()
                applyChange(apply)
            }
        },
    )

    /** Numbers use 0 as "keep the profile's value", matching the models. */
    private fun numberEntry(
        title: Int,
        summary: Int,
        current: () -> Int,
        set: (Int) -> Unit,
    ) = SettingsForm.Entry(
        title = title,
        summary = summary,
        value = { current().takeIf { it > 0 }?.toString() ?: getString(R.string.opt_follow) },
        onPick = {
            UwuDialogs.input(
                context = this,
                title = getString(title),
                message = getString(summary),
                initial = current().takeIf { it > 0 }?.toString().orEmpty(),
                validate = { value ->
                    if (value.isBlank() || value.toIntOrNull() != null) null else R.string.error_number
                },
            ) { value ->
                set(value.trim().toIntOrNull() ?: 0)
                renderDns()
                renderCore()
                renderNetwork()
                applyChange(Apply.CORE)
            }
        },
    )

    /** Shown after a change: the core reads these when it starts. */
    override fun onResume() {
        super.onResume()
        binding.tvHint.text = getString(
            if (VpnController.isRunning) R.string.advanced_hint_running else R.string.advanced_hint,
        )
    }
}
