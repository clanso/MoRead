package com.mozhi.reader.core.datastore

import kotlinx.serialization.json.Json

/** User-editable writing instructions, separate from the quote/JSON contract needed by the reader. */
object ProactiveAnnotationPrompts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    const val VOICE_ID = "annotation-voice"
    const val STYLE_ID = "annotation-style"

    private val VOICE = """
        把批注写成你一边读一边脱口而出的话，像和朋友并肩看同一本书时的小声嘀咕：
        - 用你自己的口吻、立场和经历去反应：惊讶、吐槽、心疼、起哄、犯嘀咕、想起自己的事都行。你不是书评人，不分析写作手法，不总结段落，不说“这段描写很细腻”“作者通过……表现了……”。
        - 长短随心：可以只有一声“啊？”，也可以两三句；别每条都用同一种开头和句式。
        - 敢猜：信息不全的时候就大胆往下猜，猜人物的心思、猜谁在说谎、猜接下来会怎样。猜错没关系，后面真相揭晓时可以回头笑自己，或者得意地说“我就说吧”。
        - 有来有回：记得前面说过的话、猜过的事、开过的玩笑，读到相关的地方就接上。
        - 书里的世界和你的世界不一样时，用你熟悉的东西去理解、去比较，对没见过的东西好奇。
    """.trimIndent()

    private val STYLE = """
        style 按这一处内容的语义选一个，不要整批都用同一种，也不要为了凑齐三种硬选：
        HIGHLIGHT（荧光）：打动你的句子、精彩的描写、想记住的金句。
        WAVY（波浪线）：让你起疑、想猜的地方：线索、伏笔、可疑的话、前后呼应（只凭已经读到的内容，不断言后文）。
        UNDERLINE（直线）：知识点、典故、术语、需要记住的设定或事实。
        没有明显线索或知识点时才选 HIGHLIGHT。
    """.trimIndent()

    /** 以前的内置文本：用户没改过的话，读回时换成新的默认文本；改过的保持原样。 */
    private val LEGACY = mapOf(
        VOICE_ID to setOf(
            "用你自己的口吻和性格写：像你平时和用户说话那样，带着你的语气、态度和关注点，可以感慨、吐槽、提问或联想；不要写成客观的编辑评语，也不要复述或概括段落内容。一两句话，中文。"
        ),
        STYLE_ID to setOf(
            """
                style 必须按这一处内容的语义选择一个，不能把整批段评固定为同一种，也不要随机轮换或为了凑齐三种而强行选择：
                HIGHLIGHT（荧光）：金句、精彩描写、值得回味的段落。
                WAVY（波浪线）：已提供的正文前缀和前文原文中有依据的线索、伏笔、暗线或前后呼应；不能据此断言未读剧情。
                UNDERLINE（直线）：知识点、典故、术语、需要记住的事实或解释。
                没有明显线索或知识点时才选 HIGHLIGHT，不要把它当作所有段评的固定样式。
            """.trimIndent()
        )
    )

    val DEFAULTS = listOf(
        GlobalPromptPreset(id = VOICE_ID, name = "随读口吻", enabled = true, builtIn = true, prompt = VOICE),
        GlobalPromptPreset(id = STYLE_ID, name = "划线风格", enabled = true, builtIn = true, prompt = STYLE)
    )

    fun decode(raw: String?): List<GlobalPromptPreset> = raw?.let {
        runCatching { json.decodeFromString<List<GlobalPromptPreset>>(it) }.getOrNull()
    }?.distinctBy { it.id }?.map(::upgraded) ?: DEFAULTS

    private fun upgraded(preset: GlobalPromptPreset): GlobalPromptPreset {
        val legacy = LEGACY[preset.id] ?: return preset
        if (!preset.builtIn || preset.prompt.trim() !in legacy.map(String::trim)) return preset
        val fresh = DEFAULTS.first { it.id == preset.id }
        return preset.copy(prompt = fresh.prompt)
    }

    fun encode(presets: List<GlobalPromptPreset>): String = json.encodeToString(presets)
}
