package com.mozhi.reader.ai.persona

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernCardParserTest {

    @Test
    fun parsesV2PngCardWithSeparatedWorldBook() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "白鹭",
                "description": "{{char}}是一名旅居书店的猫。",
                "personality": "慵懒、敏锐",
                "scenario": "{{user}}走进书店。",
                "first_mes": "欢迎来到白鹭书屋，{{user}}。",
                "mes_example": "<START>\n{{user}}: 你是谁？\n{{char}}: 我是白鹭。",
                "tags": ["猫", "书店", "治愈", "多余标签"],
                "creator": "someone",
                "character_book": {
                  "entries": [
                    {"content": "白鹭其实是店主的化身。", "enabled": true, "comment": "真身"},
                    {"content": "禁用条目保留但不绑定。", "enabled": false, "keys": ["秘密"]},
                    {"content": "  "}
                  ]
                }
              }
            }
        """.trimIndent()
        val png = pngWith("chara" to base64(json))

        val card = SillyTavernCardParser.parse(png)

        assertNotNull(card)
        card!!
        assertEquals("白鹭", card.name)
        assertEquals("猫 · 书店 · 治愈", card.subtitle)
        assertTrue(card.personality.contains("白鹭是一名旅居书店的猫。"))
        assertTrue(card.personality.contains("性格特质：慵懒、敏锐"))
        assertTrue(card.personality.contains("场景设定：用户走进书店。"))
        // 世界书与人设分离：人设描述里不再有设定集。
        assertTrue(!card.personality.contains("【设定集】"))
        assertTrue(!card.personality.contains("店主的化身"))
        assertEquals(2, card.worldBook.size)
        assertEquals("真身", card.worldBook[0].name)
        assertEquals("白鹭其实是店主的化身。", card.worldBook[0].content)
        assertTrue(card.worldBook[0].enabled)
        // 无触发词的条目只能常驻。
        assertTrue(card.worldBook[0].constant)
        // 禁用条目保留、enabled = false、注入方式与触发词保真、条目名回落到首个触发词。
        assertEquals("秘密", card.worldBook[1].name)
        assertTrue(!card.worldBook[1].enabled)
        assertTrue(!card.worldBook[1].constant)
        assertEquals(listOf("秘密"), card.worldBook[1].keys)
        assertEquals("欢迎来到白鹭书屋，用户。", card.greeting)
        assertEquals(1, card.exampleDialogs.size)
        assertEquals("你是谁？", card.exampleDialogs[0].user)
        assertEquals("我是白鹭。", card.exampleDialogs[0].assistant)
        // PNG 卡本身就是立绘。
        assertTrue(card.avatarPng.contentEquals(png))
    }

    @Test
    fun prefersCcv3ChunkOverChara() {
        val v2 = """{"data":{"name":"旧版"}}"""
        val v3 = """{"spec":"chara_card_v3","data":{"name":"新版"}}"""
        val png = pngWith("chara" to base64(v2), "ccv3" to base64(v3))

        assertEquals("新版", SillyTavernCardParser.parse(png)?.name)
    }

    @Test
    fun parsesPlainJsonV1WithRootLevelFields() {
        val json = """
            {"name":"简卡","description":"一段人设。","first_mes":"你好。"}
        """.trimIndent()

        val card = SillyTavernCardParser.parse(json.toByteArray())

        assertNotNull(card)
        assertEquals("简卡", card!!.name)
        assertEquals("一段人设。", card.personality)
        assertEquals("你好。", card.greeting)
        assertNull(card.avatarPng)
    }

    @Test
    fun exampleDialogsHandleMultipleBlocksAndContinuationLines() {
        val mes = """
            <START>
            {{user}}: 第一问
            {{char}}: 第一答
            续行也算我的话
            <START>
            {{user}}: 第二问
            {{char}}: 第二答
        """.trimIndent()

        val dialogs = SillyTavernCardParser.parseExampleDialogs(mes, "角色")

        assertEquals(2, dialogs.size)
        assertEquals("第一问", dialogs[0].user)
        assertEquals("第一答\n续行也算我的话", dialogs[0].assistant)
        assertEquals("第二问", dialogs[1].user)
        assertEquals("第二答", dialogs[1].assistant)
    }

    @Test
    fun garbageInputsReturnNull() {
        assertNull(SillyTavernCardParser.parse("不是 JSON".toByteArray()))
        assertNull(SillyTavernCardParser.parse(pngWith())) // 无 tEXt 卡块的 PNG
        assertNull(SillyTavernCardParser.parse("""{"description":"没有名字"}""".toByteArray()))
    }

    @Test
    fun cardBookKeepsSecondaryKeysLogicAndCase() {
        val json = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "柯萝伊",
                "character_book": {
                  "entries": [
                    {"keys": ["魔石"], "secondary_keys": ["采集", "仓库"], "content": "{{char}}的魔石。",
                     "insertion_order": 20, "selective": true, "constant": false, "comment": "魔石",
                     "extensions": {"selectiveLogic": 3}},
                    {"keys": ["Zanis"], "secondary_keys": ["学校"], "content": "札尼斯。", "insertion_order": 10,
                     "selective": false, "extensions": {"case_sensitive": true}},
                    {"keys": [], "content": "常驻。", "insertion_order": 30}
                  ]
                }
              }
            }
        """.trimIndent()

        val book = SillyTavernCardParser.parse(json.toByteArray())!!.worldBook

        assertEquals(listOf("Zanis", "魔石", ""), book.map { it.name })
        // selective = false：SillyTavern 不看次要触发词，导进来也不带。
        assertEquals(emptyList<String>(), book[0].secondaryKeys)
        assertTrue(book[0].caseSensitive)
        assertTrue(!book[0].constant)
        assertEquals("柯萝伊的魔石。", book[1].content)
        assertEquals(listOf("采集", "仓库"), book[1].secondaryKeys)
        assertEquals(3, book[1].selectiveLogic)
        assertTrue(!book[1].caseSensitive)
        assertTrue(book[2].constant)
    }

    @Test
    fun parsesSillyTavernWorldInfoFile() {
        val json = """
            {
              "entries": {
                "0": {"uid": 0, "key": ["阿黛尔"], "keysecondary": ["愧疚"], "comment": "阿黛尔的心事",
                      "content": "她一直愧疚。", "constant": false, "selective": true, "selectiveLogic": 2,
                      "order": 100, "disable": false, "caseSensitive": null},
                "1": {"uid": 1, "key": "里泽尔, 草药店，药铺", "content": "边境小镇。", "order": 50, "disable": true},
                "2": {"uid": 2, "key": [], "content": "", "order": 1}
              }
            }
        """.trimIndent()

        val entries = SillyTavernCardParser.parseLorebook(json.toByteArray(), "ChloeAdele.json")!!

        assertEquals(2, entries.size)
        assertEquals("里泽尔", entries[0].name)
        assertEquals(listOf("里泽尔", "草药店", "药铺"), entries[0].keys)
        assertTrue(!entries[0].enabled)
        assertTrue(!entries[0].constant)
        assertEquals("阿黛尔的心事", entries[1].name)
        assertEquals(listOf("阿黛尔"), entries[1].keys)
        assertEquals(listOf("愧疚"), entries[1].secondaryKeys)
        assertEquals(2, entries[1].selectiveLogic)
        assertTrue(entries[1].enabled)
        assertTrue(!entries[1].caseSensitive)
    }

    @Test
    fun lorebookFromCardPngArrayAndMarkdown() {
        val card = """{"spec":"chara_card_v2","data":{"name":"阿黛尔","character_book":{"entries":[
            {"keys":["魔石"],"content":"{{char}}管着仓库。"}]}}}"""
        val fromPng = SillyTavernCardParser.parseLorebook(pngWith("chara" to base64(card)), "card.png")!!
        assertEquals("阿黛尔管着仓库。", fromPng.single().content)

        val array = """[{"keys":["学校"],"content":"{{char}}和{{user}}在等开学。"}]"""
        val fromArray = SillyTavernCardParser.parseLorebook(array.toByteArray(), "x.json", charName = "柯萝伊")!!
        assertEquals("柯萝伊和用户在等开学。", fromArray.single().content)
        assertTrue(!fromArray.single().constant)

        val markdown = """
            开头没有标题的一段。

            # 札尼斯
            学园都市。

            ## 读书会 ##
            每周三。
        """.trimIndent()
        val fromText = SillyTavernCardParser.parseLorebook(markdown.toByteArray(), "notes/札尼斯设定.md")!!
        assertEquals(listOf("札尼斯设定", "札尼斯", "读书会"), fromText.map { it.name })
        assertEquals(listOf("开头没有标题的一段。", "学园都市。", "每周三。"), fromText.map { it.content })
        assertTrue(fromText.all { it.constant })
    }

    @Test
    fun lorebookWithoutEntriesReturnsNull() {
        assertNull(SillyTavernCardParser.parseLorebook(ByteArray(0)))
        assertNull(SillyTavernCardParser.parseLorebook("""{"foo":1}""".toByteArray()))
        assertNull(SillyTavernCardParser.parseLorebook(pngWith()))
    }

    // ---- PNG 构造 ----

    private fun base64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    private fun pngWith(vararg textChunks: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", ByteArray(13))
        textChunks.forEach { (keyword, text) ->
            writeChunk(
                out,
                "tEXt",
                keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
                    text.toByteArray(Charsets.ISO_8859_1)
            )
        }
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val length = data.size
        out.write(byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte()
        ))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(ByteArray(4)) // CRC 填零：解析器按设计不校验
    }
}
