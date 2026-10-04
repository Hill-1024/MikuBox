package com.mikubox.mihomo.service

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.miku.ray.util.ImportConfirmation
import com.mikubox.mihomo.profile.MihomoProfileImporter

/**
 * Transparent deep-link importer for UwU's share/import workflows.
 *
 * Any installed app or web page can fire these links, so nothing is imported
 * until the user has seen where it comes from and agreed.
 */
class MihomoImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        ImportConfirmation.ask(this, data.toString()) { accepted ->
            if (accepted) import(data) else finish()
        }
    }

    private fun import(data: Uri) {
        // Plain Activity has no lifecycleScope; a raw thread keeps the read
        // and parse off the main thread, and finish() only fires once the
        // import settles so the transient URI grant outlives the work.
        Thread {
            val result = runCatching {
                try { MihomoProfileImporter.importUri(this, data) }
                finally { com.mikubox.mihomo.core.MikuRayProfiles.sync() }
            }
            runOnUiThread {
                android.widget.Toast.makeText(this, result.exceptionOrNull()?.message
                    ?: getString(com.miku.ray.R.string.toast_success), android.widget.Toast.LENGTH_LONG).show()
            }
            runOnUiThread { finish() }
        }.start()
    }
}
