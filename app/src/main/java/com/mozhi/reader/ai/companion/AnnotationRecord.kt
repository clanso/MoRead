package com.mozhi.reader.ai.companion

/** 阅读记录里的一条接话。 */
internal data class RecordReply(val author: String, val text: String)

/** 阅读记录里的一条批注。坐标是它所在章节的 UTF-16 偏移，[end] 是引文的终点。 */
internal data class RecordNote(
    val start: Int,
    val end: Int,
    val author: String,
    val quote: String,
    val note: String,
    val replies: List<RecordReply> = emptyList(),
    /** 「P1」这样的标签：正在读的这一段里同伴写的、这一次可以接话的批注。 */
    val tag: String? = null
)

/** 阅读记录的一块：一章（长章里的一截）读过的正文，和读到这里时写下的批注。 */
internal data class RecordPiece(
    val chapterIndex: Int,
    val chapterTitle: String,
    /** 在章内的范围 [start, end)。 */
    val start: Int,
    val end: Int,
    val text: String,
    val notes: List<RecordNote>,
    /** 长章拆成几截时是第几截（从 1 起）；整章一块时为 0。 */
    val part: Int = 0,
    /** 正在读的这一章里、接着读的那一段之前的部分。 */
    val current: Boolean = false
) {
    val memoryKey: String get() = "recap:$chapterIndex:$start-$end"
    val label: String get() = chapterLabel(chapterIndex, chapterTitle) + if (part > 0) "（第 $part 截）" else ""
}

internal fun chapterLabel(index: Int, title: String): String =
    "第 ${index + 1} 章" + title.trim().take(60).takeIf(String::isNotEmpty)?.let { "「$it」" }.orEmpty()

/** 每一块在阅读记录里以什么形态出现：原文、回顾、并进前情提要、太早从略。 */
internal enum class RecordFold { FULL, RECAP, ARC, GONE }

/** 一块折成原文和回顾时各占多少字（回顾没写过时是估计值）。 */
internal data class RecordSizes(val full: Int, val recap: Int)

/**
 * 决定阅读记录的形态。从最早的一块开始压：原文 → 回顾（带着这一块的全部批注，每条都标着引文）→
 * 十章合成一份前情提要（只留猜测、疑问这类以后会被接上的批注）→ 从略。最新的一块永远是原文。
 * 越近的内容越完整，写到哪一章、哪一句、她们说了什么，始终能定位。
 */
internal object AnnotationRecordPlanner {
    const val ARC_CHAPTERS = 10
    const val GONE_CHARS = 40

    fun group(chapterIndex: Int): Int = chapterIndex / ARC_CHAPTERS

    fun plan(
        pieces: List<RecordPiece>,
        sizes: List<RecordSizes>,
        arcSize: (group: Int) -> Int,
        budget: Int
    ): List<RecordFold> {
        require(pieces.size == sizes.size)
        val count = pieces.size
        val folds = MutableList(count) { RecordFold.FULL }
        if (count == 0) return folds
        val groups = pieces.map { group(it.chapterIndex) }
        val order = groups.distinct()
        val newestGroup = groups.last()
        while (total(pieces, sizes, folds, arcSize) > budget) {
            val full = (0 until count - 1).firstOrNull { folds[it] == RecordFold.FULL }
            if (full != null) { folds[full] = RecordFold.RECAP; continue }
            // 前情提要以十章为单位，正在读的这一组不并。
            val arc = order.firstOrNull { group ->
                group != newestGroup && (0 until count).any { groups[it] == group && folds[it] == RecordFold.RECAP }
            }
            if (arc != null) {
                for (index in 0 until count) if (groups[index] == arc) folds[index] = RecordFold.ARC
                continue
            }
            val gone = order.firstOrNull { group ->
                group != newestGroup && (0 until count).any { groups[it] == group && folds[it] == RecordFold.ARC }
            }
            if (gone != null) {
                for (index in 0 until count) if (groups[index] == gone) folds[index] = RecordFold.GONE
                continue
            }
            break
        }
        return folds
    }

