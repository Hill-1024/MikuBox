package top.uwu.mikubox.ui

import android.app.Dialog
import android.os.Build
import android.view.Window
import android.view.WindowManager

/**
 * Window treatment shared by dialogs and sheets, following the release design:
 * on Android 12+ the content behind the window is blurred (cross-window blur)
 * with a light scrim, and older releases fall back to a plain dim.
 */
object UwuWindow {

    private const val BLUR_RADIUS = 30
    private const val BLUR_DIM = 0.34f
    private const val PLAIN_DIM = 0.55f

    fun apply(dialog: Dialog?) = apply(dialog?.window)

    fun apply(window: Window?) {
        val target = window ?: return
        target.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attributes = target.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && crossWindowBlurEnabled(target)) {
            target.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            attributes.blurBehindRadius = BLUR_RADIUS
            attributes.dimAmount = BLUR_DIM
        } else {
            attributes.dimAmount = PLAIN_DIM
        }
        target.attributes = attributes
    }

    private fun crossWindowBlurEnabled(window: Window): Boolean = runCatching {
        val manager = window.context.getSystemService(WindowManager::class.java)
        manager != null && manager.isCrossWindowBlurEnabled
    }.getOrDefault(false)
}
