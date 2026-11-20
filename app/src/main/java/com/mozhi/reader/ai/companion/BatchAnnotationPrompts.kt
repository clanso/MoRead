package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.knowledge.KnowledgeResponseJson
import com.mozhi.reader.ai.prompt.GlobalPromptInjector
import com.mozhi.reader.ai.prompt.PersonaLoreMatcher
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 模型在整段模式里写的一条批注。 */
internal data class BatchNoteDraft(
    val quote: String,
    val note: String,
    val style: String = "HIGHLIGHT",
    val voice: Boolean = false,
    val imagePrompt: String? = null
)

/** 接同伴的话：[to] 是「P1」这样的标签。 */
internal data class BatchReplyDraft(val to: String, val text: String)

internal data class BatchOutput(val notes: List<BatchNoteDraft>, val replies: List<BatchReplyDraft>)

internal object BatchAnnotationParser {
    private val thinking = Regex("(?s)<think(?:ing)?>.*?</think(?:ing)?>")
    private val digits = Regex("\\d+")

    /** 推理模型的思考过程、Markdown 围栏、JSON 前后的说明都去掉；不是 JSON 时返回 null。 */
    fun root(raw: String): JsonElement? {
        val stripped = raw.replace(thinking, "")
        val clean = runCatching { KnowledgeResponseJson.clean(stripped) }.getOrDefault(stripped.trim())
        return runCatching { AiJson.parseToJsonElement(clean) }.getOrNull()
    }

    /** null = 解析失败（这一段要重写）；空列表 = 模型这一段确实没话说。 */
    fun parse(raw: String): BatchOutput? {
        val root = root(raw) ?: return null
        val notes: List<JsonElement>
        val replies: List<JsonElement>
        when (root) {
            is JsonArray -> { notes = root; replies = emptyList() }
            is JsonObject -> {
                val list = (root["annotations"] ?: root["notes"]) as? JsonArray
                if (list == null && "quote" !in root && "replies" !in root) return null
                notes = list ?: if ("quote" in root) listOf(root) else emptyList()
                replies = (root["replies"] as? JsonArray).orEmpty()
            }
            else -> return null
        }
        return BatchOutput(
            notes.mapNotNull { (it as? JsonObject)?.let(::note) },
            replies.mapNotNull { (it as? JsonObject)?.let(::reply) }
        )
    }

    private fun note(item: JsonObject): BatchNoteDraft? {
        val quote = item.text("quote", "原文")?.trim().orEmpty()
        val note = item.text("note", "text", "批注")?.trim().orEmpty()
        if (quote.isEmpty() || note.isEmpty()) return null
        return BatchNoteDraft(
            quote = quote,
            note = note,
            style = item.text("style")?.trim()?.uppercase() ?: "HIGHLIGHT",
            voice = item.text("voice")?.trim()?.lowercase() in setOf("true", "1", "yes", "是"),
            imagePrompt = item.text("image_prompt", "imagePrompt")?.trim()?.takeIf(String::isNotEmpty)
        )
    }

    private fun reply(item: JsonObject): BatchReplyDraft? {
        val to = item.text("to", "peer")?.let(::peerTag) ?: return null
        val text = item.text("text", "reply")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (text.equals("null", true) || text == "无") return null
        return BatchReplyDraft(to, text)
    }

    /** 「P1」「p1」「1」「〔P1·阿黛尔的批注〕」都认成 P1。 */
    fun peerTag(raw: String): String? = digits.find(raw)?.value?.toIntOrNull()?.let { "P$it" }

}

/** 第一个有值的字段（字符串、数字、布尔都按文字读）。 */
private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

/** 正文按行编号，校对和定位共用。只给有字的行编号。 */
internal data class NumberedLine(val number: Int, val start: Int, val end: Int)

