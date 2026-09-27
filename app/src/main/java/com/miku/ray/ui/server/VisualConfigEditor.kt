package com.miku.ray.ui.server

import com.miku.ray.R
import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView

/** A typed tree editor: unknown Mihomo fields stay editable and survive round trips. */
class VisualConfigEditor(context: Context, private val changed: (String) -> Unit) : LinearLayout(context) {
    private var document = linkedMapOf<String, Any?>()
    private val path = mutableListOf<Any>()

    init { orientation = VERTICAL }

    fun load(text: String) {
        document = LinkedHashMap(ConfigDocument.parse(text))
        path.clear()
        render()
    }

    private fun current(): Any? {
        var value: Any? = document
        path.forEach { key -> value = if (key is Int) (value as List<*>)[key] else (value as Map<*, *>)[key] }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun set(key: Any, value: Any?) {
        when (val parent = current()) {
            is MutableMap<*, *> -> (parent as MutableMap<String, Any?>)[key as String] = value
            is MutableList<*> -> (parent as MutableList<Any?>)[key as Int] = value
        }
        changed(ConfigDocument.dump(document))
        render()
    }

    private fun button(label: String, action: () -> Unit) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun render() {
        removeAllViews()
        addView(MaterialTextView(context).apply {
            text = if (path.isEmpty()) context.getString(R.string.profile_visual_summary) else path.joinToString(" / ")
        })
        if (path.isNotEmpty()) addView(button("返回上一级") { path.removeAt(path.lastIndex); render() })
        val current = current()
        val entries: List<Pair<Any, Any?>> = when (current) {
            is Map<*, *> -> current.entries.map { it.key!! to it.value }
            is List<*> -> current.mapIndexed { index, value -> index to value }
            else -> emptyList()
        }
        entries.forEach { (key, value) ->
            val label = if (key is Int) "${key + 1}. ${(value as? Map<*, *>)?.get("name") ?: value?.toString()?.take(80).orEmpty()}" else labelFor(key.toString())
            when (value) {
                is Boolean -> addView(MaterialSwitch(context).apply {
                    text = label
                    isChecked = value
                    setOnCheckedChangeListener { _, checked -> set(key, checked) }
                })
                is Map<*, *>, is List<*> -> addView(button("$label  ›") { path.add(key); render() })
                else -> addView(button("$label${if (key is String) "：${value ?: "null"}" else ""}") { edit(key, value) })
            }
            getChildAt(childCount - 1).setOnLongClickListener {
                MaterialAlertDialogBuilder(context).setTitle(label)
                    .setItems(if (current is List<*>) arrayOf("上移", "下移", "删除") else arrayOf("删除")) { _, action ->
                        @Suppress("UNCHECKED_CAST")
                        if (current is MutableList<*>) {
                            val list = current as MutableList<Any?>
                            val index = key as Int
                            when (action) {
                                0 -> if (index > 0) java.util.Collections.swap(list, index, index - 1)
                                1 -> if (index < list.lastIndex) java.util.Collections.swap(list, index, index + 1)
                                2 -> list.removeAt(index)
                            }
                        } else (current as? MutableMap<*, *>)?.remove(key)
                        changed(ConfigDocument.dump(document)); render()
                    }.show()
                true
            }
        }
        addView(button(if (current is List<*>) "添加条目" else "添加字段") { addEntry() })
    }

    private fun labelFor(key: String): String = when (key) {
        "proxies" -> context.getString(R.string.profile_section_proxies)
        "proxy-groups" -> context.getString(R.string.profile_section_groups)
        "rules" -> context.getString(R.string.profile_section_rules)
        "dns" -> context.getString(R.string.profile_section_dns)
        "proxy-providers" -> context.getString(R.string.profile_section_providers)
        "rule-providers" -> context.getString(R.string.profile_section_rule_providers)
        else -> key
    }

    private fun edit(key: Any, old: Any?) {
        if (path.lastOrNull() == "rules" && old is String) {
            editRule(old) { set(key, it) }
            return
        }
        val input = TextInputEditText(context).apply {
            setText(old?.toString().orEmpty())
            inputType = if (old is Number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val box = TextInputLayout(context).apply { hint = key.toString(); addView(input) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle("编辑字段").setView(box)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                runCatching {
                    val text = input.text.toString()
                    val value: Any = when (old) {
                        is Int -> text.toInt()
                        is Long -> text.toLong()
                        is Number -> text.toDouble().also { require(it.isFinite()) }
                        else -> text
                    }
                    set(key, value)
                }.onSuccess { dialog.dismiss() }.onFailure { box.error = "值无效：${it.message}" }
            }
        }
        dialog.show()
    }

    private fun editRule(source: String, save: (String) -> Unit) {
        val parts = source.split(',').map(String::trim)
        val flags = parts.takeLastWhile { it.lowercase() in setOf("no-resolve", "src") }
        val rule = parts.dropLast(flags.size)
        val fields = LinearLayout(context).apply {
            orientation = VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        fun field(label: String, value: String): TextInputEditText {
            val input = TextInputEditText(context).apply { setSingleLine(); setText(value) }
            fields.addView(TextInputLayout(context).apply { hint = label; addView(input) })
            return input
        }
        val type = field("规则类型", rule.firstOrNull().orEmpty())
        val payload = field("匹配内容（MATCH 留空）", rule.drop(1).dropLast(1).joinToString(","))
        val target = field("目标策略", rule.lastOrNull().orEmpty())
        val noResolve = MaterialSwitch(context).apply { text = "no-resolve"; isChecked = "no-resolve" in flags }
        fields.addView(noResolve)
        val dialog = MaterialAlertDialogBuilder(context).setTitle("编辑分流规则").setView(fields)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val kind = type.text.toString().trim().uppercase()
                val value = payload.text.toString().trim()
                val destination = target.text.toString().trim()
                if (kind.isBlank() || ',' in kind) { type.error = "请输入规则类型"; return@setOnClickListener }
                if (destination.isBlank() || ',' in destination) { target.error = "请输入目标策略"; return@setOnClickListener }
                if (kind != "MATCH" && value.isBlank()) { payload.error = "请输入匹配内容"; return@setOnClickListener }
                val result = if (kind == "MATCH") "$kind,$destination" else "$kind,$value,$destination"
                save(result + (if (noResolve.isChecked) ",no-resolve" else "") + (if ("src" in flags) ",src" else ""))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    @Suppress("UNCHECKED_CAST")
    private fun addEntry() {
        if (path.lastOrNull() == "rules" && current() is MutableList<*>) {
            editRule("DOMAIN-SUFFIX,example.com,DIRECT") { rule ->
                (current() as MutableList<Any?>).add(rule)
                changed(ConfigDocument.dump(document)); render()
            }
            return
        }
        val container = LinearLayout(context).apply { orientation = VERTICAL }
        val keyInput = TextInputEditText(context).apply { hint = "字段名" }
        val valueInput = TextInputEditText(context).apply { hint = "值（YAML：字符串、数字、true、{} 或 []）" }
        if (current() is Map<*, *>) container.addView(keyInput)
        container.addView(valueInput)
        val dialog = MaterialAlertDialogBuilder(context).setTitle("添加")
            .setView(container).setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                runCatching {
                    val value = ConfigDocument.parse("value: ${valueInput.text}")["value"]
                    when (val parent = current()) {
                        is MutableMap<*, *> -> {
                            val key = keyInput.text.toString().trim()
                            require(key.isNotEmpty() && !parent.containsKey(key)) { "字段名为空或已存在" }
                            (parent as MutableMap<String, Any?>)[key] = value
                        }
                        is MutableList<*> -> (parent as MutableList<Any?>).add(value)
                    }
                    changed(ConfigDocument.dump(document)); render()
                }.onSuccess { dialog.dismiss() }.onFailure { valueInput.error = it.message }
            }
        }
        dialog.show()
    }
}
