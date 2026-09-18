package top.uwu.mikubox.ui

import android.view.LayoutInflater
import android.widget.LinearLayout
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.UwuRowBinding

/**
 * Builds a settings page from a list of entries, so a screen full of knobs needs
 * no hand-written row per option. Rows reuse the release design's row component
 * and are rendered top/middle/bottom so a group looks like one card.
 */
object SettingsForm {

    /**
     * One row: what it is called, what it says now, and how to change it. A
     * [switch] turns the row into a toggle instead of a value with an arrow.
     */
    data class Entry(
        val title: Int,
        val summary: Int,
        val value: () -> CharSequence,
        val switch: (() -> Boolean)? = null,
        val onPick: () -> Unit,
    )

    fun render(container: LinearLayout, entries: List<Entry>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        entries.forEachIndexed { index, entry ->
            val row = UwuRowBinding.inflate(inflater, container, false)
            UwuRow.bind(
                binding = row,
                slot = when {
                    entries.size == 1 -> UwuRow.Slot.SINGLE
                    index == 0 -> UwuRow.Slot.TOP
                    index == entries.lastIndex -> UwuRow.Slot.BOTTOM
                    else -> UwuRow.Slot.MIDDLE
                },
                icon = R.drawable.ic_settings_24dp,
                title = container.context.getString(entry.title),
                summary = container.context.getString(entry.summary),
                value = entry.value(),
                switchState = entry.switch?.invoke(),
                onClick = entry.onPick,
            )
            container.addView(row.root)
        }
    }
}