    /** 按 [folds] 折好之后整份阅读记录大约多长。 */
    fun total(pieces: List<RecordPiece>, sizes: List<RecordSizes>, folds: List<RecordFold>, arcSize: (group: Int) -> Int): Long {
        var sum = 0L
        for (index in pieces.indices) {
            sum += when (folds[index]) {
                RecordFold.FULL -> sizes[index].full
                RecordFold.RECAP -> sizes[index].recap
                else -> 0
            }
        }
        val groups = pieces.map { group(it.chapterIndex) }
        for (group in groups.distinct()) {
            val members = pieces.indices.filter { groups[it] == group }
            sum += when {
                members.all { folds[it] == RecordFold.ARC } -> arcSize(group)
                members.all { folds[it] == RecordFold.GONE } -> GONE_CHARS
                else -> 0
            }
        }
        return sum
    }
}

/** 把阅读记录写成发给模型的文字。 */
internal object AnnotationRecordText {
    private val lineBreaks = Regex("\\s*[\\r\\n\\u0085\\u2028\\u2029]+\\s*")
    /** 猜测、疑问、预感：以后读到下文时最值得被接上的批注，前情提要里只留这些。 */
    private val guessLike = Regex("[？?]|猜|会不会|难道|应该是|肯定是|一定是|是不是|说不定|大概|估计|预感|伏笔|凶手|真相|到底|该不会|莫非|八成|恐怕|赌")

    const val QUOTE_FULL = 30
    const val QUOTE_RECAP = 24
    const val NOTE_RECAP = 100
    const val REPLY_RECAP = 60
    const val QUOTE_ARC = 16
    const val NOTE_ARC = 60
    const val ARC_LEDGER = 12

    fun isGuess(note: String): Boolean = guessLike.containsMatchIn(note)

    /** 压成一行；超出 [max] 时在字符边界上截断。 */
    fun oneLine(text: String, max: Int = Int.MAX_VALUE): String {
        val flat = text.replace(lineBreaks, " / ").trim()
        return if (flat.length <= max) flat else safeTake(flat, (max - 1).coerceAtLeast(1)) + "…"
    }

    /** 引文太长时留头尾，中间用省略号。 */
    fun shortQuote(quote: String, max: Int): String {
        val flat = oneLine(quote)
        if (flat.length <= max) return flat
        val head = safeTake(flat, (max * 2 / 3).coerceAtLeast(1))
        val tailLength = (max - head.length - 1).coerceAtLeast(1)
        var tailStart = flat.length - tailLength
        if (tailStart > 0 && flat[tailStart].isLowSurrogate()) tailStart++
        return head + "…" + flat.substring(tailStart)
    }

    fun safeTake(text: String, count: Int): String {
        if (count >= text.length) return text
        var end = count.coerceAtLeast(0)
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }

    fun noteLine(note: RecordNote, quoteMax: Int = QUOTE_FULL, noteMax: Int = Int.MAX_VALUE, replyMax: Int = Int.MAX_VALUE): String =
        buildString {
            append(if (note.tag != null) "〔${note.tag}·${note.author}的批注〕" else "〔批注·${note.author}〕")
            append("「").append(shortQuote(note.quote, quoteMax)).append("」→ ")
            append(oneLine(note.note, noteMax))
            for (reply in note.replies) {
                append("\n　↳ ").append(reply.author).append("：").append(oneLine(reply.text, replyMax))
            }
        }

    /** 原文原样，每条批注插在它引用的那一行后面。[offset] 是 [text] 在章内的起点。 */
    fun inline(text: String, offset: Int, notes: List<RecordNote>): String {
        val inside = notes.filter { it.end > offset && it.end <= offset + text.length }
        if (inside.isEmpty()) return text
        val anchored = inside.sortedWith(compareBy<RecordNote>({ it.end }, { it.start }))
            .groupBy { AnnotationChunks.lineEnd(text, (it.end - offset - 1).coerceIn(0, text.length)) }
        val out = StringBuilder(text.length + inside.sumOf { it.note.length + it.quote.length + 24 })
        var cursor = 0
        while (true) {
            val newline = text.indexOf('\n', cursor)
            val lineEnd = if (newline < 0) text.length else newline
            out.append(text, cursor, lineEnd)
            anchored[lineEnd]?.forEach { out.append('\n').append(noteLine(it)) }
            if (newline < 0) break
            out.append('\n')
            cursor = newline + 1
        }
        return out.toString()
    }

