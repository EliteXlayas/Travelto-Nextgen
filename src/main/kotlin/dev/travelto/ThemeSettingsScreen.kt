package dev.travelto

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen

internal class ThemeSettingsScreen(parent: Screen) : TraveltoScreen("travelto.theme.title", parent) {
    private lateinit var panel: Box
    private lateinit var wheel: ThemeWheel
    private val hud get() = Travelto.state.hud

    override fun init() {
        val span = (width - 12).coerceIn(1, 236)
        panel = Box((width - span) / 2, (height - 132) / 2, span, 132)
        val left = panel.x + 10
        val side = (span / 3).coerceIn(16, 72)
        val top = panel.y + 25
        wheel = addRenderableWidget(ThemeWheel(Box(panel.right - side - 12, top + 7, side, side)))
        val room = (wheel.x - left - 13).coerceAtLeast(1)
        fun mode() = Ui.label("travelto.theme.bloom", Ui.switchLabel(hud.bloom.enabled))
        lateinit var toggle: GlassButton
        toggle = addRenderableWidget(GlassButton(Box(left, top, room, 20), mode()) {
            hud.bloom.enabled = !hud.bloom.enabled
            toggle.message = mode()
            Travelto.saveConfig()
        })
        addRenderableWidget(PercentSlider(Box(left, top + 24, room, 23), Ui.label("travelto.theme.bloom_size"), BLOOM_SIZE, step = 1,
            format = { "$it px" }, read = { hud.bloom.size }, commit = { hud.bloom.size = it }))
        addRenderableWidget(GlassButton(Box(left, top + 51, room, 18), Ui.label("travelto.action.reset")) {
            hud.accent = DEFAULT_ACCENT
            hud.bloom = BloomOptions()
            toggle.message = mode()
            Travelto.saveConfig()
        })
        addRenderableWidget(GlassButton(Box(left, panel.bottom - 25, room, 17), Ui.label("travelto.action.back")) { onClose() })
    }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        Ui.panel(graphics, panel)
        Ui.text(graphics, getTitle(), panel.x + 10, panel.y + 9)
        val hex = wheel.hex
        Ui.text(graphics, hex, wheel.x + (wheel.width - Ui.body.width(hex)) / 2, wheel.y + wheel.height + 3, Ui.MUTED)
        super.draw(graphics, mouseX, mouseY, delta, now)
    }

    override fun removed() {
        if (::wheel.isInitialized) wheel.finish()
        super.removed()
    }
}
