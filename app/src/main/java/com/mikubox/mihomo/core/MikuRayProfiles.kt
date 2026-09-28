package com.mikubox.mihomo.core

import com.miku.ray.MikuProfiles
import com.miku.ray.util.LogUtil
import com.mikubox.mihomo.profile.MihomoProfileStore
import com.mikubox.mihomo.profile.MihomoSubscriptionDecoder
import com.mikubox.mihomo.profile.MihomoSubscriptionUpdater

object MikuRayProfiles : MikuProfiles.Impl {
    private val context get() = checkNotNull(MikuRayBridgeContext.application)
    override fun list() = MihomoProfileStore.profiles(context).map { MikuProfiles.Profile(it.id, it.name, it.config, it.groupId) }
    override fun routingEnabled(id: String) = com.mikubox.mihomo.profile.ProfileRouting.enabled(context, id)
    override fun disabledPolicies(id: String) = com.mikubox.mihomo.profile.ProfileRouting.disabledPolicies(context, id)
    override fun setRoutingEnabled(id: String, enabled: Boolean) = com.mikubox.mihomo.profile.ProfileRouting.setEnabled(context, id, enabled)
    override fun setPolicyEnabled(id: String, policy: String, enabled: Boolean) = com.mikubox.mihomo.profile.ProfileRouting.setPolicy(context, id, policy, enabled)
    override fun groups() = com.mikubox.mihomo.profile.ProfileGroups.list(context)
    override fun createGroup(name: String) = com.mikubox.mihomo.profile.ProfileGroups.create(context, name).also { sync() }
    override fun deleteGroup(id: String) {
        MihomoProfileStore.deleteGroup(context, id)
        sync()
        com.miku.ray.handler.SettingsChangeManager.makeSetupGroupTab()
    }
    override fun moveToGroup(id: String, groupId: String) {
        require(groups().any { it.id == groupId }) { "Unknown group" }
        val profile = requireNotNull(MihomoProfileStore.profiles(context).firstOrNull { it.id == id })
        MihomoProfileStore.update(context, profile.copy(groupId = groupId))
        sync()
    }
    override fun get(id: String): MikuProfiles.Profile? =
        MihomoProfileStore.profiles(context).firstOrNull { it.id == id }?.let {
            MikuProfiles.Profile(it.id, it.name, it.config, it.groupId)
        }

    override fun save(id: String?, name: String, content: String): String {
        val config = MihomoSubscriptionDecoder.toMihomoConfig(context, content)
        com.miku.ray.ui.server.ConfigDocument.parse(config).let { com.miku.ray.ui.server.ConfigDocument.rules(it) }
        val previous = id?.takeIf { it.isNotBlank() }?.let { wanted ->
            requireNotNull(MihomoProfileStore.profiles(context).firstOrNull { it.id == wanted })
        }
        val result = if (previous == null) MihomoProfileStore.create(context, name, config)
        else previous.copy(name = name, config = config, updatedAtMillis = System.currentTimeMillis()).also {
            MihomoProfileStore.update(context, it)
        }
        sync()
        return result.id
    }

    override fun importContent(content: String, groupId: String): Pair<Int, Int> {
        val destination = groups().firstOrNull { it.id == groupId }?.id ?: com.miku.ray.AppConfig.DEFAULT_SUBSCRIPTION_ID
        return try { importProfileContent(content, destination) } finally { sync() }
    }

    private fun importProfileContent(content: String, groupId: String): Pair<Int, Int> {
        val text = content.trim()
        val uri = android.net.Uri.parse(text)
        if (!text.contains('\n') && uri.scheme in listOf("clash", "sn")) {
            val profile = try {
                com.mikubox.mihomo.profile.MihomoProfileImporter.importUri(context, uri, groupId)
            } finally { sync() }
            return (if (profile.isSubscription) 0 to 1 else 1 to 0)
        }
        if (!text.contains('\n') && uri.scheme in listOf("http", "https") && uri.userInfo == null) {
            try {
                com.mikubox.mihomo.profile.MihomoProfileImporter.importSubscription(context, uri.host ?: "Subscription", text, groupId = groupId)
            } finally { sync() }
            return 0 to 1
        }
        com.mikubox.mihomo.profile.MihomoProfileImporter.importConfig(context,
            context.getString(com.mikubox.mihomo.R.string.profile_imported_default_name), text, groupId)
        return 1 to 0
    }

    override fun remove(id: String) {
        if (get(id) != null) MihomoProfileStore.remove(context, id)
    }
    override fun select(id: String) {
        if (get(id) != null) {
            MihomoProfileStore.select(context, id)
        }
    }
    override fun sync() {
        runCatching { MikuRayProfileSync.sync(context) }
            .onFailure { LogUtil.w(message = "Profile mirror unavailable", throwable = it) }
    }
    override fun exportBackup(): String = BackupManager.export(context)
    override fun validateBackup(content: String) = BackupManager.validate(context, content)
    override fun restoreBackup(content: String) {
        BackupManager.import(context, content)
        MihomoSubscriptionUpdater.reconfigure(context)
        sync()
    }
}
