package top.uwu.mikubox.ui.tools

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.ui.EdgeToEdgeActivity
import top.uwu.mikubox.ui.UwuSnackbar
import java.net.InetSocketAddress

/** NAT / public-endpoint test, the release build's STUN tool. */
class StunActivity : EdgeToEdgeActivity() {

    private lateinit var serverInput: TextInputEditText
    private lateinit var result: TextView
    private lateinit var button: MaterialButton
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tool_stun)
        applySystemBarInsets(findViewById(R.id.tool_scroll))
        findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title =
            getString(R.string.tool_stun)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        serverInput = findViewById(R.id.tool_input)
        serverInput.setText(DEFAULT_SERVER)
        result = findViewById(R.id.tool_result)
        button = findViewById(R.id.tool_run)
        button.setOnClickListener { start() }
    }

    private fun start() {
        if (running) return
        val server = serverInput.text?.toString()?.trim().orEmpty()
        if (server.isBlank()) {
            UwuSnackbar.error(this, getString(R.string.tool_error_empty))
            return
        }
        running = true
        button.isEnabled = false
        result.text = getString(R.string.tool_running)
        lifecycleScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) { probe(server) }
            }.getOrElse { getString(R.string.tool_failed, it.message ?: "") }
            result.text = text
            running = false
            button.isEnabled = true
        }
    }

    /**
     * Asks two independent STUN servers for the mapped endpoint: the same
     * address from both sides reads as one public endpoint, different ports
     * mean the NAT rewrites per destination (symmetric).
     */
    private fun probe(server: String): String {
        val mapped = StunClient.bindingRequest(server)
            ?: return getString(R.string.tool_stun_unreachable, server)
        val second = StunClient.bindingRequest(SECOND_SERVER)
        val builder = StringBuilder(getString(R.string.tool_stun_result, mapped.host, mapped.port))
        builder.append('\n').append(getString(R.string.tool_stun_via, server))
        if (second != null) {
            builder.append('\n').append(
                if (second == mapped) {
                    getString(R.string.tool_stun_cone)
                } else {
                    getString(R.string.tool_stun_symmetric, second.toString())
                }
            )
        }
        builder.append('\n').append(
            getString(
                R.string.tool_stun_local,
                localAddress()?.hostAddress ?: "",
            )
        )
        return builder.toString()
    }

    private fun localAddress(): java.net.InetAddress? = runCatching {
        val socket = java.net.DatagramSocket()
        try {
            socket.connect(InetSocketAddress("8.8.8.8", 53))
            socket.localAddress
        } finally {
            socket.close()
        }
    }.getOrNull()

    private companion object {
        const val DEFAULT_SERVER = "stun.l.google.com:19302"
        const val SECOND_SERVER = "stun1.l.google.com:19304"
    }
}
