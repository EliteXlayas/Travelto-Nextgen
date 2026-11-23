package dev.travelto

import com.mojang.blaze3d.platform.InputConstants.*
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

internal class HudEditor(parent: Screen?, initial: Hud.Kind? = null) : TraveltoScreen("travelto.hud.editor", parent) {
    private enum class Page(val key: String) { LAYOUT("travelto.hud.layout"), OPTIONS("travelto.hud.options") }
    private data class Drag(val offsetX: Double, val offsetY: Double, var moved: Boolean = false)
    private data class Settings(val kind: Hud.Kind, var bounds: Box, val page: Page, var wheel: Box?,
                                val tabs: List<GlassButton>, val controls: List<AbstractWidget>) {
        val widgets = tabs + controls
    }

    private var selected = initial ?: Hud.Kind.WATERMARK
    private var openOnEntry = initial?.let { it.hasOptions() || it == Hud.Kind.EFFECTS } == true
    private var drag: Drag? = null
    private var popupDrag: Drag? = null
    private val popupPositions = mutableMapOf<Hud.Kind, Pair<Int, Int>>()
    private var settings: Settings? = null
    private var settingsOpen = false
    private val reveal = Motion(0f, 175)
    private val pageReveal = Motion(1f, 150)
    private lateinit var footer: Box
    private lateinit var choose: GlassButton
    private val hud get() = Travelto.state.hud
    private fun elements() = Hud.previews(minecraft, width)
    private fun element(kind: Hud.Kind = selected) = elements().first { it.kind == kind }
    private fun Hud.Kind.hasOptions() = this == Hud.Kind.WATERMARK || this == Hud.Kind.TARGET || this == Hud.Kind.ARRAY_LIST

    override fun init() {
        finishHudDrag()
        popupDrag = null
        finishPickers()
        settings = null
        settingsOpen = false
        reveal.reset(0f)
        val span = minOf(208, (width - 10).coerceAtLeast(1))
        footer = Box((width - span) / 2, height - 36, span, 30)
        val cell = ((span - 8) / 3).coerceAtLeast(1)
        fun control(index: Int, label: Component, action: () -> Unit) = addRenderableWidget(
            GlassButton(Box(footer.x + index * (cell + 4), footer.bottom - 18, cell, 18), label, radius = 3, action = action)
        )
        choose = control(0, Ui.label(selected.key)) {
            selected = Hud.Kind.entries[(selected.ordinal + 1) % Hud.Kind.entries.size]
            choose.message = Ui.label(selected.key)
            hideSettings()
        }
        control(1, Ui.label("travelto.hud.edit")) {
            val bounds = Hud.bounds(width, height, element())
            if (settingsOpen) hideSettings() else showSettings(bounds.right, bounds.y)
        }
        control(2, Ui.label("travelto.action.done")) { onClose() }
        if (openOnEntry) {
            openOnEntry = false
            val bounds = Hud.bounds(width, height, element())
            showSettings(bounds.right, bounds.y, if (selected.hasOptions()) Page.OPTIONS else Page.LAYOUT)
        }
    }

    private fun showSettings(pointerX: Int, pointerY: Int, page: Page = Page.LAYOUT) {
        val hasOptions = selected.hasOptions()
        val span = minOf(if (hasOptions) 226 else 210, (width - 12).coerceAtLeast(1))
        val edge = if (pointerX + span + 10 <= width) pointerX + 6 else pointerX - span - 6
        val (left, top) = popupPositions[selected] ?: (edge to pointerY + 5)
        pageReveal.reset(1f)
        setSettings(selected, Box(left, top, span, 0), page)
        reveal.toward(1f, System.nanoTime())
    }

    private fun fitPopup(box: Box) = box.copy(x = box.x.coerceIn(6, (width - box.w - 6).coerceAtLeast(6)),
        y = box.y.coerceIn(5, (height - box.h - 5).coerceAtLeast(5)))

