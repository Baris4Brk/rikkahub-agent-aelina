package me.rerere.rikkahub.learning.task

import me.rerere.rikkahub.learning.model.LearningCanonicalId

enum class LearningTaskClass {
    INFORMATION,
    CODE_CHANGE,
    FILE_OPERATION,
    COMMUNICATION,
    AUTOMATION,
    DEVICE_CONTROL,
    OTHER,
}

enum class LearningLanguageClass {
    CHINESE,
    ENGLISH,
    MIXED,
    OTHER,
    UNKNOWN,
}

enum class LearningModalityClass {
    TEXT_ONLY,
    IMAGE_PRESENT,
    AUDIO_PRESENT,
    DOCUMENT_PRESENT,
    MIXED_MEDIA,
}

data class LearningToolSignature(
    val canonicalToolName: String,
    val schemaSha256: String,
) {
    init {
        require(canonicalToolName.matches(Regex("[a-z][a-z0-9_.-]{0,95}"))) {
            "Invalid canonical tool name"
        }
        require(schemaSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid tool schema digest" }
    }
}

/** Weak clustering/retrieval feature. It is never a scope, grant, authority or permission key. */
@JvmInline
value class TaskSignatureV1 private constructor(val value: String) {
    override fun toString(): String = "TaskSignatureV1(<opaque>)"

    companion object {
        const val ALGORITHM_VERSION: Int = 1

        fun create(
            taskClass: LearningTaskClass,
            languageClass: LearningLanguageClass,
            modalityClass: LearningModalityClass,
            tools: Set<LearningToolSignature>,
        ): TaskSignatureV1 {
            require(tools.size <= 16) { "Too many tools in task signature" }
            val sortedTools = tools.sortedWith(
                compareBy(LearningToolSignature::canonicalToolName)
                    .thenBy(LearningToolSignature::schemaSha256),
            )
            val digest = LearningCanonicalId.digest(
                domainVersion = "task-signature-v1",
                fields = buildList {
                    add(taskClass.name)
                    add(languageClass.name)
                    add(modalityClass.name)
                    sortedTools.forEach { tool ->
                        add(tool.canonicalToolName)
                        add(tool.schemaSha256)
                    }
                },
            )
            return TaskSignatureV1("task-signature-v1:$digest")
        }

        fun parseOrNull(value: String): TaskSignatureV1? =
            value.takeIf { it.matches(Regex("task-signature-v1:[0-9a-f]{64}")) }
                ?.let(::TaskSignatureV1)
    }
}
