package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationMemoryEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.ProactiveAnnotationJobEntity
import com.mozhi.reader.core.datastore.AnnotationPromptTemplates
import com.mozhi.reader.core.datastore.AnnotationTemplateSet
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.core.datastore.ProactiveAnnotationAllowance
import com.mozhi.reader.core.datastore.ProactiveAnnotationLimits
import com.mozhi.reader.core.library.AnnotationMedia
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.text.QuoteMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/** 整段模式写一章：哪本书、哪一章、哪几位伴读（按轮流的先后）、按什么规矩。 */
data class BatchChapterRequest(
    val bookId: Long,
    val chapterIndex: Int,
    val personaIds: List<Long>,
    val limits: ProactiveAnnotationLimits,
    val prompts: List<GlobalPromptPreset>,
    val templates: AnnotationTemplateSet,
    val voice: Boolean,
    val images: Boolean,
    val interplay: Boolean,
    /** 整本生成：不看每日上限（每章上限照旧）。 */
    val ignoreDailyCap: Boolean = false,
    /** 失败过两次的章节也再试一次（用户手动开始整本生成时）。 */
    val retryFailed: Boolean = false,
    /** 只从这里往后写（进入章节时读者所在的位置）；之前的留着，读者回头时再补。 */
    val startAt: Int = 0,
    /** 每轮最多写几段，写不完的留给下一轮（预生成时让出唯一的 worker）。 */
    val maxChunksPerPass: Int = Int.MAX_VALUE
)

/** 调用方（随读调度、整本生成）决定「还能不能写、还能写多少」。 */
interface BatchGate {
    /** 每次付费调用前都要过：关掉了段评、换了书、换了伴读……就停下。 */
    suspend fun valid(): Boolean
    /** 同步版本，在数据库事务里用。 */
    fun stillValid(): Boolean
    /** 今天还能写多少；null = 不能写了。 */
    suspend fun allowance(): ProactiveAnnotationAllowance?
    /** 读者翻到别的章了，先让出去。 */
    fun shouldYield(): Boolean = false
    /** 读者此刻在本章的位置；不在本章或不跟随读者时为 null。 */
    suspend fun readerOffset(): Int? = null
    suspend fun recordQuota(annotations: Int, voices: Int, images: Int) {}
    suspend fun imageFailed() {}
}

data class BatchChapterOutcome(
    val failed: Boolean = false,
    val stopped: Boolean = false,
    val yielded: Boolean = false,
    val hasMore: Boolean = false,
    /** 有失败、还能再试的伴读。 */
    val retryable: Boolean = false,
    /** 每位伴读这一次新写的批注（接话记在被接的那条上）。 */
    val created: Map<Long, List<Long>> = emptyMap()
)

/** 账本：这一章里某位伴读已经读过、写过的范围 [start, end)，按 [s1, e1, s2, e2, …] 存。 */
internal data class DoneRange(val start: Int, val end: Int)

internal object DoneRanges {
    private val json = Json

    /** null = 账本坏了。 */
    fun decode(raw: String?, length: Int): List<DoneRange>? {
        val values = runCatching { json.decodeFromString<List<Int>>(raw ?: "[]") }.getOrNull() ?: return null
        if (values.size % 2 != 0) return null
        val ranges = values.chunked(2).map { DoneRange(it[0], it[1]) }
        if (ranges.any { it.start < 0 || it.end > length || it.start >= it.end }) return null
        return merge(ranges)
    }

    fun encode(ranges: List<DoneRange>): String = json.encodeToString(merge(ranges).flatMap { listOf(it.start, it.end) })

    fun merge(ranges: List<DoneRange>): List<DoneRange> {
        val sorted = ranges.sortedBy { it.start }
        val out = mutableListOf<DoneRange>()
        for (range in sorted) {
            val last = out.lastOrNull()
            if (last != null && range.start <= last.end) out[out.lastIndex] = DoneRange(last.start, maxOf(last.end, range.end))
            else out += range
        }
        return out
    }

    /** [start, end) 里还没读过的部分。 */
    fun pending(done: List<DoneRange>, start: Int, end: Int): List<DoneRange> {
        val out = mutableListOf<DoneRange>()
        var cursor = start
        for (range in done) {
            if (range.end <= cursor) continue
            if (range.start >= end) break
            if (range.start > cursor) out += DoneRange(cursor, minOf(range.start, end))
            cursor = maxOf(cursor, range.end)
            if (cursor >= end) break
        }
        if (cursor < end) out += DoneRange(cursor, end)
        return out
    }
}

/**
 * 整段模式的段评引擎。一章切成几段（默认五千字一段，切在换行处），几位伴读一段一段轮流读：
 * 每段一次请求写出这一段的全部批注，写完另发一次请求做二次校对，把用到后文的批注改掉、挪走或删掉，
 * 校对过的才落库。下一段（下一位伴读）读的时候，阅读记录里已经有前面所有人写下的批注，
 * 猜过的事有了下文就能接上。
 *
 * 随读调度和整本生成共用这里，同一本书同一时刻只有一处在写（[lockFor]）。
 */
