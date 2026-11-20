package com.mozhi.reader.ai.companion

import androidx.room.withTransaction
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.ProactiveAnnotationJobEntity
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.text.QuoteMatcher
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 批注包：一本书的全部批注（含伴读的接话）存成一个 JSON 文件，换手机、在别处写好了都能导进来接着看。
 * 只带文字，语音和配图不带。
 */
@Serializable
data class AnnotationPack(
    val format: String = AnnotationPackService.FORMAT,
    val version: Int = 1,
    val exportedAt: Long = 0,
    val book: PackBook,
    val personas: List<PackPersona> = emptyList(),
    val chapters: List<PackChapter> = emptyList(),
    val annotations: List<PackAnnotation> = emptyList()
)

@Serializable
data class PackBook(val title: String, val author: String = "", val chapters: Int = 0)

@Serializable
data class PackPersona(val key: String, val name: String)

@Serializable
data class PackChapter(val index: Int, val title: String = "", val chars: Int = 0, val sha: String = "")

@Serializable
data class PackAnnotation(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val note: String = "",
    val style: String = "HIGHLIGHT",
    /** [PackPersona.key]；null = 读者自己写的。 */
    val persona: String? = null,
    /** 写这条批注时读到的位置（同一章内）；导入后按引文挪过去。 */
    val scope: Int? = null,
    val createdAt: Long = 0,
    val replies: List<PackReply> = emptyList()
)

@Serializable
data class PackReply(val persona: String? = null, val text: String, val createdAt: Long = 0)

data class AnnotationPackImport(val imported: Int, val replies: Int, val skipped: Int, val duplicates: Int, val newPersonas: List<String>)

