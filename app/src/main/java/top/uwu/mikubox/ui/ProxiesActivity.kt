package top.uwu.mikubox.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoConfigStore
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.databinding.ActivityProxiesBinding
import top.uwu.mikubox.profile.MihomoConfigPreview
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoTrafficStore
import top.uwu.mikubox.service.VpnController

/**
 * ClashMetaForAndroid-style node manager: lists every proxy group of the running
 * core (select, url-test, fallback, load-balance) in configuration order and
 * their member nodes. Select groups switch nodes directly; auto groups pin a
 * node or revert to automatic selection. Nodes are only available while the
 * core is running, so this screen requires a connection.
 */
class ProxiesActivity : EdgeToEdgeActivity() {

    private lateinit var binding: ActivityProxiesBinding
    private val adapter = ProxyNodeAdapter(onSelect = ::selectNode)

    private var allProxies: Map<String, MihomoCore.Proxy> = emptyMap()
    private var groups: List<MihomoCore.Proxy> = emptyList()
    private var groupOrder: List<String> = emptyList()
    private val delayCache = mutableMapOf<String, Int>()
    private var sortByDelay = false

    /** Bytes each node and group carried for the active profile, persisted plus the running session. */
    private var proxyTotals: Map<String, MihomoTrafficStore.Totals> = emptyMap()
    private val trafficHandler = Handler(Looper.getMainLooper())
    private val trafficTick = object : Runnable {
        override fun run() {
            refreshTraffic()
            trafficHandler.postDelayed(this, TRAFFIC_REFRESH_MS)
        }
    }

