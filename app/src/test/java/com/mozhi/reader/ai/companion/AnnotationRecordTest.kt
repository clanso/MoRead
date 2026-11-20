package com.mozhi.reader.ai.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationRecordTest {

    private fun piece(chapter: Int, notes: List<RecordNote> = emptyList(), title: String = "", part: Int = 0, current: Boolean = false) =
        RecordPiece(chapter, title, 0, 100, "正文", notes, part = part, current = current)

    private val sizes = RecordSizes(full = 1_000, recap = 100)

    @Test
    fun recordStaysWholeWhenItFits() {
        val pieces = (0 until 3).map { piece(it) }

        val folds = AnnotationRecordPlanner.plan(pieces, List(3) { sizes }, { 50 }, budget = 10_000)

        assertEquals(List(3) { RecordFold.FULL }, folds)
    }

    @Test
    fun oldestPiecesFoldFirstAndTheNewestStaysFull() {
        val pieces = (0 until 3).map { piece(it) }

        assertEquals(
            listOf(RecordFold.RECAP, RecordFold.FULL, RecordFold.FULL),
            AnnotationRecordPlanner.plan(pieces, List(3) { sizes }, { 50 }, budget = 2_200)
        )
        // 同一组（前十章）里没有可以合成前情提要的旧组：压到只剩最新一块原文就停，不丢内容。
        assertEquals(
            listOf(RecordFold.RECAP, RecordFold.RECAP, RecordFold.FULL),
            AnnotationRecordPlanner.plan(pieces, List(3) { sizes }, { 50 }, budget = 100)
        )
    }

    @Test
    fun olderTenChapterGroupsBecomeArcsThenDrop() {
        val pieces = (0 until 12).map { piece(it) }
        val allSizes = List(12) { sizes }

        val arc = AnnotationRecordPlanner.plan(pieces, allSizes, { 300 }, budget = 1_500)
        assertEquals(List(10) { RecordFold.ARC } + RecordFold.RECAP + RecordFold.FULL, arc)
        assertEquals(1_400L, AnnotationRecordPlanner.total(pieces, allSizes, arc) { 300 })

        val gone = AnnotationRecordPlanner.plan(pieces, allSizes, { 300 }, budget = 1_200)
        assertEquals(List(10) { RecordFold.GONE } + RecordFold.RECAP + RecordFold.FULL, gone)
        assertEquals(
            AnnotationRecordPlanner.GONE_CHARS + 1_100L,
            AnnotationRecordPlanner.total(pieces, allSizes, gone) { 300 }
        )
    }

    @Test
    fun guessesAreRecognised() {
        assertTrue(AnnotationRecordText.isGuess("会不会是阿黛尔干的？"))
        assertTrue(AnnotationRecordText.isGuess("我猜她早就知道了"))
        assertFalse(AnnotationRecordText.isGuess("好美的雪。"))
    }

    @Test
    fun oneLineAndQuotesStayOnCharacterBoundaries() {
        assertEquals("第一行 / 第二行", AnnotationRecordText.oneLine("第一行\n\n第二行"))
        assertEquals("abc…", AnnotationRecordText.oneLine("abcdef", 4))
        assertEquals("一二三四…十", AnnotationRecordText.shortQuote("一二三四五六七八九十", 6))
        assertEquals("短句", AnnotationRecordText.shortQuote("短句", 6))
        assertEquals("a", AnnotationRecordText.safeTake("a😀b", 2))
        assertEquals("a😀", AnnotationRecordText.safeTake("a😀b", 3))
    }

    private val note = RecordNote(
        start = 104, end = 106, author = "柯萝伊", quote = "第二", note = "会不会是客人？",
        replies = listOf(RecordReply("阿黛尔", "是送信的吧"))
    )

    @Test
    fun noteLinesNameTheAuthorQuoteAndReplies() {
        assertEquals(
            "〔批注·柯萝伊〕「第二」→ 会不会是客人？\n　↳ 阿黛尔：是送信的吧",
            AnnotationRecordText.noteLine(note)
        )
        assertTrue(AnnotationRecordText.noteLine(note.copy(tag = "P1")).startsWith("〔P1·柯萝伊的批注〕「第二」"))
    }

    @Test
    fun notesAreInsertedAfterTheLineTheyQuote() {
        val text = "第一行\n第二行\n第三行"
        val outside = note.copy(start = 50, end = 60, note = "不在这一块")

        val inline = AnnotationRecordText.inline(text, 100, listOf(note, outside))

        assertEquals(
            "第一行\n第二行\n〔批注·柯萝伊〕「第二」→ 会不会是客人？\n　↳ 阿黛尔：是送信的吧\n第三行",
            inline
        )
        assertEquals(text, AnnotationRecordText.inline(text, 100, emptyList()))
    }

    @Test
    fun blocksKeepChapterLabelsAndEveryNote() {
        assertEquals("第 1 章「启程」", chapterLabel(0, "  启程 "))
        assertEquals("第 5 章", chapterLabel(4, ""))
        assertEquals("第 3 章「雨」（第 2 截）", piece(2, title = "雨", part = 2).label)
        assertEquals("recap:2:0-100", piece(2).memoryKey)

        val current = AnnotationRecordText.fullBlock(piece(0, title = "雨", current = true))
        assertTrue(current.startsWith("◆ 第 1 章「雨」（正在读的这一章，前面读过的部分）\n"))

        val recap = AnnotationRecordText.recapBlock(piece(0, listOf(note), title = "雨"), "  回顾内容  ", excerpt = false)
        assertEquals("◆ 第 1 章「雨」（回顾）\n回顾内容\n这里的批注：\n· 柯萝伊「第二」→ 会不会是客人？\n　↳ 阿黛尔：是送信的吧", recap)
        assertTrue(AnnotationRecordText.recapBlock(piece(0), "摘录", excerpt = true).startsWith("◆ 第 1 章（摘录）"))
    }

    @Test
    fun arcsKeepOnlyGuessesWithTheirChapters() {
        val notes = listOf(
            0 to note.copy(note = "会不会是他？"),
            3 to note.copy(note = "好美"),
            5 to note.copy(note = "我猜是阿黛尔")
        )

        val arc = AnnotationRecordText.arcBlock("第 1–10 章", "  提要  ", notes)

        assertTrue(arc.startsWith("◆ 第 1–10 章（前情提要）\n提要\n这几章里你们猜过、问过的："))
        assertTrue(arc.contains("· 第 1 章「第二」柯萝伊：会不会是他？"))
        assertTrue(arc.contains("· 第 6 章「第二」柯萝伊：我猜是阿黛尔"))
        assertFalse(arc.contains("好美"))
        assertTrue(arc.endsWith("（这几章另有 1 条批注，从略）"))
        assertEquals("◆ 第 3 章（太早了，从略）", AnnotationRecordText.goneBlock("第 3 章"))
        assertEquals("第 4 章", AnnotationRecordText.arcLabel(listOf(3)))
        assertEquals("第 1–10 章", AnnotationRecordText.arcLabel(listOf(0, 9, 4)))
    }

    @Test
    fun sizesAndHelpers() {
        assertEquals(listOf(1, 4, 7), AnnotationRecordText.spread((1..10).toList(), 3))
        assertEquals(listOf(1, 2), AnnotationRecordText.spread(listOf(1, 2), 5))
        assertTrue(AnnotationRecordText.spread((1..10).toList(), 0).isEmpty())

        assertEquals(4_000, AnnotationRecordText.recapTarget(100_000, 10))
        assertEquals(2_000, AnnotationRecordText.recapTarget(20_000, 10))
        assertEquals(120, AnnotationRecordText.recapTarget(500, 10))
        assertEquals(300, AnnotationRecordText.arcTarget(300))
        assertEquals(1_000, AnnotationRecordText.arcTarget(3_000))
        assertEquals(3_000, AnnotationRecordText.arcTarget(30_000))

        assertEquals("短", AnnotationRecordText.excerpt(" 短 ", 10))
        val long = "头".repeat(100) + "尾".repeat(100)
        val excerpt = AnnotationRecordText.excerpt(long, 50)
        assertTrue(excerpt.startsWith("头".repeat(30) + "\n……\n"))
        assertTrue(excerpt.endsWith("尾".repeat(20)))
    }
}
