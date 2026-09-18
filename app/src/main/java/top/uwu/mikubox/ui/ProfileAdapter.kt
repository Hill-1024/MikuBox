package top.uwu.mikubox.ui

import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.profile.MihomoConfigPreview
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoTrafficStore
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Home list of profiles, built like the release design's server list: one card
 * per profile, the selected row wearing an indicator instead of a card fill, a
 * kind pill, the row actions on the title line and a live dot while the tunnel
 * is up. Backed by DiffUtil so selection, refresh progress and reordering
 * animate instead of blinking. The same adapter renders the empty state.
 */
class ProfileAdapter(
    private val onSelect: (MihomoProfileStore.Profile) -> Unit,
    private val onUpdate: (MihomoProfileStore.Profile) -> Unit,
    private val onDelete: (MihomoProfileStore.Profile) -> Unit,
    private val onShare: (MihomoProfileStore.Profile) -> Unit,
    private val onPin: (MihomoProfileStore.Profile) -> Unit,
) : ListAdapter<ProfileAdapter.Item, RecyclerView.ViewHolder>(DIFF) {

    /**
     * One list element: either a profile with the state that changes how it
     * renders, or the empty-state placeholder. Modelling the placeholder as an
     * item keeps DiffUtil's list and the adapter's item count in sync.
     */
    sealed class Item {
        data class ProfileRow(
            val profile: MihomoProfileStore.Profile,
            val selected: Boolean,
            val updating: Boolean,
            val running: Boolean,
        ) : Item()

        data object Empty : Item()
    }

    private var updatingId: String? = null

    /** Parsed node counts, see [VH.nodeCount]. */
    private val nodeCounts = mutableMapOf<String, Int>()

    private companion object {
        const val TYPE_PROFILE = 1
        const val TYPE_EMPTY = 2

        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = when {
                oldItem is Item.ProfileRow && newItem is Item.ProfileRow ->
                    oldItem.profile.id == newItem.profile.id

                else -> oldItem is Item.Empty && newItem is Item.Empty
            }

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean =
                oldItem == newItem
        }
    }

    fun submit(
        list: List<MihomoProfileStore.Profile>,
        selectedId: String?,
        running: Boolean,
    ) {
        val items: List<Item> = if (list.isEmpty()) {
            listOf(Item.Empty)
        } else {
            list.map { profile ->
                Item.ProfileRow(
                    profile = profile,
                    selected = profile.id == selectedId,
                    updating = profile.id == updatingId,
                    running = running,
                )
            }
        }
        submitList(items)
    }

    /** Show the indeterminate progress bar on the card being refreshed. */
    fun setUpdating(profileId: String?) {
        updatingId = profileId
        submitList(
            currentList.map { item ->
                when (item) {
                    is Item.Empty -> item
                    is Item.ProfileRow -> item.copy(updating = item.profile.id == profileId)
                }
            }
        )
    }

    /** Current order, used to persist a drag. */
    fun orderedIds(): List<String> = currentList
        .filterIsInstance<Item.ProfileRow>()
        .map { it.profile.id }

    override fun getItemViewType(position: Int): Int =
        if (currentList[position] is Item.Empty) TYPE_EMPTY else TYPE_PROFILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_EMPTY) {
            EmptyVH(inflater.inflate(R.layout.item_empty, parent, false))
        } else {
            VH(inflater.inflate(R.layout.item_profile, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = currentList[position]
        if (holder is VH && item is Item.ProfileRow) {
            holder.bind(item.profile, item.selected, item.updating, item.running)
        }
    }

    private class EmptyVH(itemView: View) : RecyclerView.ViewHolder(itemView)

    private inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val context = itemView.context
        private val card: MaterialCardView = itemView.findViewById(R.id.layout_card)
        private val indicator: View = itemView.findViewById(R.id.layout_indicator)
        private val tile: View = itemView.findViewById(R.id.iv_profile_tile)
        private val glyph: ImageView = itemView.findViewById(R.id.iv_profile_glyph)
        private val progress: LinearProgressIndicator = itemView.findViewById(R.id.update_progress)
        private val name: TextView = itemView.findViewById(R.id.tv_name)
        private val pin: ImageView = itemView.findViewById(R.id.iv_pin)
        private val dot: View = itemView.findViewById(R.id.v_status_dot)
        private val pillKind: TextView = itemView.findViewById(R.id.tv_pill_kind)
        private val pillSub: TextView = itemView.findViewById(R.id.tv_pill_sub)
        private val statistics: TextView = itemView.findViewById(R.id.tv_statistics)
        private val testResult: TextView = itemView.findViewById(R.id.tv_test_result)
        private val traffic: TextView = itemView.findViewById(R.id.tv_traffic)
        private val share: ImageView = itemView.findViewById(R.id.layout_share)
        private val update: ImageView = itemView.findViewById(R.id.layout_update)
        private val remove: ImageView = itemView.findViewById(R.id.layout_remove)
        private val more: ImageView = itemView.findViewById(R.id.layout_more)

        fun bind(
            profile: MihomoProfileStore.Profile,
            selected: Boolean,
            updating: Boolean,
            running: Boolean,
        ) {
            name.text = profile.name
            pin.visibility = if (profile.pinned) View.VISIBLE else View.GONE

            pillKind.text = context.getString(
                if (profile.isSubscription) R.string.badge_subscription else R.string.badge_local
            )
            val host = profile.subscriptionUrl?.let { url ->
                runCatching { Uri.parse(url).host }.getOrNull()
            }
            pillSub.text = host.orEmpty()
            pillSub.visibility = if (host.isNullOrBlank()) View.GONE else View.VISIBLE

            statistics.text = if (profile.isSubscription) {
                if (profile.updatedAtMillis > 0) {
                    context.getString(
                        R.string.profile_updated_at,
                        relativeTime(profile.updatedAtMillis),
                    )
                } else {
                    context.getString(R.string.profile_updated_never)
                }
            } else {
                // For a local config the kind pill already says "local", so the
                // line reports the profile's size instead of repeating it.
                context.getString(R.string.profile_nodes_count, nodeCount(profile))
            }
            testResult.text = if (selected && running) context.getString(R.string.badge_selected) else ""
            testResult.visibility = if (testResult.text.isNullOrEmpty()) View.GONE else View.VISIBLE

            val totals = MihomoTrafficStore.totals(context, profile.id)
            if (totals.upload > 0 || totals.download > 0) {
                traffic.visibility = View.VISIBLE
                traffic.text = context.getString(
                    R.string.profile_traffic,
                    TrafficFormat.readable(totals.upload),
                    TrafficFormat.readable(totals.download),
                )
            } else {
                traffic.visibility = View.GONE
            }

            progress.visibility = if (updating) View.VISIBLE else View.GONE

            // The selected row hands its background to the indicator layer, so
            // the active profile reads as one soft container highlight.
            indicator.setBackgroundResource(
                if (selected) R.drawable.uwu_selected_fill else android.R.color.transparent
            )
            card.setCardBackgroundColor(
                MaterialColors.getColor(
                    card,
                    com.google.android.material.R.attr.colorSurfaceContainerLow,
                )
            )
            // The kind glyph swaps with the selection so the tile carries the
            // state as well.
            glyph.setImageResource(
                when {
                    !profile.isSubscription -> R.drawable.ic_file_24dp
                    selected -> R.drawable.ic_play_24dp
                    else -> R.drawable.ic_cloud_download_24dp
                }
            )
            tile.background = androidx.core.content.ContextCompat.getDrawable(
                context,
                if (selected) R.drawable.uwu_bg_circle_primary else R.drawable.uwu_bg_circle_container,
            )
            glyph.imageTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(
                    glyph,
                    if (selected) {
                        com.google.android.material.R.attr.colorOnPrimary
                    } else {
                        com.google.android.material.R.attr.colorOnPrimaryContainer
                    },
                )
            )

            // Live dot: only the running profile blinks.
            val showDot = selected && running
            dot.visibility = if (showDot) View.VISIBLE else View.GONE
            val blink = dot.background as? AnimationDrawable
            if (showDot) {
                blink?.start()
            } else {
                blink?.stop()
            }
            dot.contentDescription = context.getString(R.string.profile_selected_dot)

            val compact = AppSettings.compactListActions(context)
            share.visibility = if (compact) View.GONE else View.VISIBLE
            update.visibility = if (compact || !profile.isSubscription) View.GONE else View.VISIBLE
            remove.visibility = if (compact) View.GONE else View.VISIBLE
            more.visibility = if (compact) View.VISIBLE else View.GONE

            card.setOnClickListener { onSelect(profile) }
            share.setOnClickListener { onShare(profile) }
            update.setOnClickListener { onUpdate(profile) }
            remove.setOnClickListener { onDelete(profile) }
            more.setOnClickListener { showMenu(it, profile) }
        }

        private fun showMenu(anchor: View, profile: MihomoProfileStore.Profile) {
            PopupMenu(anchor.context, anchor).apply {
                menuInflater.inflate(R.menu.menu_profile, menu)
                menu.findItem(R.id.action_update)?.isVisible = profile.isSubscription
                menu.findItem(R.id.action_pin)?.title =
                    context.getString(
                        if (profile.pinned) R.string.action_unpin_server else R.string.action_pin_server
                    )
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_select -> onSelect(profile)
                        R.id.action_share -> onShare(profile)
                        R.id.action_update -> onUpdate(profile)
                        R.id.action_pin -> onPin(profile)
                        R.id.action_delete -> onDelete(profile)
                    }
                    true
                }
                show()
            }
        }

        /**
         * Node count of a profile's YAML. Parsing is not free, so the result is
         * cached per profile id and YAML length: a subscription that gets
         * refreshed is re-parsed once, everything else is a lookup.
         */
        private fun nodeCount(profile: MihomoProfileStore.Profile): Int {
            val key = profile.id + ":" + profile.config.length
            nodeCounts[key]?.let { return it }
            val count = runCatching {
                MihomoConfigPreview.nodes(profile.config).size
            }.getOrDefault(0)
            nodeCounts[key] = count
            return count
        }

        private fun relativeTime(millis: Long): String {            val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - millis)
            return when {
                minutes < 1 -> context.getString(R.string.time_just_now)
                minutes < 60 -> context.getString(R.string.time_minutes_ago, minutes)
                minutes < 60 * 24 -> context.getString(R.string.time_hours_ago, minutes / 60)
                else -> context.getString(R.string.time_days_ago, minutes / (60 * 24))
            }
        }
    }
}
