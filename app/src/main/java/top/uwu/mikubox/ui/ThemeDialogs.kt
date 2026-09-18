package top.uwu.mikubox.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings
import top.uwu.mikubox.core.ThemeManager
import top.uwu.mikubox.databinding.DialogCustomColorBinding
import top.uwu.mikubox.databinding.DialogThemeColorBinding
import top.uwu.mikubox.databinding.DialogUwuSliderBinding
import top.uwu.mikubox.databinding.ItemThemeColorBinding

/**
 * The appearance pickers: the palette swatch grid, the seed-colour picker and
 * the single-slider dialogs. Kept together so every appearance row in Settings
 * opens a dialog with the same shape and the same apply-then-recreate flow.
 */
object ThemeDialogs {

    /** Resolves a theme attribute of a context (its own theme or an overlay). */
    fun colorAttr(context: Context, attr: Int): Int {
        val values = TypedValue()
        if (!context.theme.resolveAttribute(attr, values, true)) return Color.GRAY
        return if (values.resourceId != 0) {
            ContextCompat.getColor(context, values.resourceId)
        } else {
            values.data
        }
    }

    /**
     * Palette grid. Each swatch previews the family's primary colour by
     * resolving the family theme with a themed wrapper, and the selection
     * morphs the circle into a squircle exactly like the release design.
     */
    fun showThemeColor(activity: android.app.Activity, onChanged: () -> Unit) {
        val binding = DialogThemeColorBinding.inflate(LayoutInflater.from(activity))
        val current = AppSettings.themeFamily(activity)
        val customActive = AppSettings.useCustomColor(activity) && !AppSettings.dynamicColor(activity)
        val grid = binding.gridThemeColors

        AppSettings.themeFamilies.forEach { family ->
            val item = ItemThemeColorBinding.inflate(
                LayoutInflater.from(activity),
                grid,
                false,
            )
            val swatch = ContextThemeWrapper(activity, ThemeManager.styleFor(family))
            val color = colorAttr(swatch, androidx.appcompat.R.attr.colorPrimary)
            applySwatch(item, color, family == current && !customActive)
            item.root.setOnClickListener {
                AppSettings.setThemeFamily(activity, family)
                AppSettings.setDynamicColor(activity, false)
                AppSettings.setDynamicColorFromBanner(activity, false)
                AppSettings.clearCustomColor(activity)
                dismissAndRecreate(activity, onChanged)
            }
            grid.addView(item.root)
        }

        val customCard: MaterialCardView = binding.cardCustomColor
        if (ThemeManager.supportsDynamicColor()) {
            val preview = AppSettings.customColor(activity)
            if (customActive && preview != 0) {
                binding.ivCustomPreview.setImageDrawable(circle(preview, 32f))
                binding.ivCustomPreview.imageTintList = null
            }
            customCard.setOnClickListener {
                activity.let {
                    showCustomColor(activity) { onChanged() }
                }
            }
        } else {
            customCard.visibility = View.GONE
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_theme_color)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Hue / saturation / lightness seed picker with a live preview. */
    fun showCustomColor(activity: android.app.Activity, onChanged: () -> Unit) {
        val binding = DialogCustomColorBinding.inflate(LayoutInflater.from(activity))
        val initial = AppSettings.customColor(activity).takeIf { it != 0 }
            ?: colorAttr(activity, androidx.appcompat.R.attr.colorPrimary)
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)

        binding.seekHue.progress = hsv[0].toInt().coerceIn(0, 360)
        binding.seekSaturation.progress = (hsv[1] * 100).toInt().coerceIn(0, 100)
        binding.seekLightness.progress = (hsv[2] * 80).toInt().coerceIn(0, 80)

        fun currentSeed(): Int = Color.HSVToColor(
            floatArrayOf(
                binding.seekHue.progress.toFloat(),
                binding.seekSaturation.progress / 100f,
                (binding.seekLightness.progress.coerceAtLeast(8)) / 80f,
            )
        )

        fun render() {
            val seed = currentSeed()
            binding.ivPreview.setImageDrawable(circle(seed, 64f))
            binding.tvPreviewHex.text = String.format("#%06X", 0xFFFFFF and seed)
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = render()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        binding.seekHue.setOnSeekBarChangeListener(listener)
        binding.seekSaturation.setOnSeekBarChangeListener(listener)
        binding.seekLightness.setOnSeekBarChangeListener(listener)
        render()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.theme_custom_color)
            .setView(binding.root)
            .setNeutralButton(R.string.theme_reset_preset) { _, _ ->
                AppSettings.clearCustomColor(activity)
                dismissAndRecreate(activity, onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppSettings.clearCustomColor(activity)
                AppSettings.setCustomColor(activity, currentSeed())
                AppSettings.setDynamicColor(activity, false)
                AppSettings.setDynamicColorFromBanner(activity, false)
                dismissAndRecreate(activity, onChanged)
            }
            .show()
    }

    /** Single slider with a live label and preview line (font size). */
    fun showSlider(
        activity: android.app.Activity,
        titleRes: Int,
        value: Int,
        min: Int,
        max: Int,
        step: Int,
        onApply: (Int) -> Unit,
    ) {
        val binding = DialogUwuSliderBinding.inflate(LayoutInflater.from(activity))
        val steps = ((max - min) / step).coerceAtLeast(1)
        binding.seekSlider.max = steps
        binding.seekSlider.progress = ((value - min) / step).coerceIn(0, steps)
        binding.tvSliderPreview.setTextColor(
            colorAttr(activity, com.google.android.material.R.attr.colorOnSurface)
        )

        fun current(): Int = min + binding.seekSlider.progress * step
        fun render() {
            binding.tvSliderValue.text = activity.getString(R.string.font_size_value, current())
        }
        binding.seekSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = render()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        render()

        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(binding.root)
            .setNeutralButton(R.string.theme_reset_preset) { _, _ ->
                onApply(AppSettings.FONT_SCALE_DEFAULT)
                activity.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onApply(current())
                activity.recreate()
            }
            .show()
    }

    /** Colours a swatch: squircle when selected, pill otherwise. */
    private fun applySwatch(
        item: ItemThemeColorBinding,
        color: Int,
        selected: Boolean,
    ) {
        item.ivColorCircle.setImageDrawable(circle(color, 50f, squircle = selected))
        item.ivCheck.visibility = if (selected) View.VISIBLE else View.GONE
        val luminance = MaterialColors.isColorLight(color)
        item.ivCheck.imageTintList = android.content.res.ColorStateList.valueOf(
            if (luminance) Color.BLACK else Color.WHITE
        )
        item.root.isSelected = selected
    }

    private fun circle(color: Int, sizeDp: Float, squircle: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (squircle) sizeDp * 0.32f else sizeDp * 2f
            setColor(color)
        }

    private fun dismissAndRecreate(activity: android.app.Activity, onChanged: () -> Unit) {
        onChanged()
        activity.recreate()
    }

    /** Font label lookup shared by the settings row and the picker sheet. */
    fun fontLabel(context: Context, key: String): String {
        val values = context.resources.getStringArray(R.array.font_family_values)
        val labels = context.resources.getStringArray(R.array.font_family_labels)
        val index = values.indexOf(key)
        return labels.getOrElse(if (index >= 0) index else 0) { labels.first() }
    }

    /** Bundled fonts for the picker sheet, key + label. */
    fun fontOptions(context: Context): List<Pair<String, String>> {
        val values = context.resources.getStringArray(R.array.font_family_values)
        val labels = context.resources.getStringArray(R.array.font_family_labels)
        return values.mapIndexed { index, key -> key to labels.getOrElse(index) { key } }
    }
}