    private fun movePopup(popup: Settings, x: Int, y: Int) {
        val next = fitPopup(popup.bounds.copy(x = x, y = y))
        val dx = next.x - popup.bounds.x
        val dy = next.y - popup.bounds.y
        if (dx == 0 && dy == 0) return
        popup.widgets.forEach { widget -> widget.x += dx; widget.y += dy }
        popup.wheel = popup.wheel?.let { it.copy(x = it.x + dx, y = it.y + dy) }
        popup.bounds = next
        popupPositions[popup.kind] = next.x to next.y
    }

    private fun popupHeight(kind: Hud.Kind, page: Page) = when {
        page == Page.OPTIONS && kind == Hud.Kind.WATERMARK -> if (hud.waterMarkOptions.style == WaterMarkStyle.LOGO) 109 else 128
        page == Page.OPTIONS && kind == Hud.Kind.TARGET -> if (hud.targetHudOptions.style == TargetStyle.TRAVELTO) 158 else 140
        page == Page.OPTIONS && kind == Hud.Kind.ARRAY_LIST -> if (hud.listAppearance.mode == ListColorMode.FIXED) 143 else 74
        kind.hasOptions() -> 155
        kind == Hud.Kind.EFFECTS -> 133
        else -> 110
    }

    private fun setSettings(kind: Hud.Kind, requested: Box, page: Page) {
        detachSettings()
        val bounds = fitPopup(requested.copy(h = popupHeight(kind, page)))
        val left = bounds.x + 9
        val span = (bounds.w - 18).coerceAtLeast(1)
        val cell = ((span - 4) / 2).coerceAtLeast(1)
        val tabs = if (kind.hasOptions()) Page.entries.mapIndexed { index, entry ->
            GlassButton(Box(left + index * (cell + 4), bounds.y + 21, cell, 17), Ui.label(entry.key), primary = page == entry, radius = 3) {
                if (page != entry) {
                    pageReveal.reset(.5f)
                    pageReveal.toward(1f, System.nanoTime())
                    setSettings(kind, settings?.bounds ?: bounds, entry)
                }
            }
        } else emptyList()
        val top = bounds.y + if (tabs.isEmpty()) 24 else 45
        val wheelSize = minOf(if (tabs.isEmpty()) 70 else 62, (span / 3).coerceAtLeast(16))
        val wheel = when {
            page == Page.LAYOUT -> Box(bounds.right - wheelSize - 9, top + if (tabs.isEmpty()) 0 else 9, wheelSize, wheelSize)
            kind == Hud.Kind.ARRAY_LIST && hud.listAppearance.mode == ListColorMode.FIXED ->
                Box(bounds.right - wheelSize - 9, top + 14, wheelSize, wheelSize)
            else -> null
        }
        val controls = when {
            page == Page.LAYOUT -> layoutControls(kind, left, top, requireNotNull(wheel))
            kind == Hud.Kind.ARRAY_LIST -> listControls(kind, left, top, span, wheel)
            else -> optionControls(kind, left, top, span, cell)
        }
        (tabs + controls).forEach { addWidget(it) }
        settings = Settings(kind, bounds, page, wheel, tabs, controls)
        settingsOpen = true
    }

    private fun layoutControls(kind: Hud.Kind, left: Int, top: Int, wheel: Box): List<AbstractWidget> {
        val part = kind.part(hud)
        val room = (wheel.x - left - 8).coerceAtLeast(1)
        val size = PercentSlider(Box(left, top, room, 23), Ui.label("travelto.gui.scale"), HUD_SCALE, radius = 3,
            read = { (part.scale * 100).roundToInt() }, commit = { part.scale = it / 100f })
        val animated = kind != Hud.Kind.FPS
        val shift = if (animated) 25 else 0
        val motion = if (animated) PercentSlider(Box(left, top + 25, room, 22), Ui.label("travelto.motion.hud_speed"), MOTION_SPEED, radius = 3,
            read = { part.motionSpeed }, commit = { part.motionSpeed = it }) else null
        val visible = option(Box(left, top + 26 + shift, room, 16), "travelto.hud.visibility_state", { part.visible }) {
            part.visible = it
            Travelto.saveConfig()
        }
        val glow = option(Box(left, top + 44 + shift, room, 16), "travelto.hud.glow", { hud.glow }) {
            hud.glow = it
            Travelto.saveConfig()
        }
        val reset = GlassButton(Box(left, top + 62 + shift, room, 16), Ui.label("travelto.action.reset"), radius = 3) {
            val defaults = HudStyle(waterMarkOptions = WaterMarkOptions(style = hud.waterMarkOptions.style)).let(kind::part)
            part.x = defaults.x
            part.y = defaults.y
            part.offsetX = defaults.offsetX
            part.offsetY = defaults.offsetY
            part.scale = defaults.scale
            part.visible = defaults.visible
            part.motionSpeed = defaults.motionSpeed
            visible.message = Ui.label("travelto.hud.visibility_state", Ui.switchLabel(part.visible))
            Travelto.saveConfig()
        }
        return listOfNotNull(size, motion, visible, glow, reset, ThemeWheel(wheel))
    }

