package top.uwu.mikubox.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import top.uwu.mikubox.R
import java.io.File

/** JNI entry point for the bundled Mihomo Alpha core. */
object MihomoCore {

    const val NO_TUN = -1

    private const val TAG = "MikuBox"

    data class Traffic(
        val uploadPerSecond: Long,
        val downloadPerSecond: Long,
        val uploadTotal: Long,
        val downloadTotal: Long,
    )

    /**
     * A proxy or proxy-group exposed by the running core. Groups populate [all]
     * (their members) and [now] (the selected member); plain nodes leave them empty.
     * Auto groups (url-test/fallback) expose [fixed] when a member is pinned.
     */
    data class Proxy(
        val name: String,
        val type: String,
        val now: String?,
        val all: List<String>,
        val delay: Int,
        val udp: Boolean,
        val fixed: String? = null,
        val hidden: Boolean = false,
    ) {
        val isGroup: Boolean get() = all.isNotEmpty()
        val isSelector: Boolean get() = type.equals("Selector", ignoreCase = true)

        /** Groups whose member can be chosen or pinned manually (not load-balance). */
        val isSelectableGroup: Boolean get() = type.equals("Selector", ignoreCase = true) ||
            type.equals("URLTest", ignoreCase = true) ||
            type.equals("Fallback", ignoreCase = true)

        /** Groups that pick their member automatically and support pinning one node. */
        val isAutoGroup: Boolean get() = type.equals("URLTest", ignoreCase = true) ||
            type.equals("Fallback", ignoreCase = true)

        /** The pinned member on auto groups, or null/blank when running automatically. */
        val pinnedNode: String? get() = fixed?.takeIf { it.isNotBlank() }
    }

    /** One parsed routing rule from the running configuration. */
    data class Rule(val type: String, val payload: String, val target: String)

    init {
        System.loadLibrary("mihomo")
        System.loadLibrary("mikubox_core")
    }

    fun start(
        context: Context,
        config: String,
        tunFd: Int,
        dnsOverride: String = "",
        overridesJson: String = "",
    ): Result<Unit> = runCatching {
        val home = File(context.filesDir, "mihomo").apply { mkdirs() }
        ensureGeodata(context, home)
        if (nativeStart(config, home.absolutePath, tunFd, dnsOverride, overridesJson) != 0) {
            val detail = nativeLastError().ifBlank { context.getString(R.string.mihomo_start_failed) }
            Log.e(TAG, "core start failed: $detail")
            error(detail)
        }
    }

    /**
     * Restores the bundled GeoSite/GeoIP databases into the core home directory.
     * Full Clash configurations routinely route through GEOSITE/GEOIP rules, and
     * the core aborts startup when they reference data it cannot load. Only
     * missing files are written so core-managed updates survive.
     */
    private fun ensureGeodata(context: Context, home: File) {
        for (name in GEODATA_FILES) {
            val target = File(home, name)
            if (target.exists()) continue
            val partial = File(home, "$name.part")
            runCatching {
                context.assets.open(name).use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                if (!partial.renameTo(target)) partial.delete()
            }.onFailure { partial.delete() }
        }
    }

    fun stop() {
        nativeStop()
    }

    fun version(): String = nativeVersion()

    fun traffic(): Traffic = JSONObject(nativeTraffic()).let {
        Traffic(
            uploadPerSecond = it.optLong("upload"),
            downloadPerSecond = it.optLong("download"),
            uploadTotal = it.optLong("uploadTotal"),
            downloadTotal = it.optLong("downloadTotal"),
        )
    }

    /** Live proxies/groups from the running core, keyed by name. Empty when stopped. */
    fun proxies(): Map<String, Proxy> = runCatching {
        val root = JSONObject(nativeProxies()).optJSONObject("proxies") ?: return emptyMap()
        buildMap {
            root.keys().forEach { key ->
                val obj = root.getJSONObject(key)
                val all = obj.optJSONArray("all").toStringList()
                put(
                    key,
                    Proxy(
                        name = obj.optString("name", key),
                        type = obj.optString("type"),
                        now = obj.optString("now").ifBlank { null },
                        all = all,
                        delay = obj.optJSONArray("history").lastDelay(),
                        udp = obj.optBoolean("udp"),
                        fixed = obj.optString("fixed").ifBlank { null },
                        hidden = obj.optBoolean("hidden"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    /** Declared proxy-group order of the running config; empty when stopped. */
    fun groupOrder(): List<String> = runCatching {
        JSONArray(nativeGroupOrder()).let { array -> List(array.length()) { array.optString(it) } }
    }.getOrDefault(emptyList())

    /** Live routing rules of the running core. Empty when stopped. */
    fun rules(): List<Rule> = runCatching {
        val array = JSONArray(nativeRules())
        List(array.length()) { index ->
            array.getJSONObject(index).let {
                Rule(type = it.optString("type"), payload = it.optString("payload"), target = it.optString("target"))
            }
        }
    }.getOrDefault(emptyList())

    /**
     * Points a selector [group] at one of its members; an empty [name] clears a
     * pinned node on auto groups. Returns true on success.
     */
    fun selectProxy(group: String, name: String): Boolean = nativeSelectProxy(group, name) == 0

    /** URL-tests a proxy, returning its delay in ms, or -1 on failure/timeout. */
    fun delay(name: String, url: String = "https://cp.cloudflare.com", timeoutMs: Int = 5000): Int =
        runCatching { JSONObject(nativeProxyDelay(name, url, timeoutMs)).optInt("delay", -1) }
            .getOrDefault(-1)

    /** Validates a DNS override block; returns null when valid, or an error message. */
    fun validateDns(yaml: String): String? = nativeValidateDns(yaml).ifBlank { null }

    /**
     * Effective core configuration and build facts (stack, MTU, DNS mode,
     * gVisor availability, last error) as a JSON object, for log exports.
     */
    fun runtimeInfo(): String = runCatching { nativeRuntimeInfo() }.getOrDefault("{}")

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else List(length()) { optString(it) }

    private fun JSONArray?.lastDelay(): Int {
        if (this == null || length() == 0) return 0
        return optJSONObject(length() - 1)?.optInt("delay", 0) ?: 0
    }

    private val GEODATA_FILES = arrayOf("geosite.dat", "geoip.metadb")

    private external fun nativeStart(config: String, home: String, tunFd: Int, dnsOverride: String, overridesJson: String): Int
    private external fun nativeStop()
    private external fun nativeLastError(): String
    private external fun nativeVersion(): String
    private external fun nativeTraffic(): String
    private external fun nativeProxies(): String
    private external fun nativeSelectProxy(group: String, name: String): Int
    private external fun nativeProxyDelay(name: String, url: String, timeoutMs: Int): String
    private external fun nativeValidateDns(dnsYaml: String): String
    private external fun nativeGroupOrder(): String
    private external fun nativeRules(): String
    private external fun nativeRuntimeInfo(): String
}
