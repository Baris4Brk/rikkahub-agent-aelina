package me.rerere.rikkahub.data.ai.prompts

const val LEGACY_ENGLISH_FINAL_ANSWER_REMINDER_PROMPT = """
Continue the same assistant turn. Its final meaningful output is still reasoning rather
than a user-visible answer. Based only on the current user request and the tool results
already completed in this turn, provide a complete, direct, and actionable final answer
now. Text that appeared earlier in the turn does not count when reasoning came after it.

Do not call any tools again. Do not output hidden reasoning or <think> tags. Do not repeat
this reminder, and do not answer only with "done". Output only the answer that should be
shown to the user, and make the final semantic block non-empty answer text. Do not append
reasoning after the answer. If the task could not be fully completed, clearly state what
was completed, what remains, and why.
"""

const val DEFAULT_FINAL_ANSWER_REMINDER_PROMPT = """
继续完成同一轮助手回复。当前回复最后一个有意义的内容仍然是思考，而不是用户可见的
最终回答。请只依据本轮用户请求和已经完成的工具结果，立即给出完整、直接、可执行的
最终回答。如果前面曾出现正文，但后面又继续思考，则前面的正文不算本轮最终回答。

不得再次调用任何工具，不得输出隐藏思考或 <think> 标签，不得复述本提醒，也不得只回答
“完成了”。只输出应当展示给用户的最终回答，并确保整轮最后一个语义块是非空正文，正文
之后不得继续追加思考。如果任务未能完全完成，请明确说明已经完成什么、还剩什么以及原因。

必须沿用当前用户正在使用的语言以及本对话已有的称呼。不得把内部角色标签 USER、user、
ASSISTANT、assistant 或它们的残缺形式（例如 urse）当作用户姓名或称呼。
"""

fun resolveFinalAnswerReminderPrompt(stored: String?): String {
    val candidate = stored?.trim().orEmpty()
    return when {
        candidate.isBlank() -> DEFAULT_FINAL_ANSWER_REMINDER_PROMPT
        candidate == LEGACY_ENGLISH_FINAL_ANSWER_REMINDER_PROMPT.trim() ->
            DEFAULT_FINAL_ANSWER_REMINDER_PROMPT
        else -> stored.orEmpty()
    }
}
