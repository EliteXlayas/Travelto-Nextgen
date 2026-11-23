package dev.travelto

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.Locale

internal class FontSettingsScreen(parent: Screen?) : TraveltoScreen("travelto.modernui", parent) {
    private lateinit var bounds: Box
    private lateinit var preview: Box
    private lateinit var nextFont: GlassButton
    private lateinit var shadow: GlassButton
    private lateinit var bitmap: GlassButton
    private lateinit var direction: GlassButton
    private val typography get() = Travelto.state.typography
    private val samples = listOf("Travelto 中文  🙂‍↔️  🐦‍🔥", "Latin · العربية · ייִדיש", "नमस्ते · বাংলা · தமிழ் · ไทย")

    override fun init() {
        val span = (width - 12).coerceIn(1, 294)
        val panelHeight = (height - 8).coerceIn(188, 240)
        bounds = Box((width - span) / 2, (height - panelHeight) / 2, span, panelHeight)
        val left = bounds.x + 10
        val contentWidth = (bounds.w - 20).coerceAtLeast(1)
        val step = minOf(22, contentWidth / 6).coerceAtLeast(1)
        preview = Box(left, bounds.y + 25, contentWidth, (panelHeight / 5).coerceIn(36, 49))
        val controls = preview.bottom + 4
        val row = ((bounds.bottom - 51 - controls) / 5).coerceIn(13, 22)
        val buttonHeight = row - 3
        fun control(x: Int, y: Int, size: Int, label: Component, primary: Boolean = false, action: () -> Unit) =
            addRenderableWidget(GlassButton(Box(x, y, size.coerceAtLeast(1), buttonHeight), label, primary, action = action))
        control(left, controls, step, Component.literal("‹")) { cycleFont(-1) }
        val scanWidth = minOf(60, contentWidth / 4)
        nextFont = control(left + step + 3, controls, contentWidth - step - scanWidth - 6, fontLabel()) { cycleFont(1) }
        control(bounds.right - 10 - scanWidth, controls, scanWidth, Ui.label("travelto.font.rescan")) { ModernTypography.refresh() }
        val half = (contentWidth - 4) / 2
        stepper(Box(left, controls + row, half, buttonHeight), step) { typography.size = (typography.size + it * .5f).coerceIn(7f, 12f) }
        stepper(Box(left + half + 4, controls + row, contentWidth - half - 4, buttonHeight), step) {
            typography.baseline = (typography.baseline + it * .5f).coerceIn(4f, 10f)
        }
        shadow = control(left, controls + 2 * row, half, shadowLabel()) {
            typography.shadow = !typography.shadow
            Travelto.saveConfig()
        }
        bitmap = control(left + half + 4, controls + 2 * row, contentWidth - half - 4, bitmapLabel()) {
            typography.bitmap = !typography.bitmap
            Travelto.saveConfig()
        }
        direction = control(left, controls + 3 * row, contentWidth, directionLabel()) {
            val values = ReadingDirection.entries
            typography.direction = values[(typography.direction.ordinal + 1) % values.size]
            Travelto.saveConfig()
        }
        control(left, controls + 4 * row, half, Ui.label("travelto.font.canvas")) { next { ArcPreviewScreen.open(this, minecraft) } }
        control(left + half + 4, controls + 4 * row, contentWidth - half - 4, Ui.label("travelto.action.done"), true) { onClose() }
    }

    private fun stepper(bounds: Box, width: Int, adjust: (Int) -> Unit) {
        fun apply(delta: Int) {
            adjust(delta)
            Travelto.saveConfig()
        }
        addRenderableWidget(GlassButton(bounds.copy(w = width), Component.literal("−")) { apply(-1) })
        addRenderableWidget(GlassButton(bounds.copy(x = bounds.right - width, w = width), Component.literal("+")) { apply(1) })
    }

    private fun cycleFont(direction: Int) {
        val files = ModernTypography.files
        val current = files.indexOf(typography.font) + 1
        val next = Math.floorMod(current + direction, files.size + 1)
        val font = if (next == 0) "" else files[next - 1]
        if (typography.font == font) return
        typography.font = font
        Travelto.saveConfig()
    }

    private fun fontLabel() = Component.literal(typography.font.ifEmpty { "MiSans" })
    private fun shadowLabel() = Ui.label("travelto.font.shadow", Ui.switchLabel(typography.shadow))
    private fun bitmapLabel() = Ui.label("travelto.font.bitmap", Ui.switchLabel(typography.bitmap))
    private fun directionLabel() = Ui.label("travelto.font.direction", Ui.label("travelto.direction.${typography.direction.name.lowercase(Locale.ROOT)}"))

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        Ui.panel(graphics, bounds)
        val screenTitle = getTitle()
        Ui.text(graphics, screenTitle, bounds.x + 10, bounds.y + 9)
        val status = when {
            !ModernTypography.available -> Ui.label("travelto.font.disabled").string
            ModernTypography.loading -> Ui.label("travelto.font.loading").string
            ModernTypography.missing -> Ui.label("travelto.font.fallback").string
            else -> ModernTypography.activeName.orEmpty()
        }
        val availableWidth = bounds.w - Ui.body.width(screenTitle) - 28
        val detail = Ui.fit(status, availableWidth)
        Ui.text(graphics, detail, bounds.right - 10 - Ui.body.width(detail), bounds.y + 9, Ui.MUTED)
        Ui.box(graphics, preview, Ui.INSET)
        Ui.stroke(graphics, preview, Ui.LINE, Ui.CONTROL_RADIUS)
        RoundedRect.clipped(graphics, preview.inset(3)) {
            samples.forEachIndexed { index, sample ->
                val text = if (index == 0) sample else Ui.fit(sample, preview.w - 16)
                Ui.text(graphics, text, preview.x + 8, preview.y + 3 + index * ((preview.h - 6) / samples.size), if (index == 0) Ui.TEXT else Ui.MUTED)
            }
        }
        val labelY = nextFont.y + nextFont.height + 3 + (nextFont.height - Ui.textHeight) / 2
        val cell = (preview.w - 4) / 2
        val room = cell - 2 * minOf(22, preview.w / 6).coerceAtLeast(1) - 4
        centered(graphics, Ui.label("travelto.font.size", typography.size.toString()).string, preview.x + cell / 2, labelY, room)
        centered(graphics, Ui.label("travelto.font.baseline", typography.baseline.toString()).string, preview.right - (preview.w - cell - 4) / 2, labelY, room)
        listOf("original", "port", "fabric").forEachIndexed { index, role ->
            val credit = Ui.label("travelto.font.credits.$role").string
            Ui.text(graphics, Ui.fit(credit, preview.w), preview.x, bounds.bottom - 46 + index * 13, Ui.MUTED)
        }
        super.draw(graphics, mouseX, mouseY, delta, now)
    }

    override fun tick() {
        super.tick()
        nextFont.message = fontLabel()
        shadow.message = shadowLabel()
        bitmap.message = bitmapLabel()
        direction.message = directionLabel()
    }

    private fun centered(graphics: GuiGraphicsExtractor, value: String, x: Int, y: Int, width: Int) {
        val text = Ui.fit(value, width)
        Ui.text(graphics, text, x - Ui.body.width(text) / 2, y, Ui.MUTED)
    }
}
