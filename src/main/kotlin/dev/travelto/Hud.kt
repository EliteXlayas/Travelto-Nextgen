package dev.travelto

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.awt.Color
import kotlin.math.roundToInt

internal object Hud {
    enum class Kind(val key: String, val module: Module = Module.HUD) {
        WATERMARK("travelto.hud.watermark", Module.WATER_MARK), FPS("travelto.hud.fps"), ARRAY_LIST("travelto.hud.arraylist"),
        TARGET("travelto.hud.target", Module.TARGET_HUD), EFFECTS("travelto.hud.effects", Module.EFFECTS);

        fun part(hud: HudStyle) = when (this) {
            WATERMARK -> hud.watermarkPart()
            FPS -> hud.fps
            ARRAY_LIST -> hud.arrayList
            TARGET -> hud.target
            EFFECTS -> hud.effects
        }
    }
    data class Row(val text: Component, val width: Int, val amount: Float = 1f)
    data class Element(val part: HudPart, val kind: Kind, val rows: List<Row>, val w: Int, val h: Int) {
        val label get() = Ui.label(kind.key)
    }

    private const val CORNER = 3
    private const val MARGIN = 5
    private const val GAP = 2
    private const val LIST_RAIL = 2
    private const val LIST_TEXT_INSET = 5
    private const val LIST_PADDING = 2 * LIST_TEXT_INSET + LIST_RAIL
    private val fpsLabel = Component.literal("FPS").withStyle(ChatFormatting.BOLD)
    private val labels = Module.entries.associateWith { Component.literal(it.id) }
    private val visibility = Module.entries.associateWith { Motion(if (Travelto.state.enabled(it)) 1f else 0f, 285, MotionScope.ARRAY_LIST) }
    private val rowHeight get() = maxOf(15, Ui.textHeight + 4)

    private fun listRows(now: Long): List<Row> = Module.entries.mapNotNull { module ->
        val amount = visibility.getValue(module).toward(if (Travelto.state.enabled(module)) 1f else 0f, now)
        if (amount <= 0f) null else labels.getValue(module).let { Row(it, Ui.body.width(it), amount) }
    }.sortedByDescending { it.width }

    private fun elements(client: Minecraft, preview: Boolean): List<Element> {
        val hud = Travelto.state.hud
        val fpsValue = client.getFps()
        val fpsText = Component.literal(if (fpsValue > 0) "$fpsValue " else "— ").append(fpsLabel)
        val fps = Row(fpsText, Ui.body.width(fpsText))
        val rows = listRows(System.nanoTime()).ifEmpty {
            if (preview) Ui.label(Kind.ARRAY_LIST.key).let { listOf(Row(it, Ui.body.width(it))) } else emptyList()
        }
        val listWidth = rows.maxOfOrNull { it.width + LIST_PADDING } ?: 0
        val listHeight = rows.sumOf { (it.amount * (rowHeight + GAP)).toDouble() }.roundToInt() - GAP
        return listOf(
            Element(hud.fps, Kind.FPS, listOf(fps), maxOf(52, fps.width + 18), 19),
            Element(hud.arrayList, Kind.ARRAY_LIST, rows, maxOf(46, listWidth), listHeight.coerceAtLeast(1))
        )
    }

    fun previews(client: Minecraft, width: Int): List<Element> {
        val hud = Travelto.state.hud
        val (markWidth, markHeight) = WaterMark.previewSize(client, width)
        val available = ((width - 2 * MARGIN) / hud.target.scale).toInt()
        val (targetWidth, targetHeight) = TargetHud.previewSize(client, available)
        val (effectsWidth, effectsHeight) = EffectsHud.previewSize(width, hud.effects)
        return listOf(Element(hud.watermarkPart(), Kind.WATERMARK, emptyList(), markWidth, markHeight)) + elements(client, true) + listOf(
            Element(hud.target, Kind.TARGET, emptyList(), targetWidth, targetHeight),
            Element(hud.effects, Kind.EFFECTS, emptyList(), effectsWidth, effectsHeight)
        )
    }

    fun bounds(width: Int, height: Int, element: Element) = place(width, height, element.part, element.w, element.h,
        horizontalMargin = if (element.kind == Kind.ARRAY_LIST) 0 else MARGIN)

    fun place(width: Int, height: Int, part: HudPart, w: Int, h: Int, scale: Float = part.scale, horizontalMargin: Int = MARGIN): Box {
        val span = (w * scale).roundToInt()
        val tall = (h * scale).roundToInt()
        val left = horizontalMargin + (travelSpace(width, span, horizontalMargin) * part.x + part.offsetX).roundToInt()
        val top = MARGIN + (travelSpace(height, tall, MARGIN) * part.y + part.offsetY).roundToInt()
        return Box(left.coerceIn(0, maxOf(width - span, horizontalMargin)), top.coerceIn(0, maxOf(height - tall, MARGIN)), span, tall)
    }

