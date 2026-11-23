package dev.travelto

import com.mojang.blaze3d.platform.InputConstants.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import kotlin.math.floor
import kotlin.math.roundToInt

internal class ClickGuiScreen(private val parent: Screen?, private var shortcutHeld: Boolean = false) :
    TraveltoScreen("travelto.modules", parent) {
    private enum class Entry { MODULE, BIND, MODE, LAYOUT, GLOW, MOTION, PING_SETTINGS, FREE_CAM_SETTINGS }
    private data class Drag(val category: Category, val x: Double, val y: Double, var changed: Boolean)
    private data class Row(val module: Module, val entry: Entry, val bounds: Box, val clip: Box, val reveal: Float)
    private data class Panel(val category: Category, val bounds: Box, val viewport: Box, val rows: List<Row>, val reveal: Float, val scroll: Int, val maxScroll: Int)
    private data class Results(val entries: List<Module>, val selected: Int)
    private data class Popup(val bounds: Box, val results: Results)

    private val state get() = Travelto.state
    private val categories = Category.entries.associateWith { Motion(if (state.panel(it).open) 1f else 0f, 220) }
    private val modules = Module.entries.associateWith { Motion(if (it.id in state.expanded) 1f else 0f, 190) }
    private val switches = Module.entries.associateWith { Motion(if (state.enabled(it)) 1f else 0f, 150) }
    private val hovers = Module.entries.associateWith { Entry.entries.associateWith { Motion(0f, 125) } }
    private val searchHeight = Motion(0f, 185)
    private val bindReveal = Motion(0f, 175)
    private lateinit var search: EditBox
    private lateinit var field: Box
    private lateinit var scaleControl: Box
    private lateinit var settingsControl: Box
    private var query = ""
    private var selected = 0
    private var lastResults = Results(emptyList(), 0)
    private var popup: Popup? = null
    private var recording: Module? = null
    private var lastRecording: Module? = null
    private var pendingModifier: Pair<Int, Int>? = null
    private var located: Module? = null
    private var drag: Drag? = null
    private var visiblePanels = emptyList<Panel>()
    private val header = 25
    private val rowHeight = 21
    private val panelTop = 35
    private var layoutScale = 1f
    private val logicalWidth get() = floor(width / layoutScale).toInt()
    private val logicalHeight get() = panelTop + floor((height - panelTop) / layoutScale).toInt()
    private val paneWidth get() = (logicalWidth - 12).coerceIn(1, 144)
    private fun panelX(screenX: Double) = screenX / layoutScale
    private fun panelY(screenY: Double) = panelTop + (screenY - panelTop) / layoutScale
    private val results get() = if (query.isBlank()) emptyList() else Module.entries.filter { it.id.contains(query.trim(), true) }

    override fun init() {
        finishPanelDrag()
        visiblePanels = emptyList()
        popup = null
        layoutScale = state.clickGuiScale / 100f
        val margin = minOf(9, width / 24)
        val scaleWidth = (width / 4).coerceIn(18, 108)
        val settingsWidth = (width / 5).coerceIn(18, 63)
        val fieldWidth = (width - 2 * (margin + maxOf(scaleWidth, settingsWidth) + 5)).coerceIn(12, 190)
        field = Box((width - fieldWidth) / 2, 7, fieldWidth, 22)
        scaleControl = Box(margin, 7, scaleWidth, 22)
        settingsControl = Box(width - margin - settingsWidth, 7, settingsWidth, 22)
        addRenderableWidget(PercentSlider(scaleControl, Ui.label("travelto.gui.scale"), CLICK_GUI_SCALE,
            read = { state.clickGuiScale }, commit = { state.clickGuiScale = it }))
        search = EditBox(Ui.body, field.x + 8, field.y + 6, (field.w - 16).coerceAtLeast(1), 12, Ui.label("travelto.search"))
        search.setBordered(false)
        search.setTextShadow(false)
        search.setMaxLength(64)
        search.value = query
        search.setResponder { query = it; selected = 0; located = null }
        addRenderableWidget(search)
        addRenderableWidget(GlassButton(settingsControl, Ui.label("travelto.settings.short")) {
            if (parent is SettingsScreen) onClose() else next { SettingsScreen(this) }
        })
    }

    private fun settings(module: Module) = when (module) {
        Module.HUD -> listOf(Entry.BIND, Entry.MODE, Entry.LAYOUT, Entry.GLOW)
        Module.MENU_SHADER -> listOf(Entry.BIND, Entry.MODE, Entry.MOTION)
        Module.WATER_MARK, Module.TARGET_HUD, Module.EFFECTS -> listOf(Entry.BIND, Entry.MODE, Entry.LAYOUT)
        Module.PING -> listOf(Entry.BIND, Entry.MODE, Entry.PING_SETTINGS)
        Module.FREE_CAM -> listOf(Entry.BIND, Entry.MODE, Entry.FREE_CAM_SETTINGS)
        Module.ITEM_PHYSICS -> listOf(Entry.BIND, Entry.MODE)
    }

    private fun panelLayout(now: Long): List<Panel> = Category.entries.sortedBy { state.panel(it).layer }.map { category ->
        val saved = state.panel(category)
        val reveal = categories.getValue(category).toward(if (saved.open) 1f else 0f, now)
        val x = saved.x.coerceIn(0, (logicalWidth - paneWidth).coerceAtLeast(0))
        val y = saved.y.coerceIn(panelTop, (logicalHeight - header).coerceAtLeast(panelTop))
        val contentTop = y + header
        val rows = mutableListOf<Row>()
        var cursor = contentTop
        for (module in Module.entries.filter { it.category == category }) {
            val openness = modules.getValue(module).toward(if (module.id in state.expanded) 1f else 0f, now)
            val moduleBounds = Box(x + 5, cursor, paneWidth - 10, rowHeight)
            rows += Row(module, Entry.MODULE, moduleBounds, moduleBounds, openness)
            cursor += rowHeight
            val settings = settings(module)
            val settingsHeight = (settings.size * rowHeight * openness).roundToInt()
            val clip = Box(x + 5, cursor, paneWidth - 10, settingsHeight)
            if (settingsHeight > 0) settings.forEachIndexed { index, entry ->
                val bounds = Box(x + 5, cursor + index * rowHeight, paneWidth - 10, rowHeight)
                if (bounds.y < clip.bottom) rows += Row(module, entry, bounds, clip, openness)
            }
            cursor += settingsHeight
        }
        val total = cursor - contentTop
        val fullHeight = minOf(total, (logicalHeight - contentTop - 9).coerceAtLeast(0))
        val visibleHeight = (fullHeight * reveal).roundToInt()
        val maxScroll = (total - fullHeight).coerceAtLeast(0)
        val scroll = saved.scroll.coerceIn(0, maxScroll)
        val frame = Box(x, y, paneWidth, header + visibleHeight + (5 * reveal).roundToInt())
        val viewport = Box(x + 4, contentTop, paneWidth - 8, visibleHeight)
        Panel(category, frame, viewport, rows.map { it.copy(bounds = it.bounds.copy(y = it.bounds.y - scroll), clip = it.clip.copy(y = it.clip.y - scroll)) }, reveal, scroll, maxScroll)
    }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        layoutScale = state.clickGuiScale / 100f
        visiblePanels = panelLayout(now)
        val searchOpen = query.isNotBlank()
        if (searchOpen) lastResults = Results(results, selected)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(0f, panelTop * (1f - layoutScale))
        pose.scale(layoutScale)
        try {
            drawPanels(graphics, panelX(mouseX.toDouble()), panelY(mouseY.toDouble()), now,
                popup?.bounds?.contains(mouseX.toDouble(), mouseY.toDouble()) == true)
        } finally { pose.popMatrix() }
        graphics.nextStratum()
        if (!WaterMark.searchBackground(graphics, field, search.isFocused)) {
            Ui.box(graphics, field, Ui.CONTROL)
            if (search.isFocused) Ui.themeStroke(graphics, field) else Ui.stroke(graphics, field, Ui.LINE, Ui.CONTROL_RADIUS)
        }
        if (query.isEmpty() && !search.isFocused) {
            Ui.text(graphics, Ui.fit(Ui.label("travelto.search").string, field.w - 16), field.x + 8, field.y + 6, Ui.MUTED)
        }
        search.setTextColor(Ui.tint(Ui.TEXT))
        super.draw(graphics, mouseX, mouseY, delta, now)
        drawResults(graphics, now, searchOpen)
        drawBinding(graphics, now)
    }

    private fun drawPanels(graphics: GuiGraphicsExtractor, px: Double, py: Double, now: Long, overlaid: Boolean) {
        val hit = if (!leaving && recording == null && !overlaid) visiblePanels.lastOrNull { it.bounds.contains(px, py) } else null
        for (panel in visiblePanels) {
            graphics.nextStratum()
            val frame = panel.bounds
            Ui.panel(graphics, frame, radius = Ui.CONTROL_RADIUS)
            Ui.text(graphics, Ui.fit(Ui.label(panel.category.label).string, frame.w - 35), frame.x + 11, frame.y + 8)
            val disclosure = Box(frame.right - 21, frame.y + 5, 15, 15)
            Ui.disclosure(graphics, disclosure, panel.reveal, if (hit === panel && disclosure.contains(px, py)) 1f else 0f)
            fun drawRows() {
                for (row in panel.rows) {
                    val clip = row.clip.intersection(panel.viewport) ?: continue
                    if (row.bounds.intersection(clip) == null) continue
                    val motion = hovers.getValue(row.module).getValue(row.entry)
                    val hovered = hit === panel && state.panel(panel.category).open && rowInteractive(row) && clip.contains(px, py) && row.bounds.contains(px, py)
                    val highlight = motion.toward(if (hovered) 1f else 0f, now)
                    fun drawVisibleRow() = Ui.faded(if (row.entry == Entry.MODULE) panel.reveal else panel.reveal * row.reveal) {
                        drawRow(graphics, row, highlight, now)
                    }
                    if (row.bounds.bottom > row.clip.bottom) RoundedRect.clipped(graphics, clip, ::drawVisibleRow)
                    else drawVisibleRow()
                }
            }
            if (panel.reveal < 1f || panel.maxScroll > 0) RoundedRect.clipped(graphics, panel.viewport, ::drawRows)
            else drawRows()
            val visible = panel.viewport.h
            if (visible > 0 && panel.maxScroll > 0) {
                val span = (visible * visible / (visible + panel.maxScroll)).coerceIn(minOf(4, visible), visible)
                val top = (visible - span) * panel.scroll / panel.maxScroll
                Ui.themeBox(graphics, Box(frame.right - 4, panel.viewport.y + top, 2, span), Ui.alpha(Ui.ACCENT, .56f), 1)
            }
        }
    }

    private fun drawRow(graphics: GuiGraphicsExtractor, row: Row, hover: Float, now: Long) {
        val module = row.module
        val isModule = row.entry == Entry.MODULE
        val opensScreen = row.entry == Entry.LAYOUT || row.entry == Entry.PING_SETTINGS || row.entry == Entry.FREE_CAM_SETTINGS
        val outer = row.bounds.inset(1)
        val bounds = if (opensScreen) outer.copy(x = outer.x + 4, w = outer.w - 4) else outer
        if (isModule || opensScreen) Ui.control(graphics, bounds, hover)
        else if (hover > 0f) Ui.themeBox(graphics, bounds, Ui.alpha(Ui.HOVER, hover))
        val textX = bounds.x + if (isModule || opensScreen) 6 else 10
        val rightInset = when { isModule -> 47; opensScreen -> 20; else -> 6 }
        val textY = bounds.y + (bounds.h - Ui.textHeight) / 2
        Ui.text(graphics, Ui.fit(rowLabel(row), bounds.right - textX - rightInset), textX, textY, if (isModule || opensScreen) Ui.TEXT else Ui.MUTED)
        if (opensScreen && bounds.w >= 32) Ui.text(graphics, "›", bounds.right - 9 - Ui.body.width("›"), textY, Ui.MUTED, shadow = false)
        if (!isModule) return
        val amount = switches.getValue(module).toward(if (state.enabled(module)) 1f else 0f, now)
        Ui.toggle(graphics, Box(bounds.right - 43, bounds.y + (bounds.h - 11) / 2, 22, 11), amount)
        Ui.disclosure(graphics, Box(bounds.right - 17, bounds.y + (bounds.h - 15) / 2, 15, 15), row.reveal, hover)
        if (located == module) Ui.themeBox(graphics, Box(bounds.x, bounds.y + 4, 2, bounds.h - 8), radius = 1)
    }

    private fun rowLabel(row: Row): String = when (row.entry) {
        Entry.MODULE -> row.module.id
        Entry.BIND -> Ui.label("travelto.bind.value", state.binding(row.module).display()).string
        Entry.MODE -> Ui.label("travelto.bind.mode", Ui.label("travelto.bind.${state.binding(row.module).mode.name.lowercase()}").string).string
        Entry.LAYOUT -> Ui.label("travelto.hud.editor").string
        Entry.GLOW -> Ui.label("travelto.hud.glow", Ui.switchLabel(state.hud.glow)).string
        Entry.MOTION -> Ui.label("travelto.config.motion", Ui.switchLabel(state.reducedMotion)).string
        Entry.PING_SETTINGS -> Ui.label("travelto.ping.settings").string
        Entry.FREE_CAM_SETTINGS -> Ui.label("travelto.freecam.settings").string
    }

    private fun drawResults(graphics: GuiGraphicsExtractor, now: Long, open: Boolean) {
        val displayed = lastResults
        val targetHeight = 8 + displayed.entries.size.coerceAtLeast(1) * rowHeight
        val visible = searchHeight.toward(if (open) targetHeight.toFloat() else 0f, now).roundToInt()
        val bounds = Box(field.x, field.bottom + 4, field.w, minOf(visible, (height - field.bottom - 6).coerceAtLeast(0)))
        popup = if (bounds.h > 0) Popup(bounds, displayed) else null
        if (bounds.h <= 0) return
        graphics.nextStratum()
        Ui.faded((visible.toFloat() / targetHeight).coerceAtMost(1f)) {
            Ui.panel(graphics, bounds, Ui.INSET)
            RoundedRect.clipped(graphics, bounds.inset(4)) {
                if (displayed.entries.isEmpty()) {
                    Ui.text(graphics, Ui.fit(Ui.label("travelto.search.empty").string, bounds.w - 16), bounds.x + 8, bounds.y + 10, Ui.MUTED)
                }
                displayed.entries.forEachIndexed { index, module ->
                    val row = resultRow(bounds, index)
                    if (index == displayed.selected) Ui.themeBox(graphics, row, Ui.HOVER)
                    val status = Ui.switchLabel(state.enabled(module))
                    val statusWidth = Ui.body.width(status)
                    Ui.text(graphics, Ui.fit(module.id, row.w - statusWidth - 15), row.x + 6, row.y + 6)
                    Ui.text(graphics, status, row.right - 6 - statusWidth, row.y + 6, Ui.MUTED)
                }
            }
        }
    }

    private fun resultRow(bounds: Box, index: Int) = Box(bounds.x + 4, bounds.y + 4 + index * rowHeight, bounds.w - 8, rowHeight)

    private fun drawBinding(graphics: GuiGraphicsExtractor, now: Long) {
        val amount = bindReveal.toward(if (recording != null) 1f else 0f, now)
        val module = lastRecording ?: return
        if (amount <= 0f) return
        val span = (width - 16).coerceIn(1, 246)
        val bounds = Box((width - span) / 2, (height - 54) / 2 + ((1f - amount) * 7).roundToInt(), span, 54)
        graphics.nextStratum()
        Ui.faded(amount) {
            graphics.fill(0, -8, width, height + 8, Ui.tint(0x80000000.toInt()))
            Ui.panel(graphics, bounds, Ui.INSET)
            Ui.text(graphics, Ui.fit(Ui.label("travelto.bind.title", module.id).string, span - 24), bounds.x + 12, bounds.y + 10)
            Ui.text(graphics, Ui.fit(Ui.label("travelto.bind.input").string, span - 24), bounds.x + 12, bounds.y + 25, Ui.MUTED)
            Ui.text(graphics, Ui.fit(Ui.label("travelto.bind.clear").string, span - 24), bounds.x + 12, bounds.y + 38, Ui.MUTED)
        }
    }

    private fun rowInteractive(row: Row) = row.entry == Entry.MODULE || row.module.id in state.expanded

    private fun activate(row: Row, button: Int, mouseX: Double, raised: Boolean) {
        var changed = raised
        val module = row.module
        val bind = state.binding(module)
        when (row.entry) {
            Entry.MODULE -> {
                located = module
                if (button == MOUSE_BUTTON_RIGHT || mouseX >= row.bounds.right - 20) {
                    if (!state.expanded.add(module.id)) state.expanded.remove(module.id)
                    changed = true
                } else {
                    Travelto.toggle(module)
                    return
                }
            }
            Entry.BIND -> if (button == MOUSE_BUTTON_RIGHT) {
                assign(module, -1, false, 0)
                return
            } else {
                recording = module
                lastRecording = module
                pendingModifier = null
                clearFocus()
                bindReveal.toward(1f, System.nanoTime())
            }
            Entry.MODE -> {
                bind.mode = BindMode.entries[(bind.mode.ordinal + if (button == MOUSE_BUTTON_RIGHT) 2 else 1) % BindMode.entries.size]
                changed = true
            }
            Entry.LAYOUT -> next { HudEditor(this, when (module) {
                Module.WATER_MARK -> Hud.Kind.WATERMARK
                Module.TARGET_HUD -> Hud.Kind.TARGET
                Module.EFFECTS -> Hud.Kind.EFFECTS
                else -> Hud.Kind.FPS
            }) }
            Entry.GLOW -> { state.hud.glow = !state.hud.glow; changed = true }
            Entry.MOTION -> { state.reducedMotion = !state.reducedMotion; changed = true }
            Entry.PING_SETTINGS -> next { PingSettingsScreen(this) }
            Entry.FREE_CAM_SETTINGS -> next { FreeCamSettingsScreen(this) }
        }
        if (changed) Travelto.saveConfig()
    }

    private fun raise(category: Category): Boolean {
        val ordered = Category.entries.sortedBy { state.panel(it).layer }
        if (ordered.last() == category) return false
        (ordered.filter { it != category } + category).forEachIndexed { index, value -> state.panel(value).layer = index }
        return true
    }

    private fun locate(module: Module) {
        val panel = state.panel(module.category)
        panel.open = true
        panel.scroll = Module.entries.filter { it.category == module.category }.takeWhile { it != module }.sumOf {
            rowHeight * (1 + if (it.id in state.expanded) settings(it).size else 0)
        }
        panel.y = panel.y.coerceIn(panelTop, (logicalHeight - header - rowHeight - 9).coerceAtLeast(panelTop))
        raise(module.category)
        search.value = ""
        located = module
        clearFocus()
        Travelto.saveConfig()
    }

    private fun assign(module: Module, code: Int, mouse: Boolean, modifiers: Int) {
        state.binding(module).let { it.code = code; it.mouse = mouse; it.modifiers = Bind.normalizeModifiers(code, mouse, modifiers) }
        recording = null
        pendingModifier = null
        bindReveal.toward(0f, System.nanoTime())
        Travelto.saveConfig()
    }

    override fun mouseShortcut(event: MouseButtonEvent): Boolean {
        if (recording != null || bindReveal.value(System.nanoTime()) > 0f || !Travelto.menuKey.matchesMouse(event)) return false
        if (!leaving && (event.button() == MOUSE_BUTTON_LEFT || event.button() == MOUSE_BUTTON_RIGHT)) {
            val x = event.x()
            val y = event.y()
            if (field.contains(x, y) || settingsControl.contains(x, y) || scaleControl.contains(x, y) ||
                popup?.bounds?.contains(x, y) == true || visiblePanels.any { it.bounds.contains(panelX(x), panelY(y)) }) return false
        }
        toggleClose()
        return true
    }

    override fun click(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val button = event.button()
        recording?.let { assign(it, button, true, event.modifiers()); return true }
        if (bindReveal.value(System.nanoTime()) > 0f) return true
        if (button != MOUSE_BUTTON_LEFT && button != MOUSE_BUTTON_RIGHT) return super.click(event, doubleClick)
        popup?.takeIf { it.bounds.contains(event.x(), event.y()) }?.let { shown ->
            if (query.isNotBlank() && shown.bounds.inset(4).contains(event.x(), event.y())) {
                shown.results.entries.forEachIndexed { index, module ->
                    if (resultRow(shown.bounds, index).contains(event.x(), event.y())) {
                        if (button == MOUSE_BUTTON_RIGHT) locate(module) else { selected = index; Travelto.toggle(module) }
                    }
                }
            }
            return true
        }
        if (button == MOUSE_BUTTON_LEFT && field.contains(event.x(), event.y())) {
            val position = event.x().coerceIn(search.x.toDouble(), (search.x + search.width - 1).toDouble())
            return super.click(MouseButtonEvent(position, (search.y + 2).toDouble(), event.buttonInfo()), doubleClick)
        }
        val px = panelX(event.x())
        val py = panelY(event.y())
        val panel = visiblePanels.lastOrNull { it.bounds.contains(px, py) } ?: return super.click(event, doubleClick)
        clearFocus()
        val raised = raise(panel.category)
        val bounds = panel.bounds
        if (py < bounds.y + header) {
            if (button == MOUSE_BUTTON_RIGHT || px >= bounds.right - 24) {
                state.panel(panel.category).let { it.open = !it.open }
                Travelto.saveConfig()
            } else drag = Drag(panel.category, px - bounds.x, py - bounds.y, raised)
            return true
        }
        val row = if (state.panel(panel.category).open && panel.viewport.contains(px, py)) {
            panel.rows.firstOrNull { rowInteractive(it) && it.clip.contains(px, py) && it.bounds.contains(px, py) }
        } else null
        if (row != null) activate(row, button, px, raised) else if (raised) Travelto.saveConfig()
        return true
    }

    override fun drag(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val current = drag ?: return super.drag(event, dx, dy)
        if (event.button() != MOUSE_BUTTON_LEFT) return super.drag(event, dx, dy)
        val snap = !isKeyDown(KEY_LSHIFT)
        fun align(value: Double) = if (snap) (value / 6).roundToInt() * 6 else value.roundToInt()
        val x = align(panelX(event.x()) - current.x).coerceIn(0, (logicalWidth - paneWidth).coerceAtLeast(0))
        val y = align(panelY(event.y()) - current.y).coerceIn(panelTop, (logicalHeight - header).coerceAtLeast(panelTop))
        val panel = state.panel(current.category)
        if (panel.x != x || panel.y != y) {
            panel.x = x
            panel.y = y
            current.changed = true
        }
        return true
    }

    private fun finishPanelDrag() {
        val changed = drag?.changed == true
        drag = null
        if (changed) Travelto.saveConfig()
    }

    override fun release(event: MouseButtonEvent): Boolean {
        if (event.button() == MOUSE_BUTTON_LEFT && drag != null) { finishPanelDrag(); return true }
        return super.release(event)
    }

    override fun tick() {
        if (leaving) finishPanelDrag()
        super.tick()
    }

    override fun removed() {
        finishPanelDrag()
        super.removed()
    }

    override fun scroll(x: Double, y: Double, horizontal: Double, vertical: Double): Boolean {
        if (recording != null || popup?.bounds?.contains(x, y) == true) return true
        val panel = visiblePanels.lastOrNull { it.bounds.contains(panelX(x), panelY(y)) } ?: return false
        if (state.panel(panel.category).open && panel.maxScroll > 0) {
            val saved = state.panel(panel.category)
            val offset = (panel.scroll - (vertical * rowHeight).roundToInt()).coerceIn(0, panel.maxScroll)
            if (saved.scroll != offset) {
                saved.scroll = offset
                Travelto.saveConfig()
            }
        }
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        recording?.let { module ->
            when {
                key == KEY_ESCAPE || key == KEY_DELETE || key == KEY_BACKSPACE -> assign(module, -1, false, 0)
                Bind.isModifier(key) -> pendingModifier = key to event.modifiers()
                key in Bind.validCodes(false) -> assign(module, key, false, event.modifiers())
            }
            return true
        }
        if (Travelto.menuKey.matches(event) && (key == KEY_RSHIFT || !search.isFocused || leaving)) {
            if (!shortcutHeld) { shortcutHeld = true; toggleClose() }
            return true
        }
        if (leaving) return true
        if (query.isNotBlank()) {
            val options = results
            when (key) {
                KEY_ESCAPE -> { search.value = ""; return true }
                KEY_UP, KEY_DOWN -> { if (options.isNotEmpty()) selected = (selected + (if (key == KEY_UP) -1 else 1) + options.size) % options.size; return true }
                KEY_RETURN, KEY_NUMPADENTER -> { options.getOrNull(selected)?.let { Travelto.toggle(it) }; return true }
                KEY_TAB -> options.getOrNull(selected)?.let { locate(it); return true }
            }
        }
        return super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (Travelto.menuKey.matches(event)) shortcutHeld = false
        val module = recording
        if (module != null) { pendingModifier?.takeIf { it.first == event.key() }?.let { assign(module, it.first, false, it.second) }; return true }
        return super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent) = recording != null || super.charTyped(event)
    override fun preeditUpdated(event: PreeditEvent?) = recording != null || super.preeditUpdated(event)
}
