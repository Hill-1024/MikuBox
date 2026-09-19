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
import top.uwu.mikubox.databinding.DialogIconShapeBinding
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

    /**
     * Applies the pick and rebuilds the screen. The recreate waits for the
     * dialog's exit animation: tearing the activity down while the dialog is
     * still fading out is what reads as a stutter.
     */
    private fun dismissAndRecreate(activity: android.app.Activity, onChanged: () -> Unit) {
        onChanged()
        activity.window.decorView.postDelayed({ activity.recreate() }, 220)
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

    /** Badge shape keys (raw resources) with display names, in release order. */
    fun iconShapeOptions(): List<Pair<String, String>> = listOf(
        "uwu_shape_cookie" to "Cookie",
        "uwu_shape_clover" to "Clover",
        "uwu_shape_circle" to "Circle",
        "uwu_shape_diamond" to "Diamond",
        "uwu_shape_pentagon" to "Pentagon",
        "uwu_shape_hexagon" to "Hexagon",
        "uwu_shape_octagon" to "Octagon",
        "uwu_shape_rounded_square" to "Rounded Square",
        "uwu_shape_squircle" to "Squircle",
        "uwu_shape_heart" to "Heart",
    )

    fun iconShapeLabel(key: String): String =
        iconShapeOptions().firstOrNull { it.first == key }?.second ?: key

    /** Icon-shape picker (the badge tiles across the app). */
    fun showIconShape(activity: android.app.Activity, onChanged: () -> Unit) {
        showShapePicker(
            activity = activity,
            titleRes = R.string.settings_icon_shape,
            current = AppSettings::iconShape,
            apply = AppSettings::setIconShape,
            onChanged = onChanged,
        )
    }

    /** Banner-shape picker; the release build keeps it apart from the icons. */
    fun showBannerShape(activity: android.app.Activity, onChanged: () -> Unit) {
        showShapePicker(
            activity = activity,
            titleRes = R.string.settings_banner_shape,
            current = AppSettings::bannerShape,
            apply = AppSettings::setBannerShape,
            onChanged = onChanged,
        )
    }

    /**
     * Badge shape grid. Every cell previews through the real shape-clipped
     * view, and picking applies instantly — the views listen for the
     * preference change and re-clip themselves without a screen recreation.
     */
    private fun showShapePicker(
        activity: android.app.Activity,
        titleRes: Int,
        current: (android.content.Context) -> String,
        apply: (android.content.Context, String) -> Unit,
        onChanged: () -> Unit,
    ) {
        val binding = DialogIconShapeBinding.inflate(LayoutInflater.from(activity))
        val grid = binding.gridIconShapes
        val selectedKey = current(activity)
        val density = activity.resources.displayMetrics.density
        val cells = mutableListOf<Pair<String, com.neko.widget.DynamicShapeImageView>>()

        iconShapeOptions().forEach { (key, label) ->
            val cell = com.neko.widget.DynamicShapeImageView(activity)
            cell.contentDescription = label
            cell.overrideShapeId = activity.resources.getIdentifier(key, "raw", activity.packageName)
            cell.post { cell.reloadShape() }
            cell.alpha = if (key == selectedKey) 1f else 0.45f
            val params = android.widget.GridLayout.LayoutParams()
            params.width = (48 * density).toInt()
            params.height = (48 * density).toInt()
            val margin = (6 * density).toInt()
            params.setMargins(margin, margin, margin, margin)
            cell.layoutParams = params
            cell.setOnClickListener {
                apply(activity, key)
                cells.forEach { (cellKey, cellView) -> cellView.alpha = if (cellKey == key) 1f else 0.45f }
                onChanged()
            }
            grid.addView(cell)
            cells.add(key to cell)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
