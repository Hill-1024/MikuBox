package top.uwu.mikubox.ui

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.ActivityToolsBinding
import top.uwu.mikubox.ui.tools.HostnameFinderActivity
import top.uwu.mikubox.ui.tools.HostToIpActivity
import top.uwu.mikubox.ui.tools.HotspotShareActivity
import top.uwu.mikubox.ui.tools.PingActivity
import top.uwu.mikubox.ui.tools.SpeedTestActivity
import top.uwu.mikubox.ui.tools.StunActivity
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * Tools hub. Tab one is the tunnel probe (URL/latency test plus a network
 * dump); the small-tool rows below are the utilities ported from the release
 * build's tools page.
 */
class ToolsActivity : EdgeToEdgeActivity() {

    private lateinit var binding: ActivityToolsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolsTab.addTab(
            binding.toolsTab.newTab().setText(getString(R.string.tools_connection_test))
        )
        binding.toolsTab.addTab(
            binding.toolsTab.newTab().setText(getString(R.string.tools_tab_small))
        )
        binding.toolsTab.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val probe = tab.position == 0
                binding.cardProbe.visibility = if (probe) android.view.View.VISIBLE else android.view.View.GONE
                binding.cardNetInfo.visibility =
                    if (probe) android.view.View.VISIBLE else android.view.View.GONE
                binding.sectionTools.visibility =
                    if (probe) android.view.View.GONE else android.view.View.VISIBLE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.btnTest.setOnClickListener { runTest() }
        binding.tvNetworkInfo.text = networkInfo()
        bindToolRows()
    }

    /** The ported small tools, one row each, all plain activity starts. */
    private fun bindToolRows() {
        UwuRow.bind(
            binding.toolPing,
            UwuRow.Slot.TOP,
            R.drawable.ic_connection,
            getString(R.string.tool_ping),
            getString(R.string.tool_ping_summary),
            arrow = true,
        ) { open(PingActivity::class.java) }
        UwuRow.bind(
            binding.toolSpeed,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_speed_24,
            getString(R.string.tool_speed),
            getString(R.string.tool_speed_summary),
            arrow = true,
        ) { open(SpeedTestActivity::class.java) }
        UwuRow.bind(
            binding.toolHostToIp,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_domain,
            getString(R.string.tool_host_to_ip),
            getString(R.string.tool_host_to_ip_summary),
            arrow = true,
        ) { open(HostToIpActivity::class.java) }
        UwuRow.bind(
            binding.toolHostname,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_search_icon,
            getString(R.string.tool_hostname_finder),
            getString(R.string.tool_hostname_summary),
            arrow = true,
        ) { open(HostnameFinderActivity::class.java) }
        UwuRow.bind(
            binding.toolStun,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_wan,
            getString(R.string.tool_stun),
            getString(R.string.tool_stun_summary),
            arrow = true,
        ) { open(StunActivity::class.java) }
        UwuRow.bind(
            binding.toolHotspot,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_lan,
            getString(R.string.tool_hotspot),
            getString(R.string.tool_hotspot_summary),
            arrow = true,
        ) { open(HotspotShareActivity::class.java) }
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    private fun runTest() {
        val url = binding.etTestUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) return
        binding.btnTest.isEnabled = false
        binding.tvTestResult.text = getString(R.string.tools_testing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { probe(url) }
            binding.tvTestResult.text = result
            binding.btnTest.isEnabled = true
        }
    }

    private fun probe(url: String): String = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            instanceFollowRedirects = false
        }
        val start = SystemClock.elapsedRealtime()
        conn.connect()
        val code = conn.responseCode
        val elapsed = SystemClock.elapsedRealtime() - start
        conn.disconnect()
        getString(R.string.tools_test_ok, code, elapsed)
    }.getOrElse { getString(R.string.tools_test_failed, it.message ?: it.javaClass.simpleName) }

    private fun networkInfo(): String {
        val sb = StringBuilder()
        val cm = getSystemService<ConnectivityManager>()
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> getString(R.string.tools_net_none)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> getString(R.string.tools_net_other)
        }
        sb.append(getString(R.string.tools_net_transport, transport)).append('\n')

        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .forEach { nic ->
                    val addrs = nic.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .joinToString(", ") { it.hostAddress ?: "" }
                    if (addrs.isNotBlank()) sb.append(nic.name).append(": ").append(addrs).append('\n')
                }
        }
        return sb.toString().trimEnd()
    }
}