@Singleton
class BatchAnnotationEngine @Inject constructor(
    private val database: MoReadDatabase,
    private val library: LibraryRepository,
    private val personas: PersonaRepository,
    private val clientFactory: AiClientFactory,
    private val mediaService: AiMediaGenerationService,
    private val memory: AnnotationMemoryStore
) {
    private val locks = HashMap<Long, Mutex>()
    private val commits = ProactiveAnnotationCommitStore(database)
    private val materials = object : LinkedHashMap<Long, BookMaterial>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BookMaterial>?) = size > 2
    }

    fun lockFor(bookId: Long): Mutex = synchronized(locks) { locks.getOrPut(bookId) { Mutex() } }

    suspend fun runChapter(request: BatchChapterRequest, gate: BatchGate): BatchChapterOutcome =
        lockFor(request.bookId).withLock { runLocked(request, gate) }

    /** 一本书读过的各章正文，按需读、读过的留着；正文版本变了整份换掉。 */
    private class BookMaterial(val bookId: Long, val revision: String) {
        val texts = HashMap<Int, String>()
        val titles = HashMap<Int, String>()
        val hashes = HashMap<String, String>()
    }

    private suspend fun materialFor(bookId: Long): BookMaterial {
        val revision = library.bookTextRevision(bookId)
        synchronized(materials) {
            materials[bookId]?.takeIf { it.revision == revision }?.let { return it }
            return BookMaterial(bookId, revision).also { materials[bookId] = it }
        }
    }

    private suspend fun chapterText(material: BookMaterial, index: Int): String? {
        material.texts[index]?.let { return it }
        val chapter = library.getChapter(material.bookId, index) ?: return null
        val text = library.readChapterText(material.bookId, chapter)
        material.texts[index] = text
        material.titles[index] = chapter.title
        return text
    }

    private class PersonaRun(
        val persona: PersonaEntity,
        var job: ProactiveAnnotationJobEntity,
        var done: List<DoneRange>,
        var produced: Int,
        val cap: Int
    ) {
        var failed = false
        var capped = false
        val created = mutableListOf<Long>()
    }

    /** 一条落了位的批注。 */
    private class Placed(
        var start: Int,
        var end: Int,
        var note: String,
        val style: String,
        val voice: Boolean,
        val imagePrompt: String?
    ) {
        var audioPath: String? = null
        var illustration: IllustrationEntity? = null
    }

    private class PlacedReply(val peer: AnnotationEntity, val tag: String, val author: String, var text: String)

    private enum class RangeResult { DONE, FAIL, STOP, CAPPED }

    /** 这一章这一次的全部材料。 */
    private inner class ChapterRun(
        val request: BatchChapterRequest,
        val gate: BatchGate,
        val material: BookMaterial,
        val bookLine: String,
        val chapterTitle: String,
        val body: String,
        val revision: String,
        val memories: MutableMap<String, AnnotationMemoryEntity>,
        val priorPieces: List<RecordPiece>,
        val imageTargets: Set<Int>,
        val names: MutableMap<Long, String>
    ) {
        var newRecaps = 0
        var newArcs = 0
        var imageBackend: Boolean? = null
    }

    private suspend fun runLocked(request: BatchChapterRequest, gate: BatchGate): BatchChapterOutcome {
        val book = library.getBook(request.bookId) ?: return BatchChapterOutcome(stopped = true)
        val chapter = library.getChapter(request.bookId, request.chapterIndex) ?: return BatchChapterOutcome(stopped = true)
        val body = library.readChapterText(request.bookId, chapter)
        if (body.isBlank()) return BatchChapterOutcome()
        val settings = request.limits.batch
        val revision = BATCH_REVISION_PREFIX + ProactiveAnnotationParagraphs.revision(body)
        val from = AnnotationChunks.lineStart(body, request.startAt.coerceIn(0, body.length))
        val chunks = AnnotationChunks.split(body, settings.chunkChars)
        val jobs = database.proactiveAnnotationJobDao()
        val annotationDao = database.annotationDao()
        val cap = if (request.limits.chapterUnlimited) Int.MAX_VALUE else request.limits.maxPerChapter
        fun pendingText(done: List<DoneRange>): Boolean = chunks.any { chunk ->
            DoneRanges.pending(done, maxOf(chunk.start, from), chunk.end).any { range ->
                AnnotationChunks.hasText(body, AnnotationChunk(0, range.start, range.end))
            }
        }

        val runs = mutableListOf<PersonaRun>()
        for (personaId in request.personaIds.distinct()) {
            val persona = personas.getPersona(personaId) ?: continue
            val old = jobs.find(request.bookId, request.chapterIndex, personaId, revision)
            val done = if (old == null) emptyList() else DoneRanges.decode(old.doneParagraphEnds, body.length)
            if (old != null && done == null) {
                jobs.update(old.copy(status = "FAILED", attempts = 2, failureReason = "invalid_range_ledger",
                    updatedAt = System.currentTimeMillis()))
                continue
            }
            val produced = (old?.let { annotationDao.countProactiveForJob(it.id) } ?: 0) +
                annotationDao.countPersonaRepliesForChapter(request.bookId, request.chapterIndex, personaId)
            val remaining = produced < cap && pendingText(done.orEmpty())
            val attemptable = old?.let { if (request.retryFailed) it.copy(attempts = 0) else it }
            if (attemptable != null && !attemptable.canAttempt(remaining)) continue
            if (!remaining) {
                // 上限到了或者全读过了：停在半路的账本收个尾，下次进来不再白跑一趟。
                if (old != null && old.status != "DONE") {
                    jobs.update(old.copy(status = "DONE", failureReason = null, updatedAt = System.currentTimeMillis()))
                }
                continue
            }
            val now = System.currentTimeMillis()
            var job = (attemptable ?: ProactiveAnnotationJobEntity(
                bookId = request.bookId, chapterIndex = request.chapterIndex, personaId = personaId,
                sourceRevision = revision, createdAt = now, updatedAt = now
            )).copy(status = "PENDING", updatedAt = now, failureReason = null)
            if (old == null) {
                val id = jobs.insert(job)
                if (id == -1L) continue
                job = job.copy(id = id)
            } else jobs.update(job)
            runs += PersonaRun(persona, job, done.orEmpty(), produced, cap)
        }
        if (runs.isEmpty()) return BatchChapterOutcome()

        val material = materialFor(request.bookId)
        material.texts[request.chapterIndex] = body
        material.titles[request.chapterIndex] = chapter.title
        val names = HashMap<Long, String>()
        runs.forEach { names[it.persona.id] = it.persona.name }
        val priorNotes = loadNotes(request.bookId, 0, request.chapterIndex - 1)
            .filter { visibleAt(it.first, request.chapterIndex, 0) }
        for ((row, replies) in priorNotes) {
            nameOf(names, row.personaId)
            replies.forEach { nameOf(names, it.personaId) }
        }
        val priorPieces = buildList {
            for (index in 0 until request.chapterIndex) {
                val text = chapterText(material, index) ?: continue
                if (text.isBlank()) continue
                val notes = priorNotes.filter { it.first.chapterIndex == index }.map { recordNote(it, names) }
                addAll(piecesOf(index, material.titles[index].orEmpty(), text, 0, text.length, notes, current = false))
            }
        }
        val imageTargets = if (request.images && request.limits.maxImagesPerChapter > 0) {
            ProactiveAnnotationImagery.targets(
                body, ProactiveAnnotationParagraphs.buckets(body, request.limits.maxImagesPerChapter * 3),
                request.limits.maxImagesPerChapter
            )
        } else emptySet()
        val context = ChapterRun(
            request, gate, material,
            bookLine = "《" + book.title.trim() + "》" + book.author.trim().takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty(),
            chapterTitle = chapter.title, body = body, revision = revision,
            memories = memory.all(request.bookId).toMutableMap(), priorPieces = priorPieces,
            imageTargets = imageTargets, names = names
        )

        var stopped = false
        var yielded = false
        var hasMore = false
        var passChunks = 0
        outer@ for (chunk in chunks) {
            val work = runs.associateWith { run ->
                if (run.capped) emptyList()
                else DoneRanges.pending(run.done, maxOf(chunk.start, from), chunk.end).filter {
                    AnnotationChunks.hasText(body, AnnotationChunk(0, it.start, it.end))
                }
            }
            if (work.values.all { it.isEmpty() }) continue
            if (passChunks >= request.maxChunksPerPass.coerceAtLeast(1)) { hasMore = true; break }
            var did = false
            for (run in runs) {
                for (range in work.getValue(run)) {
                    if (run.capped) break
                    // 读者已经翻过去的部分写好也只会亮在回不去的页上，留到读者回头时再写。
                    val frontier = gate.readerOffset()?.let { AnnotationChunks.lineStart(body, it.coerceIn(0, body.length)) }
                    if (frontier != null && range.end <= frontier) continue
                    val start = if (frontier != null && range.start < frontier) frontier else range.start
                    if (!AnnotationChunks.hasText(body, AnnotationChunk(0, start, range.end))) continue
                    if (gate.shouldYield()) { yielded = true; stopped = true; break@outer }
                    if (!valid(context)) { stopped = true; break@outer }
                    when (processRange(context, run, start, range.end, from)) {
                        RangeResult.DONE -> did = true
                        RangeResult.FAIL -> run.failed = true
                        RangeResult.CAPPED -> run.capped = true
                        RangeResult.STOP -> { stopped = true; break@outer }
                    }
                }
            }
            if (did) passChunks++
        }

        var retryable = false
        val now = System.currentTimeMillis()
        for (run in runs) {
            val job = when {
                stopped -> run.job.copy(status = "PAUSED", failureReason = if (yielded) "yielded_to_reader" else "policy_lifecycle_or_quota")
                hasMore && !run.capped && pendingText(run.done) -> run.job.copy(status = "PAUSED", failureReason = "sharing_lookahead_worker")
                run.failed -> run.job.copy(status = "FAILED", attempts = run.job.attempts + 1, failureReason = "generation_failed")
                else -> run.job.copy(status = "DONE", failureReason = null)
            }.copy(updatedAt = now)
            jobs.update(job)
            if (job.status == "FAILED" && job.canAttempt()) retryable = true
        }
        return BatchChapterOutcome(
            failed = runs.any { it.failed }, stopped = stopped, yielded = yielded, hasMore = hasMore && !stopped,
            retryable = retryable, created = runs.filter { it.created.isNotEmpty() }.associate { it.persona.id to it.created.toList() }
        )
    }

    /** 正文没变、调用方也还要它写。 */
    private suspend fun valid(context: ChapterRun): Boolean {
        if (!context.gate.valid()) return false
        val chapter = library.getChapter(context.request.bookId, context.request.chapterIndex) ?: return false
        val body = runCatching { library.readChapterText(context.request.bookId, chapter) }.getOrNull() ?: return false
        return BATCH_REVISION_PREFIX + ProactiveAnnotationParagraphs.revision(body) == context.revision && context.gate.valid()
    }

    private suspend fun processRange(context: ChapterRun, run: PersonaRun, start: Int, end: Int, from: Int): RangeResult {
        val request = context.request
        val gate = context.gate
        val body = context.body
        val settings = request.limits.batch
        val permit = gate.allowance() ?: return RangeResult.STOP
        if (!permit.accepted || permit.maxAnnotations <= 0) return RangeResult.STOP
        val capLeft = if (run.cap == Int.MAX_VALUE) Int.MAX_VALUE else run.cap - run.produced
        if (capLeft <= 0) return RangeResult.CAPPED

        // 这一段写几条：按字数给个节奏；有每章上限时按还没读的字数分摊，免得前一段把整章的额度用完。
        val length = end - start
        val pace = maxOf(1, (length + settings.charsPerNote / 2) / settings.charsPerNote)
        val remainingLength = DoneRanges.pending(run.done, maxOf(start, from), body.length).sumOf { it.end - it.start }
            .coerceAtLeast(length)
        var max = if (capLeft == Int.MAX_VALUE) maxOf(pace * 2, pace + 2)
        else ceilDiv(capLeft.toLong() * length, remainingLength.toLong()).coerceIn(1, capLeft)
        if (permit.maxAnnotations != Int.MAX_VALUE) max = minOf(max, permit.maxAnnotations)
        val target = minOf(pace, max)
        val min = if (request.limits.minPerChapter > 0) {
            minOf(ceilDiv(request.limits.minPerChapter.toLong() * length, body.length.toLong()), max)
        } else 0

        // 同一章里的批注：这一段之前的进阅读记录，这一段里同伴写的可以接话。
        val chapterNotes = loadNotes(request.bookId, request.chapterIndex, request.chapterIndex)
        for ((row, replies) in chapterNotes) {
            nameOf(context.names, row.personaId)
            replies.forEach { nameOf(context.names, it.personaId) }
        }
        val before = chapterNotes.filter { (row, _) -> row.endCharOffset <= start && visibleAt(row, request.chapterIndex, start) }
            .map { recordNote(it, context.names) }
        val peerRows = if (request.interplay) chapterNotes.filter { (row, replies) ->
            row.personaId != null && row.personaId != run.persona.id && row.note.isNotBlank() &&
                row.endCharOffset > start && row.endCharOffset <= end && (row.sourceScopeCharOffset ?: Int.MAX_VALUE) <= end &&
                row.sourceScopeChapterIndex == request.chapterIndex && replies.none { it.personaId != null }
        }.take(MAX_PEERS) else emptyList()
        val peers = peerRows.mapIndexed { index, pair ->
            recordNote(pair, context.names).copy(tag = "P${index + 1}", replies = emptyList())
        }
        val maxReplies = if (peers.isEmpty()) 0 else minOf(peers.size, MAX_REPLIES_PER_RANGE, capLeft)

        // 配图：本章挑好的那几段落在这一段里、还没有图的，行尾标〔配图〕。
        val illustrated = chapterNotes.filter { AnnotationMedia.decode(it.first.mediaJson).illustrationId != null }
        val imageRoom = request.limits.maxImagesPerChapter - illustrated.size
        val imageLines = if (context.imageTargets.isNotEmpty() && imageRoom > 0 && permit.maxImages > 0) {
            val drawnLines = illustrated.map { AnnotationChunks.lineEnd(body, (it.first.endCharOffset - 1).coerceAtLeast(0)) }.toSet()
            val lines = context.imageTargets.filter { it in (start + 1)..end }
                .map { AnnotationChunks.lineEnd(body, it - 1) }.filter { it !in drawnLines }.distinct().take(imageRoom).toSet()
            if (lines.isNotEmpty() && imageBackendReady(context)) lines else emptySet()
        } else emptySet()
        val voiceMax = if (request.voice && run.persona.voiceId.isNotBlank()) minOf(permit.maxVoice, MAX_VOICE_PER_RANGE) else 0

        val reading = renderReadingChunk(body, start, end, peers, imageLines)
        val readingHeader = chapterLabel(request.chapterIndex, context.chapterTitle) +
            if (start == 0) "（从这一章的开头读起）" else "（接着上面读过的部分往下读）"
        val rules = request.templates.text(AnnotationPromptTemplates.BATCH_RULES)
        val personaChars = annotationPersonaBlock(run.persona, "", request.limits.context.personaMaxChars, includeTriggered = false).length
        val budget = (settings.windowChars - personaChars - rules.length - reading.length - CONTRACT_RESERVE)
            .coerceAtLeast(MIN_RECORD_CHARS)
        val record = recordText(context, start, before, budget)
        val contract = BatchContract(target, max, min, voiceMax, imageLines.isNotEmpty(),
            peers.map { it.tag.orEmpty() to it.author }, maxReplies)
        val messages = batchAnnotationMessages(BatchPrompt(
            persona = run.persona, personaMaxChars = request.limits.context.personaMaxChars, rules = rules,
            presets = request.prompts, bookLine = context.bookLine, record = record, readingHeader = readingHeader,
            reading = reading, loreMaterial = body.substring(maxOf(0, start - LORE_LOOKBACK), end) + "\n" +
                peers.joinToString("\n") { it.note }, contract = contract
        ))

        if (!valid(context)) return RangeResult.STOP
        val output = try {
            val resolved = clientFactory.forRole(ModelRole.PROACTIVE_ANNOTATION)
            val raw = withTimeout(BATCH_TEXT_TIMEOUT_MS) { resolved.client.chat(messages = messages, options = resolved.options) }
            BatchAnnotationParser.parse(raw)
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            log("batch request timed out"); return RangeResult.FAIL
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("batch request failed: ${error.javaClass.simpleName}"); return RangeResult.FAIL
        }
        if (output == null) { log("unparseable batch reply"); return RangeResult.FAIL }
        if (!valid(context)) return RangeResult.STOP

        // 引文以原文为准：在这一段里找，优先找上一条之后的位置；同一处只留一条。
        val text = body.substring(start, end)
        var placed = mutableListOf<Placed>()
        var cursor = 0
        for (draft in output.notes) {
            if (placed.size >= max) break
            val span = locate(text, cleanQuote(draft.quote), cursor) ?: continue
            if (span.end - span.start < 2) continue
            val note = Placed(start + span.start, start + span.end, draft.note.trim(), draft.style, draft.voice, draft.imagePrompt)
            if (placed.any { it.start < note.end && note.start < it.end }) continue
            placed += note
            cursor = span.start
        }
        val tags = peerRows.mapIndexed { index, pair -> "P${index + 1}" to pair.first }.toMap()
        var replies = output.replies.mapNotNull { draft ->
            val peer = tags[draft.to] ?: return@mapNotNull null
            PlacedReply(peer, draft.to, context.names[peer.personaId] ?: FORMER_COMPANION,
                AnnotationRecordText.oneLine(draft.text, MAX_REPLY_CHARS))
        }.distinctBy { it.peer.id }.take(maxReplies).toMutableList()

        if (settings.proofread && (placed.isNotEmpty() || replies.isNotEmpty())) {
            val proofed = proofread(context, run, start, end, record, placed, replies) ?: return RangeResult.STOP
            placed = proofed.first.toMutableList()
            replies = proofed.second.toMutableList()
        }
        placed.sortBy { it.start }

        // 语音、配图：扣了额度才请求，失败不拖累批注本身。
        val detached = mutableListOf<IllustrationEntity>()
        try {
            var voices = 0
            for (note in placed) {
                if (!note.voice || voices >= voiceMax) continue
                val mediaPermit = gate.allowance() ?: return RangeResult.STOP
                if (mediaPermit.maxVoice <= 0) break
                gate.recordQuota(0, 1, 0)
                voices++
                note.audioPath = optionalMedia("voice") {
                    mediaService.synthesizeSpeech(
                        bookId = request.bookId, text = note.note, voiceId = run.persona.voiceId,
                        emotion = run.persona.voiceEmotion.takeIf(String::isNotBlank),
                        beforePaidRequest = { gate.allowance() != null && valid(context) }
                    ).path
                }
            }
            for (line in imageLines.sorted()) {
                val note = placed.firstOrNull { AnnotationChunks.lineEnd(body, it.end - 1) == line } ?: continue
                val mediaPermit = gate.allowance() ?: return RangeResult.STOP
                if (mediaPermit.maxImages <= 0) break
                gate.recordQuota(0, 0, 1)
                val scene = note.imagePrompt ?: body.substring(AnnotationChunks.lineStart(body, note.start), line).trim()
                note.illustration = optionalMedia("image") {
                    mediaService.generateIllustration(
                        bookId = request.bookId, chapterIndex = request.chapterIndex, charOffset = note.start,
                        sourceText = body.substring(note.start, note.end), prompt = scene, personaId = run.persona.id,
                        persist = false, beforePaidRequest = { gate.allowance() != null && valid(context) }
                    )
                }?.also { detached += it }
                if (note.illustration == null) gate.imageFailed()
            }
            if (!valid(context)) return RangeResult.STOP

            val now = System.currentTimeMillis()
            val rows = placed.map { note ->
                AnnotationEntity(
                    bookId = request.bookId, personaId = run.persona.id, chapterIndex = request.chapterIndex,
                    startCharOffset = note.start, endCharOffset = note.end,
                    selectedText = body.substring(note.start, note.end), note = note.note,
                    colorTag = AnnotationColors.forPersona(run.persona.id),
                    style = AnnotationStyle.fromWire(note.style).wire,
                    mediaJson = AnnotationMedia(note.audioPath, null).encode(),
                    sourceScopeChapterIndex = request.chapterIndex,
                    // 校对过的批注只知道它引用的那一行；同伴接话、读书笔记都按这个边界看它。
                    sourceScopeCharOffset = AnnotationChunks.lineEnd(body, note.end - 1),
                    createdAt = now
                ) to note.illustration
            }
            val done = DoneRanges.merge(run.done + DoneRange(start, end))
            val updated = run.job.copy(doneParagraphEnds = DoneRanges.encode(done), updatedAt = now)
            val dailyCap = if (request.ignoreDailyCap || request.limits.dailyUnlimited) Int.MAX_VALUE else request.limits.dailyMax
            val committed = commits.commitBatch(rows, replies.map { it.peer.id to it.text }, updated, dailyCap,
                startOfDay(), now, gate::stillValid) ?: return RangeResult.STOP
            val kept = committed.rows.mapNotNull { rows[it].second }.toSet()
            detached.removeAll { it in kept }
            run.job = committed.job
            run.done = done
            run.produced += committed.annotationIds.size + committed.replies.size
            run.created += committed.annotationIds
            run.created += committed.replies.filter { it !in run.created }
            val count = committed.annotationIds.size + committed.replies.size
            if (count > 0) gate.recordQuota(count, 0, 0)
            return RangeResult.DONE
        } finally {
            // 没落库的配图文件删掉；落了库的由插图表引用。
            detached.forEach { runCatching { java.io.File(it.imagePath).delete() } }
        }
    }

    /**
     * 二次校对。批注一条条编号交出去，附上「只在这一行之后才出现的词」作线索；
     * 校对请求失败时不整段作废，只丢掉线索明显的那几条。返回 null = 上下文变了，停下。
     */
    private suspend fun proofread(
        context: ChapterRun,
        run: PersonaRun,
        start: Int,
        end: Int,
        record: String,
        placed: List<Placed>,
        replies: List<PlacedReply>
    ): Pair<List<Placed>, List<PlacedReply>>? {
        val body = context.body
        val lines = numberedLines(body, start, end)
        fun lineNumber(offset: Int): Int = lines.lastOrNull { it.start <= offset }?.number ?: 1
        // 模型这一次看得到的、在这一行之前的文字：角色、阅读记录、本章到这一行为止。
        val seen = annotationPersonaBlock(run.persona, body.substring(start, end), context.request.limits.context.personaMaxChars) +
            "\n" + record
        fun known(term: String, lineEnd: Int): Boolean {
            val latin = term.first().code < 0x80
            val at = body.indexOf(term, ignoreCase = latin)
            return (at >= 0 && at + term.length <= lineEnd) || seen.contains(term, ignoreCase = latin)
        }
        val items = mutableListOf<ProofItem>()
        val hints = mutableListOf<List<String>>()
        for ((index, note) in placed.withIndex()) {
            val lineEnd = AnnotationChunks.lineEnd(body, note.end - 1)
            val found = ForesightHints.suspicious(note.note, body.substring(lineEnd.coerceAtMost(end), end)) { known(it, lineEnd) }
            hints += found
            items += ProofItem(index + 1, lineNumber(note.end - 1), body.substring(note.start, note.end), note.note, found)
        }
        for ((index, reply) in replies.withIndex()) {
            val lineEnd = AnnotationChunks.lineEnd(body, (reply.peer.endCharOffset - 1).coerceAtLeast(0))
            val found = ForesightHints.suspicious(reply.text, body.substring(lineEnd.coerceIn(start, end), end)) { known(it, lineEnd) }
            hints += found
            items += ProofItem(placed.size + index + 1, lineNumber(reply.peer.endCharOffset - 1), reply.peer.selectedText,
                reply.text, found, replyTo = "${reply.tag}（${reply.author}）")
        }
        val authorLine = "这些批注出自「${run.persona.name}」" +
            listOf(run.persona.speakingStyle, run.persona.personality).firstOrNull(String::isNotBlank)
                ?.let { "（口吻：" + AnnotationRecordText.oneLine(it, 160) + "）" }.orEmpty() + "。"
        val readingHeader = chapterLabel(context.request.chapterIndex, context.chapterTitle) + "里接着读的这一段"
        if (!valid(context)) return null
        val verdicts = try {
            val resolved = clientFactory.forRole(ModelRole.PROACTIVE_ANNOTATION)
            val raw = withTimeout(PROOFREAD_TIMEOUT_MS) {
                resolved.client.chat(
                    messages = proofreadMessages(context.request.templates.text(AnnotationPromptTemplates.PROOFREAD),
                        authorLine, readingHeader, body, lines, items),
                    options = resolved.options
                )
            }
            ProofreadParser.parse(raw)
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            log("proofread timed out"); null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("proofread failed: ${error.javaClass.simpleName}"); null
        }
        if (!valid(context)) return null
        val text = body.substring(start, end)
        val notes = mutableListOf<Placed>()
        for ((index, note) in placed.withIndex()) {
            val verdict = verdicts?.get(index + 1)
            when (verdict?.action) {
                null -> if (!ForesightHints.strong(hints[index])) notes += note
                ProofAction.KEEP -> notes += note
                ProofAction.REWRITE -> verdict.note?.let { note.note = it; notes += note }
                ProofAction.MOVE -> {
                    val quote = verdict.quote?.let(::cleanQuote).orEmpty()
                    val span = if (quote.isEmpty()) null else locate(text, quote, note.start - start)
                    // 只往后挪：挪到更早的地方，知道的只会更少。
                    if (span != null && start + span.start >= note.start) {
                        note.start = start + span.start
                        note.end = start + span.end
                        verdict.note?.let { note.note = it }
                        notes += note
                    }
                }
                ProofAction.DROP -> Unit
            }
        }
        val deduped = mutableListOf<Placed>()
        for (note in notes.sortedBy { it.start }) {
            if (deduped.none { it.start < note.end && note.start < it.end }) deduped += note
        }
        val kept = mutableListOf<PlacedReply>()
        for ((index, reply) in replies.withIndex()) {
            val verdict = verdicts?.get(placed.size + index + 1)
            when (verdict?.action) {
                null -> if (!ForesightHints.strong(hints[placed.size + index])) kept += reply
                ProofAction.KEEP -> kept += reply
                ProofAction.REWRITE -> verdict.note?.let { reply.text = AnnotationRecordText.oneLine(it, MAX_REPLY_CHARS); kept += reply }
                else -> Unit
            }
        }
        return deduped to kept
    }

    /** 阅读记录：前面各章 + 本章这一段之前，照窗口大小折叠。 */
    private suspend fun recordText(context: ChapterRun, start: Int, before: List<RecordNote>, budget: Int): String {
        val request = context.request
        val current = if (start > 0) piecesOf(request.chapterIndex, context.chapterTitle, context.body, 0, start, before, current = true)
        else emptyList()
        var pieces = context.priorPieces + current
        if (pieces.isEmpty()) return ""
        val percent = request.limits.batch.recapPercent
        fun recapOf(piece: RecordPiece): AnnotationMemoryEntity? =
            context.memories[piece.memoryKey]?.takeIf { it.sourceRevision == revisionOf(context, piece) }
        fun sizesOf(list: List<RecordPiece>) = list.map { piece ->
            RecordSizes(AnnotationRecordText.fullSize(piece), recapOf(piece)?.let { cached ->
                cached.content.length + AnnotationRecordText.recapEstimate(piece, percent) - AnnotationRecordText.recapTarget(piece.text.length, percent)
            } ?: AnnotationRecordText.recapEstimate(piece, percent))
        }
        fun arcEstimate(group: Int, list: List<RecordPiece>): Int {
            val members = list.filter { AnnotationRecordPlanner.group(it.chapterIndex) == group }
            context.memories[arcKey(group)]?.takeIf { it.sourceRevision == arcRevision(context, members) }?.let {
                return it.content.length + ARC_LEDGER_ESTIMATE
            }
            return AnnotationRecordText.arcTarget(members.sumOf { AnnotationRecordText.recapTarget(it.text.length, percent) }) + ARC_LEDGER_ESTIMATE
        }
        var sizes = sizesOf(pieces)
        var folds = AnnotationRecordPlanner.plan(pieces, sizes, { arcEstimate(it, pieces) }, budget)
        // 最新的一块（永远是原文）自己就放不下时，只留它最近的部分。
        val newest = pieces.last()
        val others = AnnotationRecordPlanner.total(pieces, sizes, folds) { arcEstimate(it, pieces) } - sizes.last().full
        val room = (budget - others).toInt()
        if (sizes.last().full > room) {
            val source = context.material.texts[newest.chapterIndex] ?: context.body
            val keep = (room - newest.label.length - 64).coerceAtLeast(MIN_RECORD_CHARS / 2)
            val cut = AnnotationChunks.lineStart(source, (newest.end - keep).coerceIn(newest.start, newest.end))
                .coerceAtLeast(newest.start)
            if (cut > newest.start && source.length >= newest.end) {
                val trimmed = newest.copy(start = cut, text = source.substring(cut, newest.end), notes = newest.notes.filter { it.end > cut })
                pieces = pieces.dropLast(1) + trimmed
                sizes = sizesOf(pieces)
                folds = AnnotationRecordPlanner.plan(pieces, sizes, { arcEstimate(it, pieces) }, budget)
            }
        }

        // 缺的回顾补上：离正在读的地方越近越先写，每章这一轮最多写几份，其余先用摘录。
        val wanted = pieces.indices.filter { folds[it] == RecordFold.RECAP && recapOf(pieces[it]) == null }.reversed()
        for (index in wanted) {
            if (context.newRecaps >= MAX_NEW_RECAPS) break
            val piece = pieces[index]
            context.newRecaps++
            val written = runCatching {
                memory.writeRecap(request.bookId, context.bookLine, piece, revisionOf(context, piece), percent,
                    request.templates.text(AnnotationPromptTemplates.RECAP))
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log("recap failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (written != null) context.memories[written.cacheKey] = written
        }

        val blocks = mutableListOf<String>()
        var index = 0
        while (index < pieces.size) {
            val piece = pieces[index]
            when (folds[index]) {
                RecordFold.FULL -> { blocks += AnnotationRecordText.fullBlock(piece); index++ }
                RecordFold.RECAP -> {
                    val cached = recapOf(piece)
                    blocks += AnnotationRecordText.recapBlock(
                        piece, cached?.content ?: AnnotationRecordText.excerpt(piece.text,
                            AnnotationRecordText.recapTarget(piece.text.length, percent)), excerpt = cached == null
                    )
                    index++
                }
                RecordFold.ARC, RecordFold.GONE -> {
                    val group = AnnotationRecordPlanner.group(piece.chapterIndex)
                    var last = index
                    while (last < pieces.size && AnnotationRecordPlanner.group(pieces[last].chapterIndex) == group &&
                        folds[last] == folds[index]) last++
                    val members = pieces.subList(index, last)
                    val label = AnnotationRecordText.arcLabel(members.map { it.chapterIndex })
                    blocks += if (folds[index] == RecordFold.GONE) AnnotationRecordText.goneBlock(label)
                    else AnnotationRecordText.arcBlock(label, arcText(context, group, label, members),
                        members.flatMap { member -> member.notes.map { member.chapterIndex to it } })
                    index = last
                }
            }
        }
        return blocks.joinToString("\n\n")
    }

    private suspend fun arcText(context: ChapterRun, group: Int, label: String, members: List<RecordPiece>): String {
        val revision = arcRevision(context, members)
        context.memories[arcKey(group)]?.takeIf { it.sourceRevision == revision }?.let { return it.content }
        val percent = context.request.limits.batch.recapPercent
        val recaps = members.map { member ->
            member.label to context.memories[member.memoryKey]?.takeIf { it.sourceRevision == revisionOf(context, member) }?.content
        }
        val target = AnnotationRecordText.arcTarget(members.sumOf { AnnotationRecordText.recapTarget(it.text.length, percent) })
        if (recaps.all { it.second != null } && context.newArcs < MAX_NEW_ARCS) {
            context.newArcs++
            val written = runCatching {
                memory.writeArc(context.request.bookId, context.bookLine, arcKey(group), label,
                    recaps.map { it.first to it.second.orEmpty() }, revision, target,
                    context.request.templates.text(AnnotationPromptTemplates.ARC))
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log("arc failed: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (written != null) {
                context.memories[written.cacheKey] = written
                return written.content
            }
        }
        // 还没合成：各章的回顾（没有回顾的用开头一小段）各取一截。
        val share = (target / members.size.coerceAtLeast(1)).coerceAtLeast(60)
        return members.zip(recaps).joinToString("\n") { (member, recap) ->
            member.label + "：" + AnnotationRecordText.oneLine(recap.second ?: member.text, share)
        }
    }

    private fun arcKey(group: Int) = "arc:$group"

    private fun arcRevision(context: ChapterRun, members: List<RecordPiece>): String =
        AnnotationMemoryStore.textRevision(members.joinToString("|") { it.memoryKey + "=" + revisionOf(context, it) })

    private fun revisionOf(context: ChapterRun, piece: RecordPiece): String =
        context.material.hashes.getOrPut(piece.memoryKey + "#" + piece.text.length) { AnnotationMemoryStore.textRevision(piece.text) }

    private suspend fun imageBackendReady(context: ChapterRun): Boolean =
        context.imageBackend ?: mediaService.illustrationBackendReady().also {
            context.imageBackend = it
            if (!it) log("image backend unavailable")
        }

    /** 某章（或几章）的批注和它们的接话，按位置排好。 */
    private suspend fun loadNotes(bookId: Long, from: Int, to: Int): List<Pair<AnnotationEntity, List<AnnotationReplyEntity>>> {
        if (to < from) return emptyList()
        val dao = database.annotationDao()
        val rows = dao.getForChapterRange(bookId, from, to).filter { it.note.isNotBlank() }
        if (rows.isEmpty()) return emptyList()
        val replies = rows.map { it.id }.chunked(900).flatMap { dao.getRepliesForAnnotations(it) }.groupBy { it.annotationId }
        return rows.map { it to replies[it.id].orEmpty() }
    }

    private suspend fun nameOf(names: MutableMap<Long, String>, id: Long?) {
        if (id != null && id !in names) names[id] = personas.getPersona(id)?.name ?: FORMER_COMPANION
    }

    private fun recordNote(pair: Pair<AnnotationEntity, List<AnnotationReplyEntity>>, names: Map<Long, String>): RecordNote {
        val (row, replies) = pair
        return RecordNote(
            start = row.startCharOffset, end = row.endCharOffset,
            author = row.personaId?.let { names[it] ?: FORMER_COMPANION } ?: READER,
            quote = row.selectedText, note = row.note,
            replies = replies.filter { it.contentMarkdown.isNotBlank() }.map {
                RecordReply(it.personaId?.let { id -> names[id] ?: FORMER_COMPANION } ?: READER, it.contentMarkdown)
            }
        )
    }

    /** 一章（或本章读过的那部分）拆成阅读记录的几块；太长的章拆成几截，各自能折成回顾。 */
    private fun piecesOf(
        chapterIndex: Int, title: String, text: String, start: Int, end: Int, notes: List<RecordNote>, current: Boolean
    ): List<RecordPiece> {
        val slice = text.substring(start, end)
        val parts = if (slice.length > PART_CHARS) AnnotationChunks.split(slice, PART_CHARS) else listOf(AnnotationChunk(0, 0, slice.length))
        return parts.map { part ->
            val from = start + part.start
            val to = start + part.end
            RecordPiece(chapterIndex, title, from, to, text.substring(from, to),
                notes.filter { it.end > from && it.end <= to }, part = if (parts.size > 1) part.index + 1 else 0, current = current)
        }
    }

    private fun locate(text: String, quote: String, from: Int): QuoteMatcher.Span? {
        if (quote.isBlank()) return null
        val start = from.coerceIn(0, text.length)
        QuoteMatcher.find(text.substring(start), quote)?.let { return QuoteMatcher.Span(it.start + start, it.end + start) }
        return QuoteMatcher.find(text, quote) ?: QuoteMatcher.bestOverlap(text, quote, 6)
    }

    private fun startOfDay(): Long = java.time.LocalDate.now()
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun log(reason: String) {
        runCatching { android.util.Log.w(TAG, "batch annotation: $reason") }
    }

    private suspend fun <T> optionalMedia(kind: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        log("$kind failed: ${error.javaClass.simpleName}")
        null
    }

    internal companion object {
        const val TAG = "BatchAnnotation"
        const val BATCH_REVISION_PREFIX = "batch:"
        const val BATCH_TEXT_TIMEOUT_MS = 300_000L
        const val PROOFREAD_TIMEOUT_MS = 180_000L
        const val MAX_PEERS = 8
        const val MAX_REPLIES_PER_RANGE = 2
        const val MAX_REPLY_CHARS = 200
        const val MAX_VOICE_PER_RANGE = 2
        const val MAX_NEW_RECAPS = 6
        const val MAX_NEW_ARCS = 2
        const val PART_CHARS = 40_000
        const val CONTRACT_RESERVE = 3_000
        const val MIN_RECORD_CHARS = 2_000
        const val LORE_LOOKBACK = 2_000
        const val ARC_LEDGER_ESTIMATE = 900
        const val READER = "读者"
        const val FORMER_COMPANION = "伴读"

        fun ceilDiv(a: Long, b: Long): Int = if (b <= 0) 0 else ((a + b - 1) / b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        /** 这条批注在 [chapterIndex] 第 [offset] 个字的位置看得到吗：写它时知道的不能超过这里。 */
        fun visibleAt(row: AnnotationEntity, chapterIndex: Int, offset: Int): Boolean {
            val scopeChapter = row.sourceScopeChapterIndex ?: return row.chapterIndex < chapterIndex || row.endCharOffset <= offset
            return scopeChapter < chapterIndex || (scopeChapter == chapterIndex && (row.sourceScopeCharOffset ?: Int.MAX_VALUE) <= offset)
        }
    }
}