    fun recapNotes(notes: List<RecordNote>): String = notes.joinToString("\n") { note ->
        buildString {
            append("· ").append(note.author).append("「").append(shortQuote(note.quote, QUOTE_RECAP)).append("」→ ")
            append(oneLine(note.note, NOTE_RECAP))
            for (reply in note.replies) {
                append("\n　↳ ").append(reply.author).append("：").append(oneLine(reply.text, REPLY_RECAP))
            }
        }
    }

    fun fullBlock(piece: RecordPiece): String = buildString {
        append("◆ ").append(piece.label)
        if (piece.current) append("（正在读的这一章，前面读过的部分）")
        append('\n').append(inline(piece.text, piece.start, piece.notes).trim('\n'))
    }

    fun recapBlock(piece: RecordPiece, recap: String, excerpt: Boolean): String = buildString {
        append("◆ ").append(piece.label).append(if (excerpt) "（摘录）" else "（回顾）").append('\n')
        append(recap.trim())
        if (piece.notes.isNotEmpty()) append("\n这里的批注：\n").append(recapNotes(piece.notes))
    }

    fun arcBlock(label: String, arc: String, notes: List<Pair<Int, RecordNote>>): String = buildString {
        append("◆ ").append(label).append("（前情提要）\n").append(arc.trim())
        val guesses = notes.filter { isGuess(it.second.note) }
        val kept = spread(guesses, ARC_LEDGER)
        if (kept.isNotEmpty()) {
            append("\n这几章里你们猜过、问过的：")
            for ((chapter, note) in kept) {
                append("\n· 第 ").append(chapter + 1).append(" 章「").append(shortQuote(note.quote, QUOTE_ARC)).append("」")
                append(note.author).append("：").append(oneLine(note.note, NOTE_ARC))
            }
        }
        val rest = notes.size - kept.size
        if (rest > 0) append("\n（这几章另有 ").append(rest).append(" 条批注，从略）")
    }

    fun goneBlock(label: String): String = "◆ $label（太早了，从略）"

    fun arcLabel(chapters: List<Int>): String {
        val first = chapters.minOrNull() ?: 0
        val last = chapters.maxOrNull() ?: first
        return if (first == last) "第 ${first + 1} 章" else "第 ${first + 1}–${last + 1} 章"
    }

    /** 回顾还没写时用的摘录：头尾各一段。 */
    fun excerpt(text: String, size: Int): String {
        val flat = text.trim()
        if (flat.length <= size) return flat
        val head = safeTake(flat, size * 3 / 5)
        val tailLength = (size - head.length).coerceAtLeast(1)
        var tailStart = flat.length - tailLength
        if (tailStart > 0 && flat[tailStart].isLowSurrogate()) tailStart++
        return head + "\n……\n" + flat.substring(tailStart)
    }

    /** 均匀挑出至多 [limit] 个，保持原来的先后。 */
    fun <T> spread(items: List<T>, limit: Int): List<T> {
        if (items.size <= limit) return items
        if (limit <= 0) return emptyList()
        return (0 until limit).map { items[(it.toLong() * items.size / limit).toInt()] }
    }

    /** 估计一块折成回顾后的长度。 */
    fun recapEstimate(piece: RecordPiece, percent: Int): Int =
        recapTarget(piece.text.length, percent) + piece.label.length + 16 +
            piece.notes.sumOf { minOf(it.note.length, NOTE_RECAP) + minOf(it.quote.length, QUOTE_RECAP) + it.author.length + 12 +
                it.replies.sumOf { reply -> minOf(reply.text.length, REPLY_RECAP) + reply.author.length + 6 } }

    fun fullSize(piece: RecordPiece): Int =
        piece.text.length + piece.label.length + 24 +
            piece.notes.sumOf { minOf(it.quote.length, QUOTE_FULL) + it.note.length + it.author.length + 12 +
                it.replies.sumOf { reply -> reply.text.length + reply.author.length + 6 } }

    /** 回顾大约写原文的百分之几，太短的章也至少留两三句。 */
    fun recapTarget(length: Int, percent: Int): Int = (length.toLong() * percent / 100).toInt().coerceIn(120, 4_000)

    /** 前情提要的长度：这几章回顾合起来的三分之一左右。 */
    fun arcTarget(recapChars: Int): Int = (recapChars / 3).coerceIn(300, 3_000)
}
