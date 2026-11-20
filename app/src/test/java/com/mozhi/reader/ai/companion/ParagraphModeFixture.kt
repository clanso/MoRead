package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.datastore.AnnotationBatchSettings
import com.mozhi.reader.core.datastore.AnnotationGenerationMode

/** 调度器的旧测试测的是逐段模式；默认改成整段之后，它们要显式选回逐段。 */
internal val PARAGRAPH_MODE = AnnotationBatchSettings(mode = AnnotationGenerationMode.PARAGRAPH)
