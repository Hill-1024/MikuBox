package top.uwu.mikubox.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.UwuLayoutBottomSheetAddConfigBinding

/**
 * Add/import sheet. Rows for the clipboard and file imports, then the form for
 * a new subscription; the host activity owns the actual importing so the sheet
 * stays a pure view.
 */
class AddConfigBottomSheet : BaseUwuSheet() {

    interface Listener {
        fun onAddSubscription(
            url: String,
            name: String,
            intervalMinutes: Long,
            connectedOnly: Boolean,
        )

        fun onImportClipboard(name: String)

        fun onImportFile()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = UwuLayoutBottomSheetAddConfigBinding.inflate(inflater, container, false)
        bindHeader(binding.header)

        UwuRow.bind(
            binding.importClipboard,
            UwuRow.Slot.TOP,
            R.drawable.ic_action_clipboard,
            getString(R.string.menu_item_import_config_clipboard),
            getString(R.string.desc_import_clipboard),
            arrow = true,
        ) {
            (activity as? Listener)?.onImportClipboard("")
            dismiss()
        }
        UwuRow.bind(
            binding.importLocal,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_file_24dp,
            getString(R.string.menu_item_import_config_local),
            getString(R.string.settings_import_summary),
            arrow = true,
        ) {
            (activity as? Listener)?.onImportFile()
            dismiss()
        }

        val intervals = resources.getStringArray(R.array.subscription_intervals)
        val minutes = resources.getIntArray(R.array.subscription_interval_minutes)
        val defaultIndex = minutes.indexOfFirst { it == 1440 }.coerceAtLeast(0)
        binding.dropdownInterval.setSimpleItems(intervals)
        binding.dropdownInterval.setText(intervals.getOrElse(defaultIndex) { intervals.first() }, false)

        binding.btnAddSub.setOnClickListener {
            val index = intervals.indexOfFirst { it == binding.dropdownInterval.text.toString() }
            val interval = minutes.getOrElse(if (index >= 0) index else defaultIndex) { 1440 }
            (activity as? Listener)?.onAddSubscription(
                binding.etSubUrl.text?.toString()?.trim().orEmpty(),
                binding.etName.text?.toString()?.trim().orEmpty(),
                interval.toLong(),
                binding.switchConnectedOnly.isChecked,
            )
            dismiss()
        }
        return binding.root
    }

    companion object {
        const val TAG = "AddConfigBottomSheet"
    }
}
