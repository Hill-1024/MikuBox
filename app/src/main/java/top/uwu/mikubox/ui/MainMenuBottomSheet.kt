package top.uwu.mikubox.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.UwuBottomSheetMainMenuBinding
import top.uwu.mikubox.databinding.UwuToolCardBinding

/**
 * Sheet behind the home button: navigation rows grouped under a section header
 * plus a rail of tool cards. Every entry is a plain activity start, matching
 * the release build where the home sheet is the app's only navigation surface.
 */
class MainMenuBottomSheet : BaseUwuSheet() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = UwuBottomSheetMainMenuBinding.inflate(inflater, container, false)
        bindHeader(binding.header)
        UwuRow.bind(
            binding.menuSettings,
            UwuRow.Slot.TOP,
            R.drawable.ic_settings_24dp,
            getString(R.string.settings),
            getString(R.string.app_settings),
            arrow = true,
        ) { open(SettingsActivity::class.java) }
        UwuRow.bind(
            binding.menuRules,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_rules_24dp,
            getString(R.string.menu_rules),
            getString(R.string.desc_rules),
            arrow = true,
        ) { open(RulesActivity::class.java) }
        UwuRow.bind(
            binding.menuProxies,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_lan,
            getString(R.string.menu_proxies),
            getString(R.string.desc_proxies),
            arrow = true,
        ) { open(ProxiesActivity::class.java) }
        UwuRow.bind(
            binding.menuConnections,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_connection,
            getString(R.string.menu_connections),
            getString(R.string.desc_connections),
            arrow = true,
        ) { open(ConnectionsActivity::class.java) }

        bindToolCard(
            binding.cardLogcat,
            R.drawable.ic_logcat_24dp,
            getString(R.string.menu_log),
            getString(R.string.desc_logcat),
        ) { open(LogcatActivity::class.java) }
        bindToolCard(
            binding.cardApps,
            R.drawable.ic_per_apps_24dp,
            getString(R.string.settings_per_app),
            getString(R.string.desc_apps),
        ) { open(AppListActivity::class.java) }
        bindToolCard(
            binding.cardTools,
            R.drawable.baseline_construction_24,
            getString(R.string.menu_tools),
            getString(R.string.desc_tools),
        ) { open(ToolsActivity::class.java) }
        bindToolCard(
            binding.cardAbout,
            R.drawable.ic_about_24dp,
            getString(R.string.menu_about),
            getString(R.string.desc_about),
        ) { open(AboutActivity::class.java) }

        return binding.root
    }

    private fun bindToolCard(
        binding: UwuToolCardBinding,
        iconRes: Int,
        title: String,
        summary: String,
        onClick: () -> Unit,
    ) {
        binding.toolIcon.setImageResource(iconRes)
        binding.toolTitle.text = title
        binding.toolSummary.text = summary
        binding.root.setOnClickListener {
            dismiss()
            onClick()
        }
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(requireContext(), target))
    }

    companion object {
        const val TAG = "MainMenuBottomSheet"

        fun show(manager: FragmentManager) {
            MainMenuBottomSheet().show(manager, TAG)
        }
    }
}
