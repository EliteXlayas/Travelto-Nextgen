package dev.travelto

import com.mojang.realmsclient.RealmsMainScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen
import net.minecraft.client.gui.screens.options.LanguageSelectScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.network.chat.Component

internal class HomeScreen : TraveltoScreen("travelto.title") {
    private var brandX = 0
    private var brandY = 0

    override fun init() {
        val left = (width / 14).coerceIn(10, 56)
        val span = (width - left * 2).coerceIn(1, 196)
        val row = ((height - 78) / 5).coerceIn(14, 29)
        val total = 39 + 5 * row
        val top = ((height - total - 25) / 2).coerceAtLeast(2)
        brandX = left + 1
        brandY = top + 1
        fun entry(index: Int, name: Component, primary: Boolean = false, enabled: Boolean = true, action: () -> Screen) {
            addRenderableWidget(GlassButton(Box(left, top + 39 + index * row, span, row - 4), name, primary) {
                next(action)
            }).active = enabled
        }
        entry(0, Ui.label("travelto.home.singleplayer"), primary = true) { SelectWorldScreen(this) }
        entry(1, Ui.label("travelto.home.multiplayer"), enabled = minecraft.allowsMultiplayer()) {
            if (minecraft.options.skipMultiplayerWarning) JoinMultiplayerScreen(this) else SafetyScreen(this)
        }
        entry(2, Ui.label("travelto.realms"), enabled = minecraft.allowsMultiplayer()) { RealmsMainScreen(this) }
        entry(3, Ui.label("travelto.home.options")) { OptionsScreen(this, minecraft.options) }
        entry(4, Ui.label("travelto.settings")) { SettingsScreen(this) }
        val footerY = (height - 24).coerceAtLeast(top + total + 1)
        var footerX = left
        val footer = listOf<Pair<Component, () -> Unit>>(
            Ui.label("travelto.home.quit") to { leave { minecraft.stop() } },
            Ui.label("travelto.language") to { next { LanguageSelectScreen(this, minecraft.options, minecraft.languageManager) } },
            Ui.label("travelto.vanilla") to { next { VanillaTitle(this) } },
        )
        val idealWidths = footer.map { (label, _) -> maxOf(42, Ui.body.width(label) + 17) }
        val fit = ((width - left - 18).coerceAtLeast(3).toFloat() / idealWidths.sum()).coerceAtMost(1f)
        footer.forEachIndexed { index, (label, action) ->
            val buttonWidth = (idealWidths[index] * fit).toInt().coerceAtLeast(1)
            addRenderableWidget(GlassButton(Box(footerX, footerY, buttonWidth, 17), label, action = action))
            footerX += buttonWidth + 4
        }
    }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        Ui.display(graphics, "Travelto", brandX, brandY)
        super.draw(graphics, mouseX, mouseY, delta, now)
    }

    override fun shouldCloseOnEsc() = false
}

private class VanillaTitle(private val parent: Screen) : TitleScreen() {
    override fun shouldCloseOnEsc() = true
    override fun onClose() { minecraft.gui.setScreen(parent) }
}

internal class SettingsScreen(parent: Screen?) : TraveltoScreen("travelto.settings", parent) {
    private lateinit var bounds: Box
    private lateinit var languageHeading: Box
    private var languageSaveFailed = false

    override fun init() {
        val span = (width - 12).coerceIn(1, 240)
        val row = ((height - 34) / 9).coerceIn(13, 24)
        val panelHeight = 34 + 9 * row
        bounds = Box((width - span) / 2, (height - panelHeight) / 2, span, panelHeight)
        val left = bounds.x + 10
        val contentWidth = (span - 20).coerceAtLeast(1)
        val firstRow = bounds.y + 26
        val state = Travelto.state
        val captions = mutableListOf<Pair<GlassButton, () -> Component>>()
        val choices = linkedMapOf<LanguageMode, GlassButton>()
        fun refresh() {
            captions.forEach { (button, label) -> button.message = label() }
            choices.forEach { (mode, button) -> button.primary = mode == TraveltoLanguage.mode }
        }
        fun rowBox(index: Int) = Box(left, firstRow + row * index, contentWidth, row - 4)
        fun control(index: Int, label: () -> Component, action: () -> Unit) {
            val button = addRenderableWidget(GlassButton(rowBox(index), label(), action = action))
            captions += button to label
        }
        fun control(index: Int, key: String, action: () -> Unit) = control(index, { Ui.label(key) }, action)
        control(0, "travelto.modules") { next { ClickGuiScreen(this) } }
        control(1, "travelto.hud.editor") { next { HudEditor(this) } }
        control(2, "travelto.modernui") { next { FontSettingsScreen(this) } }
        control(3, { Ui.label("travelto.config.blur", Ui.switchLabel(state.blur)) }) {
            state.blur = !state.blur
            refresh()
            Travelto.saveConfig()
        }
        control(4, "travelto.motion.title") { next { MotionScreen(this) } }
        control(5, "travelto.theme.title") { next { ThemeSettingsScreen(this) } }
        languageHeading = rowBox(6)
        val languageRow = rowBox(7)
        val gap = 4
        val available = (languageRow.w - gap * 2).coerceAtLeast(3)
        val systemWidth = (available * 2 / 5).coerceAtLeast(1)
        val chineseWidth = (available - systemWidth) / 2
        var offset = languageRow.x
        LanguageMode.entries.zip(listOf(systemWidth, chineseWidth, available - systemWidth - chineseWidth)).forEach { (mode, size) ->
            val label = { Ui.label("travelto.language.${mode.id}") }
            val button = addRenderableWidget(GlassButton(languageRow.copy(x = offset, w = size), label(), mode == TraveltoLanguage.mode) {
                languageSaveFailed = !TraveltoLanguage.select(mode)
                refresh()
            })
            choices[mode] = button
            captions += button to label
            offset += size + gap
        }
        control(8, "travelto.action.back") { onClose() }
    }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        Ui.panel(graphics, bounds)
        Ui.text(graphics, Ui.fit(getTitle().string, bounds.w - 24), bounds.x + 12, bounds.y + 10)
        val y = languageHeading.y + (languageHeading.h - Ui.textHeight) / 2
        val labelWidth = if (languageSaveFailed) languageHeading.w / 2 else languageHeading.w
        Ui.text(graphics, Ui.fit(Ui.label("travelto.language.title").string, labelWidth), languageHeading.x + 2, y, Ui.MUTED)
        if (languageSaveFailed) {
            val error = Ui.fit(Ui.label("travelto.language.save_failed").string, languageHeading.w / 2 - 4)
            Ui.text(graphics, error, languageHeading.right - Ui.body.width(error) - 2, y, Ui.TEXT)
        }
        super.draw(graphics, mouseX, mouseY, delta, now)
    }
}
