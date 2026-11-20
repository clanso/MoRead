package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.AnnotationMemoryEntity
import com.mozhi.reader.core.database.entity.ModelRole
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeout

/**
 * 阅读记录的回顾和前情提要：只由正文推出，与角色无关，每块只写一次，存进 annotation_memories 反复用。
 * 原文变了（[textRevision] 对不上）就当作没有，下次用到时重写。
 */
@Singleton
class AnnotationMemoryStore @Inject constructor(
    private val database: MoReadDatabase,
    private val clientFactory: AiClientFactory
) {
    suspend fun all(bookId: Long): Map<String, AnnotationMemoryEntity> =
        database.annotationMemoryDao().getAll(bookId).associateBy { it.cacheKey }

    internal suspend fun writeRecap(
        bookId: Long,
        bookLine: String,
        piece: RecordPiece,
        revision: String,
        percent: Int,
        template: String
    ): AnnotationMemoryEntity? {
        val target = AnnotationRecordText.recapTarget(piece.text.length, percent)
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, template.trim() + "\n这一份回顾写 ${target * 4 / 5}～${target * 6 / 5} 字。"),
            ChatMessage(ChatRole.USER, bookLine + "\n" + piece.label + "\n\n" + piece.text)
        )
        val text = complete(messages, RECAP_TIMEOUT_MS) ?: return null
        return save(bookId, piece.memoryKey, revision, AnnotationRecordText.safeTake(text, target * 2))
    }

    internal suspend fun writeArc(
        bookId: Long,
        bookLine: String,
        key: String,
        label: String,
        recaps: List<Pair<String, String>>,
        revision: String,
        target: Int,
        template: String
    ): AnnotationMemoryEntity? {
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, template.trim() + "\n这一份提要不超过 $target 字。"),
            ChatMessage(ChatRole.USER, bookLine + "\n" + label + "\n\n" +
                recaps.joinToString("\n\n") { (title, recap) -> "◆ $title\n$recap" })
        )
        val text = complete(messages, ARC_TIMEOUT_MS) ?: return null
        return save(bookId, key, revision, AnnotationRecordText.safeTake(text, target * 3 / 2))
    }

    private suspend fun complete(messages: List<ChatMessage>, timeout: Long): String? {
        val resolved = clientFactory.forRole(ModelRole.PROACTIVE_ANNOTATION)
        val raw = withTimeout(timeout) { resolved.client.chat(messages = messages, options = resolved.options) }
        return clean(raw).takeIf { it.length >= MIN_CHARS }
    }

    private suspend fun save(bookId: Long, key: String, revision: String, content: String): AnnotationMemoryEntity {
        val row = AnnotationMemoryEntity(bookId, key, revision, content, System.currentTimeMillis())
        database.annotationMemoryDao().upsert(row)
        return row
    }

    internal companion object {
        const val RECAP_TIMEOUT_MS = 180_000L
        const val ARC_TIMEOUT_MS = 120_000L
        private const val MIN_CHARS = 20
        private val thinking = Regex("(?s)<think(?:ing)?>.*?</think(?:ing)?>")
        private val fence = Regex("^```[a-zA-Z]*\\s*|\\s*```$")
        private val heading = Regex("^(?:#+\\s*)?(?:回顾|前情提要|章节回顾|提要)[：:]?\\s*")

        fun clean(raw: String): String = raw.replace(thinking, "").trim()
            .replace(fence, "").trim()
            .replace(heading, "").trim()

        fun textRevision(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    }
}
