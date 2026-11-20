package com.mozhi.reader.ai.companion

/** 章内的一截正文 [start, end)，UTF-16 偏移，与批注坐标同一套。 */
data class AnnotationChunk(val index: Int, val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/**
 * 整段模式的切段。段界总落在换行处（单行太长时退到句末），所以批注引文不会被切成两半；
 * 切法只由正文和目标长度决定，同一章续跑、换角色、重启应用都切在同样的位置。
 */
object AnnotationChunks {
    private const val SENTENCE_ENDS = "。！？!?；;…」』\""

    fun split(body: String, target: Int): List<AnnotationChunk> {
        if (body.isEmpty()) return emptyList()
        val size = target.coerceAtLeast(MIN_TARGET)
        val bounds = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < body.length) {
            var end = minOf(start + size, body.length)
            if (end < body.length) {
                // 先找目标位置附近最后一个换行；整段没有换行时再找句末，都没有就硬切（避开代理对）。
                val newline = body.lastIndexOf('\n', end - 1).takeIf { it >= start + size / 2 }
                end = when {
                    newline != null -> newline + 1
                    else -> (end - 1 downTo start + size / 2).firstOrNull { body[it] in SENTENCE_ENDS }?.plus(1)
                        ?: end
                }
                if (end < body.length && end > 0 && body[end - 1].isHighSurrogate() && body[end].isLowSurrogate()) end--
            }
            bounds += start to end
            start = end
        }
        // 最后一截太短（不到目标的三分之一）就并进前一截，免得为几行字单独发一次请求。
        if (bounds.size >= 2 && bounds.last().let { it.second - it.first } < size / 3) {
            val last = bounds.removeAt(bounds.lastIndex)
            val previous = bounds.removeAt(bounds.lastIndex)
            bounds += previous.first to last.second
        }
        return bounds.mapIndexed { index, (from, to) -> AnnotationChunk(index, from, to) }
    }

    /** 这一截里有没有值得读的字（全是空白、分隔线的不发请求）。 */
    fun hasText(body: String, chunk: AnnotationChunk): Boolean =
        (chunk.start until chunk.end).count { !body[it].isWhitespace() } >= MIN_TEXT_CHARS

    /** [offset] 所在那一行的行尾（不含换行符）；用来把批注插在它引用的那一行后面。 */
    fun lineEnd(body: String, offset: Int): Int {
        val from = offset.coerceIn(0, body.length)
        val newline = body.indexOf('\n', from)
        return if (newline < 0) body.length else newline
    }

    /** [offset] 所在那一行的行首。 */
    fun lineStart(body: String, offset: Int): Int {
        val at = offset.coerceIn(0, body.length)
        if (at == 0) return 0
        val newline = body.lastIndexOf('\n', at - 1)
        return newline + 1
    }

    const val MIN_TARGET = 500
    private const val MIN_TEXT_CHARS = 12
}
