package com.mozhi.reader.ai.prompt

import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.PersonaLoreEntry
import com.mozhi.reader.core.database.entity.worldBook

/**
 * 世界书条目的命中规则，所有入口（聊天、随读段评、段落讨论、书库伴读、读书笔记）共用。
 *
 * - 常驻条目总是注入；触发式条目要在「命中材料」里找到触发词。命中材料由调用方决定：
 *   聊天是场景原文 + 用户这一句，段评是这一段正文和前后文，讨论是引文、上下文与讨论串。
 * - 触发词默认按不区分大小写的子串匹配；写成 `/正则/flags` 时按正则（flags 支持 i、m、s）。
 *   正则写坏了退回按字面匹配，不让一条坏条目让整本世界书失效。
 * - 次要触发词只在主触发词命中后再过滤一次（SillyTavern 的 selectiveLogic）。
 */
object PersonaLoreMatcher {
    const val AND_ANY = 0
    const val NOT_ALL = 1
    const val NOT_ANY = 2
    const val AND_ALL = 3

    /** 启用、有内容、并且（常驻或被 [material] 触发）的条目，保持世界书原有顺序。 */
    fun active(persona: PersonaEntity, material: String): List<PersonaLoreEntry> =
        if (!persona.worldBookEnabled) emptyList()
        else persona.worldBook().filter { usable(it) && (it.constant || matches(it, material)) }

    /** 只要触发式条目里被命中的那些；常驻条目由调用方另行放进稳定的系统提示词。 */
    fun triggered(persona: PersonaEntity, material: String): List<PersonaLoreEntry> =
        if (!persona.worldBookEnabled) emptyList()
        else persona.worldBook().filter { usable(it) && !it.constant && matches(it, material) }

    fun constant(persona: PersonaEntity): List<PersonaLoreEntry> =
        if (!persona.worldBookEnabled) emptyList()
        else persona.worldBook().filter { usable(it) && it.constant }

    private fun usable(entry: PersonaLoreEntry) = entry.enabled && entry.content.isNotBlank()

    fun matches(entry: PersonaLoreEntry, material: String): Boolean {
        if (material.isBlank()) return false
        val primary = entry.keys.filter(String::isNotBlank)
        if (primary.none { hit(it, material, entry.caseSensitive) }) return false
        val secondary = entry.secondaryKeys.filter(String::isNotBlank)
        if (secondary.isEmpty()) return true
        val hits = secondary.count { hit(it, material, entry.caseSensitive) }
        return when (entry.selectiveLogic) {
            NOT_ALL -> hits < secondary.size
            NOT_ANY -> hits == 0
            AND_ALL -> hits == secondary.size
            else -> hits > 0
        }
    }

    fun hit(key: String, material: String, caseSensitive: Boolean = false): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        regexOf(trimmed)?.let { regex -> return regex.containsMatchIn(material) }
        return material.contains(trimmed, ignoreCase = !caseSensitive)
    }

    private val REGEX_KEY = Regex("^/(.+)/([a-zA-Z]*)$", RegexOption.DOT_MATCHES_ALL)
    private val cache = object : LinkedHashMap<String, Regex?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>?) = size > 256
    }

    /** `/pattern/flags` → Regex；不是这种写法或编译失败时返回 null（调用方按字面匹配）。 */
    internal fun regexOf(key: String): Regex? {
        val match = REGEX_KEY.matchEntire(key) ?: return null
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        val options = buildSet {
            match.groupValues[2].forEach { flag ->
                when (flag.lowercaseChar()) {
                    'i' -> add(RegexOption.IGNORE_CASE)
                    'm' -> add(RegexOption.MULTILINE)
                    's' -> add(RegexOption.DOT_MATCHES_ALL)
                }
            }
        }
        val compiled = runCatching { Regex(match.groupValues[1], options) }.getOrNull()
        synchronized(cache) { cache[key] = compiled }
        return compiled
    }

    /**
     * 编辑框里的一串触发词拆成列表：顿号、逗号、换行分隔；`/正则/flags` 里的逗号（如 `{1,3}`）不拆。
     * [joinKeys] 是它的反向，用顿号连起来。
     */
    fun splitKeys(text: String): List<String> {
        val keys = mutableListOf<String>()
        val current = StringBuilder()
        var inRegex = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (current.isBlank() && char == '/') inRegex = true
            if (inRegex && char == '/' && current.isNotBlank() && text.getOrNull(index - 1) != '\\') {
                // 正则在第二个 / 后面跟 flags 就结束。
                current.append(char)
                index++
                while (index < text.length && text[index].isLetter() && text[index].code < 128) current.append(text[index++])
                inRegex = false
                continue
            }
            if (!inRegex && char in KEY_SEPARATORS) {
                current.toString().trim().takeIf(String::isNotEmpty)?.let(keys::add)
                current.clear()
            } else current.append(char)
            index++
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(keys::add)
        return keys
    }

    fun joinKeys(keys: List<String>): String = keys.joinToString("、")

    private val KEY_SEPARATORS = setOf('、', ',', '，', '\n', ';', '；')

    /** 渲染成【设定集】块；没有条目时返回空串。 */
    fun block(entries: List<PersonaLoreEntry>, header: String = "【设定集】"): String =
        if (entries.isEmpty()) "" else buildString {
            append(header)
            entries.forEach { entry ->
                append("\n- ")
                if (entry.name.isNotBlank()) append(entry.name).append("：")
                append(entry.content)
            }
        }
}
