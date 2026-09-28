package com.miku.ray.ui.server

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.miku.ray.MikuProfiles

object ProfileGroupPicker {
    fun create(context: Context, done: (MikuProfiles.Group) -> Unit) {
        val input = TextInputEditText(context).apply { setSingleLine() }
        val box = TextInputLayout(context).apply {
            hint = "组名"
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle("创建配置组")
            .setView(box).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { requireNotNull(MikuProfiles.impl).createGroup(input.text.toString()) }
                    .onSuccess { dialog.dismiss(); done(it) }
                    .onFailure { box.error = it.message }
            }
        }
        dialog.show()
    }

    fun manage(context: Context, done: () -> Unit) {
        val store = MikuProfiles.impl ?: return
        val groups = store.groups().filter { it.id != com.miku.ray.AppConfig.DEFAULT_SUBSCRIPTION_ID }
        val builder = MaterialAlertDialogBuilder(context).setTitle("管理配置组")
            .setNeutralButton("创建配置组") { _, _ -> create(context) { done() } }
            .setNegativeButton(android.R.string.cancel, null)
        if (groups.isEmpty()) builder.setMessage("默认 Miku 组用于收纳配置，不能删除。你可以创建新的配置组。")
        else builder.setItems(groups.map { group ->
            "${group.name} · ${store.list().count { it.groupId == group.id }} 个配置"
        }.toTypedArray()) { _, index -> confirmDelete(context, groups[index], done) }
        builder.show()
    }

    fun confirmDelete(context: Context, group: MikuProfiles.Group, done: () -> Unit) {
        if (group.id == com.miku.ray.AppConfig.DEFAULT_SUBSCRIPTION_ID) { manage(context, done); return }
        MaterialAlertDialogBuilder(context).setTitle("删除配置组“${group.name}”？")
            .setMessage("组内的配置和订阅将移回 Miku，内容、订阅设置和当前连接都会保留。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("删除配置组") { _, _ ->
                runCatching { requireNotNull(MikuProfiles.impl).deleteGroup(group.id) }
                    .onSuccess { done() }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            }.show()
    }

    fun choose(context: Context, selected: String, done: (MikuProfiles.Group) -> Unit) {
        val groups = MikuProfiles.impl?.groups().orEmpty()
        MaterialAlertDialogBuilder(context).setTitle("配置组")
            .setSingleChoiceItems(groups.map { it.name }.toTypedArray(), groups.indexOfFirst { it.id == selected }) { dialog, index ->
                dialog.dismiss()
                done(groups[index])
            }
            .setNeutralButton("新建组") { _, _ -> create(context, done) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
