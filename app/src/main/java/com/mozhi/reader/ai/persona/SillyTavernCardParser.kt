package com.mozhi.reader.ai.persona

import com.mozhi.reader.core.database.entity.PersonaExampleDialog
import com.mozhi.reader.core.database.entity.PersonaLoreEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 从 SillyTavern 角色卡提取出的、已替换宏的人设字段。
 * 世界书条目独立在 [worldBook]（不并进人设描述），禁用条目保留但 enabled = false。
 * [avatarPng] 在 PNG 卡时就是原图字节（角色卡本身即立绘）。
 */
data class ImportedPersonaCard(
    val name: String,
    val subtitle: String,
    val personality: String,
    val greeting: String,
    val exampleDialogs: List<PersonaExampleDialog>,
    val worldBook: List<PersonaLoreEntry>,
    val avatarPng: ByteArray?
)

/**
 * SillyTavern 角色卡解析器。支持：
 * - PNG 卡：walk PNG chunk 流，取 `tEXt` 块里 `ccv3`（V3）或 `chara`（V1/V2）关键字的
 *   base64 JSON；不校验 CRC（容忍改图工具产出的脏 CRC）。
 * - 纯 JSON 卡：V2/V3 的字段在 `data` 下，V1 在根级。
 *
 * 提取规则（「自动提取人设」）：description / personality / scenario 合并进人设描述；
 * 内嵌世界书（character_book）的条目原样进角色的世界书（常驻 / 触发词 / 次要触发词 / 区分大小写），
 * 不少卡把核心人设写在世界书里。卡只链接了外部世界书（extensions.world）时，在角色编辑页用
 * 「从文件导入」把那份世界书文件导进来。
 * `{{char}}` / `{{user}}` 宏分别替换为角色名与「用户」。
 */
object SillyTavernCardParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** 解析失败返回 null（不是角色卡 / 数据损坏）。任何异常都不外抛——导入失败不该闪退。 */
    fun parse(bytes: ByteArray): ImportedPersonaCard? =
        runCatching { parseUnsafe(bytes) }.getOrNull()

    private fun parseUnsafe(bytes: ByteArray): ImportedPersonaCard? {
        val isPng = bytes.size > PNG_SIGNATURE.size &&
            bytes.sliceArray(PNG_SIGNATURE.indices).contentEquals(PNG_SIGNATURE)
        val payload = if (isPng) {
            extractCardJsonFromPng(bytes) ?: return null
        } else {
            bytes.decodeToString()
        }
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return null
        return buildCard(root, avatarPng = bytes.takeIf { isPng })
    }

    /** 取 tEXt 块：V3 的 `ccv3` 优先于 V1/V2 的 `chara`。 */
    private fun extractCardJsonFromPng(bytes: ByteArray): String? {
        var chara: String? = null
        var ccv3: String? = null
        var offset = PNG_SIGNATURE.size
        while (offset + 8 <= bytes.size) {
            val length = readUInt32(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            // 写成减法防 Int 溢出：畸形块的 length 可能接近 Int.MAX_VALUE。
            if (length < 0 || length > bytes.size - dataStart) break
            if (type == "tEXt") {
                val data = bytes.sliceArray(dataStart until dataStart + length)
                val separator = data.indexOf(0.toByte())
                if (separator > 0) {
                    val keyword = String(data, 0, separator, Charsets.ISO_8859_1)
                    val text = String(
                        data, separator + 1, data.size - separator - 1, Charsets.ISO_8859_1
                    )
                    when (keyword.lowercase()) {
                        "ccv3" -> ccv3 = text
                        "chara" -> chara = text
                    }
                }
            }
            if (type == "IEND") break
            offset = dataStart + length + 4 // 跳过 CRC
        }
        val encoded = ccv3 ?: chara ?: return null
        return runCatching {
            java.util.Base64.getDecoder().decode(encoded.filterNot(Char::isWhitespace))
                .decodeToString()
        }.getOrNull()
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun buildCard(root: JsonObject, avatarPng: ByteArray?): ImportedPersonaCard? {
        // V2/V3 字段在 data 下；V1 在根级。
        val data = root["data"] as? JsonObject ?: root
        val name = data.string("name").trim()
        if (name.isEmpty()) return null

        fun String.substituted(): String = this
            .replace("{{char}}", name, ignoreCase = true)
            .replace("{{user}}", "用户", ignoreCase = true)
            .trim()

        val description = data.string("description").substituted()
        val traits = data.string("personality").substituted()
        val scenario = data.string("scenario").substituted()

        val personality = buildString {
            if (description.isNotEmpty()) append(description)
            if (traits.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("性格特质：").append(traits)
            }
            if (scenario.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("场景设定：").append(scenario)
            }
        }

        val worldBook = extractLoreEntries(data).map { entry ->
            entry.copy(name = entry.name.substituted(), content = entry.content.substituted())
        }

        val tags = (data["tags"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()
            }
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val creator = data.string("creator").trim()
        val subtitle = when {
            tags.isNotEmpty() -> tags.take(3).joinToString(" · ")
            creator.isNotEmpty() -> "by $creator"
            else -> ""
        }

        return ImportedPersonaCard(
            name = name,
            subtitle = subtitle,
            personality = personality,
            greeting = data.string("first_mes").substituted(),
            exampleDialogs = parseExampleDialogs(data.string("mes_example"), name),
            worldBook = worldBook,
            avatarPng = avatarPng
        )
    }

    /**
     * 世界书条目全部保留（含禁用的，enabled 随卡带过来），按 insertion_order 排序。
     * 注入方式保真：constant（常驻）与 keys（触发词）照搬；无触发词的条目只能常驻。
     * 条目名取 comment，缺省用首个触发词。
     */
    private fun extractLoreEntries(data: JsonObject): List<PersonaLoreEntry> {
        val book = data["character_book"] as? JsonObject ?: return emptyList()
        return loreEntries(book["entries"])
    }

    /**
     * 读一组世界书条目，兼容两种写法：
     * - 角色卡内嵌的 character_book（V2/V3）：entries 是数组，字段 keys / secondary_keys / enabled /
     *   insertion_order / case_sensitive，selectiveLogic 在 extensions 里；
     * - SillyTavern 导出的世界书文件（World Info）：entries 是以 uid 为键的对象，字段 key / keysecondary /
     *   disable / order / caseSensitive / selectiveLogic。
     * selective 为 false 时 SillyTavern 不看次要触发词，这里也不带。触发词写成「a, b」一整串的拆开。
     */
    internal fun loreEntries(raw: JsonElement?): List<PersonaLoreEntry> {
        val items = when (raw) {
            is JsonArray -> raw.toList()
            is JsonObject -> raw.values.toList()
            else -> return emptyList()
        }
        return items.mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexedNotNull null
            val content = entry.string("content").trim()
            if (content.isEmpty()) return@mapIndexedNotNull null
            val extensions = entry["extensions"] as? JsonObject
            val enabled = entry.bool("enabled") ?: entry.bool("disable")?.not() ?: true
            val keys = entry.keyList("keys").ifEmpty { entry.keyList("key") }
            val selective = entry.bool("selective")
            val secondary = if (selective == false) emptyList()
            else entry.keyList("secondary_keys").ifEmpty { entry.keyList("keysecondary") }
            val logic = (entry.number("selectiveLogic") ?: extensions?.number("selectiveLogic"))?.toInt()
                ?.takeIf { it in 0..3 } ?: 0
            val caseSensitive = entry.bool("case_sensitive") ?: entry.bool("caseSensitive")
                ?: extensions?.bool("case_sensitive") ?: false
            val constant = entry.bool("constant") ?: false
            val order = entry.number("insertion_order") ?: entry.number("order") ?: 0.0
            val name = entry.string("comment").trim().ifEmpty { entry.string("name").trim() }
                .ifEmpty { keys.firstOrNull().orEmpty() }
            Triple(order, index, PersonaLoreEntry(
                name = name,
                content = content,
                enabled = enabled,
                constant = constant || keys.isEmpty(),
                keys = keys,
                secondaryKeys = secondary,
                selectiveLogic = logic,
                caseSensitive = caseSensitive
            ))
        }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
    }

    /**
     * 「从文件导入世界书条目」：角色卡 PNG / JSON（取内嵌世界书）、SillyTavern 世界书 JSON、条目数组，
     * 或者纯文本 / Markdown（按 # 标题拆成几条常驻条目，没有标题就整篇一条）。
     * 返回 null = 读不出任何条目。`{{char}}` 换成 [charName]，`{{user}}` 换成「用户」。
     */
    fun parseLorebook(bytes: ByteArray, fileName: String = "", charName: String = ""): List<PersonaLoreEntry>? =
        runCatching { parseLorebookUnsafe(bytes, fileName, charName) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun parseLorebookUnsafe(bytes: ByteArray, fileName: String, charName: String): List<PersonaLoreEntry> {
        val isPng = bytes.size > PNG_SIGNATURE.size &&
            bytes.sliceArray(PNG_SIGNATURE.indices).contentEquals(PNG_SIGNATURE)
        val text = if (isPng) extractCardJsonFromPng(bytes) ?: return emptyList()
        else bytes.decodeToString().removePrefix("\uFEFF")
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val entries = when {
            root is JsonArray -> loreEntries(root)
            root is JsonObject && root["entries"] != null -> loreEntries(root["entries"])
            root is JsonObject -> {
                val data = root["data"] as? JsonObject ?: root
                val book = data["character_book"] as? JsonObject
                    ?: (data["extensions"] as? JsonObject)?.get("world") as? JsonObject
                loreEntries(book?.get("entries"))
            }
            isPng -> emptyList()
            else -> textEntries(text, fileName)
        }
        val name = charName.ifBlank {
            ((root as? JsonObject)?.let { it["data"] as? JsonObject ?: it })?.string("name").orEmpty()
        }
        fun String.substituted(): String = (if (name.isNotBlank()) replace("{{char}}", name, ignoreCase = true) else this)
            .replace("{{user}}", "用户", ignoreCase = true).trim()
        return entries.map { it.copy(name = it.name.substituted(), content = it.content.substituted()) }
    }

    /** 纯文本 / Markdown：按 # 标题拆条目（标题当条目名），都设成常驻。 */
    internal fun textEntries(text: String, fileName: String = ""): List<PersonaLoreEntry> {
        val body = text.replace("\r\n", "\n").trim()
        if (body.isEmpty()) return emptyList()
        val heading = Regex("^#{1,6}\\s+(.+?)\\s*#*\\s*$")
        val sections = mutableListOf<Pair<String, StringBuilder>>()
        body.lines().forEach { line ->
            val match = heading.matchEntire(line.trim())
            if (match != null) sections += match.groupValues[1].trim() to StringBuilder()
            else if (sections.isEmpty()) sections += "" to StringBuilder(line).append('\n')
            else sections.last().second.append(line).append('\n')
        }
        val fallback = fileName.substringAfterLast('/').substringBeforeLast('.').trim()
        return sections.mapNotNull { (title, content) ->
            val clean = content.toString().trim()
            if (clean.isEmpty()) null
            else PersonaLoreEntry(name = title.ifEmpty { fallback }, content = clean, constant = true)
        }
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { primitive ->
        primitive.booleanOrNull ?: primitive.contentOrNull?.trim()?.lowercase()?.let { text ->
            when (text) { "true", "1" -> true; "false", "0" -> false; else -> null }
        }
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    /** 触发词：数组，或者「a, b，c」一整串。 */
    private fun JsonObject.keyList(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        is JsonPrimitive -> value.contentOrNull.orEmpty().split(',', '，').map(String::trim)
        else -> emptyList()
    }.filter(String::isNotEmpty)

    /**
     * mes_example 解析：`<START>` 分块，块内 `{{user}}:` / `{{char}}:` 行交替，
     * 说话人的续行并入上一句。产出 user→assistant 成对。
     */
    internal fun parseExampleDialogs(
        mesExample: String,
        name: String
    ): List<PersonaExampleDialog> {
        if (mesExample.isBlank()) return emptyList()
        val dialogs = mutableListOf<PersonaExampleDialog>()
        var pendingUser: StringBuilder? = null
        var pendingAssistant: StringBuilder? = null

        fun flushPair() {
            val user = pendingUser?.toString()?.trim().orEmpty()
            val assistant = pendingAssistant?.toString()?.trim().orEmpty()
            if (user.isNotEmpty() && assistant.isNotEmpty()) {
                dialogs += PersonaExampleDialog(user = user, assistant = assistant)
            }
            pendingUser = null
            pendingAssistant = null
        }

        mesExample.split(Regex("<START>", RegexOption.IGNORE_CASE)).forEach { block ->
            block.lines().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val userMatch = USER_PREFIX.find(line)
                val charMatch = CHAR_PREFIX.find(line)
                when {
                    userMatch != null -> {
                        if (pendingAssistant != null) flushPair()
                        pendingUser = StringBuilder(line.removeRange(userMatch.range).trim())
                    }
                    charMatch != null -> {
                        if (pendingUser == null) return@forEach // 没有 user 先导的孤立回答，丢弃
                        val text = line.removeRange(charMatch.range).trim()
                        pendingAssistant?.append('\n')?.append(text)
                            ?: run { pendingAssistant = StringBuilder(text) }
                    }
                    else -> {
                        // 续行归当前说话人。
                        pendingAssistant?.append('\n')?.append(line)
                            ?: pendingUser?.append('\n')?.append(line)
                    }
                }
            }
            flushPair()
        }
        return dialogs.map { dialog ->
            PersonaExampleDialog(
                user = dialog.user
                    .replace("{{char}}", name, ignoreCase = true)
                    .replace("{{user}}", "用户", ignoreCase = true),
                assistant = dialog.assistant
                    .replace("{{char}}", name, ignoreCase = true)
                    .replace("{{user}}", "用户", ignoreCase = true)
            )
        }
    }

    // 注意：闭花括号必须转义。JVM 正则容忍裸 `}`，Android 的 ICU 引擎会直接
    // PatternSyntaxException（还炸在类初始化里）——单测跑 JVM 发现不了这个差异。
    private val USER_PREFIX = Regex("^\\{\\{user\\}\\}\\s*[:：]", RegexOption.IGNORE_CASE)
    private val CHAR_PREFIX = Regex("^\\{\\{char\\}\\}\\s*[:：]", RegexOption.IGNORE_CASE)

    private fun JsonObject.string(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
}
