package com.miku.ray.ui.server

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Full YAML parsing, including flow collections, quoted values and aliases. */
object ConfigDocument {
    fun parse(text: String): MutableMap<String, Any?> {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            codePointLimit = 8 * 1024 * 1024
        }
        val value = Yaml(SafeConstructor(options)).load<Any?>(text)
        require(value is Map<*, *> && value.keys.all { it is String }) { "配置必须是 YAML 对象" }
        @Suppress("UNCHECKED_CAST")
        return value.normalizeKeys() as MutableMap<String, Any?>
    }

    /**
     * YAML 1.1 reads `on:`/`yes:` as a Boolean key, `1.5:` as a number and `~:`
     * as null. The visual editor addresses nested fields by String key or list
     * index, so a non-string key below the top level crashed it; stringifying
     * them matches how mihomo's own parser reads such a key.
     */
    private fun Any?.normalizeKeys(): Any? = when (this) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out ->
            forEach { (k, v) -> out[k?.toString() ?: "null"] = v.normalizeKeys() }
        }
        is List<*> -> mapTo(ArrayList()) { it.normalizeKeys() }
        else -> this
    }

    fun dump(value: Any?): String = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
    }).dump(value)

    fun policy(rule: String): String {
        val parts = rule.split(',').map(String::trim).dropLastWhile { it.lowercase() in setOf("no-resolve", "src") }
        return parts.lastOrNull().orEmpty()
    }

    fun rules(document: Map<String, Any?>): List<String> {
        val rules = document["rules"] ?: return emptyList()
        require(rules is List<*> && rules.all { it is String }) { "rules 必须是规则字符串列表" }
        return rules.filterIsInstance<String>()
    }
}