internal fun numberedLines(body: String, start: Int, end: Int): List<NumberedLine> {
    val lines = mutableListOf<NumberedLine>()
    var cursor = start
    while (cursor < end) {
        val newline = body.indexOf('\n', cursor).let { if (it < 0 || it > end) end else it }
        if ((cursor until newline).any { !body[it].isWhitespace() }) lines += NumberedLine(lines.size + 1, cursor, newline)
        cursor = newline + 1
    }
    return lines
}

/** 一段正文的渲染：原文原样，同伴的批注插在它引用的那一行后面，要配图的行尾标〔配图〕。 */
internal fun renderReadingChunk(body: String, start: Int, end: Int, peers: List<RecordNote>, imageLineEnds: Set<Int>): String {
    val text = body.substring(start, end)
    val anchored = peers.filter { it.end > start && it.end <= end }
        .sortedWith(compareBy<RecordNote>({ it.end }, { it.start }))
        .groupBy { AnnotationChunks.lineEnd(body, (it.end - 1).coerceIn(start, end)) }
    val out = StringBuilder(text.length + 64)
    var cursor = start
    while (cursor < end) {
        val newline = body.indexOf('\n', cursor).let { if (it < 0 || it > end) end else it }
        out.append(body, cursor, newline)
        if (newline in imageLineEnds) out.append(IMAGE_MARK)
        anchored[newline]?.forEach { out.append('\n').append(AnnotationRecordText.noteLine(it)) }
        if (newline < end) out.append('\n')
        cursor = newline + 1
    }
    return out.toString().trimEnd('\n')
}

internal const val IMAGE_MARK = "〔配图〕"

/** 模型有时把标记、行号一起抄进引文。 */
internal fun cleanQuote(quote: String): String = quote
    .replace(IMAGE_MARK, "")
    .replace(Regex("^\\s*L\\d+\\s*[:：.、]?\\s*"), "")
    .trim()

/** 这一次的数量、语音、配图、接话要求；应用按情况生成，接在可编辑的规则后面。 */
internal data class BatchContract(
    val target: Int,
    val max: Int,
    val min: Int = 0,
    val voiceMax: Int = 0,
    val image: Boolean = false,
    val peers: List<Pair<String, String>> = emptyList(),
    val maxReplies: Int = 0
)

internal fun batchContractText(contract: BatchContract): String = buildString {
    append("【本次要求】\n")
    if (contract.max > 0) {
        append("- 这一段大约写 ").append(contract.target).append(" 条批注")
        if (contract.max != Int.MAX_VALUE) append("，最多 ").append(contract.max).append(" 条")
        if (contract.min > 0) append("，至少 ").append(contract.min).append(" 条")
        append("；没什么想说的地方就少写，不要凑数。\n")
        append("- quote 从【接着读】里逐字复制，是你批注的那一小句（不要带上〔〕里的标记）；note 是批注；")
        append("style 选 HIGHLIGHT、WAVY 或 UNDERLINE。\n")
        if (contract.voiceMax > 0) {
            append("- 想用声音说出来的批注加上 \"voice\": true，最多 ").append(contract.voiceMax).append(" 条。\n")
        }
        if (contract.image) {
            append("- 行尾标着").append(IMAGE_MARK).append("的那一行要配一幅插图：在那一行挑一句写一条批注，并加上 image_prompt，")
            append("用一两句话写出这一刻的画面——谁、在哪里、在做什么、光线和气氛，人物写姓名；")
            append("只写读到这一行为止看得到的内容，不写对白、文字、画风和长相。\n")
        }
    } else {
        append("- 这一段不写新的批注，只看要不要接同伴的话。\n")
    }
    if (contract.peers.isNotEmpty() && contract.maxReplies > 0) {
        append("- 正文里〔P1·名字的批注〕这样标出的是一起读书的同伴写的批注（")
        append(contract.peers.joinToString("、") { (tag, name) -> "$tag 是$name" }).append("）。")
        append("想接谁的话，就在 replies 里写 {\"to\":\"P1\",\"text\":\"…\"}：直接对写批注的人说，一两句，不超过 80 字，")
        append("最多 ").append(contract.maxReplies).append(" 条；只是附和、复述就不必接。\n")
    }
    append("- 只输出一个 JSON 对象，例如 {\"annotations\":[{\"quote\":\"…\",\"note\":\"…\",\"style\":\"WAVY\"}]")
    if (contract.peers.isNotEmpty() && contract.maxReplies > 0) append(",\"replies\":[{\"to\":\"P1\",\"text\":\"…\"}]")
    append("}；不要 Markdown，不要其它说明。")
}

