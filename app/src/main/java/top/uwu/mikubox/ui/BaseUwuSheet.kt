package top.uwu.mikubox.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.databinding.UwuSheetHeaderBinding

/**
 * Shared behaviour for the release design's sheets: always expanded, never
 * collapsing into a peek state, capped below the status bar and padded above
 * the navigation bar. Subclasses inflate their own layout and call
 * [bindHeader] to fill the banner card every sheet starts with.
 */
abstract class BaseUwuSheet : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        UwuWindow.apply(dialog)
        with(dialog.behavior) {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        val unit = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom + (8 * unit).toInt())
            dialog.behavior.maxHeight =
                resources.displayMetrics.heightPixels - bars.top - (8 * unit).toInt()
            insets
        }
        ViewCompat.requestApplyInsets(sheet)
    }

    /** Fills the banner header shared by every sheet. */
    protected fun bindHeader(binding: UwuSheetHeaderBinding) {
        binding.sheetUsername.text = profileName()
        binding.sheetSubtitle.text = getString(R.string.scaffold_subtitle)
        binding.particlesView.visibility = if (AppSettings.particlesEnabled(requireContext())) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    protected fun profileName(): CharSequence {
        val name = AppSettings.profileName(requireContext())
        return if (name.isBlank()) {
            getString(R.string.uwu_profile_banner_title)
        } else {
            getString(R.string.uwu_profile_banner_title_custom, name)
        }
    }
}
