package me.rerere.highlight

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map

val LocalHighlighter = compositionLocalOf<Highlighter> { error("No Highlighter provided") }

@OptIn(FlowPreview::class)
@Composable
fun HighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = LocalHighlighter.current
    val tracker = remember { HighlightRequestTracker() }
    var annotatedString by remember { mutableStateOf(AnnotatedString(code)) }

    val updatedCode by rememberUpdatedState(code)
    val updatedLanguage by rememberUpdatedState(language)
    val updatedColors by rememberUpdatedState(colors)
    LaunchedEffect(highlighter) {
        snapshotFlow { Triple(updatedCode, updatedLanguage, updatedColors) }
            // Allocate a request id before debounce so a streaming append invalidates an older
            // result immediately, even while the replacement request is still coalescing.
            .map { tracker.next() to it }
            .debounce(120)
            .collectLatest { (requestId, request) ->
                val (requestCode, requestLanguage, requestColors) = request
                val result = runCatching {
                    if (exceedsHighlightBudget(requestCode)) {
                        AnnotatedString(requestCode)
                    } else {
                        val tokens = highlighter.highlight(requestCode, requestLanguage)
                        buildAnnotatedString {
                            tokens.forEach { token -> buildHighlightText(token, requestColors) }
                        }
                    }
                }.getOrElse { AnnotatedString(requestCode) }

                if (tracker.isCurrent(requestId)) {
                    annotatedString = result
                }
            }
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
