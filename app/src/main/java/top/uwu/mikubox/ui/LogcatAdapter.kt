package top.uwu.mikubox.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import top.uwu.mikubox.R

/**
 * Log rows: newest first, with the level baked into the tag line and a colour
 * per level so errors stand out while scrolling.
 */
class LogcatAdapter : RecyclerView.Adapter<LogcatAdapter.VH>() {

    data class LogLine(
        val level: Char,
        val tag: String,
        val time: String,
        val message: String,
        val raw: String,
    )

    private var all: List<LogLine> = emptyList()
    private var visible: List<LogLine> = emptyList()
    private var query: String = ""

    fun submit(lines: List<LogLine>) {
        all = lines
        applyFilter()
    }

    fun filter(text: String) {
        query = text.trim()
        applyFilter()
    }

    fun visibleText(): String = visible.joinToString("\n") { it.raw }

    private fun applyFilter() {
        visible = if (query.isEmpty()) {
            all
        } else {
            all.filter { it.raw.contains(query, ignoreCase = true) }
        }
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_log_row, parent, false)
    )

    override fun getItemCount(): Int = visible.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(visible[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val context = itemView.context
        private val tag: TextView = itemView.findViewById(R.id.log_tag)
        private val content: TextView = itemView.findViewById(R.id.log_content)

        fun bind(line: LogLine) {
            tag.text = context.getString(
                R.string.logcat_tag_line,
                levelLabel(line.level),
                line.tag.trim(),
                line.time,
            )
            tag.setTextColor(levelColor(line.level))
            content.text = line.message
            itemView.setOnLongClickListener {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MikuBox", line.raw))
                (context as? android.app.Activity)?.let {
                    UwuSnackbar.info(it, context.getString(R.string.logcat_line_copied))
                }
                true
            }
        }

        private fun levelLabel(level: Char): String = context.getString(
            when (level) {
                'V' -> R.string.logcat_level_verbose
                'D' -> R.string.logcat_level_debug
                'I' -> R.string.logcat_level_info
                'W' -> R.string.logcat_level_warn
                'E', 'F' -> R.string.logcat_level_error
                else -> R.string.logcat_level_default
            }
        )

        private fun levelColor(level: Char): Int = ContextCompat.getColor(
            context,
            when (level) {
                'E', 'F' -> R.color.miku_tertiary
                'W' -> R.color.miku_orange
                'I' -> R.color.ping_green
                'D' -> R.color.logcat_debug
                else -> R.color.logcat_verbose
            },
        )
    }

    companion object {
        private val LINE = Regex(
            "^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+(.*?):\\s?(.*)$"
        )

        /** Parses `logcat -v threadtime` output; unparsable lines stay as-is. */
        fun parse(text: String): List<LogLine> = text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val match = LINE.find(line)
                if (match == null) {
                    LogLine(' ', "", "", line, line)
                } else {
                    LogLine(
                        level = match.groupValues[5].firstOrNull() ?: ' ',
                        tag = match.groupValues[6],
                        time = match.groupValues[2],
                        message = match.groupValues[7],
                        raw = line,
                    )
                }
            }
            .toList()
    }
}
