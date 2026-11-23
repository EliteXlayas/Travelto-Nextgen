package dev.travelto

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen

internal class FreeCamSettingsScreen(parent: Screen) : TraveltoScreen("travelto.freecam.settings", parent) {
    private lateinit var panel: Box
    private var sectionY = 0
    private val options get() = Travelto.state.freeCam

    override fun init() {
        val span = (width - 12).coerceIn(1, 252)
        val stride = ((height - 59) / 4).coerceIn(22, 28)
        val tall = 48 + 4 * stride
        panel = Box((width - span) / 2, (height - tall) / 2, span, tall)
        val left = panel.x + 10
        val inner = (span - 20).coerceAtLeast(1)
        val top = panel.y + 24
        addRenderableWidget(PercentSlider(Box(left, top, inner, stride - 3), Ui.label("travelto.freecam.speed"), FREE_CAM_SPEED,
            step = 2, format = { Ui.label("travelto.freecam.blocks_per_second", it).string },
            read = { options.speed }, commit = { options.speed = it }))

        sectionY = top + stride + 3
        val cell = ((inner - 6) / 2).coerceAtLeast(1)
        for ((index, reason) in FreeCamCancel.entries.withIndex()) {
            val bounds = Box(left + (index % 2) * (cell + 6), sectionY + 15 + (index / 2) * stride, cell, stride - 4)
            fun label() = Ui.label("travelto.freecam.cancel.value", Ui.label("travelto.freecam.cancel.${reason.key}").string,
                Ui.switchLabel(reason in options.cancelOn))
            lateinit var control: GlassButton
            control = addRenderableWidget(GlassButton(bounds, label()) {
                if (!options.cancelOn.add(reason)) options.cancelOn.remove(reason)
                control.message = label()
                Travelto.saveConfig()
            })
        }
        addRenderableWidget(GlassButton(Box(left, sectionY + 15 + 2 * stride, inner, stride - 3), Ui.label("travelto.action.back")) { onClose() })
    }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        Ui.panel(graphics, panel)
        Ui.text(graphics, HudEffects.bold(getTitle().string), panel.x + 10, panel.y + 8)
        Ui.text(graphics, Ui.label("travelto.freecam.cancel"), panel.x + 10, sectionY, Ui.MUTED)
        super.draw(graphics, mouseX, mouseY, delta, now)
    }
}
