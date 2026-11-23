package dev.travelto

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Hud as VanillaHud
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.Holder
import net.minecraft.data.AtlasIds
import net.minecraft.network.chat.Component
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffectUtil
import net.minecraft.world.effect.MobEffects
import kotlin.math.roundToInt

internal object EffectsHud {
    private const val GAP = 2
    private const val ICON = 16
    private const val INSET = 6
    private const val TEXT_X = INSET + ICON + 6
    private const val WIDTH = 128
    private const val EDGE = 5
    private const val TILE = 18
    private const val COLUMNS = 8
    private val fallback = Travelto.id("textures/gui/effect_icons.png")
    private val cells by lazy {
        EffectsHud::class.java.getResourceAsStream("/META-INF/travelto/effect-icons.txt")
            ?.bufferedReader()?.useLines { lines ->
                lines.filter { it.isNotBlank() }.mapIndexed { index, line -> line.substringBefore(' ') to index }.toMap()
            }.orEmpty()
    }
    private val examples by lazy { listOf(MobEffectInstance(MobEffects.SPEED, 1120, 1), MobEffectInstance(MobEffects.HASTE, 2840)) }
    private class Entry(var effect: MobEffectInstance) {
        val motion = Motion(0f, 240, MotionScope.EFFECTS)
        var visible = true
    }
    private val entries = linkedMapOf<Holder<MobEffect>, Entry>()
    private var player: LocalPlayer? = null
    private var level: ClientLevel? = null
    private val rowHeight get() = maxOf(25, Ui.textHeight * 2 + 5)

    fun tick(client: Minecraft) {
        val currentPlayer = client.player
        val currentLevel = client.level
        val enabled = Travelto.state.enabled(Module.EFFECTS)
        if (player !== currentPlayer || level !== currentLevel || !enabled) entries.clear()
        player = currentPlayer
        level = currentLevel
        if (currentPlayer == null || currentLevel == null || !enabled) return
        entries.values.forEach { it.visible = false }
        for (effect in currentPlayer.activeEffects) {
            if (!effect.showIcon()) continue
            val entry = entries.getOrPut(effect.effect) { Entry(effect) }
            entry.effect = effect
            entry.visible = true
        }
        val now = System.nanoTime()
        entries.values.removeIf { entry ->
            !entry.visible && entry.motion.toward(0f, now) <= 0f && entry.motion.finished(now)
        }
    }

    private fun width(viewport: Int, part: HudPart) = ((viewport - 2 * EDGE) / part.scale).toInt().coerceIn(1, WIDTH)

    fun previewSize(viewport: Int, part: HudPart) = width(viewport, part) to (rowHeight * examples.size + GAP * (examples.size - 1))

    fun preview(graphics: GuiGraphicsExtractor, client: Minecraft, width: Int) {
        for ((index, example) in examples.withIndex()) {
            row(graphics, client, example, Box(0, index * (rowHeight + GAP), width, rowHeight), 20f)
        }
    }

