package top.uwu.mikubox.ui

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.ShapeAppearanceModel
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.UwuRowBinding

/**
 * Section slots for the stacked cards of the release design. Rows in one
 * section share the 16dp side margins and only the first/last keep the big
 * 28dp corners, so a stack reads as a single card.
 */
object UwuRow {

    enum class Slot { TOP, MIDDLE, BOTTOM, SINGLE }

    /** The corner shape that [slot] uses, also usable for hand-built cards. */
    @StyleRes
    fun styleFor(slot: Slot): Int = when (slot) {
        Slot.TOP -> R.style.ShapeAppearance_App_CardView_Top
        Slot.MIDDLE -> R.style.ShapeAppearance_App_CardView_Middle
        Slot.BOTTOM -> R.style.ShapeAppearance_App_CardView_Bottom
        Slot.SINGLE -> R.style.ShapeAppearance_App_CardView_Single
    }

    /** Applies the corner shape and the vertical rhythm for [slot]. */
    fun applySlot(card: MaterialCardView, slot: Slot) {
        card.setShapeAppearanceModel(
            ShapeAppearanceModel.builder(card.context, styleFor(slot), 0).build()
        )
        val params = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val unit = card.resources.displayMetrics.density
        params.topMargin = ((if (slot == Slot.TOP || slot == Slot.SINGLE) 10 else 0) * unit).toInt()
        params.bottomMargin = ((if (slot == Slot.BOTTOM || slot == Slot.SINGLE) 10 else 4) * unit).toInt()
        card.layoutParams = params
    }

    /**
     * Fills one row. Only the trailing widgets that are requested are shown,
     * matching the release build where a row is either a switch, a value or a
     * navigation arrow.
     */
    fun bind(
        binding: UwuRowBinding,
        slot: Slot,
        @DrawableRes icon: Int,
        title: CharSequence,
        summary: CharSequence? = null,
        value: CharSequence? = null,
        switchState: Boolean? = null,
        arrow: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        applySlot(binding.rowCard, slot)
        binding.rowIcon.setImageResource(icon)
        binding.rowTitle.text = title
        binding.rowSummary.text = summary ?: ""
        binding.rowSummary.visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.rowValue.text = value ?: ""
        binding.rowValue.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.rowArrow.visibility = if (arrow) View.VISIBLE else View.GONE
        // The switch is hidden on rows that do not use it; setting the checked
        // state in the same pass as the visibility change leaves its thumb and
        // track drawables on the old state, so the state is applied first and
        // then settled before the switch is shown.
        if (switchState != null) {
            binding.rowSwitch.isChecked = switchState
            binding.rowSwitch.jumpDrawablesToCurrentState()
            binding.rowSwitch.visibility = View.VISIBLE
        } else {
            binding.rowSwitch.visibility = View.GONE
        }
        binding.rowCard.setOnClickListener { onClick?.invoke() }
        binding.rowCard.isClickable = onClick != null
    }

    /** dp helper shared by the slot margins. */
    fun Resources.dp(value: Int): Int = (value * displayMetrics.density).toInt()
}
