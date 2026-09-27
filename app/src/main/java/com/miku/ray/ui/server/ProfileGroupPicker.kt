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
