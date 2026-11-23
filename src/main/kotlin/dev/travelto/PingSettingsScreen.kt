package dev.travelto

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen

internal class PingSettingsScreen(parent: Screen) : TraveltoScreen("travelto.ping.settings", parent) {
    private lateinit var panel: Box
    private lateinit var field: Box
    private lateinit var note: Box
    private lateinit var soundInput: EditBox
    private lateinit var trySound: GlassButton
    private var error = false
    private val options get() = Travelto.state.ping

    override fun init() {
        val span = (width - 12).coerceIn(1, 275)
        val stride = ((height - 49) / 6).coerceIn(18, 27)
        val tall = 30 + stride * 6
        panel = Box((width - span) / 2, (height - tall) / 2, span, tall)
        fun row(index: Int) = Box(panel.x + 10, panel.y + 23 + index * stride, (span - 20).coerceAtLeast(1), stride - 3)
        addRenderableWidget(GlassButton(row(0), Ui.label("travelto.ping.key", Travelto.pingKey.translatedKeyMessage.string)) {
            next { FilteredKeyBindsScreen(this, minecraft.options) }
        })
        addRenderableWidget(PercentSlider(row(1), Ui.label("travelto.ping.range"), PING_RANGE, step = 4,
            format = { Ui.label("travelto.unit.blocks", it).string }, read = { options.range }, commit = { options.range = it }))
        addRenderableWidget(PercentSlider(row(2), Ui.label("travelto.ping.duration"), PING_LIFETIME, step = 1,
            format = { Ui.label("travelto.unit.seconds", it).string }, read = { options.seconds }, commit = { options.seconds = it }))
        addRenderableWidget(PercentSlider(row(3), Ui.label("travelto.ping.volume"), PING_VOLUME,
            read = { options.volume }, commit = { options.volume = it }))
        val buttons = row(4)
        val actionWidth = (buttons.w / 3).coerceIn(1, 82)
        field = buttons.copy(w = (buttons.w - actionWidth - 4).coerceAtLeast(1))
        val hint = Ui.label("travelto.ping.sound")
        soundInput = addRenderableWidget(EditBox(Ui.body, field.x + 7, field.y + (field.h - Ui.textHeight) / 2,
            (field.w - 14).coerceAtLeast(1), Ui.textHeight + 1, hint).apply {
            setBordered(false)
            setTextShadow(false)
            setHint(hint)
            setMaxLength(128)
            value = options.sound
            setResponder { error = false }
        })
        trySound = addRenderableWidget(GlassButton(Box(buttons.right - actionWidth, buttons.y, actionWidth, buttons.h), Ui.label("travelto.ping.preview")) { applySound(true) })
        val bottom = row(5)
        val done = (bottom.w / 3).coerceIn(1, 76)
        note = bottom.copy(w = (bottom.w - done - 6).coerceAtLeast(1))
        addRenderableWidget(GlassButton(Box(bottom.right - done, bottom.y, done, bottom.h), Ui.label("travelto.action.done")) {
            if (applySound(false)) onClose()
        })
    }

    private fun applySound(preview: Boolean): Boolean {
        val id = PingMarker.resolveSound(minecraft, soundInput.value)
        if (id == null) {
            error = preview || soundInput.value != options.sound
            return !error
        }
        val sound = id.toString()
        if (options.sound != sound) {
            options.sound = sound
            Travelto.saveConfig()
        }
        soundInput.value = sound
        error = false
        if (preview) PingMarker.playSound(minecraft, id, options.volume)
        return true
    }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        Ui.panel(graphics, panel)
        Ui.text(graphics, HudEffects.bold(getTitle().string), panel.x + 10, panel.y + 8)
        Ui.box(graphics, field, Ui.CONTROL)
        if (soundInput.isFocused && !error) Ui.themeStroke(graphics, field)
        else Ui.stroke(graphics, field, if (error) HudEffects.DAMAGE else Ui.LINE, Ui.CONTROL_RADIUS)
        soundInput.setTextColor(Ui.tint(Ui.TEXT))
        val foot = Ui.fit(Ui.label(if (error) "travelto.ping.sound_invalid" else "travelto.ping.local").string, note.w - 2)
        Ui.text(graphics, foot, note.x + 2, note.y + (note.h - Ui.textHeight) / 2, if (error) HudEffects.DAMAGE else Ui.MUTED)
        super.draw(graphics, mouseX, mouseY, delta, now)
    }
}
