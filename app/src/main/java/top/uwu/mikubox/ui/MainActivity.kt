package top.uwu.mikubox.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.core.MihomoConfigStore
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.core.MihomoCoreSettings
import top.uwu.mikubox.core.RoutingMode
import top.uwu.mikubox.databinding.ActivityMainBinding
import top.uwu.mikubox.profile.MihomoProfileImporter
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoSubscriptionUpdater
import top.uwu.mikubox.service.MikuVpnService
import top.uwu.mikubox.service.VpnController
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Home screen. Layout and interaction follow the release design: a banner
 * header with the greeting, a pill tab strip that filters the profile list,
 * per-row actions with pin, and a floating status card whose body re-tests the
 * active node while its trailing button connects.
 */
class MainActivity : EdgeToEdgeActivity(), AddConfigBottomSheet.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ProfileAdapter

    private enum class Filter { ALL, SUBSCRIPTION, LOCAL }

    private var filter = Filter.ALL
    private var query = ""
    private var lastTestResult: CharSequence? = null

    /** Bottom stack metrics, kept so [layoutBottomStack] can run outside the inset callback. */
    private var navBarInsetPx = 0
    private var panelMarginPx = 0
    private var quickGapPx = 0
    private var listPaddingPx = 0

    /** Set while the mode switch is rendered, so showing it never counts as a tap. */
    private var suppressModeCallback = false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Connection state the status card currently shows. The core reports ready
     * seconds after the service is asked to start, so the card has to follow the
     * flag on its own: one refresh right after the request would render "not
     * connected" and keep showing it until the next lifecycle event.
     */
    private var renderedRunning = false

    private val trafficTick = object : Runnable {
        override fun run() {
            val running = VpnController.isRunning
            if (running != renderedRunning) {
                renderedRunning = running
                // The list mirrors the connection state (running dot, status
                // line), so a state change refreshes it together with the card.
                refresh()
            }
            updateTraffic()
            refreshFab()
            handler.postDelayed(this, 1000)
        }
    }

    private val vpnFailureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val detail = intent.getStringExtra(MikuVpnService.EXTRA_FAILURE_DETAIL).orEmpty()
            UwuSnackbar.error(this@MainActivity, getString(R.string.toast_vpn_start_failed, detail))
            refresh()
        }
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            // Reading the document and parsing its YAML happen off the main
            // thread; a multi-megabyte subscription must not freeze the screen.
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { MihomoProfileImporter.importUri(this@MainActivity, uri) }
                }
                result.onSuccess { profile ->
                    UwuSnackbar.success(this@MainActivity, getString(R.string.toast_config_imported))
                    refresh()
                    selectProfile(profile)
                }.onFailure { error ->
                    UwuSnackbar.error(
                        this@MainActivity,
                        getString(R.string.toast_import_failed, error.message.orEmpty()),
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyMainSystemBarInsets()

        adapter = ProfileAdapter(
            onSelect = { profile -> selectProfile(profile) },
            onUpdate = { profile -> confirmUpdate(profile) },
            onDelete = { profile -> confirmDelete(profile) },
            onShare = { profile -> shareProfile(profile) },
            onPin = { profile -> togglePin(profile) },
        )
        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter
        // Selection changes should cross-fade, not blink.
        (binding.rvProfiles.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        setupDragReorder()

        setupHeader()
        setupTabs()
        setupStatusCard()
        setupQuickActions()
        setupModeSwitch()

        binding.btnHome.setOnClickListener { MainMenuBottomSheet.show(supportFragmentManager) }
        binding.btnMoreMenu.setOnClickListener {
            MoreMenuBottomSheet.show(supportFragmentManager) { action -> onMoreAction(action) }
        }
        binding.btnAddConfig.setOnClickListener { showAddConfig() }
        binding.btnSearch.setOnClickListener {
            val reveal = binding.searchRow.visibility != View.VISIBLE
            binding.searchRow.visibility = if (reveal) View.VISIBLE else View.GONE
            if (reveal) binding.searchViewInline.requestFocus() else {
                binding.searchViewInline.setQuery("", false)
                query = ""
                refresh()
            }
        }
        blurBannerArtwork()
        binding.searchViewInline.setOnQueryTextListener(
            object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?): Boolean = false

                override fun onQueryTextChange(text: String?): Boolean {
                    query = text.orEmpty()
                    refresh()
                    return true
                }
            }
        )

        requestNotificationPermission()

        if (savedInstanceState == null &&
            MihomoCoreSettings.autoConnectOnStart(this) &&
            !VpnController.isRunning &&
            MihomoProfileStore.selected(this) != null
        ) {
            VpnController.connect(this)
        }
    }

    private fun setupHeader() {
        binding.tvGreetingSub.text = getString(greetingRes())
        binding.tvUsername.text = greetingName()
        binding.ivProfile.setOnClickListener { promptProfileName() }
    }

    /**
     * The landscape artwork behind the banner card is blurred the way the
     * release build blurs it, so the portrait card and the avatar stay crisp on
     * top of it. RenderEffect needs Android 12; older releases keep it sharp.
     */
    private fun blurBannerArtwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            binding.headerImage.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    22f,
                    22f,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            )
        }
    }

    private fun greetingName(): CharSequence {
        val name = AppSettings.profileName(this)
        return if (name.isBlank()) {
            getString(R.string.uwu_profile_banner_title)
        } else {
            getString(R.string.uwu_profile_banner_title_custom, name)
        }
    }

    private fun promptProfileName() {
        UwuDialogs.input(
            context = this,
            title = getString(R.string.uwu_profile_name_dialog),
            message = getString(R.string.settings_profile_name_summary),
            initial = AppSettings.profileName(this),
            hint = getString(R.string.settings_profile_name),
        ) { value ->
            AppSettings.setProfileName(this, value)
            refresh()
        }
    }

    private fun greetingRes(): Int = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> R.string.greeting_morning
        in 12..17 -> R.string.greeting_afternoon
        in 18..22 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }

    /** One pill tab per category, each carrying its profile count. */
    private fun setupTabs() {
        val entries = listOf(
            Triple(Filter.ALL, R.string.filter_config_all, R.drawable.ic_cards_variant),
            Triple(Filter.SUBSCRIPTION, R.string.filter_config_subscription, R.drawable.ic_subscriptions_24dp),
            Triple(Filter.LOCAL, R.string.filter_config_local, R.drawable.ic_file_24dp),
        )
        val contentTint = AppCompatResources.getColorStateList(this, R.color.uwu_tab_content_tint)
        val badgeTint = AppCompatResources.getColorStateList(this, R.color.uwu_badge_bg_tint)
        val badgeText = AppCompatResources.getColorStateList(this, R.color.uwu_badge_text_color)

        binding.groupTab.removeAllTabs()
        entries.forEach { (entryFilter, labelRes, iconRes) ->
            val tab = binding.groupTab.newTab()
            val view = layoutInflater.inflate(R.layout.item_tab_group, binding.groupTab, false)
            val icon = view.findViewById<ImageView>(R.id.tab_icon)
            icon.setImageResource(iconRes)
            ImageViewCompat.setImageTintList(icon, contentTint)
            val label = view.findViewById<TextView>(R.id.tab_label)
            label.setText(labelRes)
            label.setTextColor(contentTint)
            val badge = view.findViewById<TextView>(R.id.tab_badge)
            badge.setTextColor(badgeText)
            badge.backgroundTintList = badgeTint
            view.tag = entryFilter
            tab.customView = view
            binding.groupTab.addTab(tab, entryFilter == filter)
        }
        binding.groupTab.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                filter = tab.customView?.tag as? Filter ?: Filter.ALL
                refresh()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun refreshTabBadges() {
        val profiles = MihomoProfileStore.profiles(this)
        val counts = mapOf(
            Filter.ALL to profiles.size,
            Filter.SUBSCRIPTION to profiles.count { it.isSubscription },
            Filter.LOCAL to profiles.count { !it.isSubscription },
        )
        for (index in 0 until binding.groupTab.tabCount) {
            val view = binding.groupTab.getTabAt(index)?.customView ?: continue
            val badge = view.findViewById<TextView>(R.id.tab_badge) ?: continue
            val count = counts[view.tag as? Filter ?: Filter.ALL] ?: 0
            badge.text = count.toString()
            badge.visibility = if (count > 0) View.VISIBLE else View.GONE
        }
    }

    private fun setupStatusCard() {
        binding.fab.setOnClickListener { toggleConnection() }
        installPressAnimation()
    }

    /**
     * Long-press drag reorders the list, the way the release build lets a
     * profile be moved. The new order is written back to the store and the list
     * is switched to the manual order so the drop is what the user sees.
     */
    private fun setupDragReorder() {
        val callback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or
                androidx.recyclerview.widget.ItemTouchHelper.DOWN,
            0,
        ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                val rows = adapter.currentList.toMutableList()
                if (from !in rows.indices || to !in rows.indices) return false
                rows.add(to, rows.removeAt(from))
                adapter.submitList(rows)
                return true
            }

            override fun onSwiped(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                direction: Int,
            ) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS
                    )
                }
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                persistOrder()
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(callback)
            .attachToRecyclerView(binding.rvProfiles)
    }

    private fun persistOrder() {
        val ordered = adapter.orderedIds()
        if (ordered.isEmpty()) return
        // A manual drop implies the manual order, otherwise the next refresh
        // would re-sort the list back and undo the drag.
        if (AppSettings.sortOrder(this) != AppSettings.SORT_ORIGIN) {
            AppSettings.setSortOrder(this, AppSettings.SORT_ORIGIN)
        }
        MihomoProfileStore.reorder(this, ordered)
        refresh()
    }

    /**
     * The status card follows the finger: it swells on touch, stretches with a
     * drag, lights up and springs back on release, then re-tests the active
     * node. Same feel as the release build's bottom card.
     */
    private fun installPressAnimation() {
        val glow = binding.statusGlow
        var startX = 0f
        var startY = 0f
        binding.statusPanel.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    view.animate().cancel()
                    view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110).start()
                    glow.animate().alpha(1f).setDuration(110).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    view.animate().cancel()
                    val deltaX = event.rawX - startX
                    val deltaY = event.rawY - startY
                    view.scaleX = 1.06f + (kotlin.math.abs(deltaX) / view.width * 0.15f)
                        .coerceAtMost(0.09f)
                    view.scaleY = 1.06f + (kotlin.math.abs(deltaY) / view.height * 0.15f)
                        .coerceAtMost(0.09f)
                    view.translationX = deltaX * 0.18f
                    view.translationY = deltaY * 0.18f
                    true
                }

                MotionEvent.ACTION_UP -> {
                    glow.animate().alpha(0f).setDuration(200).start()
                    springBack(view)
                    testCurrentNode()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    glow.animate().alpha(0f).setDuration(200).start()
                    springBack(view)
                    true
                }

                else -> false
            }
        }
    }

    private fun springBack(view: View) {
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    private fun setupQuickActions() {
        binding.btnQuickSubUpdate.setOnClickListener { confirmRefreshAllSubscriptions() }
        binding.btnQuickSort.setOnClickListener {
            val next = when (AppSettings.sortOrder(this)) {
                AppSettings.SORT_ORIGIN -> AppSettings.SORT_NAME
                AppSettings.SORT_NAME -> AppSettings.SORT_UPDATED
                else -> AppSettings.SORT_ORIGIN
            }
            AppSettings.setSortOrder(this, next)
            UwuSnackbar.info(this, getString(sortOrderLabel(next)))
            refresh()
        }
        binding.btnQuickTcping.setOnClickListener { open(ProxiesActivity::class.java) }
        binding.btnQuickProxies.setOnClickListener { open(RulesActivity::class.java) }
    }

    private fun sortOrderLabel(order: Int): Int = when (order) {
        AppSettings.SORT_NAME -> R.string.group_order_by_name
        AppSettings.SORT_UPDATED -> R.string.group_order_by_updated
        else -> R.string.group_order_origin
    }

    // ----------------------------------------------------------------- modes

    /**
     * Rule / global / direct switch. The tabs are filled from [RoutingMode], so
     * the order stays in one place, and a tap applies the mode to the running
     * core immediately — no reconnect needed.
     */
    private fun setupModeSwitch() {
        val modes = RoutingMode.SELECTABLE
        binding.modeTab.removeAllTabs()
        modes.forEach { mode ->
            binding.modeTab.addTab(binding.modeTab.newTab().setText(modeLabel(mode)))
        }
        binding.modeTab.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (suppressModeCallback) return
                applyMode(modes[tab.position])
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            /** Tapping the active tab again re-opens the global exit picker. */
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (suppressModeCallback) return
                if (modes[tab.position] == MihomoCoreSettings.ProxyMode.GLOBAL) showGlobalExitPicker()
            }
        })
        binding.modeExit.setOnClickListener { showGlobalExitPicker() }
    }

    private fun modeLabel(mode: MihomoCoreSettings.ProxyMode): Int = when (mode) {
        MihomoCoreSettings.ProxyMode.GLOBAL -> R.string.mode_global
        MihomoCoreSettings.ProxyMode.DIRECT -> R.string.mode_direct
        else -> R.string.mode_rule
    }

    /** Shows the mode the core is actually using, and the exit while in global mode. */
    private fun refreshModeSwitch() {
        val mode = RoutingMode.effective(this, MihomoConfigStore.activeConfig(this))
        val position = RoutingMode.SELECTABLE.indexOf(mode).coerceAtLeast(0)
        suppressModeCallback = true
        binding.modeTab.getTabAt(position)?.select()
        suppressModeCallback = false
        refreshModeExitRow(mode)
    }

    private fun refreshModeExitRow(mode: MihomoCoreSettings.ProxyMode) {
        val global = mode == MihomoCoreSettings.ProxyMode.GLOBAL
        binding.modeExit.visibility = if (global) View.VISIBLE else View.GONE
        if (global) {
            val exit = RoutingMode.globalExit()
            binding.modeExit.text = getString(
                R.string.mode_global_exit_row,
                exit ?: getString(R.string.mode_global_exit_default),
            )
        }
        // The card changes height with the row, which moves everything below it.
        binding.cardMode.post { layoutBottomStack() }
    }

    private fun applyMode(mode: MihomoCoreSettings.ProxyMode) {
        if (!RoutingMode.select(this, mode)) {
            UwuSnackbar.error(this, getString(R.string.mode_switch_failed))
            refreshModeSwitch()
            return
        }
        refreshModeExitRow(mode)
        // Global mode is only meaningful once it knows where to leave the device.
        if (mode == MihomoCoreSettings.ProxyMode.GLOBAL) showGlobalExitPicker()
    }

    /**
     * Picker for the exit global mode uses: every member of the core's `GLOBAL`
     * selector, which lists the profile's groups and its nodes.
     */
    private fun showGlobalExitPicker() {
        val options = RoutingMode.globalExitOptions()
        if (options.isEmpty()) {
            UwuSnackbar.error(this, getString(R.string.mode_global_exit_unavailable))
            return
        }
        val current = RoutingMode.globalExit()
        UwuDialogs.choose(
            context = this,
            title = getString(R.string.mode_global_exit),
            items = options.map { exitLabel(it) }.toTypedArray(),
            selected = options.indexOfFirst { it.name == current }.coerceAtLeast(0),
        ) { index ->
            if (!RoutingMode.selectGlobalExit(options[index].name)) {
                UwuSnackbar.error(this, getString(R.string.mode_switch_failed))
            }
            refreshModeSwitch()
        }
    }

    /** Groups are called out by type; a bare name would not tell them from nodes. */
    private fun exitLabel(proxy: MihomoCore.Proxy): CharSequence =
        if (proxy.isGroup) "${proxy.name} · ${proxy.type}" else proxy.name

    private fun showAddConfig() {
        AddConfigBottomSheet().show(supportFragmentManager, AddConfigBottomSheet.TAG)
    }

    private fun applyMainSystemBarInsets() {
        val unit = resources.displayMetrics.density
        listPaddingPx = resources.getDimensionPixelSize(R.dimen.uwu_list_bottom_padding)
        panelMarginPx = (16 * unit).toInt()
        quickGapPx = (8 * unit).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(
                left = maxOf(bars.left, cutout.left),
                right = maxOf(bars.right, cutout.right),
            )
            binding.headerContent.updatePadding(top = maxOf(bars.top, cutout.top))
            navBarInsetPx = maxOf(bars.bottom, cutout.bottom)
            layoutBottomStack()
            insets
        }
        ViewCompat.requestApplyInsets(binding.mainContent)
    }

    /**
     * Stacks the mode switch, the quick actions and the status card above the
     * navigation bar and gives the profile list the same total as bottom
     * padding, so the last row never hides behind the panel. Run again whenever
     * one of those rows changes visibility or size.
     */
    private fun layoutBottomStack() {
        val bottom = navBarInsetPx
        val modeHeight = binding.cardMode.measuredHeight.takeIf { it > 0 }
            ?: (60 * resources.displayMetrics.density).toInt()
        val quickActions = binding.layoutQuickActions
        val quickHeight = if (quickActions.visibility == View.VISIBLE) quickActions.measuredHeight else 0

        // The stack is anchored from the bottom up: the status card hugs the
        // navigation bar while the mode card and the quick actions ride above it
        // through their layout_above chain, so only the gaps are set here.
        (binding.statusPanel.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.bottomMargin = panelMarginPx + bottom
            binding.statusPanel.layoutParams = params
        }
        (quickActions.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.bottomMargin = quickGapPx
            quickActions.layoutParams = params
        }
        (binding.cardMode.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.bottomMargin = quickGapPx
            binding.cardMode.layoutParams = params
        }
        val stack = modeHeight + quickGapPx +
            (if (quickHeight > 0) quickHeight + quickGapPx else 0) +
            binding.statusPanel.measuredHeight
        binding.rvProfiles.updatePadding(bottom = stack + panelMarginPx * 2 + bottom)
    }

    override fun onResume() {
        super.onResume()
        applyRecentsVisibility()
        refresh()
        handler.post(trafficTick)
    }

    /**
     * Applies the "hide from recents" preference to this app's task. It is a task
     * property, so it is set whenever the task comes to the front — including
     * right after the user flips the setting.
     */
    private fun applyRecentsVisibility() {
        val hidden = AppSettings.hideFromRecents(this)
        runCatching {
            getSystemService(android.app.ActivityManager::class.java)
                ?.appTasks
                ?.firstOrNull()
                ?.setExcludeFromRecents(hidden)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            vpnFailureReceiver,
            IntentFilter(MikuVpnService.ACTION_VPN_START_FAILED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(vpnFailureReceiver)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(trafficTick)
    }

    override fun onDestroy() {
        // Delayed restart/refresh callbacks must not fire against a dead activity.
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- state

    private fun refresh() {
        val selected = MihomoProfileStore.selected(this)
        adapter.submit(visibleProfiles(), selected?.id, VpnController.isRunning)
        refreshTabBadges()
        binding.layoutQuickActions.visibility =
            if (AppSettings.quickActions(this)) View.VISIBLE else View.GONE
        binding.tvGreetingSub.text = getString(greetingRes())
        binding.tvUsername.text = greetingName()
        refreshStatusCard()
        refreshModeSwitch()
        updateTraffic()
        ViewCompat.requestApplyInsets(binding.mainContent)
    }

    private fun visibleProfiles(): List<MihomoProfileStore.Profile> {
        val all = MihomoProfileStore.profiles(this)
        val byFilter = when (filter) {
            Filter.ALL -> all
            Filter.SUBSCRIPTION -> all.filter { it.isSubscription }
            Filter.LOCAL -> all.filterNot { it.isSubscription }
        }
        val searched = if (query.isBlank()) {
            byFilter
        } else {
            byFilter.filter { profile ->
                profile.name.contains(query, ignoreCase = true) ||
                    profile.subscriptionUrl?.contains(query, ignoreCase = true) == true
            }
        }
        val ordered = when (AppSettings.sortOrder(this)) {
            AppSettings.SORT_NAME -> searched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            AppSettings.SORT_UPDATED -> searched.sortedByDescending { it.updatedAtMillis }
            else -> searched
        }
        // Pinned profiles float to the top; sortedByDescending is stable, so the
        // chosen order survives inside both halves.
        return ordered.sortedByDescending { if (it.pinned) 1 else 0 }
    }

    private fun refreshStatusCard() {
        val running = VpnController.isRunning
        renderedRunning = running
        if (!running) lastTestResult = null
        binding.tvTestState.text = if (running) {
            lastTestResult ?: getString(R.string.connection_connected)
        } else {
            getString(R.string.connection_not_connected)
        }
        binding.fab.setIconResource(
            if (running) R.drawable.ic_service_busy else R.drawable.ic_service_idle
        )
        binding.fab.contentDescription =
            getString(if (running) R.string.action_stop_service else R.string.action_start_service)
        binding.statusPanel.contentDescription = getString(R.string.connection_test_pending)
        refreshFab()
    }

    /** Extended button shows the session timer while the tunnel is up. */
    private fun refreshFab() {
        val running = VpnController.isRunning
        if (running && AppSettings.fabExtended(this)) {
            val startedAt = MikuVpnService.startedAtMillis
            val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
            val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60
            binding.fab.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            if (!binding.fab.isExtended) binding.fab.extend()
        } else if (binding.fab.isExtended) {
            binding.fab.shrink()
            binding.fab.text = null
        } else {
            binding.fab.text = null
        }
    }

    /**
     * The status card reports what the current connection consumed, not a
     * momentary rate: a per-second sample read as "0 B/s" whenever the link was
     * briefly idle, which told the user nothing.
     */
    private fun updateTraffic() {
        if (!VpnController.isRunning) {
            binding.tvIpState.text =
                getString(R.string.traffic_session, TrafficFormat.readable(0), TrafficFormat.readable(0))
            return
        }
        val traffic = runCatching { MihomoCore.traffic() }.getOrNull() ?: return
        binding.tvIpState.text = getString(
            R.string.traffic_session,
            TrafficFormat.readable(traffic.uploadSession),
            TrafficFormat.readable(traffic.downloadSession),
        )
    }

    // ------------------------------------------------------------- actions

    private fun selectProfile(profile: MihomoProfileStore.Profile) {
        if (MihomoProfileStore.selected(this)?.id == profile.id) {
            // Tapping the row that is already active offers the pin action, the
            // way the release build's double-tap on a selected row does.
            showPinDialog(profile)
            return
        }
        MihomoProfileStore.select(this, profile.id)
        if (VpnController.isRunning) restartVpn()
        refresh()
    }

    private fun showPinDialog(profile: MihomoProfileStore.Profile) {
        val action = getString(
            if (profile.pinned) R.string.action_unpin_server else R.string.action_pin_server
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_pin_server)
            .setItems(arrayOf(action)) { _, _ -> togglePin(profile) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun togglePin(profile: MihomoProfileStore.Profile) {
        MihomoProfileStore.update(this, profile.copy(pinned = !profile.pinned))
        UwuSnackbar.success(
            this,
            getString(
                if (profile.pinned) R.string.toast_server_unpinned else R.string.toast_server_pinned
            ),
        )
        refresh()
    }

    private fun restartVpn() {
        // Reload in place instead of a stop plus a delayed start: that sequence
        // raced the teardown, and when the reconnect fired before the stop had
        // been handled the request was dropped and the tunnel stayed down.
        VpnController.restart(this)
        UwuSnackbar.info(this, getString(R.string.service_restart_started))
        handler.postDelayed({ refresh() }, 1600)
    }

    private fun confirmDelete(profile: MihomoProfileStore.Profile) {
        if (MihomoProfileStore.selected(this)?.id == profile.id) {
            UwuSnackbar.error(this, getString(R.string.toast_action_not_allowed))
            return
        }
        if (profile.pinned) {
            UwuSnackbar.error(this, getString(R.string.toast_pinned_server_delete_blocked))
            return
        }
        UwuDialogs.confirm(
            context = this,
            title = getString(R.string.del_config_comfirm),
            message = getString(R.string.del_config_dialog_comfirm_message, profile.name),
        ) {
            MihomoProfileStore.remove(this, profile.id)
            UwuSnackbar.success(this, getString(R.string.toast_deleted))
            refresh()
        }
    }

    private fun confirmUpdate(profile: MihomoProfileStore.Profile) {
        UwuDialogs.confirm(
            context = this,
            title = getString(R.string.dialog_update_subscription_title),
            message = getString(R.string.dialog_update_subscription_message, profile.name),
            icon = R.drawable.ic_cloud_download_24dp,
            positiveRes = R.string.action_update,
        ) { pull(profile) }
    }

    private fun shareProfile(profile: MihomoProfileStore.Profile) {
        ShareProfileBottomSheet.show(supportFragmentManager, profile)
    }

    private fun onMoreAction(action: MoreMenuBottomSheet.Action) {
        when (action) {
            MoreMenuBottomSheet.Action.RESTART_SERVICE -> {
                if (VpnController.isRunning) restartVpn() else toggleConnection()
            }

            MoreMenuBottomSheet.Action.UPDATE_SUBSCRIPTIONS -> confirmRefreshAllSubscriptions()
            MoreMenuBottomSheet.Action.TEST_ALL -> open(ProxiesActivity::class.java)
            MoreMenuBottomSheet.Action.EXPORT_ALL -> exportAllToClipboard()
            MoreMenuBottomSheet.Action.DELETE_ALL -> confirmDeleteAll()
            MoreMenuBottomSheet.Action.ORDER_ORIGIN -> setOrder(AppSettings.SORT_ORIGIN)
            MoreMenuBottomSheet.Action.ORDER_NAME -> setOrder(AppSettings.SORT_NAME)
            MoreMenuBottomSheet.Action.ORDER_UPDATED -> setOrder(AppSettings.SORT_UPDATED)
            MoreMenuBottomSheet.Action.ADD_CONFIG -> showAddConfig()
        }
    }

    private fun setOrder(order: Int) {
        AppSettings.setSortOrder(this, order)
        UwuSnackbar.info(this, getString(sortOrderLabel(order)))
        refresh()
    }

    private fun exportAllToClipboard() {
        val profiles = MihomoProfileStore.profiles(this)
        if (profiles.isEmpty()) {
            UwuSnackbar.error(this, getString(R.string.toast_export_all_empty))
            return
        }
        val text = profiles.joinToString("\n\n") { "${it.name}\n${it.config.trim()}" }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("MikuBox", text))
        UwuSnackbar.success(this, getString(R.string.toast_configs_exported))
    }

    private fun confirmDeleteAll() {
        val profiles = MihomoProfileStore.profiles(this)
        if (profiles.isEmpty()) {
            UwuSnackbar.error(this, getString(R.string.toast_export_all_empty))
            return
        }
        UwuDialogs.confirm(
            context = this,
            title = getString(R.string.title_del_all_config),
            message = getString(R.string.del_all_dialog_comfirm_message, profiles.size),
        ) {
            profiles.forEach { MihomoProfileStore.remove(this, it.id) }
            UwuSnackbar.success(this, getString(R.string.toast_delete_all_done, profiles.size))
            refresh()
        }
    }

    private fun refreshAllSubscriptions() {
        val subscriptions = MihomoProfileStore.profiles(this).filter { it.isSubscription }
        if (subscriptions.isEmpty()) {
            UwuSnackbar.error(this, getString(R.string.toast_no_subscriptions))
            return
        }
        lifecycleScope.launch {
            var updated = 0
            for (profile in subscriptions) {
                adapter.setUpdating(profile.id)
                val result = withContext(Dispatchers.IO) {
                    runCatching { MihomoSubscriptionUpdater.update(this@MainActivity, profile) }
                }
                if (result.isSuccess) updated++
            }
            adapter.setUpdating(null)
            UwuSnackbar.success(
                this@MainActivity,
                getString(R.string.toast_subscriptions_updated, updated, subscriptions.size),
            )
            refresh()
        }
    }

    private fun confirmRefreshAllSubscriptions() {
        val count = MihomoProfileStore.profiles(this).count { it.isSubscription }
        if (count == 0) {
            UwuSnackbar.error(this, getString(R.string.toast_no_subscriptions))
            return
        }
        UwuDialogs.confirm(
            context = this,
            title = getString(R.string.dialog_refresh_subscriptions_title),
            message = getString(R.string.dialog_refresh_subscriptions_message, count),
            icon = R.drawable.ic_cloud_download_24dp,
            positiveRes = R.string.action_update,
        ) { refreshAllSubscriptions() }
    }

    private fun toggleConnection() {
        if (VpnController.isRunning) {
            VpnController.disconnect(this)
        } else {
            if (MihomoProfileStore.selected(this) == null) {
                UwuSnackbar.error(this, getString(R.string.toast_select_profile_first))
                return
            }
            VpnController.connect(this)
        }
        handler.postDelayed({ refresh() }, 600)
    }

    /** Real-ping the node the running core is currently using. */
    private fun testCurrentNode() {
        if (!VpnController.isRunning) {
            UwuSnackbar.info(this, getString(R.string.connection_not_connected))
            return
        }
        val node = activeNodeName()
        if (node.isNullOrBlank()) {
            UwuSnackbar.error(this, getString(R.string.connection_test_no_node))
            return
        }
        lastTestResult = getString(R.string.connection_test_testing)
        refreshStatusCard()
        lifecycleScope.launch {
            val delay = withContext(Dispatchers.IO) {
                runCatching {
                    MihomoCore.delay(
                        node,
                        MihomoCoreSettings.testUrl(this@MainActivity),
                        MihomoCoreSettings.testTimeout(this@MainActivity),
                    )
                }.getOrDefault(-1)
            }
            lastTestResult = if (delay >= 0) {
                getString(R.string.proxies_delay_ms, delay)
            } else {
                getString(R.string.connection_test_fail)
            }
            refreshStatusCard()
        }
    }

    /** The member the first selectable group resolved to, i.e. the live node. */
    private fun activeNodeName(): String? {
        val proxies = runCatching { MihomoCore.proxies() }.getOrNull() ?: return null
        val groups = MihomoCore.groupOrder()
            .mapNotNull { proxies[it] }
            .filter { it.isGroup && !it.name.equals("GLOBAL", ignoreCase = true) }
        groups.firstOrNull { it.isSelector && !it.now.isNullOrBlank() }?.let { return it.now }
        return groups.firstOrNull { !it.now.isNullOrBlank() }?.now
    }

    // ------------------------------------------------------------- listener

    override fun onAddSubscription(
        url: String,
        name: String,
        intervalMinutes: Long,
        connectedOnly: Boolean,
    ) {
        if (url.isEmpty()) {
            UwuSnackbar.error(this, getString(R.string.error_subscription_url_blank))
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    MihomoProfileImporter.importSubscription(
                        this@MainActivity,
                        name,
                        url,
                        intervalMinutes,
                        connectedOnly,
                    )
                }
            }
            result
                .onSuccess { profile ->
                    UwuSnackbar.success(this@MainActivity, getString(R.string.toast_subscription_added))
                    refresh()
                    pull(profile)
                }
                .onFailure { error ->
                    UwuSnackbar.error(
                        this@MainActivity,
                        getString(R.string.toast_import_failed, error.message.orEmpty()),
                    )
                }
        }
    }

    override fun onImportClipboard(name: String) {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (clip.isEmpty()) {
            UwuSnackbar.error(this, getString(R.string.toast_clipboard_empty))
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    MihomoProfileImporter.importConfig(this@MainActivity, name, clip)
                }
            }
            result
                .onSuccess {
                    UwuSnackbar.success(this@MainActivity, getString(R.string.toast_config_imported))
                    refresh()
                }
                .onFailure { error ->
                    UwuSnackbar.error(
                        this@MainActivity,
                        getString(R.string.toast_import_failed, error.message.orEmpty()),
                    )
                }
        }
    }

    override fun onImportFile() {
        importFile.launch(arrayOf("*/*"))
    }

    private fun pull(profile: MihomoProfileStore.Profile) {
        lifecycleScope.launch {
            adapter.setUpdating(profile.id)
            val result = withContext(Dispatchers.IO) {
                runCatching { MihomoSubscriptionUpdater.update(this@MainActivity, profile) }
            }
            adapter.setUpdating(null)
            result
                .onSuccess { changes ->
                    UwuSnackbar.success(
                        this@MainActivity,
                        getString(R.string.toast_subscription_updated),
                    )
                    showSubscriptionChanges(profile, changes)
                }
                .onFailure {
                    UwuSnackbar.error(
                        this@MainActivity,
                        getString(R.string.toast_update_failed, it.message.orEmpty()),
                    )
                }
            refresh()
        }
    }

    private fun showSubscriptionChanges(
        profile: MihomoProfileStore.Profile,
        changes: MihomoSubscriptionUpdater.UpdateResult,
    ) {
        if (changes.added.isEmpty() && changes.deleted.isEmpty()) return
        val message = buildList {
            if (changes.added.isNotEmpty()) {
                add(getString(R.string.subscription_changes_added, changes.added.joinToString("\n")))
            }
            if (changes.deleted.isNotEmpty()) {
                add(getString(R.string.subscription_changes_deleted, changes.deleted.joinToString("\n")))
            }
        }.joinToString("\n\n")
        UwuDialogs.confirm(
            context = this,
            title = getString(R.string.subscription_changes_title, profile.name),
            message = message,
            icon = R.drawable.ic_cloud_download_24dp,
            positiveRes = android.R.string.ok,
        ) {}
    }

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
