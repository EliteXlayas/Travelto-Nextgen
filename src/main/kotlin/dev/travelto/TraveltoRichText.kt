package dev.travelto

import icyllis.modernui.graphics.Canvas
import icyllis.modernui.graphics.text.FontMetricsInt
import icyllis.modernui.text.SpannableStringBuilder
import icyllis.modernui.text.Spanned
import icyllis.modernui.text.TextPaint
import icyllis.modernui.text.style.RelativeSizeSpan
import icyllis.modernui.text.style.ReplacementSpan
import icyllis.modernui.text.style.StrikethroughSpan
import icyllis.modernui.text.style.StyleSpan
import icyllis.modernui.text.style.TypefaceSpan
import icyllis.modernui.text.style.UnderlineSpan
import java.awt.Font
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator
import kotlin.math.roundToInt

object TraveltoRichText {
    private val names = Regex("[a-z][a-z0-9_.-]{0,39}")
    private val tokens = Regex("\\{\\{([a-z][a-z0-9_.-]{0,39})}}")
    private val embeds = ConcurrentHashMap<String, () -> ReplacementSpan>().apply { put("arc", ::ArcMark) }

    @JvmStatic
    fun registerEmbed(name: String, factory: () -> ReplacementSpan) {
        require(names.matches(name)) { "Invalid inline embed name: $name" }
        require(embeds.putIfAbsent(name, factory) == null) { "Inline embed already registered: $name" }
    }

    internal fun withEmbeds(text: CharSequence): CharSequence {
        val matches = tokens.findAll(text).take(64).toList()
        if (matches.isEmpty()) return text
        val output = SpannableStringBuilder(text)
        for (match in matches.asReversed()) {
            val factory = embeds[match.groupValues[1]] ?: continue
            val start = match.range.first
            output.replace(start, match.range.last + 1, "\uFFFC")
            output.setSpan(factory(), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return output
    }

    internal fun html(input: String): CharSequence {
        val output = SpannableStringBuilder()
        val open = ArrayDeque<Pair<HTML.Tag, Int>>()
        val blocks = setOf(HTML.Tag.P, HTML.Tag.DIV, HTML.Tag.BLOCKQUOTE, HTML.Tag.LI, HTML.Tag.PRE,
            HTML.Tag.H1, HTML.Tag.H2, HTML.Tag.H3, HTML.Tag.H4, HTML.Tag.H5, HTML.Tag.H6, HTML.Tag.TR)
        val headings = setOf(HTML.Tag.H1, HTML.Tag.H2, HTML.Tag.H3, HTML.Tag.H4, HTML.Tag.H5, HTML.Tag.H6)
        fun lineBreak() { if (output.isNotEmpty() && output.last() != '\n') output.append('\n') }
        fun span(style: Any, start: Int) { if (start < output.length) output.setSpan(style, start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        ParserDelegator().parse(StringReader(input), object : HTMLEditorKit.ParserCallback() {
            private var ignored = 0
            override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) {
                    ignored++
                    return
                }
                if (ignored != 0) return
                if (tag in blocks) lineBreak()
                if (tag == HTML.Tag.LI) output.append("• ")
                open.addLast(tag to output.length)
            }

            override fun handleEndTag(tag: HTML.Tag, position: Int) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) {
                    if (ignored > 0) ignored--
                    return
                }
                if (ignored != 0 || open.lastOrNull()?.first != tag) return
                val start = open.removeLast().second
                when (tag) {
                    HTML.Tag.B, HTML.Tag.STRONG -> span(StyleSpan(Font.BOLD), start)
                    HTML.Tag.I, HTML.Tag.EM -> span(StyleSpan(Font.ITALIC), start)
                    HTML.Tag.U, HTML.Tag.A -> span(UnderlineSpan(), start)
                    HTML.Tag.S, HTML.Tag.STRIKE -> span(StrikethroughSpan(), start)
                    HTML.Tag.CODE, HTML.Tag.PRE -> span(TypefaceSpan("monospace"), start)
                }
                if (tag in headings) {
                    span(StyleSpan(Font.BOLD), start)
                    span(RelativeSizeSpan(if (tag == HTML.Tag.H1) 1.35f else 1.15f), start)
                }
                if (tag in blocks) lineBreak()
            }

            override fun handleSimpleTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
                if (ignored != 0) return
                if (tag == HTML.Tag.BR) output.append('\n')
                if (tag == HTML.Tag.IMG) (attributes.getAttribute(HTML.Attribute.ALT) as? String)?.let { output.append(it) }
            }

            override fun handleText(data: CharArray, position: Int) {
                if (ignored == 0) output.append(String(data))
            }
        }, true)
        return output
    }

    private class ArcMark : ReplacementSpan() {
        override fun getSize(paint: TextPaint, text: CharSequence, start: Int, end: Int, metrics: FontMetricsInt?) =
            paint.textSize.roundToInt()

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, baseline: Int, bottom: Int, paint: TextPaint) {
            val ink = icyllis.modernui.graphics.Paint.obtain()
            try {
                val size = paint.textSize
                val accent = Ui.ACCENT
                val bloom = Travelto.state.hud.bloom
                val center = x + size * .5f
                val middle = baseline - size * .35f
                val radius = size * .34f
                if (bloom.enabled) {
                    val spread = minOf(bloom.size * .32f, size * .16f)
                    ink.setColor(Ui.alpha(accent, .13f))
                    canvas.drawPie(center, middle, radius + spread, -55f, 280f, ink)
                    ink.setColor(Ui.alpha(accent, .20f))
                    canvas.drawPie(center, middle, radius + spread * .5f, -55f, 280f, ink)
                }
                ink.setColor(accent)
                canvas.drawPie(center, middle, radius, -55f, 280f, ink)
            } finally { ink.recycle() }
        }
    }
}
