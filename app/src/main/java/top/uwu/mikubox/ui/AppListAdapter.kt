package top.uwu.mikubox.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import top.uwu.mikubox.R

/**
 * Per-app proxy list. Rows are cards with the package icon, its label and a
 * switch; the whole row toggles, matching the release design's bypass list.
 */
class AppListAdapter(
    private val pm: PackageManager,
    private val onToggle: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    data class AppItem(val packageName: String, val label: String, val info: ApplicationInfo)

    private var all: List<AppItem> = emptyList()
    private var apps: List<AppItem> = emptyList()
    private val checked = mutableSetOf<String>()
    private var enabled = true
    private var query: String = ""

    @SuppressWarnings("NotifyDataSetChanged")
    fun submit(list: List<AppItem>, selected: Set<String>, listEnabled: Boolean) {
        all = list
        checked.clear()
        checked.addAll(selected)
        enabled = listEnabled
        applyFilter()
    }

    @SuppressWarnings("NotifyDataSetChanged")
    fun setEnabled(listEnabled: Boolean) {
        enabled = listEnabled
        notifyDataSetChanged()
    }

    @SuppressWarnings("NotifyDataSetChanged")
    fun filter(text: String) {
        query = text.trim()
        applyFilter()
    }

    /** Packages of the rows currently on screen, for select-all/invert. */
    fun visiblePackages(): List<String> = apps.map { it.packageName }

    @SuppressWarnings("NotifyDataSetChanged")
    private fun applyFilter() {
        apps = if (query.isEmpty()) {
            all
        } else {
            all.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(apps[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.app_icon)
        private val name: TextView = itemView.findViewById(R.id.app_name)
        private val pkg: TextView = itemView.findViewById(R.id.app_package)
        private val check: MaterialSwitch = itemView.findViewById(R.id.app_check)

        fun bind(item: AppItem) {
            icon.setImageDrawable(item.info.loadIcon(pm))
            name.text = item.label
            pkg.text = item.packageName
            check.isChecked = item.packageName in checked
            itemView.isEnabled = enabled
            itemView.alpha = if (enabled) 1f else 0.4f
            itemView.setOnClickListener {
                if (!enabled) return@setOnClickListener
                val now = item.packageName !in checked
                if (now) checked.add(item.packageName) else checked.remove(item.packageName)
                check.isChecked = now
                onToggle(item.packageName, now)
            }
        }
    }
}
