package dev.travelto

import com.mojang.blaze3d.platform.InputConstants.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

internal class PercentSlider(bounds: Box, label: Component, private val limits: IntRange, private val step: Int = 5,
                             private val radius: Int = Ui.CONTROL_RADIUS, private val railColor: () -> Int = { Ui.ACCENT },
                             private val format: (Int) -> String = { "$it%" },
                             private val read: () -> Int, private val commit: (Int) -> Unit) :
    AbstractWidget(bounds.x, bounds.y, bounds.w, bounds.h, label) {
    private var pending: Int? = null
    private val hover = Motion(0f, 145)
    private val inset = 6
    private val railWidth get() = (width - 2 * inset).coerceAtLeast(1)
    private val value get() = (pending ?: read()).coerceIn(limits)

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val bounds = Box(x, y, width, height)
        Ui.control(graphics, bounds, hover.toward(if (isHoveredOrFocused || pending != null) 1f else 0f, System.nanoTime()),
            isFocused, active, radius = radius)
        val percent = format(value)
        val span = Ui.body.width(percent)
        val name = Ui.fit(message.string, width - span - 3 * inset)
        Ui.text(graphics, name, x + inset, y + 3, Ui.MUTED)
        Ui.text(graphics, percent, bounds.right - inset - span, y + 3)
        val rail = Box(x + inset, bounds.bottom - 6, railWidth, 2)
        val length = (rail.w * (value - limits.first).toFloat() / (limits.last - limits.first)).roundToInt()
        Ui.box(graphics, rail, Ui.LINE, 1)
        Ui.themeBox(graphics, rail.copy(w = length), railColor(), 1)
        Ui.box(graphics, Box(rail.x + length - 2, rail.y - 1, 4, 4), Ui.TEXT, 2)
    }

    private fun update(position: Double) {
        val fraction = ((position - x - inset) / railWidth).coerceIn(0.0, 1.0)
        pending = (limits.first + (fraction * (limits.last - limits.first) / step).roundToInt() * step).coerceIn(limits)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) = update(event.x())
    override fun onDrag(event: MouseButtonEvent, dx: Double, dy: Double) { if (pending != null) update(event.x()) }
    override fun onRelease(event: MouseButtonEvent) {
        if (pending == null) return
        update(event.x())
        val next = value
        pending = null
        apply(next)
    }

    private fun apply(next: Int) {
        if (next == read()) return
        commit(next)
        Travelto.saveConfig()
    }

    override fun setFocused(focused: Boolean) {
        if (!focused) pending = null
        super.setFocused(focused)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!isFocused || !active) return false
        val next = when (event.key()) {
            KEY_LEFT -> value - step
            KEY_RIGHT -> value + step
            KEY_HOME -> limits.first
            KEY_END -> limits.last
            else -> return false
        }
        apply(next.coerceIn(limits))
        return true
    }

    override fun createNarrationMessage() = Component.literal("${message.string}: ${format(value)}")
    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}
