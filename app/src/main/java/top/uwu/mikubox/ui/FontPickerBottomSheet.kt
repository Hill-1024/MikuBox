package top.uwu.mikubox.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.core.ThemeManager
import top.uwu.mikubox.databinding.ItemFontPickerBinding
import top.uwu.mikubox.databinding.UwuBottomSheetFontPickerBinding

/**
 * Font picker. Every option is rendered in its own typeface so the list is a
 * preview, and picking one applies it as a theme overlay on the next recreate.
 */
class FontPickerBottomSheet : BaseUwuSheet() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = UwuBottomSheetFontPickerBinding.inflate(inflater, container, false)
        bindHeader(binding.header)
        binding.recyclerFonts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFonts.adapter = FontAdapter(ThemeDialogs.fontOptions(requireContext()))
        return binding.root
    }

    private inner class FontAdapter(
        private val options: List<Pair<String, String>>,
    ) : RecyclerView.Adapter<FontAdapter.VH>() {

        private val selected = AppSettings.fontFamily(requireContext())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
            ItemFontPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount(): Int = options.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(options[position])

        inner class VH(private val item: ItemFontPickerBinding) :
            RecyclerView.ViewHolder(item.root) {

            fun bind(option: Pair<String, String>) {
                val (key, label) = option
                item.tvFontName.text = label
                item.tvFontName.typeface = typefaceFor(key)
                item.ivFontCheck.visibility =
                    if (key == selected) View.VISIBLE else View.GONE
                item.root.setOnClickListener {
                    AppSettings.setFontFamily(requireContext(), key)
                    dismiss()
                    activity?.recreate()
                }
            }

            private fun typefaceFor(key: String): Typeface {
                val overlay = ThemeManager.fontOverlayFor(key)
                val fontRes = when (key) {
                    "uwu_font_title" -> R.font.uwu_font_title
                    "uwu_font_summary" -> R.font.uwu_font_summary
                    "uwu_font_typography" -> R.font.uwu_font_typography
                    "googlesansregular" -> R.font.googlesansregular
                    "robotoregular" -> R.font.robotoregular
                    "poppinsregular" -> R.font.poppinsregular
                    "sfprodisplay" -> R.font.sfprodisplay
                    "oneui" -> R.font.oneui
                    "rine" -> R.font.rine
                    "chococookyregular" -> R.font.chococookyregular
                    "simpleday" -> R.font.simpleday
                    "fucek" -> R.font.fucek
                    "dancingscript" -> R.font.dancingscript
                    "cream" -> R.font.cream
                    "emilyscandy" -> R.font.emilyscandy
                    "summerdream" -> R.font.summerdream
                    "incosolata" -> R.font.incosolata
                    "jetbrains_mono" -> R.font.jetbrains_mono
                    else -> 0
                }
                if (fontRes == 0 || overlay == null) return Typeface.DEFAULT
                return ResourcesCompat.getFont(requireContext(), fontRes) ?: Typeface.DEFAULT
            }
        }
    }

    companion object {
        const val TAG = "FontPickerBottomSheet"

        fun show(manager: FragmentManager) {
            FontPickerBottomSheet().show(manager, TAG)
        }
    }
}
