package top.uwu.mikubox

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.uwu.mikubox.core.BackupManager
import top.uwu.mikubox.profile.MihomoProfileStore
import top.uwu.mikubox.profile.MihomoTrafficStore
import top.uwu.mikubox.profile.MihomoSubscriptionDecoder
import top.uwu.mikubox.service.CoreOwnership

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RegressionTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test fun malformedLaterStoreDoesNotOverwriteEarlierStore() {
        val prefs = context.getSharedPreferences("miku_app_settings", 0)
        prefs.edit().putString("sentinel", "original").commit()
        val stores = JSONObject()
            .put("miku_app_settings", JSONObject().put("sentinel", entry("s", "replacement")))
            .put("mihomo_profiles", JSONObject().put("profiles", entry("unknown", "bad")))
        assertThrows(IllegalStateException::class.java) {
            BackupManager.import(context, JSONObject().put("version", 1).put("stores", stores).toString())
        }
        assertEquals("original", prefs.getString("sentinel", null))
    }

    @Test fun unsupportedBackupVersionDoesNotChangePreferences() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.import(context, """{"version":99,"stores":{}}""")
        }
    }

    @Test fun backupRoundTripPreservesPreferenceTypes() {
        val prefs = context.getSharedPreferences("miku_app_settings", 0)
        prefs.edit().putString("s", "value").putInt("i", 8).putLong("l", 9000000000L)
            .putFloat("f", 1.5f).putBoolean("b", true).putStringSet("ss", setOf("a", "b")).commit()
        val original = prefs.all
        val backup = BackupManager.export(context)
        prefs.edit().clear().commit()
        BackupManager.import(context, backup)
        assertEquals(original, prefs.all)
    }

    @Test fun subscriptionRefreshPreservesConcurrentMetadataChanges() {
        val source = seedProfile()
        seedProfile(pinned = true, name = "renamed")
        MihomoProfileStore.updateSubscription(context, source, "new config")
        val result = MihomoProfileStore.profiles(context).single()
        assertTrue(result.pinned)
        assertEquals("renamed", result.name)
        assertEquals("new config", result.config)
    }

    @Test fun subscriptionRefreshRejectsChangedContentAndDeletedProfiles() {
        val source = seedProfile()
        seedProfile(config = "edited")
        assertThrows(IllegalStateException::class.java) {
            MihomoProfileStore.updateSubscription(context, source, "stale download")
        }
        assertEquals("edited", MihomoProfileStore.profiles(context).single().config)
        context.getSharedPreferences("mihomo_profiles", 0).edit().putString("profiles", "[]").commit()
        assertThrows(IllegalStateException::class.java) {
            MihomoProfileStore.updateSubscription(context, source, "stale download")
        }
        assertTrue(MihomoProfileStore.profiles(context).isEmpty())
    }

    @Test fun inactiveProfileDoesNotReadLiveNativeTraffic() {
        context.getSharedPreferences("mihomo_traffic", 0).edit()
            .putString("inactive.proxy", """{"node":{"upload":12,"download":34}}""").commit()
        // Loading the Android JNI core on this JVM would fail: inactive profiles
        // must return their own persisted counters without consulting it.
        assertEquals(mapOf("node" to MihomoTrafficStore.Totals(12, 34)),
            MihomoTrafficStore.proxyTotals(context, "inactive"))
    }

    @Test fun directOnlyConfigImportsWithoutProxyNodes() {
        val config = "mode: rule\nrules:\n  - MATCH,DIRECT"
        assertEquals(config, MihomoSubscriptionDecoder.toMihomoConfig(context, config))
        val encoded = android.util.Base64.encodeToString(config.toByteArray(), android.util.Base64.NO_WRAP)
        assertEquals(config, MihomoSubscriptionDecoder.toMihomoConfig(context, encoded))
    }

    @Test fun commentMentioningProxiesIsNotAConfiguration() {
        assertThrows(IllegalStateException::class.java) {
            MihomoSubscriptionDecoder.toMihomoConfig(context, "# proxies: not a config")
        }
    }

    @Test fun obsoleteServiceCannotStopSuccessorAndStopIsIdempotent() {
        val ownership = CoreOwnership()
        val old = Any()
        val current = Any()
        var stops = 0
        ownership.claim(old)
        ownership.claim(current)
        ownership.release(old) { stops++ }
        assertEquals(0, stops)
        ownership.release(current) { stops++ }
        ownership.release(current) { stops++ }
        assertEquals(1, stops)
    }

    @Test fun coreHandoverFinishesPreviousSessionOnlyOnce() {
        val ownership = CoreOwnership()
        val old = Any()
        val next = Any()
        val events = mutableListOf<String>()
        ownership.claim(old)
        ownership.releaseCurrent { events += "finish old" }
        events += "start next"
        ownership.claim(next)
        ownership.release(old) { events += "late old stop" }
        ownership.release(next) { events += "finish next" }
        ownership.releaseCurrent { events += "duplicate stop" }
        assertEquals(listOf("finish old", "start next", "finish next"), events)
    }

    private fun entry(type: String, value: Any) = JSONObject().put("t", type).put("v", value)

    private fun seedProfile(pinned: Boolean = false, name: String = "original", config: String = "old config"):
        MihomoProfileStore.Profile {
        val profile = JSONObject().put("id", "test").put("name", name).put("config", config)
            .put("subscriptionUrl", "https://example.test/sub").put("updatedAtMillis", 1)
            .put("pinned", pinned)
        context.getSharedPreferences("mihomo_profiles", 0).edit()
            .putString("profiles", JSONArray().put(profile).toString()).commit()
        return MihomoProfileStore.profiles(context).single()
    }
}