/** 设置页展示用：一次典型的追加内容。 */
internal fun sampleBatchContract(): String = batchContractText(
    BatchContract(target = 5, max = 8, voiceMax = 1, image = true, peers = listOf("P1" to "阿黛尔"), maxReplies = 2)
)

internal data class BatchPrompt(
    val persona: PersonaEntity,
    val personaMaxChars: Int,
    val rules: String,
    val presets: List<GlobalPromptPreset>,
    val bookLine: String,
    val record: String,
    val readingHeader: String,
    val reading: String,
    /** 世界书触发式条目的命中材料：这一段正文和它之前的一小段。 */
    val loreMaterial: String,
    val contract: BatchContract
)

/**
 * 整段模式的请求。系统提示词只放角色（人设、示例对话、常驻世界书）和规则，整本书写下来都不变，
 * 便于提示词缓存；阅读记录从最早的往后排，旧的部分前后两次请求也一样。
 * 会变的（被这段正文触发的世界书条目、本次要求）都放在用户消息的最后。
 */
internal fun batchAnnotationMessages(prompt: BatchPrompt): List<ChatMessage> {
    val voice = annotationPersonaBlock(prompt.persona, "", prompt.personaMaxChars, includeTriggered = false)
    val lore = PersonaLoreMatcher.block(
        PersonaLoreMatcher.triggered(prompt.persona, prompt.loreMaterial),
        header = "【相关设定】（世界书里被这一段正文触发的条目）"
    )
    val user = buildString {
        append(prompt.bookLine)
        if (prompt.record.isNotBlank()) {
            append("\n\n【阅读记录】按读的顺序排列，越早的越简略。〔批注·名字〕是你们读到那一行时写下的批注，↳ 是接话。\n")
            append(prompt.record.trim())
        }
        append("\n\n【接着读】").append(prompt.readingHeader).append('\n').append(prompt.reading)
        if (lore.isNotBlank()) append("\n\n").append(lore)
        append("\n\n").append(batchContractText(prompt.contract))
    }
    return GlobalPromptInjector.inject(
        listOf(
            ChatMessage(ChatRole.SYSTEM, voice + "\n\n" + prompt.rules.trim()),
            ChatMessage(ChatRole.USER, user)
        ),
        prompt.presets, label = "段评预设"
    )
}

internal enum class ProofAction { KEEP, REWRITE, MOVE, DROP }

internal data class ProofVerdict(val index: Int, val action: ProofAction, val note: String? = null, val quote: String? = null)

/** 交给校对的一条：批注或接话。[line] 是它落在第几行。 */
internal data class ProofItem(
    val index: Int,
    val line: Int,
    val quote: String,
    val note: String,
    val hints: List<String>,
    /** 接话时是接谁的。 */
    val replyTo: String? = null
)

internal const val PROOFREAD_CONTRACT =
    "只输出 JSON：{\"verdicts\":[{\"i\":1,\"action\":\"keep\"},{\"i\":2,\"action\":\"rewrite\",\"note\":\"…\"}," +
        "{\"i\":3,\"action\":\"move\",\"quote\":\"…\",\"note\":\"…\"},{\"i\":4,\"action\":\"drop\"}]}，每条批注都要有结果；" +
        "接话只能 keep、rewrite 或 drop。不要 Markdown，不要其它说明。"

