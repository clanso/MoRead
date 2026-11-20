package com.mozhi.reader.core.datastore

import com.mozhi.reader.ai.companion.AnnotationTemplatePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationPromptTemplatesTest {

    @Test
    fun everyTemplateHasADefaultAndAPreview() {
        val ids = AnnotationPromptTemplates.ALL.map { it.id }
        assertEquals(ids.distinct(), ids)
        listOf(
            AnnotationPromptTemplates.BATCH_RULES, AnnotationPromptTemplates.PROOFREAD, AnnotationPromptTemplates.RECAP,
            AnnotationPromptTemplates.ARC, AnnotationPromptTemplates.PARAGRAPH_RULES, AnnotationPromptTemplates.REPLY_RULES
        ).forEach { id ->
            assertNotNull(AnnotationPromptTemplates.template(id))
            assertTrue(AnnotationPromptTemplates.default(id).isNotBlank())
            assertTrue(AnnotationTemplatePreview.appended(id).isNotBlank())
        }
        // 设置页给出的追加内容就是应用真正接在后面的那一段。
        assertTrue(AnnotationTemplatePreview.appended(AnnotationPromptTemplates.BATCH_RULES).contains("【本次要求】"))
        assertTrue(AnnotationTemplatePreview.appended(AnnotationPromptTemplates.PROOFREAD).contains("\"verdicts\""))
    }

    @Test
    fun decodeKeepsOnlyKnownNonBlankOverrides() {
        val set = AnnotationPromptTemplates.decode(
            """{"batch.rules":"自定义规则","unknown.id":"x","batch.proofread":"   "}"""
        )

        assertEquals(mapOf(AnnotationPromptTemplates.BATCH_RULES to "自定义规则"), set.overrides)
        assertEquals("自定义规则", set.text(AnnotationPromptTemplates.BATCH_RULES))
        assertEquals(AnnotationPromptTemplates.default(AnnotationPromptTemplates.PROOFREAD), set.text(AnnotationPromptTemplates.PROOFREAD))
        assertTrue(set.isCustom(AnnotationPromptTemplates.BATCH_RULES))
        assertFalse(set.isCustom(AnnotationPromptTemplates.PROOFREAD))

        assertEquals(AnnotationTemplateSet(), AnnotationPromptTemplates.decode(null))
        assertEquals(AnnotationTemplateSet(), AnnotationPromptTemplates.decode("坏掉的"))
        assertEquals(set, AnnotationPromptTemplates.decode(AnnotationPromptTemplates.encode(set)))
    }

    @Test
    fun savingTheDefaultOrBlankRestoresIt() {
        val id = AnnotationPromptTemplates.RECAP
        val custom = AnnotationTemplateSet().with(id, "  只写三句。  ")
        assertEquals("只写三句。", custom.overrides[id])

        assertFalse(id in custom.with(id, null).overrides)
        assertFalse(id in custom.with(id, "   ").overrides)
        assertFalse(id in custom.with(id, AnnotationPromptTemplates.default(id) + "\n").overrides)

        val long = AnnotationTemplateSet().with(id, "长".repeat(AnnotationPromptTemplates.MAX_CHARS + 10))
        assertEquals(AnnotationPromptTemplates.MAX_CHARS, long.overrides.getValue(id).length)
    }

    @Test
    fun untouchedLegacyBuiltInPromptsUpgradeAndEditedOnesStay() {
        val legacyVoice = "用你自己的口吻和性格写：像你平时和用户说话那样，带着你的语气、态度和关注点，可以感慨、吐槽、提问或联想；" +
            "不要写成客观的编辑评语，也不要复述或概括段落内容。一两句话，中文。"
        val fresh = ProactiveAnnotationPrompts.DEFAULTS.first { it.id == ProactiveAnnotationPrompts.VOICE_ID }
        val stored = listOf(
            fresh.copy(prompt = legacyVoice, enabled = false),
            ProactiveAnnotationPrompts.DEFAULTS.first { it.id == ProactiveAnnotationPrompts.STYLE_ID }.copy(prompt = "我自己改过的划线规则"),
            GlobalPromptPreset(id = "mine", name = "我的", prompt = legacyVoice, enabled = true)
        )

        val decoded = ProactiveAnnotationPrompts.decode(ProactiveAnnotationPrompts.encode(stored))

        // 没改过的旧内置文本换成新的默认文本，开关照旧。
        assertEquals(fresh.prompt, decoded[0].prompt)
        assertFalse(decoded[0].enabled)
        // 改过的、自己加的都不动。
        assertEquals("我自己改过的划线规则", decoded[1].prompt)
        assertEquals(legacyVoice, decoded[2].prompt)
        assertEquals(ProactiveAnnotationPrompts.DEFAULTS, ProactiveAnnotationPrompts.decode(null))
    }

    @Test
    fun batchSettingsClampToTheirRanges() {
        val low = AnnotationBatchSettings(chunkChars = 10, charsPerNote = 1, windowChars = 1, recapPercent = 0).normalized()
        assertEquals(AnnotationBatchSettings.MIN_CHUNK_CHARS, low.chunkChars)
        assertEquals(AnnotationBatchSettings.MIN_CHARS_PER_NOTE, low.charsPerNote)
        assertEquals(AnnotationBatchSettings.MIN_WINDOW_CHARS, low.windowChars)
        assertEquals(AnnotationBatchSettings.MIN_RECAP_PERCENT, low.recapPercent)

        val high = AnnotationBatchSettings(chunkChars = Int.MAX_VALUE, windowChars = Int.MAX_VALUE, recapPercent = 99).normalized()
        assertEquals(AnnotationBatchSettings.MAX_CHUNK_CHARS, high.chunkChars)
        assertEquals(AnnotationBatchSettings.MAX_WINDOW_CHARS, high.windowChars)
        assertEquals(AnnotationBatchSettings.MAX_RECAP_PERCENT, high.recapPercent)

        assertTrue(AnnotationBatchSettings().batch)
        assertFalse(AnnotationBatchSettings(mode = AnnotationGenerationMode.PARAGRAPH).batch)
    }
}
