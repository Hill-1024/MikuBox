package top.uwu.mikubox.ui.tools

import android.content.Context
import androidx.annotation.StringRes
import top.uwu.mikubox.R
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException

/**
 * Host checks shared by the small tools. The tools that fire HTTP requests may
 * only talk to public http/https endpoints: localhost, loopback, private and
 * other reserved ranges are refused before anything goes out.
 *
 * Everything here does DNS work — callers must invoke it off the main thread.
 */
object ToolHosts {

    private val HOST_PATTERN = Regex("^[A-Za-z0-9.\\-_]{1,253}$")

    /** Shared syntax check: what a host must look like before we touch it. */
    fun syntacticError(host: String): Int? = when {
        host.isBlank() -> R.string.tool_error_empty
        !HOST_PATTERN.matches(host) -> R.string.tool_error_invalid_host
        else -> null
    }

    /**
     * Resolves [host] and applies the address policy. [allowPrivate] keeps the
     * ping/lookup tools useful inside a LAN; URL-based tools must not enable it.
     * Returns the resolved addresses, or the error to show.
     */
    fun resolve(
        context: Context,
        host: String,
        allowPrivate: Boolean,
    ): Lookup = try {
        val addresses = InetAddress.getAllByName(host).filterNotNull()
        when {
            addresses.isEmpty() -> Lookup(error = context.getString(R.string.tool_error_unresolved))
            !allowPrivate && addresses.any { it.isReserved() } ->
                Lookup(error = context.getString(R.string.tool_error_private))

            else -> Lookup(addresses = addresses)
        }
    } catch (e: UnknownHostException) {
        Lookup(error = context.getString(R.string.tool_error_unresolved))
    } catch (e: Exception) {
        Lookup(error = context.getString(R.string.tool_failed, e.message ?: ""))
    }

    /** Validates an http/https URL for the request-based tools. */
    fun checkUrl(context: Context, url: String): URL? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if (parsed.protocol != "http" && parsed.protocol != "https") return null
        val host = parsed.host ?: return null
        if (syntacticError(host) != null) return null
        val lookup = resolve(context, host, allowPrivate = false)
        return if (lookup.error != null) null else parsed
    }

    class Lookup(
        val addresses: List<InetAddress> = emptyList(),
        val error: String? = null,
    )

    /**
     * Loopback, link-local, unique-local, CGNAT, benchmark and the other
     * ranges that must never be probed over the network.
     */
    fun InetAddress.isReserved(): Boolean {
        if (isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress ||
            isSiteLocalAddress || isMulticastAddress
        ) {
            return true
        }
        val bytes = address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0) return true                        // 0.0.0.0/8
            if (first == 100 && second in 64..127) return true // 100.64.0.0/10
            if (first == 192 && second == 0 && (bytes[2].toInt() and 0xFF) == 2) return true
            if (first in 198..199 && (bytes[2].toInt() and 0xFF) in 18..19) return true
            if (first in 224..255) return true                 // multicast + reserved
        } else if (bytes.size == 16) {
            if (bytes[0].toInt() and 0xFF == 0xFF) return true // multicast
            if (bytes[0].toInt() and 0xFE == 0xFC) return true // unique-local fc00::/7
        }
        return false
    }
}