    private fun listControls(kind: Hud.Kind, left: Int, top: Int, span: Int, wheel: Box?): List<AbstractWidget> {
        fun caption() = Ui.label("travelto.arraylist.color_mode", Ui.label("travelto.arraylist.${hud.listAppearance.mode.name.lowercase()}").string)
        val modeWidth = if (wheel == null) span else (wheel.x - left - 8).coerceAtLeast(1)
        val mode = GlassButton(Box(left, top, modeWidth, 18), caption()) {
            hud.listAppearance.mode = hud.listAppearance.mode.next()
            Travelto.saveConfig()
            settings?.let { setSettings(kind, it.bounds, it.page) }
            settings?.controls?.firstOrNull()?.let(::setFocused)
        }
        if (wheel == null) return listOf(mode)
        val reset = GlassButton(Box(left, top + 22, modeWidth, 18), Ui.label("travelto.action.reset")) {
            hud.listAppearance.color = DEFAULT_LIST_COLOR
            Travelto.saveConfig()
        }
        return listOf(mode, reset, ThemeWheel(wheel, Ui.label("travelto.arraylist.fixed_color"),
            read = { hud.listAppearance.color }, write = { hud.listAppearance.color = it }))
    }

    private fun optionControls(kind: Hud.Kind, left: Int, top: Int, span: Int, cell: Int): List<AbstractWidget> {
        val mark = hud.waterMarkOptions
        val target = hud.targetHudOptions
        val isMark = kind == Hud.Kind.WATERMARK
        fun style() = Ui.label("travelto.hud.style", if (isMark) Ui.label("travelto.watermark.style.${mark.style.name.lowercase()}").string else target.style.label)
        lateinit var selectStyle: GlassButton
        selectStyle = GlassButton(Box(left, top, span, 18), style(), radius = 3) {
            if (isMark) mark.style = mark.style.next() else target.style = target.style.next()
            Travelto.saveConfig()
            settings?.let { popup ->
                val focused = selectStyle.isFocused
                setSettings(kind, popup.bounds, popup.page)
                if (focused) settings?.controls?.firstOrNull()?.let(::setFocused)
            }
        }
        val moduleState = option(Box(left, top + 21, cell, 16), "travelto.hud.module_state", { Travelto.state.enabled(kind.module) }) {
            Travelto.toggle(kind.module)
        }
        val controls = mutableListOf<AbstractWidget>(selectStyle, moduleState)
        fun control(key: String, read: () -> Boolean, write: (Boolean) -> Unit) {
            val index = controls.size - 1
            controls += option(Box(left + index % 2 * (cell + 4), top + 21 + index / 2 * 19, cell, 16), key, read) {
                write(it)
                Travelto.saveConfig()
            }
        }
        if (isMark) {
            val island = mark.style == WaterMarkStyle.DYNAMIC_ISLAND
            if (island) {
                control("travelto.hud.notices", { mark.notices }) { mark.notices = it }
                control("travelto.hud.block_stock", { mark.blockStock }) { mark.blockStock = it }
                control("travelto.hud.chat", { mark.chat }) { mark.chat = it }
            }
            val part = hud.watermarkPart()
            fun caption(): Component {
                val key = WaterMarkPosition.of(part)?.name?.lowercase() ?: "custom"
                return Ui.label("travelto.watermark.position", Ui.label("travelto.watermark.position.$key").string)
            }
            lateinit var position: GlassButton
            position = GlassButton(Box(left, top + if (island) 59 else 40, span, 16), caption()) {
                (WaterMarkPosition.of(part)?.next() ?: WaterMarkPosition.TOP_LEFT).applyTo(part)
                position.message = caption()
                Travelto.saveConfig()
            }
            controls += position
        } else {
            control("travelto.hud.follow_target", { target.followTarget }) { target.followTarget = it }
            control("travelto.hud.cell_mitosis", { target.cellMitosis }) { target.cellMitosis = it }
            controls += PercentSlider(Box(left, top + 60, span, 23), Ui.label("travelto.hud.aim_range"), TARGET_AIM_RANGE, step = 1, radius = 3,
                format = { Ui.label("travelto.unit.blocks", it).string }, read = { target.aimRange }, commit = { target.aimRange = it })
            if (target.style == TargetStyle.TRAVELTO) {
                fun avatarCaption() = Ui.label("travelto.hud.mob_icon", Ui.label("travelto.hud.mob_icon.${target.mobIcon.name.lowercase()}").string)
                lateinit var avatar: GlassButton
                avatar = GlassButton(Box(left, top + 86, span, 17), avatarCaption()) {
                    target.mobIcon = if (target.mobIcon == TargetMobIcon.PORTRAIT) TargetMobIcon.SPAWN_EGG else TargetMobIcon.PORTRAIT
                    avatar.message = avatarCaption()
                    Travelto.saveConfig()
                }
                controls += avatar
            }
        }
        return controls
    }

