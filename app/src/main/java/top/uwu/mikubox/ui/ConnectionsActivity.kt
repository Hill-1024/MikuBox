package top.uwu.mikubox.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.databinding.ActivityConnectionsBinding
import top.uwu.mikubox.service.VpnController

/**
 * Live connection list, mirroring what the controller exposes on /connections.
 *
 * Rows are built in code and refreshed once a second while the screen is open;
 * tapping a row closes that connection and the header closes all of them, which
 * is what the desktop clients offer for a stuck transfer. The snapshot and the
 * close calls round-trip through the core, so they run off the main thread.
 */
class ConnectionsActivity : EdgeToEdgeActivity() {

    private lateinit var binding: ActivityConnectionsBinding
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnCloseAll.setOnClickListener {
            lifecycleScope.launch {
                val closed = withContext(Dispatchers.IO) { MihomoCore.closeConnections() }
                if (closed) {
                    UwuSnackbar.info(this@ConnectionsActivity, getString(R.string.connections_closed_all))
                }
                refresh()
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    /** Fetches the snapshot off the main thread, then paints it. */
    private fun refresh() {
        val running = VpnController.isRunning
        lifecycleScope.launch {
            val connections = if (running) {
                withContext(Dispatchers.IO) { MihomoCore.connections() }
            } else {
                emptyList()
            }
            render(running, connections)
        }
    }

    private fun render(running: Boolean, connections: List<MihomoCore.Connection>) {
        binding.tvEmpty.visibility = if (connections.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.setText(
            if (running) R.string.connections_empty else R.string.connections_offline,
        )
        binding.btnCloseAll.isEnabled = connections.isNotEmpty()

        val up = connections.sumOf { it.upload }
        val down = connections.sumOf { it.download }
        binding.tvSummary.text = getString(
            R.string.connections_summary,
            connections.size,
            TrafficFormat.compact(up),
            TrafficFormat.compact(down),
        )

        binding.listConnections.removeAllViews()
        connections.take(MAX_ROWS).forEach { connection ->
            binding.listConnections.addView(row(connection))
        }
    }

    /** One connection: target and counters on top, how it was routed below. */
    private fun row(connection: MihomoCore.Connection): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            setCardBackgroundColor(MaterialColors.getColor(this, R.attr.colorCard))
            radius = 16 * resources.displayMetrics.density
            cardElevation = 0f
            setContentPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            TextView(this).apply {
                text = getString(
                    R.string.connections_row_title,
                    connection.target,
                    connection.network.uppercase(),
                )
                textSize = 14f
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurface,
                    ),
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
        )
        column.addView(
            TextView(this).apply {
                text = getString(
                    R.string.connections_row_detail,
                    connection.matchedRule,
                    connection.chains.joinToString(" → "),
                    TrafficFormat.compact(connection.upload),
                    TrafficFormat.compact(connection.download),
                )
                textSize = 12f
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
                maxLines = 2
            },
        )
        card.addView(column)
        card.setOnClickListener {
            lifecycleScope.launch {
                val closed = withContext(Dispatchers.IO) { MihomoCore.closeConnection(connection.id) }
                if (closed) refresh()
            }
        }
        return card
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 1_000L
        const val MAX_ROWS = 200
    }
}
