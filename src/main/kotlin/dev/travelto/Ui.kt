package dev.travelto

import com.mojang.blaze3d.platform.InputConstants.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

internal data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    fun inset(amount: Int) = Box(x + amount, y + amount, w - 2 * amount, h - 2 * amount)
    fun contains(px: Double, py: Double) = px >= x && px < right && py >= y && py < bottom
    fun intersection(other: Box): Box? {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val span = minOf(right, other.right) - left
        val height = minOf(bottom, other.bottom) - top
        return if (span > 0 && height > 0) Box(left, top, span, height) else null
    }
}

internal object Ui {
    const val CANVAS = 0xff08090d.toInt()
    const val TEXT = 0xffedeef2.toInt()
    const val MUTED = 0xffc0c0d0.toInt()
    const val SURFACE_ALPHA = 210
    private const val SURFACE_MASK = SURFACE_ALPHA shl 24
    const val INSET = SURFACE_MASK or 0x080808
    const val PANEL = SURFACE_MASK or 0x111111
    const val CONTROL = SURFACE_MASK or 0x1b1b1b
    const val LINE = 0x30eeeeee
    private const val EMPHASIZED_CONTROL = SURFACE_MASK or 0x272727
    val ACCENT get() = accent()
    val HOVER get() = alpha(ACCENT, .22f)
    const val CARD_RADIUS = 12
    const val CONTROL_RADIUS = 3
    private var opacity = 1f
    val body get() = Minecraft.getInstance().font
    val textHeight get() = Travelto.state.typography.size.roundToInt() + 1