    fun draw(graphics: GuiGraphicsExtractor, client: Minecraft, now: Long) {
        if (client.player == null || client.player !== player || client.level !== level || entries.isEmpty()) return
        val part = Travelto.state.hud.effects
        val height = rowHeight
        val available = ((graphics.guiHeight() - 2 * EDGE) / part.scale).toInt()
        if (available < height) return
        val rows = entries.values.asSequence().mapNotNull { entry ->
            val amount = entry.motion.toward(if (entry.visible) 1f else 0f, now)
            if (amount <= 0f) null else entry.effect to amount
        }.take((available + GAP) / (height + GAP)).toList()
        if (rows.isEmpty()) return
        val span = width(graphics.guiWidth(), part)
        val tall = (rows.sumOf { ((height + GAP) * it.second).toDouble() }.roundToInt() - GAP).coerceAtLeast(1)
        val bounds = Hud.place(graphics.guiWidth(), graphics.guiHeight(), part, span, tall)
        val tickRate = level?.tickRateManager()?.tickrate() ?: 20f
        val pose = graphics.pose()
        graphics.nextStratum()
        pose.pushMatrix().translate(bounds.x.toFloat(), bounds.y.toFloat()).scale(part.scale)
        try {
            var top = 0f
            val direction = if (part.x < .5f) -12 else 12
            for ((effect, amount) in rows) {
                val x = (direction * (1f - amount)).roundToInt()
                val y = top.roundToInt()
                val visibleHeight = (top + height * amount).roundToInt() - y
                val surface = Box(x, y, span, height)
                if (visibleHeight > 0) Ui.faded(amount) {
                    if (amount == 1f) row(graphics, client, effect, surface, tickRate)
                    else RoundedRect.clipped(graphics, surface.copy(h = visibleHeight)) { row(graphics, client, effect, surface, tickRate) }
                }
                top += (height + GAP) * amount
            }
        } finally { pose.popMatrix() }
    }

    private fun row(graphics: GuiGraphicsExtractor, client: Minecraft, effect: MobEffectInstance, bounds: Box, tickRate: Float) {
        if (bounds.w <= TEXT_X + INSET) return
        Ui.box(graphics, bounds, Ui.INSET)
        val marker = Box(bounds.x, bounds.y + 4, 2, bounds.h - 8)
        Ui.themeBox(graphics, marker, radius = 1)
        val icon = Box(bounds.x + INSET, bounds.y + (bounds.h - ICON) / 2, ICON, ICON)
        val name = effect.effect.value().displayName.string
        if (!drawIcon(graphics, client, effect.effect, icon)) {
            val symbol = String(Character.toChars(name.codePoints().findFirst().orElse(63)))
            Ui.text(graphics, symbol, icon.x + (icon.w - Ui.body.width(symbol)) / 2, icon.y + (icon.h - Ui.textHeight) / 2, Ui.MUTED, false)
        }
        val textX = bounds.x + TEXT_X
        val available = bounds.right - textX - INSET
        val first = bounds.y + (bounds.h - 2 * Ui.textHeight) / 2
        val level = effect.amplifier + 1
        val suffix = if (level <= 1) "" else " " + if (level <= 10) Component.translatable("enchantment.level.$level").string else level.toString()
        Ui.text(graphics, Ui.fit(name, available - Ui.body.width(suffix)) + suffix, textX, first, Ui.TEXT, false)
        val duration = MobEffectUtil.formatDuration(effect, 1f, tickRate)
        Ui.text(graphics, Ui.fit(duration.string, available), textX, first + Ui.textHeight, Ui.MUTED, false)
    }

    private fun drawIcon(graphics: GuiGraphicsExtractor, client: Minecraft, effect: Holder<MobEffect>, box: Box): Boolean {
        val tint = Ui.tint(-1)
        if (tint ushr 24 <= 3) return true
        val id = VanillaHud.getMobEffectSprite(effect)
        val atlas = client.atlasManager.getAtlasOrThrow(AtlasIds.GUI)
        val sprite = atlas.getSprite(id)
        if (sprite !== atlas.missingSprite()) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, box.x, box.y, box.w, box.h, tint)
            return true
        }
        val texture = id.withPath("textures/${id.path}.png")
        if (client.resourceManager.getResource(texture).isPresent) {
            Ui.image(graphics, texture, box, TILE)
            return true
        }
        if (id.namespace != "minecraft") return false
        val index = cells[id.path.removePrefix("mob_effect/")] ?: return false
        val rows = (cells.size + COLUMNS - 1) / COLUMNS
        graphics.blit(RenderPipelines.GUI_TEXTURED, fallback, box.x, box.y, (index % COLUMNS * TILE).toFloat(),
            (index / COLUMNS * TILE).toFloat(), box.w, box.h, TILE, TILE, COLUMNS * TILE, rows * TILE, tint)
        return true
    }
}
