package com.miku.ray.util

import android.app.Activity
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.miku.ray.R

/**
 * The question asked before a link that another app handed us is imported.
 *
 * The deep-link entry points are reachable from any installed app or web page,
 * and an imported profile carries its own rules and DNS, so a link must not
 * change what the tunnel does without the user agreeing to it.
 */
object ImportConfirmation {

    private val WRAPPER_SCHEMES = setOf("clash", "sn", "v2rayng")
    private const val MAX_HOST_LENGTH = 64

    /**
     * Names where [text] comes from without echoing anything secret: only the
     * scheme and host are shown, never the userinfo, path, query or a Base64
     * payload. A multi-line paste shows its first link and how many follow it.
     */
    fun describe(text: String): String {
        val links = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (links.isEmpty()) return ""
        val first = describeOne(links.first())
        return if (links.size > 1) "$first (+${links.size - 1})" else first
    }

    private fun describeOne(link: String): String {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return "?"
        val scheme = uri.scheme?.lowercase() ?: return "?"
        // clash://install-config?url=https://provider/sub names the provider, not
        // the wrapper.
        val target = if (scheme in WRAPPER_SCHEMES) {
            uri.getQueryParameter("url")
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.scheme != null && it.host != null }
                ?: uri
        } else uri
        val host = target.host?.takeIf { it.length <= MAX_HOST_LENGTH }
        return "${target.scheme?.lowercase() ?: scheme}://${host ?: "…"}"
    }

    /** Reports the decision once, whichever way the dialog is closed. */
    fun ask(activity: Activity, text: String, onDecision: (Boolean) -> Unit) {
        var decided = false
        fun decide(accepted: Boolean) {
            if (decided) return
            decided = true
            onDecision(accepted)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.import_confirm_title)
            .setMessage(activity.getString(R.string.import_confirm_message, describe(text)))
            .setPositiveButton(R.string.import_confirm_positive) { _, _ -> decide(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> decide(false) }
            .setOnCancelListener { decide(false) }
            // A rotation tears the dialog down with the activity; the recreated
            // instance asks again, so that dismissal is not an answer.
            .setOnDismissListener { if (!activity.isChangingConfigurations) decide(false) }
            .show()
    }
}
