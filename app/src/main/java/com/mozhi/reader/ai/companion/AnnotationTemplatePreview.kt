package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.datastore.AnnotationPromptTemplates

/**
 * 设置页「流程提示词」里，每段模板后面应用会自动接上的内容：能照原样给出的就给原文，
 * 随每次请求变化的（字数、条数、伴读名字）用一次典型请求的样子展示。
 */
object AnnotationTemplatePreview {
    fun appended(id: String): String = when (id) {
        AnnotationPromptTemplates.BATCH_RULES ->
            "（以下是一次典型请求的样子：这一段约 5 条、最多 8 条，开了语音，这一行要配图，和阿黛尔一起读）\n\n" +
                sampleBatchContract() +
                "\n\n系统提示词里排在这段规则前面的是角色设定（人设、示例对话、常驻世界书）和段评预设；" +
                "用户消息里依次是书名、【阅读记录】、【接着读】（这一段正文）、【相关设定】（被这一段触发的世界书条目）和上面的【本次要求】。"
        AnnotationPromptTemplates.PROOFREAD ->
            PROOFREAD_CONTRACT +
                "\n\n用户消息里是这一段正文（按行编号 L1、L2……）和刚写的批注（每条标着落在第几行），" +
                "以及程序找出的「可疑词」：批注里出现、却在那一行之后才第一次出现的词。"
        AnnotationPromptTemplates.RECAP ->
            "这一份回顾写 400～600 字。（字数按「回顾篇幅」和这一章的长度算）\n\n用户消息是书名、章节名和这一章（或这一部分）的全文。"
        AnnotationPromptTemplates.ARC ->
            "这一份提要不超过 1200 字。（字数按这几章回顾的总长度算）\n\n用户消息是书名、这几章的范围和每一章的回顾。"
        else -> AnnotationPromptTemplates.template(id)?.appended.orEmpty()
    }
}
