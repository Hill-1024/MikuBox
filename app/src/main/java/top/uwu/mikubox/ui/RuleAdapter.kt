package top.uwu.mikubox.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoCore

/** Read-only list of the routing rules of the running configuration. */
class RuleAdapter : RecyclerView.Adapter<RuleAdapter.VH>() {

    private var rules: List<MihomoCore.Rule> = emptyList()

    @SuppressWarnings("NotifyDataSetChanged")
    fun submit(list: List<MihomoCore.Rule>) {
        rules = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false))

    override fun getItemCount(): Int = rules.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(rules[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val payload: TextView = itemView.findViewById(R.id.tv_payload)
        private val type: TextView = itemView.findViewById(R.id.tv_type)
        private val target: TextView = itemView.findViewById(R.id.tv_target)

        fun bind(rule: MihomoCore.Rule) {
            val ctx = itemView.context
            payload.text = rule.payload.ifBlank { rule.type }
            type.text = rule.type
            target.text = rule.target
            target.setTextColor(ContextCompat.getColor(ctx, targetColor(rule.target)))
        }

        /** Built-in policies get distinct colors; everything else routes through a group. */
        private fun targetColor(target: String): Int = when (target.uppercase()) {
            "REJECT", "REJECT-DROP" -> R.color.miku_tertiary
            "DIRECT" -> R.color.ping_green
            else -> R.color.miku_orange
        }
    }
}