    /** True while only the profile's declared groups are shown, without a core. */
    private var offline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProxiesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_test_delay -> {
                    testCurrentGroup()
                    true
                }
                R.id.action_sort -> {
                    sortByDelay = !sortByDelay
                    item.setTitle(if (sortByDelay) R.string.sort_by_name else R.string.proxies_sort_delay)
                    renderGroup(binding.groupTab.selectedTabPosition)
                    true
                }
                else -> false
            }
        }

        binding.rvNodes.layoutManager = LinearLayoutManager(this)
        binding.rvNodes.adapter = adapter

        binding.groupTab.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = renderGroup(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        load()
    }

    private fun load() {
        if (!VpnController.isRunning) {
            loadOfflinePreview()
            return
        }
        lifecycleScope.launch {
            allProxies = withContext(Dispatchers.IO) { MihomoCore.proxies() }
            groupOrder = withContext(Dispatchers.IO) { MihomoCore.groupOrder() }
            groups = visibleGroups()
            if (groups.isEmpty()) {
                showEmpty(R.string.proxies_empty_none)
                return@launch
            }
            binding.tvEmpty.visibility = android.view.View.GONE
            binding.rvNodes.visibility = android.view.View.VISIBLE
            binding.cardGroups.visibility = android.view.View.VISIBLE
            binding.tvOffline.visibility = android.view.View.GONE
            buildTabs()
        }
    }

    /**
     * While disconnected, every page still shows the nodes and groups the active
     * profile declares, so the configuration can be inspected without starting
     * the core. Selecting or testing requires a running core.
     */
    private fun loadOfflinePreview() {
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { MihomoConfigStore.activeConfig(this@ProxiesActivity) }
            val previewNodes = withContext(Dispatchers.IO) { MihomoConfigPreview.nodes(config) }
            val previewGroups = withContext(Dispatchers.IO) { MihomoConfigPreview.groups(config) }
            offline = true
            allProxies = previewNodes.mapValues { (name, node) ->
                MihomoCore.Proxy(name = name, type = node.type, now = null, all = emptyList(), delay = 0, udp = false)
            }
            groups = previewGroups.map { group ->
                MihomoCore.Proxy(name = group.name, type = group.type, now = null, all = group.members, delay = 0, udp = false)
            }
            if (groups.isEmpty()) {
                showEmpty(R.string.proxies_empty_none)
                return@launch
            }
            binding.tvEmpty.visibility = android.view.View.GONE
            binding.rvNodes.visibility = android.view.View.VISIBLE
            binding.cardGroups.visibility = android.view.View.VISIBLE
            binding.tvOffline.setText(R.string.proxies_offline_hint)
            binding.tvOffline.visibility = android.view.View.VISIBLE
            buildTabs()
        }
    }

    /**
     * Every group the core exposes except the mode-only GLOBAL pseudo-group and
     * groups the config marked hidden, ordered as the profile declared them
     * (unknown names keep their map order after the declared ones).
     */
    private fun visibleGroups(): List<MihomoCore.Proxy> {
        val visible = allProxies.values.filter { it.isGroup && !it.hidden && it.name != GLOBAL_GROUP }
        val position = groupOrder.withIndex().associate { (index, name) -> name to index }
        return visible.sortedBy { position[it.name] ?: Int.MAX_VALUE }
    }

    private fun buildTabs() {
        val tabs = binding.groupTab
        tabs.removeAllTabs()
        groups.forEach { group -> tabs.addTab(tabs.newTab().setText(group.name)) }
        renderGroup(0)
    }

    private fun renderGroup(index: Int) {
        val group = groups.getOrNull(index) ?: return
        val memberNodes = group.all.map { memberNode(group, it) }
        // Reachable nodes first (ascending latency); untested/testing/timeout sink to the bottom.
        val ordered = if (sortByDelay) {
            memberNodes.sortedBy { if (it.delay >= 0) it.delay else Int.MAX_VALUE }
        } else {
            memberNodes
        }
        // Auto groups lead with a virtual row that reverts them to automatic selection.
        // The pinned state only exists while the core runs, so it is hidden offline.
        adapter.submit(if (group.isAutoGroup && !offline) listOf(autoEntry(group)) + ordered else ordered)
        showGroupTraffic(group)
    }

    /** The selected group's carried traffic, so the list says what it cost to run. */
    private fun showGroupTraffic(group: MihomoCore.Proxy) {
        val totals = proxyTotals[group.name]
        if (totals == null || (totals.upload == 0L && totals.download == 0L)) {
            binding.tvGroupTraffic.visibility = android.view.View.GONE
            return
        }
        binding.tvGroupTraffic.visibility = android.view.View.VISIBLE
        binding.tvGroupTraffic.text = getString(
            R.string.traffic_group,
            TrafficFormat.readable(totals.upload),
            TrafficFormat.readable(totals.download),
        )
    }

    private fun autoEntry(group: MihomoCore.Proxy) = ProxyNodeAdapter.Node(
        name = getString(R.string.proxies_auto),
        type = group.type,
        delay = -2,
        selected = group.pinnedNode == null,
        isAutoEntry = true,
    )

    private fun memberNode(group: MihomoCore.Proxy, memberName: String): ProxyNodeAdapter.Node {
        val info = allProxies[memberName]
        val totals = proxyTotals[memberName]
        return ProxyNodeAdapter.Node(
            name = memberName,
            type = info?.type ?: "",
            delay = delayCache[memberName] ?: (info?.delay ?: 0).let { if (it > 0) it else -2 },
            selected = memberName == group.now,
            pinned = group.pinnedNode == memberName,
            upload = totals?.upload ?: 0,
            download = totals?.download ?: 0,
        )
    }

    private fun selectNode(node: ProxyNodeAdapter.Node) {
        if (offline) {
            toast(getString(R.string.proxies_offline_hint))
            return
        }
        val group = groups.getOrNull(binding.groupTab.selectedTabPosition) ?: return
        when {
            !group.isSelectableGroup -> toast(getString(R.string.proxies_group_not_selectable))
            node.isAutoEntry -> applySelection(group, "")
            else -> applySelection(group, node.name)
        }
    }

    private fun applySelection(group: MihomoCore.Proxy, name: String) {
        val index = binding.groupTab.selectedTabPosition
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { MihomoCore.selectProxy(group.name, name) }
            if (ok) {
                allProxies = withContext(Dispatchers.IO) { MihomoCore.proxies() }
                groups = visibleGroups()
                renderGroup(index)
                toast(
                    if (name.isEmpty()) getString(R.string.toast_node_auto)
                    else getString(R.string.toast_node_selected, name),
                )
            } else {
                toast(getString(R.string.toast_node_select_failed))
            }
        }
    }

    private fun testCurrentGroup() {
        if (offline) {
            toast(getString(R.string.proxies_offline_hint))
            return
        }
        val index = binding.groupTab.selectedTabPosition
        val group = groups.getOrNull(index) ?: return
        val members = group.all
        if (members.isEmpty()) return
        val testUrl = MihomoCoreSettings.testUrl(this)
        val timeout = MihomoCoreSettings.testTimeout(this)
        // Show a per-node testing state, then stream results in as each probe returns.
        members.forEach { delayCache[it] = -3 }
        renderGroup(index)
        lifecycleScope.launch {
            val gate = Semaphore(16)
            members.map { name ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val result = MihomoCore.delay(name, testUrl, timeout)
                        val value = if (result < 0) -1 else result
                        delayCache[name] = value
                        withContext(Dispatchers.Main) { adapter.updateDelay(name, value) }
                    }
                }
            }.awaitAll()
            if (sortByDelay) renderGroup(index)
        }
    }

    private fun showEmpty(messageRes: Int) {
        binding.tvEmpty.setText(messageRes)
        binding.tvEmpty.visibility = android.view.View.VISIBLE
        binding.rvNodes.visibility = android.view.View.GONE
        binding.cardGroups.visibility = android.view.View.GONE
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------- traffic

    /**
     * Re-reads the per-node and per-group totals while the screen is open: the
     * stored profile totals plus whatever the running session has added.
     */
    private fun refreshTraffic() {
        val profileId = MihomoProfileStore.selected(this)?.id ?: return
        val totals = MihomoTrafficStore.proxyTotals(this, profileId)
        if (totals == proxyTotals) return
        proxyTotals = totals
        adapter.updateTraffic { name ->
            val entry = totals[name]
            (entry?.upload ?: 0) to (entry?.download ?: 0)
        }
        groups.getOrNull(binding.groupTab.selectedTabPosition)?.let { showGroupTraffic(it) }
    }

    override fun onResume() {
        super.onResume()
        trafficHandler.post(trafficTick)
    }

    override fun onPause() {
        super.onPause()
        trafficHandler.removeCallbacks(trafficTick)
    }

    private companion object {
        const val GLOBAL_GROUP = "GLOBAL"
        const val TRAFFIC_REFRESH_MS = 2_000L
    }
}
