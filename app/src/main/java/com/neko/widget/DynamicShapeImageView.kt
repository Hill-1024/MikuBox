package com.neko.widget

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import com.neko.shapeimageview.ShaderImageView
import com.neko.shapeimageview.shader.ShaderHelper
import com.neko.shapeimageview.shader.SvgShader
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings

class DynamicShapeImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ShaderImageView(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {

    private var currentShapeId: Int = R.raw.uwu_shape_cookie

    /** When set, forces this shape regardless of the shared preference (previews). */
    var overrideShapeId: Int? = null

    override fun createImageViewHelper(): ShaderHelper {
        val shapeId = overrideShapeId ?: resolveShapeId()
        currentShapeId = shapeId
        return SvgShader(shapeId)
    }

    init {
        scaleType = ScaleType.CENTER_CROP
        loadColorBitmap()
    }

    private fun loadColorBitmap() {
        try {
            val color = themeColor(androidx.appcompat.R.attr.colorPrimary)

            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)

            setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) {
            AppSettings.prefs(context).registerOnSharedPreferenceChangeListener(this)
            checkAndUpdateShape()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!isInEditMode) {
            AppSettings.prefs(context).unregisterOnSharedPreferenceChangeListener(this)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == AppSettings.KEY_ICON_SHAPE) {
            post { checkAndUpdateShape() }
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun resolveShapeId(): Int {
        return try {
            when (AppSettings.iconShape(context)) {
                "uwu_shape_clover" -> R.raw.uwu_shape_clover
                "uwu_shape_circle" -> R.raw.uwu_shape_circle
                "uwu_shape_diamond" -> R.raw.uwu_shape_diamond
                "uwu_shape_pentagon" -> R.raw.uwu_shape_pentagon
                "uwu_shape_hexagon" -> R.raw.uwu_shape_hexagon
                "uwu_shape_octagon" -> R.raw.uwu_shape_octagon
                "uwu_shape_rounded_square" -> R.raw.uwu_shape_rounded_square
                "uwu_shape_squircle" -> R.raw.uwu_shape_squircle
                "uwu_shape_heart" -> R.raw.uwu_shape_heart
                else -> R.raw.uwu_shape_cookie
            }
        } catch (e: Exception) {
            R.raw.uwu_shape_cookie
        }
    }

    private fun checkAndUpdateShape() {
        try {
            val newShapeId = resolveShapeId()
            if (currentShapeId != newShapeId) {
                currentShapeId = newShapeId
                reloadShape()
                invalidate()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
