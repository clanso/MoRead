package com.mozhi.reader.ai.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationChunksTest {

    /** [lines] 行，每行 99 个字加一个换行，共 100 个字符。 */
    private fun body(lines: Int) = (0 until lines).joinToString("") { "字".repeat(99) + "\n" }

    @Test
    fun splitsOnLineBreaksAndCoversTheWholeChapter() {
        val text = body(30)

        val chunks = AnnotationChunks.split(text, 1_000)

        assertEquals(listOf(0 to 1_000, 1_000 to 2_000, 2_000 to 3_000), chunks.map { it.start to it.end })
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        chunks.dropLast(1).forEach { assertEquals('\n', text[it.end - 1]) }
        // 切法只看正文和目标长度：再切一次一模一样。
        assertEquals(chunks, AnnotationChunks.split(text, 1_000))
    }

    @Test
    fun shortTailMergesIntoThePreviousChunk() {
        val chunks = AnnotationChunks.split(body(21), 1_000)

        assertEquals(listOf(0 to 1_000, 1_000 to 2_100), chunks.map { it.start to it.end })
    }

    @Test
    fun emptyAndShortBodies() {
        assertTrue(AnnotationChunks.split("", 1_000).isEmpty())
        assertEquals(listOf(AnnotationChunk(0, 0, 5)), AnnotationChunks.split("一二三四五", 1_000))
        // 目标太小时按下限切。
        assertEquals(AnnotationChunks.split(body(30), AnnotationChunks.MIN_TARGET), AnnotationChunks.split(body(30), 10))
    }

    @Test
    fun withoutLineBreaksCutsAfterASentence() {
        val text = ("甲".repeat(9) + "。").repeat(100)

        val chunks = AnnotationChunks.split(text, 605)

        assertEquals(listOf(0 to 600, 600 to 1_000), chunks.map { it.start to it.end })
        assertEquals('。', text[599])
    }

    @Test
    fun hardCutNeverSplitsASurrogatePair() {
        val text = "😀".repeat(400)

        val chunks = AnnotationChunks.split(text, 501)

        assertEquals(listOf(0 to 500, 500 to 800), chunks.map { it.start to it.end })
        chunks.forEach { assertFalse(text[it.start].isLowSurrogate()) }
    }

    @Test
    fun hasTextSkipsBlankAndSeparatorChunks() {
        val blank = "   \n———\n  "
        assertFalse(AnnotationChunks.hasText(blank, AnnotationChunk(0, 0, blank.length)))
        val real = "她推开门，看见柯萝伊坐在柜台后面。"
        assertTrue(AnnotationChunks.hasText(real, AnnotationChunk(0, 0, real.length)))
    }

    @Test
    fun lineStartAndLineEnd() {
        val text = "第一行\n第二行\n第三行"

        assertEquals(7, AnnotationChunks.lineEnd(text, 5))
        assertEquals(3, AnnotationChunks.lineEnd(text, 3))
        assertEquals(text.length, AnnotationChunks.lineEnd(text, 9))
        assertEquals(4, AnnotationChunks.lineStart(text, 5))
        assertEquals(4, AnnotationChunks.lineStart(text, 4))
        assertEquals(0, AnnotationChunks.lineStart(text, 0))
        assertEquals(8, AnnotationChunks.lineStart(text, 9))
    }
}
