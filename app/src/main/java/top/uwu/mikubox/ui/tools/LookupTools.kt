package top.uwu.mikubox.ui.tools

import top.uwu.mikubox.R
import java.net.InetAddress

/** Ping a host with the system `ping` binary, the way the release build does. */
class PingActivity : ToolLookupActivity(
    titleRes = R.string.tool_ping,
    hintRes = R.string.tool_ping_hint,
    allowPrivateHost = true, // pinging the router is a normal LAN diagnostic
) {
    override fun run(
        host: String,
        allowPrivate: Boolean,
        addresses: List<InetAddress>,
    ): String {
        // Runtime.exec with an argument array — no shell, no injection surface.
        val process = ProcessBuilder("ping", "-c", "4", "-W", "3", host)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        return output.trim().ifBlank {
            if (code == 0) "" else getString(R.string.tool_ping_failed)
        }
    }
}

/** Host → every address the resolver offers, the local-DNS half of Host→IP. */
class HostToIpActivity : ToolLookupActivity(
    titleRes = R.string.tool_host_to_ip,
    hintRes = R.string.tool_host_hint,
    // Display-only: whatever the active DNS answers (including a fake-ip from
    // the tunnel) is shown, and nothing here connects to the result.
    allowPrivateHost = true,
) {
    override fun run(
        host: String,
        allowPrivate: Boolean,
        addresses: List<InetAddress>,
    ): String {
        if (addresses.isEmpty()) return getString(R.string.tool_no_result)
        return buildString {
            appendLine(getString(R.string.tool_host_to_ip_result, host))
            addresses.distinctBy { it.address }.forEachIndexed { index, address ->
                appendLine("${index + 1}. ${address.hostAddress}")
            }
            append(getString(R.string.tool_lookup_hint_geo))
        }
    }
}

/** IP → hostname (reverse DNS), the release build's Hostname Finder core. */
class HostnameFinderActivity : ToolLookupActivity(
    titleRes = R.string.tool_hostname_finder,
    hintRes = R.string.tool_ip_hint,
    allowPrivateHost = true, // display-only, like Host → IP
) {
    override fun run(
        host: String,
        allowPrivate: Boolean,
        addresses: List<InetAddress>,
    ): String {
        val name = addresses.firstOrNull()?.hostName
        return if (name != null && name != host) {
            getString(R.string.tool_hostname_result, host, name)
        } else {
            getString(R.string.tool_hostname_none, host)
        }
    }
}
