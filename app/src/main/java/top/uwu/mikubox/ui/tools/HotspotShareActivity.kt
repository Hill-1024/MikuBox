package top.uwu.mikubox.ui.tools

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.getSystemService
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.materialswitch.MaterialSwitch
import top.uwu.mikubox.R
import top.uwu.mikubox.core.CoreOverrides
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.ui.EdgeToEdgeActivity
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LAN-sharing helper, our take on the release build's hotspot tool: it shows
 * whether the mixed port accepts LAN clients, which port to use and which
 * addresses the clients should point at. The sharing itself is done by the
 * core's `allow-lan`, so nothing is proxied here — this page is the guide.
 */
class HotspotShareActivity : EdgeToEdgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tool_hotspot)
        applySystemBarInsets(findViewById(R.id.tool_scroll))
        findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title =
            getString(R.string.tool_hotspot)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        val allowLanSwitch = findViewById<MaterialSwitch>(R.id.switch_allow_lan)
        allowLanSwitch.isChecked = MihomoCoreSettings.allowLan(this)
        allowLanSwitch.setOnClickListener {
            val enabled = !MihomoCoreSettings.allowLan(this)
            MihomoCoreSettings.setAllowLan(this, enabled)
            allowLanSwitch.isChecked = enabled
            renderBody()
        }
        renderBody()
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialSwitch>(R.id.switch_allow_lan).isChecked =
            MihomoCoreSettings.allowLan(this)
        renderBody()
    }

    private fun renderBody() {
        val allowLan = MihomoCoreSettings.allowLan(this)
        val port = CoreOverrides.mixedPort(this).takeIf { it > 0 } ?: DEFAULT_MIXED_PORT
        findViewById<View>(R.id.hotspot_warn).visibility =
            if (allowLan) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.hotspot_addresses).text = buildString {
            appendLine(getString(R.string.tool_hotspot_port, port))
            appendLine()
            val addresses = lanAddresses()
            if (addresses.isEmpty()) {
                appendLine(getString(R.string.tool_hotspot_no_lan))
            } else {
                addresses.forEach { appendLine(it) }
            }
            appendLine()
            append(getString(R.string.tool_hotspot_hint, if (allowLan) 1 else 0))
        }
    }

    private fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { "${nic.name}: ${it.hostAddress ?: ""}" }
            }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private companion object {
        const val DEFAULT_MIXED_PORT = 7890
    }
}
