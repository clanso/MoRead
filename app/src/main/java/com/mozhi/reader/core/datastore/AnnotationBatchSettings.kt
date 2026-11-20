package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable

/** 段评怎么生成：整段（一次请求写一段正文里的多条）或逐段（旧版，每条一次请求）。 */
@Serializable
enum class AnnotationGenerationMode(val label: String) { BATCH("整段"), PARAGRAPH("逐段") }

/**
 * 整段模式的参数。
 *
 * - [chunkChars]：每次请求读多少字正文。模型一次读完这一段再写，写某一条时只当自己读到它引用的那一句，
 *   越界的由二次校对挑出来。段越长请求越少、越省，单段里「先看到后文」的机会也越多。
 * - [charsPerNote]：大约每多少字一条，只是给模型的节奏参考；每章上限仍按「数量与额度」。
 * - [windowChars]：阅读记录（前文原文 + 之前的批注 + 回顾）一次最多带多少字。超过时把较早的章节折叠成
 *   回顾，回顾也放不下时再合并成前情提要；批注始终留着「第几章、哪一句、说了什么」。
 * - [proofread]：每段写完后另发一次请求做二次校对。
 * - [recapPercent]：章节回顾大约写原文的百分之几。
 */
@Serializable
data class AnnotationBatchSettings(
    val mode: AnnotationGenerationMode = AnnotationGenerationMode.BATCH,
    val chunkChars: Int = DEFAULT_CHUNK_CHARS,
    val charsPerNote: Int = DEFAULT_CHARS_PER_NOTE,
    val windowChars: Int = DEFAULT_WINDOW_CHARS,
    val proofread: Boolean = true,
    val recapPercent: Int = DEFAULT_RECAP_PERCENT
) {
    val batch: Boolean get() = mode == AnnotationGenerationMode.BATCH

    fun normalized() = copy(
        chunkChars = chunkChars.coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS),
        charsPerNote = charsPerNote.coerceIn(MIN_CHARS_PER_NOTE, MAX_CHARS_PER_NOTE),
        windowChars = windowChars.coerceIn(MIN_WINDOW_CHARS, MAX_WINDOW_CHARS),
        recapPercent = recapPercent.coerceIn(MIN_RECAP_PERCENT, MAX_RECAP_PERCENT)
    )

    companion object {
        const val DEFAULT_CHUNK_CHARS = 5_000
        const val MIN_CHUNK_CHARS = 1_000
        const val MAX_CHUNK_CHARS = 40_000
        const val DEFAULT_CHARS_PER_NOTE = 1_000
        const val MIN_CHARS_PER_NOTE = 100
        const val MAX_CHARS_PER_NOTE = 20_000
        /**
         * 约 8～10 万 token：远低于百万上下文的上限，长上下文里中段注意力衰减还不明显；
         * 一部轻小说一卷大致能整卷放进来，更早的部分折叠成回顾。
         */
        const val DEFAULT_WINDOW_CHARS = 120_000
        const val MIN_WINDOW_CHARS = 16_000
        const val MAX_WINDOW_CHARS = 900_000
        const val DEFAULT_RECAP_PERCENT = 10
        const val MIN_RECAP_PERCENT = 3
        const val MAX_RECAP_PERCENT = 30
    }
}
