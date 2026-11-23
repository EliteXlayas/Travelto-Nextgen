package dev.travelto

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import kotlin.math.roundToInt

internal class MotionScreen(parent: Screen) : TraveltoScreen("travelto.motion.title", parent) {
    private lateinit var panel: Box
    private lateinit var preview: Box
    private val sample = Motion(0f, 750)
    private var destination = 0f
    private val state get() = Travelto.state

    override fun init() {
        val size = (width - 12).coerceIn(1, 232)
        val step = ((height - 47) / 5).coerceIn(18, 28)
        val tall = 31 + step * 5
        panel = Box((width - size) / 2, (height - tall) / 2, size, tall)
        val span = (size - 20).coerceAtLeast(1)
        fun row(index: Int) = Box(panel.x + 10, panel.y + 24 + step * index, span, step - 3)
        fun replay() {
            destination = if (destination == 0f) 1f else 0f
            sample.toward(destination, System.nanoTime())
        }
        fun curveText() = Ui.label("travelto.motion.curve", Ui.label("travelto.motion.${state.motion.curve.key}").string)
        lateinit var curve: GlassButton
        curve = addRenderableWidget(GlassButton(row(0), curveText()) {
            state.motion.curve = MotionCurve.entries[(state.motion.curve.ordinal + 1) % MotionCurve.entries.size]
            curve.message = curveText()
            replay()
            Travelto.saveConfig()
        })
        addRenderableWidget(PercentSlider(row(1), Ui.label("travelto.motion.global_speed"), MOTION_SPEED,
            read = { state.motion.speed }, commit = { state.motion.speed = it; replay() }))
        fun reducedText() = Ui.label("travelto.config.motion", Ui.switchLabel(state.reducedMotion))
        lateinit var reduced: GlassButton
        reduced = addRenderableWidget(GlassButton(row(2), reducedText()) {
            state.reducedMotion = !state.reducedMotion
            reduced.message = reducedText()
            replay()
            Travelto.saveConfig()
        })
        preview = row(3)
        addRenderableWidget(GlassButton(row(4), Ui.label("travelto.action.back")) { onClose() })
        if (destination == 0f) replay()
    }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        Ui.panel(graphics, panel)
        Ui.text(graphics, getTitle().copy().withStyle(ChatFormatting.BOLD), panel.x + 12, panel.y + 8)
        val rail = Box(preview.x + 7, preview.bottom - 5, (preview.w - 14).coerceAtLeast(1), 2)
        val offset = ((rail.w - 7).coerceAtLeast(0) * sample.toward(destination, now)).roundToInt()
        Ui.text(graphics, Ui.label("travelto.motion.preview"), rail.x, preview.y, Ui.MUTED)
        Ui.box(graphics, rail, Ui.LINE, 1)
        Ui.themeBox(graphics, Box(rail.x + offset, rail.y - 2, minOf(7, rail.w), 6), radius = 2)
        super.draw(graphics, mouseX, mouseY, delta, now)
    }
}
