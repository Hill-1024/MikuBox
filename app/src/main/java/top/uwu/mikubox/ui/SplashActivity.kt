package top.uwu.mikubox.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.uwu.mikubox.R

class SplashActivity : EdgeToEdgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        // The layout is full-bleed colourPrimary and carries fitsSystemWindows,
        // so the status and navigation bars stay part of the splash colour.
        val info = packageManager.getPackageInfo(packageName, 0)
        val versionName = info.versionName ?: "UwU"
        val versionCode =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        findViewById<TextView>(R.id.splash_version).text =
            getString(R.string.splash_version, versionName, versionCode)
        lifecycleScope.launch {
            delay(1500)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }

    @Deprecated("Splash screen does not handle back navigation")
    override fun onBackPressed() = Unit
}