    private fun option(bounds: Box, key: String, read: () -> Boolean, write: (Boolean) -> Unit): GlassButton {
        fun caption() = Ui.label(key, Ui.switchLabel(read()))
        lateinit var widget: GlassButton
        widget = GlassButton(bounds, caption(), radius = 3) {
            write(!read())
            widget.message = caption()
        }
        return widget
    }

    private fun detachSettings() {
        if (!settingsOpen) return
        clearFocus()
        setDragging(false)
        popupDrag = null
        settings?.widgets?.forEach {
            if (it is ThemeWheel) it.finish()
            removeWidget(it)
        }
    }

    private fun hideSettings() {
        if (!settingsOpen) return
        detachSettings()
        settingsOpen = false
        reveal.toward(0f, System.nanoTime())
    }

    override fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) {
        val items = elements()
        Hud.draw(graphics, minecraft, items, selected)
        graphics.nextStratum()
        val hint = Ui.fit(Ui.label("travelto.hud.edit_hint").string, footer.w)
        Ui.text(graphics, hint, (width - Ui.body.width(hint)) / 2, footer.y - 2, Ui.MUTED)
        super.draw(graphics, mouseX, mouseY, delta, now)
        val popup = settings ?: return
        val amount = reveal.toward(if (settingsOpen) 1f else 0f, now)
        if (amount <= 0f) {
            if (!settingsOpen) settings = null
            return
        }
        graphics.nextStratum()
        Ui.faded(amount) {
            Ui.panel(graphics, popup.bounds, radius = 5)
            val title = Ui.fit(Ui.label(popup.kind.key).string, popup.bounds.w - 31)
            Ui.text(graphics, HudEffects.bold(title), popup.bounds.x + 9, popup.bounds.y + 7)
            val grip = if (mouseY in popup.bounds.y until popup.bounds.y + 21 && mouseX in popup.bounds.x until popup.bounds.right) Ui.ACCENT else Ui.MUTED
            for (row in 0..2) for (column in 0..1) {
                val dot = Box(popup.bounds.right - 14 + column * 4, popup.bounds.y + 6 + row * 4, 2, 2)
                if (grip == Ui.ACCENT) Ui.themeBox(graphics, dot, grip, 1) else Ui.box(graphics, dot, grip, 1)
            }
            fun drawControl(widget: AbstractWidget) = widget.extractRenderState(graphics,
                if (settingsOpen) mouseX else -1, if (settingsOpen) mouseY else -1, delta)
            popup.tabs.forEach(::drawControl)
            Ui.faded(pageReveal.toward(1f, now)) {
                popup.wheel?.let { wheel ->
                    val picker = popup.controls.filterIsInstance<ThemeWheel>().first()
                    val theme = Ui.fit(picker.message.string, wheel.w)
                    val labelY = if (popup.tabs.isEmpty()) popup.bounds.y + 8 else wheel.y - 13
                    Ui.text(graphics, theme, wheel.x + (wheel.w - Ui.body.width(theme)) / 2, labelY, Ui.MUTED)
                    val hex = picker.hex
                    Ui.text(graphics, hex, wheel.x + (wheel.w - Ui.body.width(hex)) / 2, wheel.bottom + 1, Ui.MUTED)
                }
                popup.controls.forEach(::drawControl)
            }
        }
    }

    override fun click(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val button = event.button()
        if (button != MOUSE_BUTTON_LEFT && button != MOUSE_BUTTON_RIGHT) return super.click(event, doubleClick)
        val x = event.x()
        val y = event.y()
        val popup = settings
        if (settingsOpen && popup != null && popup.bounds.contains(x, y)) {
            if (button == MOUSE_BUTTON_LEFT && reveal.value(System.nanoTime()) > .20f) {
                val widget = popup.widgets.firstOrNull { it.mouseClicked(event, doubleClick) }
                if (widget != null && settings === popup) {
                    setFocused(widget)
                    setDragging(true)
                } else if (widget == null && y < popup.bounds.y + 21) {
                    clearFocus()
                    setDragging(false)
                    popupDrag = Drag(x - popup.bounds.x, y - popup.bounds.y)
                }
            }
            return true
        }
        if (footer.contains(x, y)) return super.click(event, doubleClick)
        val items = elements()
        val item = items.asReversed().firstOrNull { Hud.bounds(width, height, it).contains(x, y) }
        hideSettings()
        if (item == null) return popup != null
        selected = item.kind
        choose.message = item.label
        clearFocus()
        if (button == MOUSE_BUTTON_RIGHT) showSettings(x.roundToInt(), y.roundToInt()) else {
            val bounds = Hud.bounds(width, height, item)
            drag = Drag(x - bounds.x, y - bounds.y)
        }
        return true
    }

    override fun drag(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val popup = settings
        val moving = popupDrag
        if (popup != null && moving != null && event.button() == MOUSE_BUTTON_LEFT) {
            movePopup(popup, (event.x() - moving.offsetX).roundToInt(), (event.y() - moving.offsetY).roundToInt())
            return true
        }
        val movingHud = drag ?: return super.drag(event, dx, dy)
        if (event.button() != MOUSE_BUTTON_LEFT) return super.drag(event, dx, dy)
        val item = element()
        val bounds = Hud.bounds(width, height, item)
        val left = event.x() - movingHud.offsetX
        val x = if (item.kind == Hud.Kind.ARRAY_LIST && left + bounds.w >= width - 5) 1f
            else Hud.fraction(left, width, bounds.w, flush = item.kind == Hud.Kind.ARRAY_LIST)
        val y = Hud.fraction(event.y() - movingHud.offsetY, height, bounds.h)
        if (item.part.x != x || item.part.y != y || item.part.offsetX != 0f || item.part.offsetY != 0f) {
            item.part.x = x
            item.part.y = y
            item.part.offsetX = 0f
            item.part.offsetY = 0f
            movingHud.moved = true
        }
        return true
    }

    private fun finishHudDrag() {
        val moved = drag?.moved == true
        drag = null
        if (moved) Travelto.saveConfig()
    }

    override fun release(event: MouseButtonEvent): Boolean {
        if (event.button() == MOUSE_BUTTON_LEFT && (popupDrag != null || drag != null)) {
            popupDrag = null
            finishHudDrag()
            return true
        }
        return super.release(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == KEY_ESCAPE && settingsOpen) { hideSettings(); return true }
        return super.keyPressed(event)
    }

    private fun finishPickers() = settings?.controls?.filterIsInstance<ThemeWheel>()?.forEach(ThemeWheel::finish)

    override fun removed() {
        finishHudDrag()
        finishPickers()
        super.removed()
    }
}
