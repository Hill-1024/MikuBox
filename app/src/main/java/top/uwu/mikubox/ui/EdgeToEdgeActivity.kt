package top.uwu.mikubox.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Shared MikuRay-style system-bar handling for secondary screens. */
abstract class EdgeToEdgeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    protected fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            // The app bar owns the status-bar strip (it is colourPrimary and
            // has fitsSystemWindows), so the root only takes the side and
            // bottom insets -- padding it here would paint colourBg up there.
            view.updatePadding(
                left = maxOf(bars.left, cutout.left),
                right = maxOf(bars.right, cutout.right),
                bottom = maxOf(bars.bottom, cutout.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
