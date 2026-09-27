package com.mikubox.mihomo.profile

import android.content.Context
import com.miku.ray.AppConfig
import com.miku.ray.MikuProfiles
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Group metadata lives with profiles, including in exported backups. */
object ProfileGroups {
    private fun prefs(context: Context) = context.getSharedPreferences("mihomo_profiles", Context.MODE_PRIVATE)

    @Synchronized fun list(context: Context): List<MikuProfiles.Group> {
        val saved = JSONArray(prefs(context).getString("groups", "[]"))
        return listOf(MikuProfiles.Group(AppConfig.DEFAULT_SUBSCRIPTION_ID, "Miku")) +
            List(saved.length()) { saved.getJSONObject(it).let { g -> MikuProfiles.Group(g.getString("id"), g.getString("name")) } }
    }

    @Synchronized fun create(context: Context, name: String): MikuProfiles.Group {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入组名" }
        require(list(context).none { it.name.equals(trimmed, ignoreCase = true) }) { "组名已存在" }
        val group = MikuProfiles.Group(UUID.randomUUID().toString(), trimmed)
        val saved = JSONArray(prefs(context).getString("groups", "[]"))
        saved.put(JSONObject().put("id", group.id).put("name", group.name))
        check(prefs(context).edit().putString("groups", saved.toString()).commit())
        return group
    }
}
