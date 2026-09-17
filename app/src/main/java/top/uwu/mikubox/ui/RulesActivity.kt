package top.uwu.mikubox.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoConfigStore
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.databinding.ActivityRulesBinding
import top.uwu.mikubox.profile.MihomoConfigPreview
import top.uwu.mikubox.service.VpnController

/**
 * Read-only viewer for the routing rules of the running core — the rules a
 * Clash-format profile imports (domain/geosite/geoip/…) are listed here in
 * evaluation order. Rules only exist while the core is running.
 */
class RulesActivity : EdgeToEdgeActivity() {

    private lateinit var binding: ActivityRulesBinding
    private val adapter = RuleAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rvRules.layoutManager = LinearLayoutManager(this)
        binding.rvRules.adapter = adapter

        load()
    }

    private fun load() {
        if (!VpnController.isRunning) {
            loadOfflinePreview()
            return
        }
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) { MihomoCore.rules() }
            if (rules.isEmpty()) {
                showEmpty(R.string.rules_empty_none)
                return@launch
            }
            binding.tvOffline.visibility = View.GONE
            binding.tvEmpty.visibility = View.GONE
            binding.rvRules.visibility = View.VISIBLE
            binding.toolbar.subtitle = getString(R.string.rules_count, rules.size)
            adapter.submit(rules)
        }
    }

    /**
     * Shows the rules the active profile declares while the core is stopped, so
     * the imported routing configuration can be reviewed without connecting.
     */
    private fun loadOfflinePreview() {
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { MihomoConfigStore.activeConfig(this@RulesActivity) }
            val rules = withContext(Dispatchers.IO) { MihomoConfigPreview.rules(config) }
            if (rules.isEmpty()) {
                showEmpty(R.string.rules_empty_none)
                return@launch
            }
            binding.tvEmpty.visibility = View.GONE
            binding.rvRules.visibility = View.VISIBLE
            binding.tvOffline.setText(getString(R.string.rules_offline_hint, rules.size))
            binding.tvOffline.visibility = View.VISIBLE
            adapter.submit(rules)
        }
    }

    private fun showEmpty(messageRes: Int) {
        binding.tvEmpty.setText(messageRes)
        binding.tvEmpty.visibility = View.VISIBLE
        binding.rvRules.visibility = View.GONE
    }
}