internal fun proofreadMessages(
    rules: String,
    authorLine: String,
    readingHeader: String,
    body: String,
    lines: List<NumberedLine>,
    items: List<ProofItem>
): List<ChatMessage> {
    val user = buildString {
        append(authorLine).append("\n\n【正文】").append(readingHeader).append("，按行编号：")
        for (line in lines) append("\nL").append(line.number).append(' ').append(body, line.start, line.end)
        append("\n\n【批注】")
        for (item in items) {
            append('\n').append(item.index).append(".〔L").append(item.line)
            if (item.replyTo != null) append("，接").append(item.replyTo).append("的话").append("〕")
            else append("〕「").append(AnnotationRecordText.oneLine(item.quote)).append("」")
            append("→ ").append(AnnotationRecordText.oneLine(item.note))
            if (item.hints.isNotEmpty()) append("\n　可疑词：").append(item.hints.joinToString("、"))
        }
    }
    return listOf(
        ChatMessage(ChatRole.SYSTEM, rules.trim() + "\n" + PROOFREAD_CONTRACT),
        ChatMessage(ChatRole.USER, user)
    )
}

internal object ProofreadParser {
    /** null = 解析失败；序号对不上的条目忽略。 */
    fun parse(raw: String): Map<Int, ProofVerdict>? {
        val root = BatchAnnotationParser.root(raw) ?: return null
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["verdicts"] ?: root["results"] ?: root["items"]) as? JsonArray ?: return null
            else -> return null
        }
        val verdicts = mutableMapOf<Int, ProofVerdict>()
        for (element in list) {
            val item = element as? JsonObject ?: continue
            val index = item.text("i", "index", "n", "id")?.trim()?.toIntOrNull() ?: continue
            val action = when (item.text("action", "verdict")?.trim()?.lowercase()) {
                "keep", "保留" -> ProofAction.KEEP
                "rewrite", "改写" -> ProofAction.REWRITE
                "move", "挪", "挪动" -> ProofAction.MOVE
                "drop", "删", "删除" -> ProofAction.DROP
                else -> continue
            }
            verdicts[index] = ProofVerdict(
                index, action,
                note = item.text("note", "text")?.trim()?.takeIf(String::isNotEmpty),
                quote = item.text("quote")?.trim()?.takeIf(String::isNotEmpty)
            )
        }
        return verdicts
    }
}

/**
 * 「未卜先知」的线索：批注里只在引文那一行之后才第一次出现的词。
 * 汉字（含假名）取 2～6 个字的片段，外文取 3 个字母以上的词；长的命中了就不再报它的子串。
 * 只是给校对的提示，校对失败时才用它兜底。
 */
internal object ForesightHints {
    private val term = Regex("[\\u3040-\\u30FF\\u3400-\\u9FFF\\uF900-\\uFAFF]{2,}|[A-Za-z][A-Za-z'\\-]{2,}")

    fun suspicious(note: String, after: String, limit: Int = 5, known: (String) -> Boolean): List<String> {
        if (after.isBlank()) return emptyList()
        val found = linkedSetOf<String>()
        for (match in term.findAll(note)) {
            val run = match.value
            if (run[0].isLetter() && run[0].code < 0x80) {
                if (after.contains(run, ignoreCase = true) && !known(run)) found += run
                continue
            }
            for (length in minOf(6, run.length) downTo 2) {
                for (start in 0..run.length - length) {
                    val piece = run.substring(start, start + length)
                    if (found.any { piece in it }) continue
                    if (after.contains(piece) && !known(piece)) found += piece
                }
            }
            if (found.size >= limit) break
        }
        return found.take(limit)
    }

    /** 校对没成功时，哪些批注宁可不要：有三个字以上的可疑词，或者可疑词不止一个。 */
    fun strong(hints: List<String>): Boolean = hints.size >= 2 || hints.any { it.length >= 3 }
}
