package top.uwu.mikubox.ui.tools

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.ui.EdgeToEdgeActivity
import top.uwu.mikubox.ui.UwuSnackbar
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Download speed test, the release build's speed tool: streams a fixed payload
 * from a fixed https endpoint and reports the observed goodput. The endpoints
 * are built-in constants, so no user-supplied URL is ever requested.
 */
class SpeedTestActivity : EdgeToEdgeActivity() {

    private lateinit var result: TextView
    private lateinit var button: MaterialButton
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tool_speed)
        applySystemBarInsets(findViewById(R.id.tool_scroll))
        findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title =
            getString(R.string.tool_speed)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }
        result = findViewById(R.id.tool_result)
        button = findViewById(R.id.tool_run)
        button.setOnClickListener { start() }
        render(0.0, 0, getString(R.string.tool_speed_idle))
    }

    private fun start() {
        if (running) return
        running = true
        button.isEnabled = false
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { measure() }
            }
            running = false
            button.isEnabled = true
            outcome
                .onSuccess { (mbps, bytes) -> render(mbps, bytes, null) }
                .onFailure {
                    render(0.0, 0, getString(R.string.tool_failed, it.message ?: ""))
                    UwuSnackbar.error(this@SpeedTestActivity, it.message ?: "")
                }
        }
    }

    private data class Outcome(val mbps: Double, val bytes: Long)

    private fun measure(): Outcome {
        val url = URL(SPEED_URL)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Cache-Control", "no-cache")
            val startedAt = System.nanoTime()
            var bytes = 0L
            connection.inputStream.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (bytes < BYTE_BUDGET) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    bytes += read
                }
            }
            val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
            if (bytes == 0L) throw IOException(getString(R.string.tool_speed_no_data))
            val mbps = bytes * 8 / seconds / 1_000_000.0
            return Outcome(mbps, bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun render(mbps: Double, bytes: Long, error: String?) {
        findViewById<View>(R.id.progress_bar).visibility =
            if (running) View.VISIBLE else View.GONE
        if (error != null) {
            result.text = error
            return
        }
        if (mbps == 0.0) {
            result.text = error ?: getString(R.string.tool_speed_idle)
            return
        }
        result.text = getString(
            R.string.tool_speed_result,
            String.format(java.util.Locale.US, "%.2f", mbps),
            formatBytes(bytes),
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private companion object {
        const val SPEED_URL =
            "https://speed.cloudflare.com/__down?bytes=25000000"
        const val BYTE_BUDGET = 32L * 1024 * 1024
    }
}
