package top.uwu.mikubox.profile

import android.content.Context
import org.json.JSONObject
import top.uwu.mikubox.core.MihomoCore

/**
 * Persistent per-profile traffic accounting, equivalent to UwU's profile traffic
 * log.
 *
 * The core reports what the running session moved in total and how that splits
 * across nodes and proxy groups (a connection counts for every proxy it passed
 * through, so a group carries the sum of its members). Both are cumulative for
 * the session, so this store keeps a baseline and folds only the difference into
 * the profile's stored numbers: the service checkpoints that every so often, and
 * a connection that ends abruptly still keeps everything up to the last
 * checkpoint instead of losing the whole session.
 */
object MihomoTrafficStore {

    data class Totals(val upload: Long, val download: Long)

    private const val PREFS = "mihomo_traffic"
    private const val KEY_PROXY_SUFFIX = ".proxy"

    private var activeProfileId: String? = null
    private var uploadBaseline = 0L
    private var downloadBaseline = 0L
    private var proxyBaseline: Map<String, MihomoCore.ProxyTraffic> = emptyMap()

    fun begin(profile: MihomoProfileStore.Profile?) {
        activeProfileId = profile?.id
        MihomoCore.traffic().also {
            uploadBaseline = it.uploadTotal
            downloadBaseline = it.downloadTotal
        }
        proxyBaseline = emptyMap()
    }

    /** Folds everything measured so far into the profile, without ending the session. */
    fun checkpoint(context: Context) = fold(context)

    fun finish(context: Context) {
        fold(context)
        activeProfileId = null
    }

    fun totals(context: Context, profileId: String): Totals = Totals(
        upload = prefs(context).getLong("$profileId.upload", 0),
        download = prefs(context).getLong("$profileId.download", 0),
    )

    /**
     * Traffic each node and group carried for [profileId] so far, including the
     * part of the running session that has not been folded in yet.
     */
    fun proxyTotals(context: Context, profileId: String): Map<String, Totals> {
        val stored = storedProxyTotals(context, profileId).toMutableMap()
        foldPending(profileId, stored, MihomoCore.trafficByProxy())
        return stored
    }

    /** Clears both the profile total and its per-node breakdown. */
    fun reset(context: Context, profileId: String) {
        prefs(context).edit()
            .remove("$profileId.upload")
            .remove("$profileId.download")
            .remove("$profileId$KEY_PROXY_SUFFIX")
            .commit()
    }

    /** Adds what the session moved since the last fold to the stored numbers. */
    private fun fold(context: Context) {
        val id = activeProfileId ?: return
        val session = MihomoCore.trafficByProxy()
        val stored = storedProxyTotals(context, id).toMutableMap()
        foldPending(id, stored, session)

        val traffic = MihomoCore.traffic()
        val previous = totals(context, id)
        prefs(context).edit()
            .putLong(
                "$id.upload",
                previous.upload + (traffic.uploadTotal - uploadBaseline).coerceAtLeast(0),
            )
            .putLong(
                "$id.download",
                previous.download + (traffic.downloadTotal - downloadBaseline).coerceAtLeast(0),
            )
            .putString("$id$KEY_PROXY_SUFFIX", encode(stored))
            .commit()

        uploadBaseline = traffic.uploadTotal
        downloadBaseline = traffic.downloadTotal
        proxyBaseline = session
    }

    /**
     * Adds the per-proxy difference between [session] and the last fold into
     * [stored]. Keeping the baseline means a checkpoint never counts the same
     * bytes twice.
     */
    private fun foldPending(
        profileId: String,
        stored: MutableMap<String, Totals>,
        session: Map<String, MihomoCore.ProxyTraffic>,
    ) {
        val folded = if (profileId == activeProfileId) proxyBaseline else emptyMap()
        session.forEach { (name, current) ->
            val last = folded[name]
            val upload = (current.upload - (last?.upload ?: 0L)).coerceAtLeast(0)
            val download = (current.download - (last?.download ?: 0L)).coerceAtLeast(0)
            if (upload == 0L && download == 0L) return@forEach
            val base = stored[name] ?: Totals(0, 0)
            stored[name] = Totals(base.upload + upload, base.download + download)
        }
    }

    private fun encode(totals: Map<String, Totals>): String = JSONObject().also { root ->
        totals.forEach { (name, value) ->
            root.put(
                name,
                JSONObject().put("upload", value.upload).put("download", value.download),
            )
        }
    }.toString()

    private fun storedProxyTotals(context: Context, profileId: String): Map<String, Totals> {
        val raw = prefs(context).getString("$profileId$KEY_PROXY_SUFFIX", null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { name ->
                    val entry = root.optJSONObject(name) ?: return@forEach
                    put(name, Totals(entry.optLong("upload"), entry.optLong("download")))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
