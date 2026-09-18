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

    /** Row views by connection id, reused across the once-a-second snapshots. */
    private val rows = LinkedHashMap<String, Row>()
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

        val wanted = connections.take(MAX_ROWS)
        val wantedIds = wanted.map { it.id }
        if (rows.keys.toList() != wantedIds) rearrange(wanted)
        wanted.forEach { connection -> bind(rows.getValue(connection.id), connection) }
    }

    /**
     * Matches the row views to the new snapshot: rows for connections that are
     * gone leave, new ones are created, the rest are only moved. Rebuilding every
     * row once a second threw away and re-inflated the whole list, which showed
     * up as dropped frames and as a list that screen readers could not follow.
     */
    private fun rearrange(wanted: List<MihomoCore.Connection>) {
        val wantedIds = wanted.map { it.id }.toSet()
        rows.keys.filterNot { it in wantedIds }.forEach { id ->
            rows.remove(id)?.let { binding.listConnections.removeView(it.card) }
        }
        wanted.forEachIndexed { index, connection ->
            val row = rows[connection.id] ?: newRow().also { rows[connection.id] = it }
            val list = binding.listConnections
            if (row.card.parent === list) {
                if (list.indexOfChild(row.card) != index) {
                    list.removeView(row.card)
                    list.addView(row.card, index)
                }
            } else {
                list.addView(row.card, index.coerceAtMost(list.childCount))
            }
        }
    }

    /** Writes one connection into an existing row, keeping the views in place. */
    private fun bind(row: Row, connection: MihomoCore.Connection) {
        row.connectionId = connection.id
        row.title.text = getString(
            R.string.connections_row_title,
            connection.target,
            connection.network.uppercase(),
        )
        row.detail.text = getString(
            R.string.connections_row_detail,
            connection.matchedRule,
            connection.chains.joinToString(" → "),
            TrafficFormat.compact(connection.upload),
            TrafficFormat.compact(connection.download),
        )
    }

    /** One connection: target and counters on top, how it was routed below. */
    private fun newRow(): Row {
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
        val title = TextView(this).apply {
            textSize = 14f
            setTextColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurface,
                ),
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val detail = TextView(this).apply {
            textSize = 12f
            setTextColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                ),
            )
            maxLines = 2
        }
        column.addView(title)
        column.addView(detail)
        card.addView(column)
        val row = Row(card, title, detail)
        card.setOnClickListener {
            // The row is reused across snapshots, so the id is read at click time.
            val id = row.connectionId ?: return@setOnClickListener
            lifecycleScope.launch {
                val closed = withContext(Dispatchers.IO) { MihomoCore.closeConnection(id) }
                if (closed) refresh()
            }
        }
        return row
    }

    /** The views of one connection row, reused between snapshots. */
    private class Row(
        val card: MaterialCardView,
        val title: TextView,
        val detail: TextView,
    ) {
        var connectionId: String? = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 1_000L
        const val MAX_ROWS = 200
    }
}
