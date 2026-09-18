package top.uwu.mikubox.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.LayoutSnackbarUwuBinding

/**
 * MikuRay's top-anchored snackbar: a rounded card that slides in under the
 * status bar, tinted by the message kind, with an icon, a bold title and the
 * message underneath. Activity-scoped feedback should use this instead of a
 * toast so it matches the release design.
 */
object UwuSnackbar {

    fun success(activity: Activity, message: CharSequence, title: CharSequence? = null) = show(
        activity,
        message,
        title ?: activity.getText(R.string.title_alerter_success),
        R.drawable.ic_check_circle,
        androidx.appcompat.R.attr.colorPrimary,
        com.google.android.material.R.attr.colorOnPrimary,
    )

    fun error(activity: Activity, message: CharSequence, title: CharSequence? = null) = show(
        activity,
        message,
        title ?: activity.getText(R.string.title_alerter_error),
        R.drawable.ic_warning,
        androidx.appcompat.R.attr.colorError,
        com.google.android.material.R.attr.colorOnError,
    )

    fun info(activity: Activity, message: CharSequence, title: CharSequence? = null) = show(
        activity,
        message,
        title ?: activity.getText(R.string.title_alerter_info),
        R.drawable.ic_baseline_info_24,
        com.google.android.material.R.attr.colorTertiary,
        com.google.android.material.R.attr.colorOnTertiary,
    )

    private fun show(
        activity: Activity,
        message: CharSequence,
        title: CharSequence?,
        @DrawableRes icon: Int,
        @AttrRes backgroundAttr: Int,
        @AttrRes foregroundAttr: Int,
    ) {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        val snackbar = Snackbar.make(root, "", Snackbar.LENGTH_LONG)
            .setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
        val snackbarLayout = snackbar.view as? Snackbar.SnackbarLayout ?: return

        val binding = LayoutSnackbarUwuBinding.inflate(activity.layoutInflater, snackbarLayout, false)
        val background = MaterialColors.getColor(snackbarLayout, backgroundAttr)
        val foreground = MaterialColors.getColor(snackbarLayout, foregroundAttr)
        binding.snackbarIcon.setImageResource(icon)
        binding.snackbarIcon.setColorFilter(foreground)
        binding.snackbarTitle.text = title ?: ""
        binding.snackbarTitle.setTextColor(foreground)
        binding.snackbarTitle.visibility = if (title.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.snackbarMessage.text = message
        binding.snackbarMessage.setTextColor(foreground)
        snackbarLayout.addView(
            binding.root,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        snackbarLayout.background = GradientDrawable().apply {
            cornerRadius = 28f * snackbarLayout.resources.displayMetrics.density
            setColor(background)
        }
        snackbarLayout.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            ?.visibility = View.INVISIBLE

        // Pin the card under the status bar the way the release build does.
        val density = snackbarLayout.resources.displayMetrics.density
        val topInset = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
        val topMargin = topInset + (5 * density).toInt()
        when (val params = snackbarLayout.layoutParams) {
            is CoordinatorLayout.LayoutParams -> {
                params.gravity = Gravity.TOP
                params.topMargin = topMargin
            }

            is FrameLayout.LayoutParams -> {
                params.gravity = Gravity.TOP
                params.topMargin = topMargin
            }
        }

        snackbarLayout.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                view.removeOnLayoutChangeListener(this)
                view.translationY = -view.height.toFloat()
                view.animate()
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        })
        snackbar.show()
    }
}