    fun fraction(position: Double, viewport: Int, span: Int, flush: Boolean = false): Float {
        val margin = if (flush) 0 else MARGIN
        return ((position - margin) / travelSpace(viewport, span, margin).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
    }

    private fun travelSpace(viewport: Int, span: Int, margin: Int) = (viewport - span - 2 * margin).coerceAtLeast(0)

    fun draw(graphics: GuiGraphicsExtractor, client: Minecraft) = draw(graphics, client, elements(client, false))

    fun draw(graphics: GuiGraphicsExtractor, client: Minecraft, items: List<Element>, selected: Kind? = null) {
        val style = Travelto.state.hud
        for (item in items) {
            val active = item.part.visible && Travelto.state.enabled(item.kind.module)
            if ((!active && selected == null) || (item.kind == Kind.ARRAY_LIST && item.rows.isEmpty())) continue
            val bounds = bounds(graphics.guiWidth(), graphics.guiHeight(), item)
            graphics.nextStratum()
            val pose = graphics.pose()
            pose.pushMatrix()
            val x = if (item.kind == Kind.ARRAY_LIST) {
                val travel = graphics.guiWidth() - item.w * item.part.scale
                (travel * item.part.x + item.part.offsetX).coerceIn(minOf(0f, travel), maxOf(0f, travel))
            } else bounds.x.toFloat()
            pose.translate(x, bounds.y.toFloat())
            pose.scale(item.part.scale)
            try {
                val radius = if (item.kind == Kind.FPS) item.h / 2 else CORNER
                if (item.kind == selected) Ui.themeStroke(graphics, Box(-2, -2, item.w + 4, item.h + 4), radius = radius + 1, thickness = 1f)
                Ui.faded(if (active) 1f else .56f) {
                    when (item.kind) {
                        Kind.WATERMARK -> WaterMark.preview(graphics, client, Box(0, 0, item.w, item.h))
                        Kind.TARGET -> TargetHud.preview(graphics, client, Box(0, 0, item.w, item.h))
                        Kind.EFFECTS -> EffectsHud.preview(graphics, client, item.w)
                        Kind.ARRAY_LIST -> drawList(graphics, item, style)
                        Kind.FPS -> {
                            val content = item.rows.single()
                            badge(graphics, Box(0, 0, item.w, item.h), radius)
                            Ui.soften(graphics, content.text, (item.w - content.width) / 2, (item.h - Ui.textHeight) / 2, style.glow)
                        }
                    }
                }
            } finally { pose.popMatrix() }
        }
    }

    private fun drawList(graphics: GuiGraphicsExtractor, item: Element, style: HudStyle) {
        val pose = graphics.pose()
        val right = item.part.x >= .5f
        val direction = if (right) 1f else -1f
        val rowHeight = rowHeight
        val settled = item.rows.all { it.amount == 1f }
        val railX = item.w - LIST_RAIL
        val appearance = style.listAppearance
        val hue = if (Travelto.state.reducedMotion) 0f else MotionSettings.phase(System.nanoTime(), 5300, MotionScope.ARRAY_LIST)
        var top = 0f
        for ((index, row) in item.rows.withIndex()) {
            val width = row.width + LIST_PADDING
            val nextAmount = item.rows.getOrNull(index + 1)?.amount ?: 0f
            val continuation = (GAP * nextAmount).roundToInt()
            val box = Box(item.w - width, 0, width, rowHeight + continuation)
            val color = when (appearance.mode) {
                ListColorMode.FIXED -> Ui.accent(appearance.color)
                ListColorMode.THEME -> Ui.ACCENT
                ListColorMode.RAINBOW -> Color.HSBtoRGB((hue + index * .055f) % 1f, .62f, 1f)
            }
            val transition = 1f - row.amount
            graphics.nextStratum()
            pose.pushMatrix()
            pose.translate(direction * minOf(width + 6, 38) * transition, top - rowHeight * transition * item.part.y)
            try {
                Ui.faded(row.amount) {
                    RoundedRect.draw(graphics, box, 2f, Ui.tint(Ui.INSET), squareRight = true)
                    if (!settled) drawListRail(graphics, Box(railX, 0, LIST_RAIL, box.h))
                    Ui.soften(graphics, row.text, railX - LIST_TEXT_INSET - row.width, (box.h - Ui.textHeight) / 2, style.glow, color, color)
                }
            } finally { pose.popMatrix() }
            top += (rowHeight + GAP) * row.amount
        }
        if (settled) {
            graphics.nextStratum()
            drawListRail(graphics, Box(railX, 0, LIST_RAIL, item.h))
        }
    }

    private fun drawListRail(graphics: GuiGraphicsExtractor, rail: Box) = Ui.themeBox(graphics, rail, radius = 0)

    private fun badge(graphics: GuiGraphicsExtractor, box: Box, radius: Int) {
        Ui.box(graphics, box, Ui.INSET, radius)
        Ui.stroke(graphics, box, Ui.LINE, radius)
    }
}