    fun accent(rgb: Int = Travelto.state.hud.accent) = rgb or 0xff000000.toInt()
    fun mix(base: Int, target: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val from = (base ushr shift) and 255
            val to = (target ushr shift) and 255
            return (from + (to - from) * amount).roundToInt() shl shift
        }
        return (base and 0xff000000.toInt()) or channel(16) or channel(8) or channel(0)
    }
    fun label(key: String, vararg arguments: Any) = TraveltoLanguage.label(key, *arguments)
    fun switchLabel(enabled: Boolean) = label(if (enabled) "travelto.state.on" else "travelto.state.off").string
    fun alpha(color: Int, strength: Float) = (color and 0xffffff) or (((color ushr 24) * strength.coerceIn(0f, 1f)).roundToInt() shl 24)
    fun tint(color: Int) = alpha(color, opacity)

    fun faded(amount: Float, draw: () -> Unit) {
        val previous = opacity
        opacity *= amount.coerceIn(0f, 1f)
        try { draw() } finally { opacity = previous }
    }

    fun box(graphics: GuiGraphicsExtractor, bounds: Box, color: Int, radius: Int = CONTROL_RADIUS) =
        RoundedRect.draw(graphics, bounds, radius.toFloat(), tint(color))

    fun stroke(graphics: GuiGraphicsExtractor, bounds: Box, color: Int, radius: Int, thickness: Float = .75f) =
        RoundedRect.draw(graphics, bounds, radius.toFloat(), tint(color), thickness)

    fun shadow(graphics: GuiGraphicsExtractor, bounds: Box, color: Int, radius: Int, spread: Float) =
        RoundedRect.draw(graphics, bounds, radius.toFloat(), tint(color), -spread)

    fun bloom(graphics: GuiGraphicsExtractor, bounds: Box, color: Int, radius: Int, strength: Float = .40f) {
        val settings = Travelto.state.hud.bloom
        if (settings.enabled) shadow(graphics, bounds, alpha(color, strength), radius, settings.size.toFloat())
    }

    fun themeBox(graphics: GuiGraphicsExtractor, bounds: Box, color: Int = ACCENT, radius: Int = CONTROL_RADIUS) {
        bloom(graphics, bounds, color, radius)
        box(graphics, bounds, color, radius)
    }

    fun themeStroke(graphics: GuiGraphicsExtractor, bounds: Box, color: Int = ACCENT, radius: Int = CONTROL_RADIUS, thickness: Float = .75f) {
        bloom(graphics, bounds, color, radius, .28f)
        stroke(graphics, bounds, color, radius, thickness)
    }

    fun panel(graphics: GuiGraphicsExtractor, bounds: Box, surface: Int = PANEL, radius: Int = CARD_RADIUS) {
        shadow(graphics, bounds.copy(y = bounds.y + 3), 0x76000000, radius, 9f)
        box(graphics, bounds, surface, radius)
        stroke(graphics, bounds, LINE, radius)
    }

    fun text(graphics: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int = TEXT, shadow: Boolean = Travelto.state.typography.shadow) {
        val tinted = tint(color)
        if (tinted ushr 24 > 3) graphics.text(body, text, x, y, tinted, shadow)
    }

    fun text(graphics: GuiGraphicsExtractor, text: Component, x: Int, y: Int, color: Int = TEXT, shadow: Boolean = Travelto.state.typography.shadow) {
        val tinted = tint(color)
        if (tinted ushr 24 > 3) graphics.text(body, text, x, y, tinted, shadow)
    }

    fun image(graphics: GuiGraphicsExtractor, image: Identifier, bounds: Box, textureSize: Int) {
        val color = tint(-1)
        if (color ushr 24 > 3) graphics.blit(RenderPipelines.GUI_TEXTURED, image, bounds.x, bounds.y,
            0f, 0f, bounds.w, bounds.h, textureSize, textureSize, textureSize, textureSize, color)
    }

    fun control(graphics: GuiGraphicsExtractor, bounds: Box, highlight: Float, focused: Boolean = false,
                active: Boolean = true, primary: Boolean = false, radius: Int = CONTROL_RADIUS) {
        if (active) {
            val shine = when { focused -> .45f; primary -> .31f; else -> highlight * .19f }
            if (shine > 0f) bloom(graphics, bounds, ACCENT, radius, shine)
        }
        val surface = when {
            !active -> PANEL
            primary -> EMPHASIZED_CONTROL
            else -> CONTROL
        }
        box(graphics, bounds, if (active && highlight > 0f) mix(surface, ACCENT, highlight * .22f) else surface, radius)
        stroke(graphics, bounds, if (focused && active) ACCENT else if (primary && active) alpha(ACCENT, .70f) else LINE,
            radius, if (focused) 1.25f else .75f)
    }

    fun display(graphics: GuiGraphicsExtractor, value: String, x: Int, y: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(29f / Travelto.state.typography.size)
        try { text(graphics, value, 0, 0) } finally { pose.popMatrix() }
    }

    fun fit(text: String, width: Int): String {
        if (width <= 0) return ""
        if (body.width(text) <= width) return text
        val ellipsis = "…"
        val remaining = width - body.width(ellipsis)
        return if (remaining <= 0) "" else body.plainSubstrByWidth(text, remaining) + ellipsis
    }

    fun soften(graphics: GuiGraphicsExtractor, text: Component, x: Int, y: Int, glow: Boolean,
               color: Int = TEXT, glowColor: Int = ACCENT) {
        val bloom = Travelto.state.hud.bloom
        if (glow && bloom.enabled) {
            val pose = graphics.pose()
            val spread = .65f + (bloom.size - 4) * .15f
            for ((dx, dy) in glowOffsets) {
                pose.pushMatrix(); pose.translate(dx * spread, dy * spread)
                try { text(graphics, text, x, y, alpha(glowColor, .16f), shadow = false) }
                finally { pose.popMatrix() }
            }
        }
        text(graphics, text, x, y, color)
    }

    private val glowOffsets = listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)

    fun toggle(graphics: GuiGraphicsExtractor, bounds: Box, value: Float) {
        bloom(graphics, bounds, ACCENT, bounds.h / 2, value * .40f)
        box(graphics, bounds, mix(SURFACE_MASK or 0x343434, ACCENT, value), bounds.h / 2)
        val size = bounds.h - 4
        val thumb = 2 + ((bounds.w - size - 4) * value).roundToInt()
        box(graphics, Box(bounds.x + thumb, bounds.y + 2, size, size), TEXT, size / 2 + 1)
    }

    fun disclosure(graphics: GuiGraphicsExtractor, bounds: Box, value: Float, highlight: Float) {
        box(graphics, bounds, 0x1bedeef2)
        themeBox(graphics, bounds, alpha(HOVER, highlight))
        val midX = bounds.x + bounds.w / 2
        val midY = bounds.y + bounds.h / 2
        box(graphics, Box(midX - 3, midY, 7, 1), TEXT, 1)
        val arm = ((1f - value) * 3f).roundToInt()
        if (arm > 0) box(graphics, Box(midX, midY - arm, 1, arm * 2 + 1), alpha(TEXT, 1f - value), 1)
    }

    fun worldOverlay(graphics: GuiGraphicsExtractor, amount: Float) {
        if (amount <= 0f) return
        if (amount > .45f && Travelto.state.blur && Minecraft.getInstance().options.menuBackgroundBlurriness > 0) {
            graphics.nextStratum(); graphics.blurBeforeThisStratum()
        }
        graphics.fillGradient(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha(0x9c000000.toInt(), amount), alpha(0xc9000000.toInt(), amount))
    }
}

internal class GlassButton(bounds: Box, label: Component, var primary: Boolean = false,
                           private val radius: Int = Ui.CONTROL_RADIUS, private val action: () -> Unit) :
    AbstractWidget(bounds.x, bounds.y, bounds.w, bounds.h, label) {
    private val hover = Motion(0f, 145)

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val highlight = hover.toward(if (isHoveredOrFocused && active) 1f else 0f, System.nanoTime())
        val bounds = Box(x, y, width, height)
        Ui.control(graphics, bounds, highlight, isFocused, active, primary, radius)
        val fitted = Ui.fit(message.string, width - 12)
        val text = if (fitted == message.string) message else Component.literal(fitted)
        Ui.text(graphics, text, x + (width - Ui.body.width(text)) / 2, y + (height - Ui.textHeight) / 2, if (active) Ui.TEXT else Ui.MUTED)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) = action()
    override fun keyPressed(event: KeyEvent): Boolean {
        if (active && isFocused && event.key() in listOf(KEY_RETURN, KEY_NUMPADENTER, KEY_SPACE)) {
            playDownSound(Minecraft.getInstance().soundManager); action(); return true
        }
        return false
    }
    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}
