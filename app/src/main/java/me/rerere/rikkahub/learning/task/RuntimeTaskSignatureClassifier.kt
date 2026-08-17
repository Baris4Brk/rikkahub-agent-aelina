package me.rerere.rikkahub.learning.task

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage

/**
 * Foreground counterpart of the admission-time P1 Episode classifier.
 *
 * COMMAND_ADMITTED has no authoritative terminal modality or executed-tool set. Runtime Policy
 * lookup therefore uses only the exact conservative tuple admission can also reconstruct. Tool
 * and modality observations remain Trace evidence; they never rewrite an Episode identity.
 */
object RuntimeTaskSignatureClassifier {
    fun classify(
        @Suppress("UNUSED_PARAMETER") messages: List<UIMessage>,
        @Suppress("UNUSED_PARAMETER") tools: List<Tool>,
    ): TaskSignatureV1 = admissionSignature()

    fun admissionSignature(): TaskSignatureV1 = TaskSignatureV1.create(
        taskClass = LearningTaskClass.OTHER,
        languageClass = LearningLanguageClass.UNKNOWN,
        modalityClass = LearningModalityClass.TEXT_ONLY,
        tools = emptySet(),
    )
}
