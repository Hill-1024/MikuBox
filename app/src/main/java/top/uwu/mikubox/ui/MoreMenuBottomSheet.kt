package top.uwu.mikubox.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.google.android.material.color.MaterialColors
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.databinding.UwuBottomSheetMoreMenuBinding
import top.uwu.mikubox.databinding.UwuRowBinding

/**
 * Sheet behind the second header button. Groups the actions that do not fit in
 * the header: service restart, subscription refresh, list management and the
 * list order radio group.
 */
class MoreMenuBottomSheet : BaseUwuSheet() {

    enum class Action {
        RESTART_SERVICE,
        UPDATE_SUBSCRIPTIONS,
        TEST_ALL,
        ADD_CONFIG,
        EXPORT_ALL,
        DELETE_ALL,
        ORDER_ORIGIN,
        ORDER_NAME,
        ORDER_UPDATED,
    }

    private var listener: ((Action) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = UwuBottomSheetMoreMenuBinding.inflate(inflater, container, false)
        bindHeader(binding.header)

        UwuRow.bind(
            binding.actionRestart,
            UwuRow.Slot.TOP,
            R.drawable.ic_refresh,
            getString(R.string.title_service_restart),
            getString(R.string.desc_service_restart),
        ) { fire(Action.RESTART_SERVICE) }
        UwuRow.bind(
            binding.actionUpdateSubs,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_cloud_download_24dp,
            getString(R.string.title_sub_update),
            getString(R.string.desc_sub_update),
        ) { fire(Action.UPDATE_SUBSCRIPTIONS) }
        UwuRow.bind(
            binding.actionTestAll,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_baseline_speed_24,
            getString(R.string.title_ping_all_server),
            getString(R.string.proxies_test),
        ) { fire(Action.TEST_ALL) }
        UwuRow.bind(
            binding.actionAddConfig,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_add_circle_fill_24px,
            getString(R.string.action_add_profile),
            getString(R.string.import_configuration),
        ) { fire(Action.ADD_CONFIG) }

        UwuRow.bind(
            binding.actionExportAll,
            UwuRow.Slot.TOP,
            R.drawable.ic_copy,
            getString(R.string.title_export_all),
        ) { fire(Action.EXPORT_ALL) }
        UwuRow.bind(
            binding.actionDeleteAll,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_delete_empty,
            getString(R.string.title_del_all_config),
        ) { fire(Action.DELETE_ALL) }

        bindOrderRows(binding)
        return binding.root
    }

    private fun bindOrderRows(binding: UwuBottomSheetMoreMenuBinding) {
        val current = AppSettings.sortOrder(requireContext())
        val check = getString(R.string.order_selected_mark)
        bindOrderRow(
            binding.orderOrigin,
            UwuRow.Slot.TOP,
            R.drawable.ic_order_numeric_ascending,
            getString(R.string.group_order_origin),
            current == AppSettings.SORT_ORIGIN,
            check,
        ) { fire(Action.ORDER_ORIGIN) }
        bindOrderRow(
            binding.orderByName,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_order_alphabetical_ascending,
            getString(R.string.group_order_by_name),
            current == AppSettings.SORT_NAME,
            check,
        ) { fire(Action.ORDER_NAME) }
        bindOrderRow(
            binding.orderByUpdated,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_history,
            getString(R.string.group_order_by_updated),
            current == AppSettings.SORT_UPDATED,
            check,
        ) { fire(Action.ORDER_UPDATED) }
    }

    private fun bindOrderRow(
        row: UwuRowBinding,
        slot: UwuRow.Slot,
        iconRes: Int,
        title: String,
        selected: Boolean,
        check: String,
        onClick: () -> Unit,
    ) {
        UwuRow.bind(row, slot, iconRes, title, value = if (selected) check else null, onClick = onClick)
        if (selected) {
            row.rowValue.setTextColor(
                MaterialColors.getColor(row.rowValue, androidx.appcompat.R.attr.colorPrimary)
            )
        } else {
            row.rowValue.setTextColor(
                ContextCompat.getColor(requireContext(), android.R.color.transparent)
            )
        }
    }

    private fun fire(action: Action) {
        dismiss()
        listener?.invoke(action)
    }

    companion object {
        const val TAG = "MoreMenuBottomSheet"

        fun show(manager: FragmentManager, onAction: (Action) -> Unit) {
            MoreMenuBottomSheet().apply { listener = onAction }.show(manager, TAG)
        }
    }
}
