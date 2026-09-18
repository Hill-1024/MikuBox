package top.uwu.mikubox.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.ActivityAppListBinding
import top.uwu.mikubox.service.MihomoVpnSettings
import top.uwu.mikubox.service.MihomoVpnSettings.AppMode

/** Per-app proxy picker, wired to [MihomoVpnSettings] (mode + package set). */
class AppListActivity : EdgeToEdgeActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var adapter: AppListAdapter
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_app_list)
        val search = binding.toolbar.menu.findItem(R.id.action_search_apps)?.actionView
            as? androidx.appcompat.widget.SearchView
        search?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = false

            override fun onQueryTextChange(text: String?): Boolean {
                adapter.filter(text.orEmpty())
                return true
            }
        })
        search?.setOnCloseListener {
            adapter.filter("")
            false
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_all -> {
                    toggleSelectAll()
                    true
                }

                R.id.action_invert_selection -> {
                    invertSelection()
                    true
                }

                else -> false
            }
        }

        selected.addAll(MihomoVpnSettings.packages(this))

        adapter = AppListAdapter(packageManager) { pkg, checked ->
            if (checked) selected.add(pkg) else selected.remove(pkg)
            MihomoVpnSettings.setPackages(this, selected)
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        when (MihomoVpnSettings.appMode(this)) {
            AppMode.ALL -> binding.modeGroup.check(binding.modeAll.id)
            AppMode.ALLOW_LIST -> binding.modeGroup.check(binding.modeAllow.id)
            AppMode.DISALLOW_LIST -> binding.modeGroup.check(binding.modeDisallow.id)
        }
        binding.modeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when (checkedIds.firstOrNull()) {
                binding.modeAllow.id -> AppMode.ALLOW_LIST
                binding.modeDisallow.id -> AppMode.DISALLOW_LIST
                else -> AppMode.ALL
            }
            MihomoVpnSettings.setAppMode(this, mode)
            adapter.setEnabled(mode != AppMode.ALL)
        }

        loadApps()
    }

    private fun loadApps() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { installedApps() }
            adapter.submit(apps, selected, MihomoVpnSettings.appMode(this@AppListActivity) != AppMode.ALL)
            binding.progress.visibility = View.GONE
        }
    }

    /** Select every row currently on screen, or clear them when all are on. */
    private fun toggleSelectAll() {
        val visible = adapter.visiblePackages()
        if (visible.isEmpty()) return
        val allSelected = visible.all { it in selected }
        if (allSelected) {
            selected.removeAll(visible.toSet())
        } else {
            selected.addAll(visible)
        }
        persistSelection()
    }

    private fun invertSelection() {
        val visible = adapter.visiblePackages()
        if (visible.isEmpty()) return
        visible.forEach { pkg ->
            if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
        }
        persistSelection()
    }

    private fun persistSelection() {
        MihomoVpnSettings.setPackages(this, selected)
        adapter.submit(
            adapterItems,
            selected,
            MihomoVpnSettings.appMode(this) != AppMode.ALL,
        )
        UwuSnackbar.success(this, getString(R.string.apps_selection_updated, selected.size))
    }

    private var adapterItems: List<AppListAdapter.AppItem> = emptyList()

    private fun installedApps(): List<AppListAdapter.AppItem> {
        val self = packageName
        val items = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != self }
            .filter { it.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
            .map { pkg ->
                val info = pkg.applicationInfo!!
                AppListAdapter.AppItem(
                    packageName = pkg.packageName,
                    label = info.loadLabel(packageManager).toString(),
                    info = info,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
        adapterItems = items
        return items
    }
}
