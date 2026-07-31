package me.rerere.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

fun AnnotatedString.Builder.buildHighlightText(
    token: HighlightToken,
    colors: HighlightTextColorPalette,
) {
    when (token) {
        is HighlightToken.Plain -> append(token.content)
        is HighlightToken.Styled -> {
            withStyle(getStyleForTokenType(token.type, colors)) {
                append(token.content)
            }
        }
    }
}

data class HighlightTextColorPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val operator: Color,
    val punctuation: Color,
    val className: Color,
    val property: Color,
    val boolean: Color,
    val variable: Color,
    val tag: Color,
    val attrName: Color,
    val attrValue: Color,
    val fallback: Color,
) {
    companion object {
        val Default = HighlightTextColorPalette(
            keyword = Color(0xFFCC7832),
            string = Color(0xFF6A8759),
            number = Color(0xFF6897BB),
            comment = Color(0xFF808080),
            function = Color(0xFFFFC66D),
            operator = Color(0xFFCC7832),
            punctuation = Color(0xFFCC7832),
            className = Color(0xFFCB772F),
            property = Color(0xFFCB772F),
            boolean = Color(0xFF6897BB),
            variable = Color(0xFF6A8759),
            tag = Color(0xFFE8BF6A),
            attrName = Color(0xFFBABABA),
            attrValue = Color(0xFF6A8759),
            fallback = Color(0xFF808080),
        )
    }
}

/**
 * Resolves a token scope to a span style.
 *
 * Scopes follow the `highlight.js` vocabulary and can be tiered, such as `title.function` or
 * `char.escape`. An unknown tier falls back to its parent scope, which is what the upstream CSS
 * themes do by emitting one class per tier.
 */
private fun getStyleForTokenType(
    type: String,
    colors: HighlightTextColorPalette,
): SpanStyle {
    var scope = type
    while (true) {
        styleForScope(scope, colors)?.let { return it }
        val separator = scope.lastIndexOf('.')
        if (separator == -1) return SpanStyle(color = colors.fallback)
        scope = scope.substring(0, separator)
    }
}

/**
 * Colours follow the Atom One theme, the same palette the upstream stylesheet of that name uses,
 * so several scopes deliberately share a slot.
 */
private fun styleForScope(
    scope: String,
    colors: HighlightTextColorPalette,
): SpanStyle? = when (scope) {
    "comment", "quote" -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)

    "keyword", "doctag", "formula" -> SpanStyle(color = colors.keyword)

    "string", "addition" -> SpanStyle(color = colors.string)

    "attribute", "attr-value" -> SpanStyle(color = colors.attrValue)

    "attr", "attr-name", "template-variable" -> SpanStyle(color = colors.attrName)

    "number", "selector-class", "selector-attr", "selector-pseudo" ->
        SpanStyle(color = colors.number)

    "literal", "boolean", "constant" -> SpanStyle(color = colors.boolean)

    "char", "operator" -> SpanStyle(color = colors.operator)

    "built_in", "type", "class-name", "title.class", "title.class.inherited" ->
        SpanStyle(color = colors.className)

    "title", "title.function", "function", "method", "symbol", "bullet", "link", "meta", "selector-id" ->
        SpanStyle(color = colors.function)

    "section", "name", "selector-tag", "deletion", "subst", "property" ->
        SpanStyle(color = colors.property)

    "tag" -> SpanStyle(color = colors.tag)

    "variable", "regexp", "regex", "important" -> SpanStyle(color = colors.variable)

    "punctuation", "params" -> SpanStyle(color = colors.punctuation)

    "emphasis" -> SpanStyle(color = colors.fallback, fontStyle = FontStyle.Italic)

    "strong" -> SpanStyle(color = colors.fallback, fontWeight = FontWeight.Bold)

    else -> null
}
