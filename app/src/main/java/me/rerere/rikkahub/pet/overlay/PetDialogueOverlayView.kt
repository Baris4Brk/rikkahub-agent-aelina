package me.rerere.rikkahub.pet.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import me.rerere.rikkahub.pet.MAX_PET_INPUT_CODE_POINTS

internal enum class PetQuickAction { FORTUNE, JOKE, WEATHER }

internal data class PetOverlayTurnUi(
    val userText: String,
    val assistantText: String?,
)

/** Small, focusable application-overlay surface for pet sidecar dialogue. */
internal class PetDialogueOverlayView(
    context: Context,
    private val onSend: (String) -> Unit,
    private val onHandoff: (String) -> Unit,
    private val onQuickAction: (PetQuickAction) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private val title = TextView(context).apply {
        textSize = 17f
        setTextColor(WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subtitle = TextView(context).apply {
        text = "与第二用户绑定 · 最多 20 轮"
        textSize = 11f
        setTextColor(MUTED)
    }
    private val status = TextView(context).apply {
        textSize = 12f
        setTextColor(ACCENT_TEXT)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = rounded(ACCENT_SOFT, 14)
    }
    private val messages = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }
    private val timeline = ScrollView(context).apply {
        isFillViewport = true
        addView(messages, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val input = EditText(context).apply {
        hint = "和桌宠说两句……"
        textSize = 14f
        minLines = 1
        maxLines = 4
        setTextColor(WHITE)
        setHintTextColor(MUTED)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(INPUT, 18, BORDER)
        filters = arrayOf(InputFilter.LengthFilter(MAX_PET_INPUT_CODE_POINTS))
    }
    private val send = actionButton("发送", primary = true)
    private val handoff = actionButton("交给第二用户", primary = false)
    private val quickActions = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
        addView(quickButton("✨ 今日运势", PetQuickAction.FORTUNE), matchWrap(top = dp(8)))
        addView(quickButton("☺ 讲个笑话", PetQuickAction.JOKE), matchWrap(top = dp(8)))
        addView(quickButton("☁ 查天气（交给第二用户）", PetQuickAction.WEATHER), matchWrap(top = dp(8)))
    }
    private val dialogueActions = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.END
        addView(send, LayoutParams(0, dp(44), 0.8f).apply { marginEnd = dp(8) })
        addView(handoff, LayoutParams(0, dp(44), 1.2f))
    }

    init {
        orientation = VERTICAL
        elevation = dp(18).toFloat()
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = rounded(SURFACE, 28, BORDER)

        val avatar = TextView(context).apply {
            text = "宠"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(ACCENT_TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(ACCENT_SOFT, 18)
        }
        val heading = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title, matchWrap())
            addView(subtitle, matchWrap(top = dp(1)))
        }
        val close = TextView(context).apply {
            text = "×"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(MUTED)
            background = rounded(INPUT, 18)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClose() }
        }
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(avatar, LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
                addView(heading, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(close, LayoutParams(dp(38), dp(38)))
            },
            matchWrap(),
        )
        addView(status, matchWrap(top = dp(12)))
        addView(timeline, LayoutParams(LayoutParams.MATCH_PARENT, dp(260)).apply { topMargin = dp(8) })
        addView(input, matchWrap(top = dp(10)))
        addView(dialogueActions, matchWrap(top = dp(10)))
        addView(quickActions, matchWrap(top = dp(4)))

        send.setOnClickListener { consumeInput(onSend) }
        handoff.setOnClickListener { consumeInput(onHandoff) }
    }

    fun showDialogue(name: String) {
        title.text = name.ifBlank { "桌宠短会话" }
        subtitle.text = "短会话侧车 · 最多 20 轮"
        timeline.visibility = VISIBLE
        input.visibility = VISIBLE
        dialogueActions.visibility = VISIBLE
        quickActions.visibility = GONE
        post {
            input.requestFocus()
            timeline.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun showQuickMenu(name: String) {
        title.text = name.ifBlank { "桌宠快捷指令" }
        subtitle.text = "选择一个快捷动作"
        timeline.visibility = GONE
        input.visibility = GONE
        dialogueActions.visibility = GONE
        quickActions.visibility = VISIBLE
    }

    fun renderTurns(turns: List<PetOverlayTurnUi>) {
        messages.removeAllViews()
        if (turns.isEmpty()) {
            messages.addView(messageBubble("还没有对白，先说句话吧。", mine = false))
        } else {
            turns.takeLast(20).forEach { turn ->
                messages.addView(messageBubble(turn.userText, mine = true))
                turn.assistantText?.takeIf { it.isNotBlank() }?.let {
                    messages.addView(messageBubble(it, mine = false))
                }
            }
        }
        timeline.post { timeline.fullScroll(View.FOCUS_DOWN) }
    }

    fun setStatus(text: String, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) ERROR_TEXT else ACCENT_TEXT)
        status.background = rounded(if (error) ERROR_SOFT else ACCENT_SOFT, 14)
    }

    fun setSending(sending: Boolean) {
        input.isEnabled = !sending
        send.isEnabled = !sending
        handoff.isEnabled = !sending
        send.alpha = if (sending) 0.55f else 1f
        handoff.alpha = if (sending) 0.55f else 1f
        send.text = if (sending) "回应中" else "发送"
    }

    fun restoreInput(text: String) {
        input.setText(text)
        input.setSelection(input.length())
    }

    fun focusInput(): View {
        input.requestFocus()
        return input
    }

    private fun consumeInput(action: (String) -> Unit) {
        val value = input.text?.toString().orEmpty().trim()
        if (value.isBlank()) return
        input.setText("")
        action(value)
    }

    private fun quickButton(label: String, action: PetQuickAction) = actionButton(label, false).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onQuickAction(action) }
    }

    private fun actionButton(label: String, primary: Boolean) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(if (primary) Color.rgb(20, 24, 32) else WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(if (primary) ACCENT else INPUT, 16, if (primary) null else BORDER)
        isClickable = true
        isFocusable = true
    }

    private fun messageBubble(text: String, mine: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(WHITE)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(if (mine) USER_BUBBLE else ASSISTANT_BUBBLE, 17)
        maxWidth = dp(310)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(6)
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun matchWrap(top: Int = 0) = LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private companion object {
        val SURFACE = Color.rgb(22, 24, 30)
        val INPUT = Color.rgb(35, 38, 46)
        val BORDER = Color.rgb(57, 61, 73)
        val USER_BUBBLE = Color.rgb(62, 75, 116)
        val ASSISTANT_BUBBLE = Color.rgb(40, 43, 52)
        val ACCENT = Color.rgb(166, 196, 255)
        val ACCENT_SOFT = Color.rgb(42, 53, 78)
        val ACCENT_TEXT = Color.rgb(190, 211, 255)
        val ERROR_SOFT = Color.rgb(79, 41, 45)
        val ERROR_TEXT = Color.rgb(255, 185, 190)
        val MUTED = Color.rgb(166, 170, 181)
        val WHITE = Color.rgb(242, 244, 250)
    }
}
