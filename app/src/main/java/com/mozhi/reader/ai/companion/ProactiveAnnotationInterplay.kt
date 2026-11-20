package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.knowledge.KnowledgeResponseJson
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.dao.AnnotationDao
import com.mozhi.reader.core.database.entity.PersonaEntity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ProactivePeerNote(
    val annotationId: Long,
    val personaId: Long,
    val personaName: String,
    val personaTraits: String,
    val start: Int,
    val end: Int,
    val quote: String,
    val note: String,
    val hasPersonaReply: Boolean,
    /** 引文可能只摘了半段；接话还要检查对方写段评时实际读到的终点，避免借段评泄露后文。 */
    val sourceEnd: Int = end
)

internal suspend fun loadProactivePeerNotes(
    dao: AnnotationDao, personas: PersonaRepository, bookId: Long, chapterIndex: Int, personaId: Long
): List<ProactivePeerNote> {
    val rows = dao.getForChapter(bookId, chapterIndex).filter {
        it.personaId != null && it.personaId != personaId && it.proactiveJobId != null
    }
    if (rows.isEmpty()) return emptyList()
    // 批量读讨论串；用户留言不占角色接话位，已经删除的角色留言仍占位。
    val replied = rows.map { it.id }.chunked(900).flatMap { dao.getRepliesForAnnotations(it) }
        .filter { it.personaId != null }.map { it.annotationId }.toSet()
    val authors = rows.mapNotNull { it.personaId }.distinct().associateWith { personas.getPersona(it) }
    return rows.mapNotNull { row ->
        val author = authors[row.personaId] ?: return@mapNotNull null
        ProactivePeerNote(
            annotationId = row.id, personaId = author.id, personaName = author.name,
            personaTraits = listOf(author.personality, author.speakingStyle).filter { it.isNotBlank() }
                .joinToString(" ").replace(Regex("\\s+"), " ").trim().take(160),
            start = row.startCharOffset, end = row.endCharOffset, quote = row.selectedText, note = row.note,
            hasPersonaReply = row.id in replied,
            sourceEnd = when {
                row.sourceScopeChapterIndex == chapterIndex -> row.sourceScopeCharOffset ?: Int.MAX_VALUE
                row.sourceScopeChapterIndex != null && row.sourceScopeChapterIndex < chapterIndex -> row.endCharOffset
                else -> Int.MAX_VALUE
            }
        )
    }
}

/** 轮换楼主，避免同一个角色总是先占段落；关闭互见时保留原顺序。 */
internal fun annotationPersonaOrder(personaIds: List<Long>, chapterIndex: Int, interplay: Boolean): List<Long> {
    if (!interplay || personaIds.size < 2) return personaIds
    val start = Math.floorMod(chapterIndex, personaIds.size)
    return personaIds.drop(start) + personaIds.take(start)
}

internal object ProactiveReplyParser {
    /** null 结果代表解析失败；结果中的 null 代表角色明确不接话。 */
    data class Parsed(val reply: String?)
    private val thinking = Regex("(?s)<think(?:ing)?>.*?</think(?:ing)?>")
    private val lineBreaks = Regex("[\\r\\n\\u0085\\u2028\\u2029]+")

    fun parse(raw: String): Parsed? {
        val clean = runCatching { KnowledgeResponseJson.clean(raw.replace(thinking, "")) }
            .getOrDefault(raw.trim())
        val root = runCatching { AiJson.parseToJsonElement(clean) }.getOrNull() as? JsonObject ?: return null
        val value = root["reply"] ?: return null
        if (value == JsonNull) return Parsed(null)
        if (value !is JsonPrimitive || !value.isString) return null
        val text = value.content.replace(lineBreaks, " ").trim()
        return Parsed(text.takeUnless {
            it.isBlank() || it.length > 200 || it.equals("null", true) || it == "无" || it.equals("pass", true)
        })
    }
}

internal fun proactiveReplyMessages(
    persona: PersonaEntity, peer: ProactivePeerNote, prefix: String, target: String, background: String,
    personaMaxChars: Int = com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings.UNLIMITED,
    rulesTemplate: String = com.mozhi.reader.core.datastore.AnnotationPromptTemplates.default(
        com.mozhi.reader.core.datastore.AnnotationPromptTemplates.REPLY_RULES)
): List<ChatMessage> {
    val voice = annotationPersonaBlock(persona, listOf(background, prefix, target, peer.note).joinToString("\n"), personaMaxChars)
    val rules = rulesTemplate.trim().replace("{{peer}}", peer.personaName) +
        "\n只输出一个 JSON 对象，字段为 reply（字符串或 null）。不要 Markdown 或额外解释。"
    return listOf(
        ChatMessage(ChatRole.SYSTEM, voice + "\n\n" + rules),
        ChatMessage(ChatRole.USER, buildString {
            if (background.isNotBlank()) append("相关前文资料（均早于本章）：\n").append(background).append("\n\n")
            append("正文前缀（只到目标结束）：\n").append(prefix)
            append("\n\n目标段落：\n").append(target)
            append("\n\n「").append(peer.personaName).append("」")
            if (peer.personaTraits.isNotBlank()) append("（").append(peer.personaTraits).append("）")
            append("划的原文：\n").append(peer.quote)
            append("\n「").append(peer.personaName).append("」的段评：\n").append(peer.note)
        })
    )
}
