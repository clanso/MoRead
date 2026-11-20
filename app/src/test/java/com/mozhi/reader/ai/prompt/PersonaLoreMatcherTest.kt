package com.mozhi.reader.ai.prompt

import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.PersonaLoreEntry
import com.mozhi.reader.core.database.entity.encodeWorldBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaLoreMatcherTest {

    private fun persona(entries: List<PersonaLoreEntry>, enabled: Boolean = true) = PersonaEntity(
        id = 1,
        name = "阿黛尔",
        personality = "草药店店主。",
        isRoleplay = true,
        worldBookJson = encodeWorldBook(entries),
        worldBookEnabled = enabled,
        createdAt = 0
    )

    private val book = listOf(
        PersonaLoreEntry(name = "草药店", content = "里泽尔的草药店。"),
        PersonaLoreEntry(name = "魔石", content = "魔石的来历。", constant = false, keys = listOf("魔石")),
        PersonaLoreEntry(name = "关掉的", content = "不该出现。", enabled = false),
        PersonaLoreEntry(name = "空的", content = "   ")
    )

    @Test
    fun constantEntriesAlwaysInjectAndTriggeredOnlyOnHit() {
        val persona = persona(book)

        assertEquals(listOf("草药店"), PersonaLoreMatcher.active(persona, "今天天气不错。").map { it.name })
        assertEquals(listOf("草药店", "魔石"), PersonaLoreMatcher.active(persona, "她拿出一颗魔石。").map { it.name })
        assertEquals(listOf("魔石"), PersonaLoreMatcher.triggered(persona, "她拿出一颗魔石。").map { it.name })
        assertEquals(listOf("草药店"), PersonaLoreMatcher.constant(persona).map { it.name })
    }

    @Test
    fun masterSwitchTurnsTheWholeBookOff() {
        val persona = persona(book, enabled = false)

        assertTrue(PersonaLoreMatcher.active(persona, "魔石").isEmpty())
        assertTrue(PersonaLoreMatcher.triggered(persona, "魔石").isEmpty())
        assertTrue(PersonaLoreMatcher.constant(persona).isEmpty())
    }

    @Test
    fun keysIgnoreCaseUnlessTheEntrySaysOtherwise() {
        val loose = PersonaLoreEntry(content = "学园都市。", constant = false, keys = listOf("Zanis"))

        assertTrue(PersonaLoreMatcher.matches(loose, "welcome to ZANIS"))
        assertFalse(PersonaLoreMatcher.matches(loose.copy(caseSensitive = true), "welcome to ZANIS"))
        assertTrue(PersonaLoreMatcher.matches(loose.copy(caseSensitive = true), "welcome to Zanis"))
        assertFalse(PersonaLoreMatcher.matches(loose, "   "))
    }

    @Test
    fun slashWrappedKeysAreRegexesAndBrokenOnesFallBackToLiteral() {
        assertTrue(PersonaLoreMatcher.hit("/魔石(采集|收集)/", "每周的魔石采集停了"))
        assertFalse(PersonaLoreMatcher.hit("/魔石(采集|收集)/", "一颗魔石"))
        assertTrue(PersonaLoreMatcher.hit("/zanis/i", "ZANIS"))
        assertFalse(PersonaLoreMatcher.hit("/zanis/", "ZANIS"))
        assertTrue(PersonaLoreMatcher.hit("/第\\d{2,}章/", "读到第12章了"))

        assertNull(PersonaLoreMatcher.regexOf("/[没闭合/"))
        assertTrue(PersonaLoreMatcher.hit("/[没闭合/", "原文里真的写着 /[没闭合/ 这几个字"))
        assertFalse(PersonaLoreMatcher.hit("/[没闭合/", "没闭合"))
        assertNull(PersonaLoreMatcher.regexOf("魔石"))
    }

    @Test
    fun secondaryKeysFollowSillyTavernLogic() {
        val base = PersonaLoreEntry(
            content = "x", constant = false, keys = listOf("魔石"), secondaryKeys = listOf("采集", "仓库")
        )
        // AND_ANY：主词命中，次要词任意一个。
        assertTrue(PersonaLoreMatcher.matches(base, "魔石采集"))
        assertFalse(PersonaLoreMatcher.matches(base, "一颗魔石"))
        // AND_ALL：次要词全都要有。
        val all = base.copy(selectiveLogic = PersonaLoreMatcher.AND_ALL)
        assertFalse(PersonaLoreMatcher.matches(all, "魔石采集"))
        assertTrue(PersonaLoreMatcher.matches(all, "魔石采集，仓库里"))
        // NOT_ANY：次要词一个都不能有。
        val none = base.copy(selectiveLogic = PersonaLoreMatcher.NOT_ANY)
        assertTrue(PersonaLoreMatcher.matches(none, "一颗魔石"))
        assertFalse(PersonaLoreMatcher.matches(none, "魔石采集"))
        // NOT_ALL：次要词不能全有。
        val notAll = base.copy(selectiveLogic = PersonaLoreMatcher.NOT_ALL)
        assertTrue(PersonaLoreMatcher.matches(notAll, "魔石采集"))
        assertFalse(PersonaLoreMatcher.matches(notAll, "魔石采集，仓库里"))
        // 主词没命中，次要逻辑怎么写都不触发。
        assertFalse(PersonaLoreMatcher.matches(none, "采集"))
    }

    @Test
    fun splitKeysKeepsCommasInsideRegexes() {
        assertEquals(
            listOf("魔石", "采集", "Zanis", "仓库", "a", "b"),
            PersonaLoreMatcher.splitKeys("魔石、采集, Zanis，仓库\na；b")
        )
        assertEquals(listOf("/魔石{1,3}/i", "采集"), PersonaLoreMatcher.splitKeys("/魔石{1,3}/i, 采集"))
        assertEquals(emptyList<String>(), PersonaLoreMatcher.splitKeys(" 、 ,， "))

        val keys = listOf("魔石", "/采集|收集/", "Zanis")
        assertEquals("魔石、/采集|收集/、Zanis", PersonaLoreMatcher.joinKeys(keys))
        assertEquals(keys, PersonaLoreMatcher.splitKeys(PersonaLoreMatcher.joinKeys(keys)))
    }

    @Test
    fun blockRendersNamesAndSkipsEmptyLists() {
        assertEquals("", PersonaLoreMatcher.block(emptyList()))
        assertEquals(
            "【设定集】\n- 草药店：里泽尔的草药店。\n- 无名条目。",
            PersonaLoreMatcher.block(
                listOf(PersonaLoreEntry(name = "草药店", content = "里泽尔的草药店。"), PersonaLoreEntry(content = "无名条目。"))
            )
        )
    }
}
