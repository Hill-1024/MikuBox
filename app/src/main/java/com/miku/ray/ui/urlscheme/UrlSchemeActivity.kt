package com.miku.ray.ui.urlscheme

import com.miku.ray.ui.base.BaseActivity
import com.miku.ray.ui.main.MainActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.miku.ray.AppConfig
import com.miku.ray.R
import com.miku.ray.databinding.ActivityLogcatBinding
import com.miku.ray.extension.snackbarDefault
import com.miku.ray.extension.snackbarError
import com.miku.ray.handler.AngConfigManager
import com.miku.ray.util.ImportConfirmation
import com.miku.ray.util.LogUtil
import com.miku.ray.util.requestSubscriptionImportName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseActivity() {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        try {
            var link: String? = null
            // Shared text was sent by the user picking this app in the share
            // sheet; a deep link can be fired by any app or web page, so only
            // that one asks first.
            var fromDeepLink = false
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        link = getStringExtra(Intent.EXTRA_TEXT)?.let { prepare(it, null) }
                    }
                } else if (action == Intent.ACTION_VIEW) {
                    when (data?.host) {
                        "install-config", "install-sub" -> {
                            val uri: Uri? = intent.data
                            val shareUrl = uri?.getQueryParameter("url").orEmpty()
                            link = prepare(shareUrl, uri?.fragment)
                            fromDeepLink = true
                        }

                        else -> {
                            snackbarError(R.string.toast_failure, title = getString(R.string.title_alerter_error))
                        }
                    }
                }
            }

            val pending = link
            when {
                pending == null -> openMain()
                fromDeepLink -> ImportConfirmation.ask(this, pending) { accepted ->
                    if (accepted) import(pending) else openMain()
                }
                else -> import(pending)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
            finish()
        }
    }

    /** The text to import, or null when there is nothing usable in [uriString]. */
    private fun prepare(uriString: String?, fragment: String?): String? {
        if (uriString.isNullOrEmpty()) {
            return null
        }
        // The shared text is a node link or subscription URL, i.e. credentials:
        // it must not reach the log buffer that the crash report attaches.
        var decodedUrl = runCatching { URLDecoder.decode(uriString, "UTF-8") }.getOrDefault(uriString)
        val uri = Uri.parse(decodedUrl) ?: return null
        if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
            decodedUrl += "#${fragment}"
        }
        return decodedUrl
    }

    private fun import(decodedUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // importBatchConfig throws on a failed fetch or an unparsable body;
            // an uncaught exception here would take the process down.
            val (count, countSub) = try {
                AngConfigManager.importBatchConfig(
                    decodedUrl,
                    "",
                    false
                ) { suggested, existing ->
                    requestSubscriptionImportName(suggested, existing)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import the shared link", e)
                0 to 0
            }
            withContext(Dispatchers.Main) {
                if (count + countSub > 0) {
                    snackbarDefault(R.string.import_subscription_success, title = getString(R.string.title_alerter_info))
                } else {
                    snackbarDefault(R.string.import_subscription_failure, title = getString(R.string.title_alerter_info))
                }
                // Only now: finishing earlier cancels lifecycleScope and the
                // import with it.
                openMain()
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
