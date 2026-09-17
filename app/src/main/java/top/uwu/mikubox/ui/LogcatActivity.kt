package top.uwu.mikubox.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.core.MihomoDnsSettings
import top.uwu.mikubox.databinding.ActivityLogcatBinding
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.service.VpnController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-app logcat viewer for the app's own process, with a shareable log export. */
class LogcatActivity : EdgeToEdgeActivity() {

    private lateinit var binding: ActivityLogcatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogcatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_logcat)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_export_log) {
                exportLogs()
                true
            } else {
                false
            }
        }
        binding.fabClear.setOnClickListener {
            runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() }
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) { readLogcat(600) }
            binding.logcatText.text = log
            binding.logcatScroll.post {
                binding.logcatScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    /**
     * Writes a diagnostic report (app/core versions, active profile summary and
     * the full process log) to a cache file and hands it to the share sheet, so
     * a startup failure can be reported without a cable or adb.
     */
    private fun exportLogs() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { writeReport() } }
            result
                .onSuccess { file -> shareReport(file) }
                .onFailure {
                    Toast.makeText(
                        this@LogcatActivity,
                        getString(R.string.logcat_export_failed, it.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private suspend fun writeReport(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "mikubox-log-$stamp.txt")
        file.writeText(buildString {
            appendLine("=== MikuBox diagnostics ===")
            appendLine("exported_at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("package: $packageName")
            appendLine("version: ${appVersion()}")
            appendLine("core: ${runCatching { MihomoCore.version() }.getOrDefault("unavailable")}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            appendLine("vpn_running: ${VpnController.isRunning}")
            appendLine("mode_override: ${MihomoCoreSettings.mode(this@LogcatActivity)}")
            appendLine("dns_source: ${MihomoDnsSettings.source(this@LogcatActivity)}")
            appendProfileSummary()
            appendCoreState()
            appendLine()
            appendLine("=== core log (persisted, last session) ===")
            appendLine(readCoreLog())
            appendLine("=== process log (${Process.myPid()}) ===")
            appendLine(readLogcat(PROCESS_LOG_LINES, pid = Process.myPid()))
            appendLine("=== crash log ===")
            appendLine(readLogcat(200, buffer = "crash"))
        })
        return file
    }

    /**
     * Reads the core log the bridge persists next to its home directory. The
     * core logs its own startup, DNS and every proxied connection there, so a
     * failed connection attempt stays reviewable even after logcat rotated.
     */
    private fun readCoreLog(): String = runCatching {
        val file = File(File(filesDir, "mihomo"), "core.log")
        if (!file.exists()) return "not present (core has not started yet)"
        val lines = file.readLines()
        lines.takeLast(CORE_LOG_LINES).joinToString("\n")
    }.getOrElse { "unreadable: ${it.message}" }

    /**
     * Effective core configuration (stack, MTU, DNS mode, gVisor support) plus
     * the live group/node state, so a report shows whether nodes answer at all.
     */
    private suspend fun StringBuilder.appendCoreState() {
        appendLine()
        appendLine("=== core state ===")
        appendLine("runtime: ${MihomoCore.runtimeInfo()}")
        val proxies = runCatching { MihomoCore.proxies() }.getOrDefault(emptyMap())
        if (proxies.isEmpty()) {
            appendLine("proxies: none (core not running)")
            return
        }
        proxies.values.filter { it.isGroup }.forEach { group ->
            appendLine("group ${group.name} (${group.type}) now=${group.now ?: "-"} pinned=${group.pinnedNode ?: "-"} members=${group.all.size}")
        }
        proxies.values.filterNot { it.isGroup }.forEach { node ->
            appendLine("node ${node.name} (${node.type}) delay=${if (node.delay > 0) "${node.delay}ms" else "untested"}")
        }
        appendProbes(proxies)
    }

    /**
     * Actively URL-tests the first few members of one group so the report shows
     * whether the proxy nodes are reachable at all, without the user having to
     * trigger a latency test first.
     */
    private suspend fun StringBuilder.appendProbes(proxies: Map<String, MihomoCore.Proxy>) {
        val group = proxies.values.firstOrNull { it.isGroup && it.all.isNotEmpty() } ?: return
        val members = group.all.take(PROBE_COUNT)
        val testUrl = MihomoCoreSettings.testUrl(this@LogcatActivity)
        appendLine("probe: ${group.name} (${testUrl}, ${PROBE_TIMEOUT_MS}ms)")
        val results = coroutineScope {
            members.map { name ->
                async(Dispatchers.IO) { name to MihomoCore.delay(name, testUrl, PROBE_TIMEOUT_MS) }
            }.awaitAll()
        }
        results.forEach { (name, delay) ->
            appendLine("probe $name = ${if (delay > 0) "${delay}ms" else "timeout"}")
        }
    }

    /** Section describing the selected profile without leaking node credentials. */
    private fun StringBuilder.appendProfileSummary() {
        val context = this@LogcatActivity
        val profile = MihomoProfileStore.selected(context)
        if (profile == null) {
            appendLine("profile: none selected")
            return
        }
        val config = profile.config
        appendLine("profile: ${profile.name}")
        appendLine("profile_subscription: ${profile.isSubscription}")
        appendLine("config_chars: ${config.length}")
        appendLine("config_has_dns: ${MihomoDnsSettings.configHasDns(config)}")
        appendLine("config_has_listen: ${config.contains(Regex("^\\s+listen:", RegexOption.MULTILINE))}")
        appendLine("config_has_unix_controller: ${config.contains("external-controller-unix")}")
        appendLine("config_proxy_count: ${countSection(config, "proxies")}")
        appendLine("config_group_count: ${countSection(config, "proxy-groups")}")
        appendLine("config_rule_count: ${countSection(config, "rules")}")
    }

    /** Counts the top-level `- ` entries of a YAML section, for a quick sanity check. */
    private fun countSection(config: String, section: String): Int {
        val lines = config.lines()
        val start = lines.indexOfFirst { it.startsWith("$section:") }
        if (start < 0) return 0
        return lines.drop(start + 1)
            .takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("\t") || it.trimStart().startsWith("#") }
            .count { it.trimStart().startsWith("- ") }
    }

    private fun shareReport(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.logcat_export)))
    }

    private fun appVersion(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("unknown")

    private fun readLogcat(lines: Int, pid: Int? = null, buffer: String? = null): String = runCatching {
        val command = mutableListOf("logcat", "-d", "-v", "threadtime", "-t", lines.toString())
        buffer?.let { command += listOf("-b", it) }
        pid?.let { command += listOf("--pid=$it") }
        val process = Runtime.getRuntime().exec(command.toTypedArray())
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { getString(R.string.logcat_read_failed, it.message ?: "") }

    private companion object {
        const val PROCESS_LOG_LINES = 4000
        const val CORE_LOG_LINES = 3000
        const val PROBE_COUNT = 4
        const val PROBE_TIMEOUT_MS = 3000
    }
}