@Singleton
class AnnotationPackService @Inject constructor(
    private val database: MoReadDatabase,
    private val library: LibraryRepository,
    private val personas: PersonaRepository
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    /** 返回导出的条数。 */
    suspend fun export(bookId: Long, output: OutputStream): Int {
        val book = library.getBook(bookId) ?: error("书籍已不存在")
        val chapters = library.getChapters(bookId).sortedBy { it.chapterIndex }
        val dao = database.annotationDao()
        val rows = dao.getForChapterRange(bookId, 0, Int.MAX_VALUE)
        val replies = rows.map { it.id }.chunked(900).flatMap { dao.getRepliesForAnnotations(it) }.groupBy { it.annotationId }
        val authorIds = (rows.mapNotNull { it.personaId } + replies.values.flatten().mapNotNull { it.personaId }).distinct()
        val keys = authorIds.mapIndexed { index, id -> id to "p${index + 1}" }.toMap()
        val packPersonas = authorIds.mapNotNull { id -> personas.getPersona(id)?.let { PackPersona(keys.getValue(id), it.name) } }
        val known = packPersonas.map { it.key }.toSet()
        fun keyOf(id: Long?): String? = id?.let(keys::get)?.takeIf { it in known }
        val pack = AnnotationPack(
            exportedAt = System.currentTimeMillis(),
            book = PackBook(book.title, book.author, chapters.size),
            personas = packPersonas,
            chapters = chapters.map { chapter ->
                val text = runCatching { library.readChapterText(bookId, chapter) }.getOrDefault("")
                PackChapter(chapter.chapterIndex, chapter.title, text.length,
                    if (text.isEmpty()) "" else ProactiveAnnotationParagraphs.revision(text))
            },
            annotations = rows.map { row ->
                PackAnnotation(
                    chapter = row.chapterIndex, start = row.startCharOffset, end = row.endCharOffset,
                    quote = row.selectedText, note = row.note, style = row.style, persona = keyOf(row.personaId),
                    scope = row.sourceScopeCharOffset?.takeIf { row.sourceScopeChapterIndex == row.chapterIndex },
                    createdAt = row.createdAt,
                    replies = replies[row.id].orEmpty().filter { it.contentMarkdown.isNotBlank() }
                        .map { PackReply(keyOf(it.personaId), it.contentMarkdown, it.createdAt) }
                )
            }
        )
        output.write(json.encodeToString(AnnotationPack.serializer(), pack).toByteArray(Charsets.UTF_8))
        output.flush()
        return pack.annotations.size
    }

    /**
     * 导进 [bookId]。章节先按序号对，序号对不上按章名找，再不行全书找引文；引文以本机正文为准，
     * 对不上原文的跳过。伴读按名字对应本机的角色，没有同名的就新建一个只有名字的角色（之后可以在角色页补人设）。
     * 导入了伴读批注的章节记成「整段模式已写完」，随读段评不会在这些章节再写一遍。
     */
    suspend fun import(bookId: Long, input: InputStream): AnnotationPackImport {
        val raw = input.readBytes().toString(Charsets.UTF_8)
        val pack = runCatching { json.decodeFromString(AnnotationPack.serializer(), raw) }
            .getOrElse { error("不是墨知的批注包") }
        require(pack.format == FORMAT) { "不是墨知的批注包" }
        library.getBook(bookId) ?: error("书籍已不存在")
        val chapters = library.getChapters(bookId).sortedBy { it.chapterIndex }
        val texts = HashMap<Int, String>()
        suspend fun textOf(index: Int): String? = texts[index] ?: chapters.firstOrNull { it.chapterIndex == index }
            ?.let { runCatching { library.readChapterText(bookId, it) }.getOrNull() }?.also { texts[index] = it }

        // 伴读：按名字对应，没有就新建。
        val existing = personas.getPersonas()
        val created = mutableListOf<String>()
        val personaIds = HashMap<String, Long>()
        for (persona in pack.personas) {
            val match = existing.firstOrNull { it.name.trim() == persona.name.trim() }
            personaIds[persona.key] = match?.id ?: personas.save(
                PersonaEntity(name = persona.name.trim().ifEmpty { "伴读" }, personality = "", isRoleplay = true,
                    createdAt = System.currentTimeMillis())
            ).also { created += persona.name }
        }

        data class Located(val chapter: Int, val start: Int, val end: Int)
        val titles = pack.chapters.associate { it.index to it.title }
        suspend fun locate(item: PackAnnotation): Located? {
            val quote = item.quote
            if (quote.isBlank()) return null
            val candidates = buildList {
                add(item.chapter)
                titles[item.chapter]?.takeIf(String::isNotBlank)?.let { title ->
                    chapters.filter { it.title.trim() == title.trim() }.forEach { add(it.chapterIndex) }
                }
            }.distinct()
            for (index in candidates) {
                val text = textOf(index) ?: continue
                if (item.start in 0..text.length && item.end in item.start..text.length &&
                    text.substring(item.start, item.end) == quote) return Located(index, item.start, item.end)
                val from = (item.start - SEARCH_SLACK).coerceIn(0, text.length)
                val near = QuoteMatcher.find(text.substring(from), quote)?.let { QuoteMatcher.Span(it.start + from, it.end + from) }
                val span = near ?: QuoteMatcher.find(text, quote)
                if (span != null) return Located(index, span.start, span.end)
            }
            for (chapter in chapters) {
                if (chapter.chapterIndex in candidates) continue
                val text = textOf(chapter.chapterIndex) ?: continue
                QuoteMatcher.find(text, quote)?.let { return Located(chapter.chapterIndex, it.start, it.end) }
            }
            return null
        }

        val located = pack.annotations.map { it to locate(it) }
        var imported = 0
        var repliesImported = 0
        var duplicates = 0
        val skipped = located.count { it.second == null }
        val annotationDao = database.annotationDao()
        val writtenChapters = mutableSetOf<Pair<Int, Long>>()
        database.withTransaction {
            val present = HashMap<Int, List<AnnotationEntity>>()
            for ((item, place) in located) {
                place ?: continue
                val text = texts[place.chapter] ?: continue
                val personaId = item.persona?.let(personaIds::get)
                val rows = present.getOrPut(place.chapter) { annotationDao.getForChapter(bookId, place.chapter) }
                if (rows.any { it.startCharOffset == place.start && it.endCharOffset == place.end && it.note == item.note && it.personaId == personaId }) {
                    duplicates++
                    continue
                }
                val scope = item.scope?.let { (it - item.end + place.end).coerceIn(place.end, text.length) }
                    ?: AnnotationChunks.lineEnd(text, place.end - 1)
                val id = annotationDao.insert(AnnotationEntity(
                    bookId = bookId, personaId = personaId, chapterIndex = place.chapter,
                    startCharOffset = place.start, endCharOffset = place.end,
                    selectedText = text.substring(place.start, place.end), note = item.note,
                    colorTag = personaId?.let(AnnotationColors::forPersona).orEmpty(),
                    style = AnnotationStyle.fromWire(item.style).wire,
                    sourceScopeChapterIndex = personaId?.let { place.chapter },
                    sourceScopeCharOffset = personaId?.let { scope },
                    createdAt = item.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                ))
                imported++
                if (personaId != null) writtenChapters += place.chapter to personaId
                for (reply in item.replies) {
                    if (reply.text.isBlank()) continue
                    annotationDao.insertReply(AnnotationReplyEntity(
                        annotationId = id, personaId = reply.persona?.let(personaIds::get), replyToId = null,
                        contentMarkdown = reply.text, createdAt = reply.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    ))
                    repliesImported++
                }
            }
            // 导入了伴读批注的章节记成整段模式写完了，随读段评不会在上面再叠一层。
            val jobs = database.proactiveAnnotationJobDao()
            val now = System.currentTimeMillis()
            for ((chapter, personaId) in writtenChapters) {
                val text = texts[chapter] ?: continue
                if (text.isEmpty()) continue
                val revision = BatchAnnotationEngine.BATCH_REVISION_PREFIX + ProactiveAnnotationParagraphs.revision(text)
                val ledger = DoneRanges.encode(listOf(DoneRange(0, text.length)))
                val old = jobs.find(bookId, chapter, personaId, revision)
                if (old == null) jobs.insert(ProactiveAnnotationJobEntity(
                    bookId = bookId, chapterIndex = chapter, personaId = personaId, sourceRevision = revision,
                    status = "DONE", doneParagraphEnds = ledger, failureReason = "imported", createdAt = now, updatedAt = now
                )) else jobs.update(old.copy(status = "DONE", doneParagraphEnds = ledger, updatedAt = now))
            }
        }
        return AnnotationPackImport(imported, repliesImported, skipped, duplicates, created)
    }

    companion object {
        const val FORMAT = "moread.annotation-pack"
        private const val SEARCH_SLACK = 2_000
    }
}
