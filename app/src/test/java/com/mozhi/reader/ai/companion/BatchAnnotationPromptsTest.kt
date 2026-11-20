package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.PersonaLoreEntry
import com.mozhi.reader.core.database.entity.encodeWorldBook
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnnotationPromptsTest {

    @Test
    fun parsesAnnotationsAndReplies() {
        val output = BatchAnnotationParser.parse(
            """{"annotations":[{"quote":"她推开门","note":"谁来了？","style":"wavy","voice":true},
               {"quote":"没有批注"}],
               "replies":[{"to":"P1","text":"是送信的"},{"to":"〔P2·阿黛尔的批注〕","text":"null"}]}"""
        )!!

        assertEquals(listOf(BatchNoteDraft("她推开门", "谁来了？", style = "WAVY", voice = true)), output.notes)
        assertEquals(listOf(BatchReplyDraft("P1", "是送信的")), output.replies)
    }

    @Test
    fun toleratesThinkingFencesAndOtherShapes() {
        val fenced = BatchAnnotationParser.parse("<think>先想想</think>\n```json\n{\"annotations\":[]}\n```")!!
        assertTrue(fenced.notes.isEmpty())

        val array = BatchAnnotationParser.parse("""[{"原文":"门开了","批注":"啊","image_prompt":"柯萝伊站在门口"}]""")!!
        assertEquals("门开了", array.notes.single().quote)
        assertEquals("柯萝伊站在门口", array.notes.single().imagePrompt)
        assertEquals("HIGHLIGHT", array.notes.single().style)

        assertEquals(1, BatchAnnotationParser.parse("""{"quote":"门开了","note":"啊"}""")!!.notes.size)
        assertNull(BatchAnnotationParser.parse("""{"foo":1}"""))
        assertNull(BatchAnnotationParser.parse("今天没什么想说的"))
    }

    @Test
    fun peerTagsAcceptLooseSpellings() {
        assertEquals("P3", BatchAnnotationParser.peerTag("p3"))
        assertEquals("P1", BatchAnnotationParser.peerTag("1"))
        assertEquals("P2", BatchAnnotationParser.peerTag("〔P2·阿黛尔的批注〕"))
        assertNull(BatchAnnotationParser.peerTag("阿黛尔"))
    }

    @Test
    fun numbersOnlyLinesWithText() {
        val body = "第一行\n\n  \n第四行\n第五行"

        assertEquals(
            listOf(NumberedLine(1, 0, 3), NumberedLine(2, 8, 11), NumberedLine(3, 12, 15)),
            numberedLines(body, 0, body.length)
        )
        assertEquals(listOf(NumberedLine(1, 8, 11)), numberedLines(body, 8, 11))
    }

    @Test
    fun readingChunkCarriesPeerNotesAndImageMarks() {
        val body = "前文\n她推开门。\n门外站着人。\n后文"
        val peer = RecordNote(start = 3, end = 7, author = "阿黛尔", quote = "她推开门", note = "谁啊", tag = "P1")
        val before = RecordNote(start = 0, end = 2, author = "阿黛尔", quote = "前文", note = "不在这一段")

        val rendered = renderReadingChunk(body, 3, 16, listOf(peer, before), imageLineEnds = setOf(15))

        assertEquals("她推开门。\n〔P1·阿黛尔的批注〕「她推开门」→ 谁啊\n门外站着人。〔配图〕", rendered)
    }

    @Test
    fun cleanQuoteDropsMarksAndLineNumbers() {
        assertEquals("她推开门", cleanQuote("L12: 她推开门〔配图〕"))
        assertEquals("她推开门", cleanQuote("  她推开门 "))
        assertEquals("Lily走了", cleanQuote("Lily走了"))
    }

    @Test
    fun contractSpellsOutTheNumbersForThisRequest() {
        val full = batchContractText(
            BatchContract(target = 5, max = 8, min = 2, voiceMax = 1, image = true, peers = listOf("P1" to "阿黛尔"), maxReplies = 2)
        )
        assertTrue(full.startsWith("【本次要求】"))
        assertTrue(full.contains("这一段大约写 5 条批注，最多 8 条，至少 2 条"))
        assertTrue(full.contains("\"voice\": true，最多 1 条"))
        assertTrue(full.contains("image_prompt"))
        assertTrue(full.contains("P1 是阿黛尔"))
        assertTrue(full.contains("\"replies\":[{\"to\":\"P1\""))

        val open = batchContractText(BatchContract(target = 5, max = Int.MAX_VALUE))
        assertTrue(open.contains("这一段大约写 5 条批注；"))
        assertFalse(open.contains("image_prompt"))
        assertFalse(open.contains("replies"))

        val repliesOnly = batchContractText(BatchContract(target = 0, max = 0, peers = listOf("P1" to "阿黛尔"), maxReplies = 1))
        assertTrue(repliesOnly.contains("这一段不写新的批注"))
        assertFalse(repliesOnly.contains("quote 从"))
    }

    private val persona = PersonaEntity(
        id = 1,
        name = "柯萝伊",
        personality = "草药店的助手。",
        isRoleplay = true,
        worldBookJson = encodeWorldBook(
            listOf(
                PersonaLoreEntry(name = "店", content = "常驻设定：里泽尔的草药店。"),
                PersonaLoreEntry(name = "魔石", content = "触发设定：魔石的来历。", constant = false, keys = listOf("魔石"))
            )
        ),
        createdAt = 0
    )

    private fun prompt(material: String) = BatchPrompt(
        persona = persona,
        personaMaxChars = ProactiveAnnotationContextSettings.UNLIMITED,
        rules = "  规则正文  ",
        presets = emptyList(),
        bookLine = "书名：《异世界漂流书》",
        record = "◆ 第 1 章\n读过的内容",
        readingHeader = "第 2 章「雨」",
        reading = material,
        loreMaterial = material,
        contract = BatchContract(target = 3, max = 5)
    )

    @Test
    fun stableSystemPromptAndChangingPartsAtTheEnd() {
        val messages = batchAnnotationMessages(prompt("她拿出一颗魔石。"))

        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), messages.map { it.role })
        val system = messages[0].content
        assertTrue(system.contains("常驻设定"))
        assertTrue(system.endsWith("规则正文"))
        assertFalse(system.contains("触发设定"))

        val user = messages[1].content
        val order = listOf("书名：", "【阅读记录】", "【接着读】第 2 章「雨」", "【相关设定】", "【本次要求】").map(user::indexOf)
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
        assertTrue(user.contains("触发设定：魔石的来历。"))
        assertTrue(user.endsWith(batchContractText(BatchContract(target = 3, max = 5))))

        // 换一段没有触发词的正文，系统提示词一个字都不变。
        val quiet = batchAnnotationMessages(prompt("她在柜台后面算账。"))
        assertEquals(system, quiet[0].content)
        assertFalse(quiet[1].content.contains("【相关设定】"))
    }

    @Test
    fun proofreadMessagesNumberLinesAndListHints() {
        val body = "她推开门。\n门外站着阿黛尔。"
        val messages = proofreadMessages(
            rules = "校对规则",
            authorLine = "作者：柯萝伊",
            readingHeader = "第 2 章",
            body = body,
            lines = numberedLines(body, 0, body.length),
            items = listOf(
                ProofItem(1, 1, "她推开门", "会不会是阿黛尔？", hints = listOf("阿黛尔")),
                ProofItem(2, 2, "", "是送信的", hints = emptyList(), replyTo = "柯萝伊")
            )
        )

        assertTrue(messages[0].content.endsWith(PROOFREAD_CONTRACT))
        val user = messages[1].content
        assertTrue(user.contains("\nL1 她推开门。\nL2 门外站着阿黛尔。"))
        assertTrue(user.contains("\n1.〔L1〕「她推开门」→ 会不会是阿黛尔？\n　可疑词：阿黛尔"))
        assertTrue(user.contains("\n2.〔L2，接柯萝伊的话〕→ 是送信的"))
    }

    @Test
    fun proofreadVerdictsParseLeniently() {
        val verdicts = ProofreadParser.parse(
            """{"verdicts":[{"i":1,"action":"keep"},{"i":2,"action":"rewrite","note":"改过的"},
               {"i":"3","action":"MOVE","quote":"后面那句","note":"挪过去"},{"i":4,"action":"drop"},
               {"i":5,"action":"不知道"},{"action":"keep"}]}"""
        )!!

        assertEquals(setOf(1, 2, 3, 4), verdicts.keys)
        assertEquals(ProofAction.KEEP, verdicts.getValue(1).action)
        assertEquals(ProofVerdict(2, ProofAction.REWRITE, note = "改过的"), verdicts[2])
        assertEquals(ProofVerdict(3, ProofAction.MOVE, note = "挪过去", quote = "后面那句"), verdicts[3])
        assertEquals(ProofAction.DROP, verdicts.getValue(4).action)

        val chinese = ProofreadParser.parse("""[{"index":1,"verdict":"保留"},{"index":2,"verdict":"删除"}]""")!!
        assertEquals(ProofAction.KEEP, chinese.getValue(1).action)
        assertEquals(ProofAction.DROP, chinese.getValue(2).action)

        assertNull(ProofreadParser.parse("都挺好"))
        assertNull(ProofreadParser.parse("""{"foo":[]}"""))
    }

    @Test
    fun foresightHintsAreWordsThatOnlyShowUpLater() {
        val note = "阿黛尔肯定会回来"
        val after = "这时阿黛尔推门进来"

        val before = "柯萝伊在店里等。"
        assertEquals(listOf("阿黛尔"), ForesightHints.suspicious(note, after) { before.contains(it) })
        val seen = "阿黛尔出门了。柯萝伊在店里等。"
        assertTrue(ForesightHints.suspicious(note, after) { seen.contains(it) }.isEmpty())
        assertTrue(ForesightHints.suspicious(note, "  ") { false }.isEmpty())
        assertEquals(listOf("Zanis"), ForesightHints.suspicious("Zanis again", "they reached Zanis") { false })

        assertFalse(ForesightHints.strong(emptyList()))
        assertFalse(ForesightHints.strong(listOf("阿黛")))
        assertTrue(ForesightHints.strong(listOf("阿黛尔")))
        assertTrue(ForesightHints.strong(listOf("阿黛", "魔石")))
    }

    @Test
    fun doneRangesMergeEncodeAndFindGaps() {
        val merged = DoneRanges.merge(listOf(DoneRange(5, 10), DoneRange(0, 3), DoneRange(3, 5), DoneRange(12, 15)))
        assertEquals(listOf(DoneRange(0, 10), DoneRange(12, 15)), merged)
        assertEquals("[0,10,12,15]", DoneRanges.encode(merged))

        assertEquals(merged, DoneRanges.decode("[0,10,12,15]", 20))
        assertEquals(emptyList<DoneRange>(), DoneRanges.decode(null, 20))
        assertNull(DoneRanges.decode("[0,10,12]", 20))
        assertNull(DoneRanges.decode("[0,30]", 20))
        assertNull(DoneRanges.decode("[5,5]", 20))
        assertNull(DoneRanges.decode("坏了", 20))

        assertEquals(listOf(DoneRange(10, 12), DoneRange(15, 20)), DoneRanges.pending(merged, 0, 20))
        assertEquals(listOf(DoneRange(3, 8)), DoneRanges.pending(emptyList(), 3, 8))
        assertTrue(DoneRanges.pending(listOf(DoneRange(0, 20)), 5, 10).isEmpty())
    }
}
