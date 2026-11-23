package dev.travelto

import com.mojang.blaze3d.platform.InputConstants.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.awt.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal class ThemeWheel(
    bounds: Box, label: Component = Ui.label("travelto.hud.color"),
    private val read: () -> Int = { Travelto.state.hud.accent },
    private val write: (Int) -> Unit = { Travelto.state.hud.accent = it },
    private val commit: () -> Unit = Travelto::saveConfig
) : AbstractWidget(bounds.x, bounds.y, bounds.w, bounds.h, label) {
    private enum class Region { HUE, SATURATION_VALUE }
    private var region: Region? = null
    private var applied = -1
    private var dirty = false
    private var hue = 0f
    private var saturation = 0f
    private var brightness = 0f
    private val radius get() = width / 2f
    private val centerX get() = x + radius
    private val centerY get() = y + radius
    private val square get() = radius * .49f
    val hex get() = "#" + read().toString(16).padStart(6, '0').uppercase()

    private fun sync() {
        val rgb = read()
        if (applied == rgb) return
        applied = rgb
        val hsb = Color.RGBtoHSB(rgb ushr 16, (rgb ushr 8) and 255, rgb and 255, null)
        if (hsb[1] > 0f) hue = hsb[0]
        saturation = hsb[1]
        brightness = hsb[2]
    }

    private fun applyColor() {
        applied = Color.HSBtoRGB(hue, saturation, brightness) and 0xffffff
        if (applied != read()) {
            write(applied)
            dirty = true
        }
    }

    fun finish() {
        region = null
        if (dirty) {
            dirty = false
            commit()
        }
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        sync()
        val bounds = Box(x, y, width, height)
        val pureHue = Color.HSBtoRGB(hue, 1f, 1f)
        if (RoundedRect.wheel(graphics, bounds, Ui.tint(pureHue))) {
            val angle = hue * 2.0 * PI
            marker(graphics, centerX + cos(angle).toFloat() * radius * .87f, centerY + sin(angle).toFloat() * radius * .87f, pureHue)
            marker(graphics, centerX + (2f * saturation - 1f) * square, centerY + (1f - 2f * brightness) * square, Ui.accent(read()))
        } else {
            Ui.control(graphics, bounds, 0f, radius = 3)
            Ui.themeBox(graphics, bounds.inset(12), Ui.accent(read()))
        }
        if (isFocused) Ui.themeStroke(graphics, bounds, Ui.alpha(Ui.ACCENT, .75f), 4, 1f)
    }

    private fun marker(graphics: GuiGraphicsExtractor, px: Float, py: Float, color: Int) {
        val ring = Box(px.roundToInt() - 3, py.roundToInt() - 3, 7, 7)
        Ui.box(graphics, ring, Ui.INSET, 4)
        Ui.box(graphics, ring.inset(1), Ui.TEXT, 3)
        Ui.themeBox(graphics, ring.inset(2), color, 2)
    }

    private fun update(event: MouseButtonEvent) {
        val dx = event.x() - centerX
        val dy = event.y() - centerY
        when (region) {
            Region.HUE -> hue = ((atan2(dy, dx) / (2.0 * PI) + 1.0) % 1.0).toFloat()
            Region.SATURATION_VALUE -> {
                saturation = (.5 + dx / (2 * square)).toFloat().coerceIn(0f, 1f)
                brightness = (.5 - dy / (2 * square)).toFloat().coerceIn(0f, 1f)
            }
            null -> return
        }
        applyColor()
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        sync()
        val dx = event.x() - centerX
        val dy = event.y() - centerY
        val distance = hypot(dx, dy)
        region = when {
            distance in (radius * .72)..(radius * 1.02) -> Region.HUE
            kotlin.math.abs(dx) <= square + 2 && kotlin.math.abs(dy) <= square + 2 -> Region.SATURATION_VALUE
            else -> null
        }
        update(event)
    }

    override fun onDrag(event: MouseButtonEvent, dx: Double, dy: Double) = update(event)
    override fun onRelease(event: MouseButtonEvent) { update(event); finish() }
    override fun setFocused(focused: Boolean) {
        if (!focused) finish()
        super.setFocused(focused)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!isFocused || !active) return false
        sync()
        when (event.key()) {
            KEY_LEFT -> hue = (hue + .99f) % 1f
            KEY_RIGHT -> hue = (hue + .01f) % 1f
            KEY_UP -> saturation = (saturation + .04f).coerceAtMost(1f)
            KEY_DOWN -> saturation = (saturation - .04f).coerceAtLeast(0f)
            KEY_PAGEUP -> brightness = (brightness + .04f).coerceAtMost(1f)
            KEY_PAGEDOWN -> brightness = (brightness - .04f).coerceAtLeast(0f)
            else -> return false
        }
        applyColor()
        finish()
        return true
    }

    override fun createNarrationMessage() = Component.literal("${message.string}: $hex")
    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}
