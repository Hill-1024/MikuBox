package com.neko.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.View
import com.neko.shapeimageview.ShaderImageView
import com.neko.shapeimageview.shader.ShaderHelper
import com.neko.shapeimageview.shader.SvgShader
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings

/**
 * The banner avatar: a shape-clipped artwork with the border set from XML
 * (siBorderColor / siBorderWidth), re-clipping itself when the shape setting
 * changes. Port of the UwU view without the Glide-backed custom image; the
 * bundled uwu_banner_profile artwork is always shown.
 */
class ProfileBannerImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ShaderImageView(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {

    private var currentShapeId: Int = R.raw.uwu_shape_cookie

    override fun createImageViewHelper(): ShaderHelper {
        val shapeId = resolveShapeId()
        currentShapeId = shapeId
        return SvgShader(shapeId)
    }

    init {
        scaleType = ScaleType.CENTER_CROP
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setImageResource(R.drawable.uwu_banner_profile)
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
        if (key == AppSettings.KEY_BANNER_SHAPE) {
            post { checkAndUpdateShape() }
        }
    }

    private fun resolveShapeId(): Int {
        return try {
            when (AppSettings.bannerShape(context)) {
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
