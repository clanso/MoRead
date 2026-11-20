package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable

@Serializable
enum class AnnotationContextMode(val label: String, val chars: Int) {
    ECONOMY("省流", 8_000), BALANCED("均衡", 16_000), FULL("充分", 32_000), CUSTOM("自定义", 0)
}

/**
 * 阅读资料预算含本章正文、目标段落、前文片段和梗概；角色与写作提示词另计（逐段模式）。
 * [personaMaxChars] 是角色身份块（人设、示例对话、世界书）进入段评提示词的上限，
 * [UNLIMITED] = 不截断。旧版固定截到 2400 字，长人设的世界书整段被截掉，见 PersonaLoreMatcher。
 */
@Serializable
data class ProactiveAnnotationContextSettings(
    val mode: AnnotationContextMode = AnnotationContextMode.BALANCED,
    val customChars: Int = 24_000,
    val personaMaxChars: Int = UNLIMITED
) {
    val budgetChars: Int get() = if (mode == AnnotationContextMode.CUSTOM) {
        customChars.coerceIn(MIN_CHARS, MAX_CHARS)
    } else mode.chars

    val personaUnlimited: Boolean get() = personaMaxChars == UNLIMITED

    fun normalized() = copy(
        customChars = customChars.coerceIn(MIN_CHARS, MAX_CHARS),
        personaMaxChars = if (personaMaxChars == UNLIMITED) UNLIMITED
        else personaMaxChars.coerceIn(MIN_PERSONA_CHARS, MAX_PERSONA_CHARS)
    )

    companion object {
        const val MIN_CHARS = 4_000
        const val MAX_CHARS = 64_000
        const val UNLIMITED = -1
        const val MIN_PERSONA_CHARS = 800
        const val MAX_PERSONA_CHARS = 200_000
    }
}
