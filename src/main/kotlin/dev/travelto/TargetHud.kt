package dev.travelto

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.phys.EntityHitResult
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal object TargetHud {
    private val motion = MotionScope.TARGET
    private const val CAPACITY = 4
    private const val GAP_X = 10
    private const val GAP_Y = 8
    private const val LOST_HOLD = 140_000_000L
    private const val ATTACK_HOLD = 2_450_000_000L
    private const val DEATH_MS = 360
    private const val EDGE = 5
    private val spawnEggs = mutableMapOf<EntityType<*>, ItemStack>()

    private data class Sample(val entity: LivingEntity, val until: Long)
    private data class Point(val x: Float, val y: Float) {
        fun toward(other: Point, amount: Float) = Point(HudEffects.blend(x, other.x, amount), HudEffects.blend(y, other.y, amount))
    }
    private data class Size(val w: Int, val h: Int)
    private data class Placement(val center: Point, val size: Size, val scale: Float, val visibility: Float)
    private class Card(var entity: LivingEntity, val started: Long) {
        var lastSeen = started
        var closeAt: Long? = null
        var diedAt: Long? = null
        var hurtAt: Long? = null
        var oldHurt = 0
        var health = fraction(entity.health, entity.maxHealth)
        var lastHealth = health
        var trail = health
        var absorb = absorption(entity)
        val activation = Motion(0f, 390, motion)
        var slot = 0
        var parentId: Int? = null
        var center: Point? = null
        var placement: Placement? = null
        var lastProjection = Long.MIN_VALUE
        var projection: Point? = null
        var distanceScale = 1f
        var right: Boolean? = null
        var value = ""
        var oldValue = ""
        var rolledAt = started
    }

    private val recent = linkedMapOf<Int, Sample>()
    private val cards = linkedMapOf<Int, Card>()
    private val styles = TargetStyle.entries.associateWith { Motion(0f, 240, motion) }
    private var level: ClientLevel? = null
    private var profile: HudStyle? = null
    private var lastFrame = 0L
    private var lastFollow = false
    private var initializedStyle = false

    private fun fraction(amount: Float, maximum: Float): Float =
        if (maximum > 0f && amount.isFinite() && maximum.isFinite()) (amount / maximum).coerceIn(0f, 1f) else 0f

    private fun absorption(entity: LivingEntity): Float {
        val amount = TargetAbsorption.remaining(entity)
        val maximum = entity.maxHealth
        return if (amount.isFinite() && maximum.isFinite() && maximum > 0f) (amount / maximum).coerceAtLeast(0f) else 0f
    }

    private fun sync(client: Minecraft) {
        val state = Travelto.state.hud
        if (level === client.level && profile === state) return
        level = client.level
        profile = state
        recent.clear()
        cards.clear()
        lastFrame = 0L
        initializedStyle = false
    }

    fun tick(client: Minecraft) {
        sync(client)
        val now = System.nanoTime()
        recent.values.removeIf { it.until <= now || it.entity.isRemoved }
        if (!Travelto.state.enabled(Module.TARGET_HUD)) {
            cards.clear()
            recent.clear()
        }
        TargetAbsorption.track(client, cards.values.map { it.entity })
    }

    fun attacked(client: Minecraft, entity: LivingEntity) {
        if (!Travelto.state.enabled(Module.TARGET_HUD) || entity === client.player || !entity.isAlive) return
        sync(client)
        recent.remove(entity.id)
        recent[entity.id] = Sample(entity, System.nanoTime() + ATTACK_HOLD)
        while (recent.size > CAPACITY) recent.remove(recent.keys.first())
    }

    private fun measure(style: TargetStyle, name: String, available: Int): Size {
        val width = if (style == TargetStyle.TRAVELTO) 116 else maxOf(145, Ui.body.width(name) + 12)
        val height = if (style == TargetStyle.TRAVELTO) maxOf(38, Ui.textHeight * 2 + 15) else maxOf(37, Ui.textHeight * 2 + 14)
        return Size(minOf(available.coerceAtLeast(1), width), height)
    }

    fun previewSize(client: Minecraft, available: Int): Pair<Int, Int> {
        val size = measure(Travelto.state.hud.targetHudOptions.style, previewName(client), available)
        return size.w to size.h
    }

    private fun previewName(client: Minecraft) = client.player?.displayName?.string ?: client.user.name

    fun preview(graphics: GuiGraphicsExtractor, client: Minecraft, bounds: Box) {
        val entity = client.player
        val ratio = entity?.let { fraction(it.health, it.maxHealth) }?.takeIf { it > 0f } ?: .76f
        val absorption = entity?.let(::absorption) ?: .16f
        val hp = healthText((ratio + absorption) * (entity?.maxHealth ?: 20f))
        surface(graphics, bounds, Travelto.state.hud.targetHudOptions.style, previewName(client), entity,
            ratio, ratio, absorption, hp, hp, 1f, 0f, 0f, 0L, true)
    }

    fun draw(graphics: GuiGraphicsExtractor, client: Minecraft, partial: Float, now: Long) {
        sync(client)
        val hud = Travelto.state.hud
        val options = hud.targetHudOptions
        val part = hud.target
        if (!Travelto.state.enabled(Module.TARGET_HUD) || !part.visible) {
            cards.clear()
            return
        }
        val screen = client.gui.screen()
        if (screen != null && screen !is ChatScreen) return
        val desired = linkedMapOf<Int, LivingEntity>()
        if (screen is ChatScreen) {
            if (!options.followTarget) client.player?.takeIf { it.isAlive }?.let { desired[it.id] = it }
        } else {
            val aim = (AimRay.pick(client, options.aimRange, partial) as? EntityHitResult)?.entity as? LivingEntity
            if (aim != null && aim.isAlive && aim !== client.player) desired[aim.id] = aim
            for (sample in recent.values.toList().asReversed()) {
                if (desired.size >= CAPACITY) break
                if (sample.until > now && sample.entity.isAlive) desired.putIfAbsent(sample.entity.id, sample.entity)
            }
        }
        if (screen !is ChatScreen) client.player?.let { cards.remove(it.id) }
        else cards.keys.retainAll(desired.keys)
        updateCards(desired, now)
        if (cards.isEmpty()) return
        if (options.followTarget != lastFollow) {
            for (card in cards.values) {
                card.projection = null
                card.lastProjection = Long.MIN_VALUE
                card.center = null
                card.right = null
            }
            lastFollow = options.followTarget
        }
        if (!initializedStyle) {
            styles.forEach { (style, motion) -> motion.reset(if (style == options.style) 1f else 0f) }
            initializedStyle = true
        }
        val styleAmounts = styles.mapValues { (style, motion) -> motion.toward(if (style == options.style) 1f else 0f, now) }
        val elapsed = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, .05f)
        lastFrame = now
        val availableWidth = (graphics.guiWidth() - EDGE * 2).coerceAtLeast(1)
        val uiScale = minOf(part.scale, availableWidth / 78f)
        val scale = uiScale.coerceAtLeast(.01f)
        val logicalWidth = (availableWidth / scale).toInt()
        val sizes = cards.mapValues { (_, card) ->
            val name = card.entity.displayName.string
            val first = measure(TargetStyle.TRAVELTO, name, logicalWidth)
            val second = measure(TargetStyle.SKYLINE, name, logicalWidth)
            val mix = styleAmounts.getValue(TargetStyle.SKYLINE)
            Size(HudEffects.blend(first.w.toFloat(), second.w.toFloat(), mix).roundToInt(),
                HudEffects.blend(first.h.toFloat(), second.h.toFloat(), mix).roundToInt())
        }
        val maxWidth = sizes.values.maxOf { it.w }
        val maxHeight = sizes.values.maxOf { it.h }
        val columns = if (maxWidth * 2 + GAP_X <= logicalWidth) 2 else 1
        val ordered = cards.values.sortedWith(compareBy<Card> { it.closeAt == null }.thenBy { it.slot })
        val live = ordered.filter { it.closeAt == null }.sortedBy { it.slot }
        val layout = (if (live.isEmpty()) ordered else live).mapIndexed { index, card -> card.entity.id to index }.toMap()
        val count = layout.size.coerceAtLeast(1)
        val used = minOf(columns, count)
        val rows = (count + columns - 1) / columns
        val unscaledW = used * maxWidth + (used - 1) * GAP_X
        val unscaledH = rows * maxHeight + (rows - 1) * GAP_Y
        val origin = Hud.place(graphics.guiWidth(), graphics.guiHeight(), part, unscaledW, unscaledH, scale)
        val camera = client.gameRenderer.mainCamera()
        val projection = if (options.followTarget && camera.isInitialized) camera.getViewRotationProjectionMatrix(Matrix4f()) else null
        val vector = Vector4f()
        for (card in ordered) {
            val size = sizes.getValue(card.entity.id)
            val amount = card.activation.toward(if (card.closeAt == null) 1f else 0f, now)
            if (amount < .004f) { card.placement = null; continue }
            val damage = damageAmount(card, now)
            val death = deathAmount(card, now)
            val index = layout[card.entity.id] ?: card.slot.coerceIn(0, (rows * columns - 1).coerceAtLeast(0))
            val column = if (part.x > .5f) used - 1 - index % used else index % used
            val row = index / columns
            val localX = column * (maxWidth + GAP_X) + if (part.x > .5f) maxWidth - size.w else 0
            val base = Point(origin.x + (localX + size.w * .5f) * scale, origin.y + (row * (maxHeight + GAP_Y) + size.h * .5f) * scale)
            var location = base
            var visibility = amount
            if (options.followTarget) {
                if (projection != null && card.closeAt == null) {
                    val pos = card.entity.getPosition(partial).add(0.0, card.entity.bbHeight * .875, 0.0).subtract(camera.position())
                    projection.transform(vector.set(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat(), 1f))
                    if (vector.w > .001f) {
                        val nx = vector.x / vector.w
                        val ny = vector.y / vector.w
                        if (nx.isFinite() && ny.isFinite() && kotlin.math.abs(nx) <= 1.06f && kotlin.math.abs(ny) <= 1.06f) {
                            card.projection = Point((1f + nx) * graphics.guiWidth() * .5f, (1f - ny) * graphics.guiHeight() * .5f)
                            card.lastProjection = now
                            val range = pos.length().toFloat()
                            card.distanceScale = MotionSettings.follow(card.distanceScale, (1f - (range - 4f) * .045f).coerceIn(.72f, 1f), 12f, elapsed, motion)
                        }
                    }
                }
                val point = card.projection ?: continue
                val missing = now - card.lastProjection - 160_000_000L
                if (missing > 0L) visibility *= 1f - MotionSettings.progress(missing, 240, motion)
                val middle = graphics.guiWidth() / 2f
                val right = when {
                    card.right == null -> point.x <= middle
                    point.x < middle - 12f -> true
                    point.x > middle + 12f -> false
                    else -> card.right == true
                }
                card.right = right
                val halfW = size.w * scale * card.distanceScale * .5f
                val halfH = size.h * scale * card.distanceScale * .5f
                fun clamp(value: Float, dimension: Int, half: Float): Float =
                    if (2 * (half + EDGE) >= dimension) dimension * .5f else value.coerceIn(half + EDGE, dimension - half - EDGE)
                location = Point(clamp(point.x + (if (right) 1 else -1) * (12f + halfW), graphics.guiWidth(), halfW),
                    clamp(point.y, graphics.guiHeight(), halfH))
            }
            val old = card.center ?: location
            val rate = if (options.followTarget) 20f else 17f
            val center = Point(MotionSettings.follow(old.x, location.x, rate, elapsed, motion), MotionSettings.follow(old.y, location.y, rate, elapsed, motion))
            card.center = center
            val reduced = Travelto.state.reducedMotion
            val shake = if (reduced) 0f else sin(MotionSettings.raw(now - (card.hurtAt ?: now), 360, motion) * PI * 6).toFloat() * damage * 1.5f
            val entrance = if (reduced) 0f else (1f - amount) * if (card.closeAt == null) -6f else 3f
            val pulse = if (reduced || !options.cellMitosis || options.followTarget) 0f else
                sin(MotionSettings.progress(now - card.started, 560, motion) * PI).toFloat() * .055f
            val cardScale = (if (reduced) 1f else (.78f + .22f * amount + pulse)) * scale * if (options.followTarget) card.distanceScale else 1f
            card.placement = Placement(Point(center.x + shake * scale, center.y + (entrance + if (reduced) 0f else death * death * 8f) * scale),
                size, cardScale, visibility)
            updateHealth(card, now, elapsed)
        }
        val shape = !options.followTarget && options.cellMitosis && !Travelto.state.reducedMotion
        for (card in ordered) {
            var placement = card.placement ?: continue
            if (placement.visibility < .004f) continue
            if (shape) {
                val sibling = if (card.closeAt == null) card.parentId?.let(cards::get) else live.minByOrNull { other ->
                    other.placement?.center?.let { hypot(it.x - placement.center.x, it.y - placement.center.y) } ?: Float.MAX_VALUE
                }
                val relation = sibling?.placement
                if (relation != null && sibling !== card) {
                    val progress = if (card.closeAt == null) MotionSettings.progress(now - card.started, 760, motion) else placement.visibility
                    if (progress < .997f) {
                        val shifted = relation.center.toward(placement.center, progress)
                        val arc = sin(progress * PI).toFloat() * 5f * scale
                        placement = placement.copy(center = shifted.copy(y = shifted.y + if (card.slot % 2 == 0) arc else -arc))
                        membrane(graphics, relation, placement, progress)
                    }
                }
            }
            graphics.nextStratum()
            drawCard(graphics, card, placement, styleAmounts, desired.keys.firstOrNull() == card.entity.id, now)
        }
        cards.values.removeIf { it.closeAt != null && it.activation.finished(now) }
    }

    private fun updateCards(desired: Map<Int, LivingEntity>, now: Long) {
        val reserved = cards.values.filter { it.closeAt == null && (it.entity.id in desired || now - it.lastSeen < LOST_HOLD ||
            it.diedAt?.let { died -> MotionSettings.raw(now - died, DEATH_MS, motion) < 1f } == true) }.map { it.slot }.toMutableSet()
        for ((id, entity) in desired) {
            val current = cards[id]
            if (current != null && current.entity === entity && current.diedAt == null) {
                current.closeAt = null
                current.lastSeen = now
                reserved += current.slot
                continue
            }
            val sibling = cards.values.lastOrNull { it.entity.id != id && it.closeAt == null && it.placement != null }
            val slot = (0 until CAPACITY).firstOrNull { it !in reserved } ?: desired.keys.indexOf(id)
            cards[id] = Card(entity, now).apply { this.slot = slot; parentId = sibling?.entity?.id }
            reserved += slot
        }
        for (card in cards.values) {
            if (card.entity.isDeadOrDying && card.diedAt == null) card.diedAt = now
            val keep = card.diedAt?.let { MotionSettings.raw(now - it, DEATH_MS, motion) < 1f }
                ?: (card.entity.id in desired || now - card.lastSeen < LOST_HOLD)
            if (!keep && card.closeAt == null) card.closeAt = now
        }
        while (cards.size > CAPACITY * 2) {
            val victim = cards.entries.firstOrNull { it.key !in desired && it.value.closeAt != null } ?: break
            cards.remove(victim.key)
        }
    }

    private fun damageAmount(card: Card, now: Long): Float = card.hurtAt?.let {
        1f - MotionSettings.progress(now - it, 360, motion)
    } ?: 0f

    private fun deathAmount(card: Card, now: Long): Float = card.diedAt?.let {
        MotionSettings.progress(now - it, DEATH_MS, motion)
    } ?: 0f

    private fun updateHealth(card: Card, now: Long, elapsed: Float) {
        val entity = card.entity
        val target = fraction(entity.health, entity.maxHealth)
        if (target + .0001f < card.lastHealth || entity.hurtTime > card.oldHurt) card.hurtAt = now
        card.oldHurt = entity.hurtTime
        card.lastHealth = target
        card.health = MotionSettings.follow(card.health, target, 20f, elapsed, motion)
        card.absorb = MotionSettings.follow(card.absorb, absorption(entity), 16f, elapsed, motion)
        val lag = card.hurtAt?.let { MotionSettings.raw(now - it, 130, motion) < 1f } == true
        if (card.health > card.trail) card.trail = card.health
        else if (!lag) card.trail = MotionSettings.follow(card.trail, card.health, 5f, elapsed, motion)
        val text = healthText((entity.health + TargetAbsorption.remaining(entity)).coerceAtLeast(0f))
        if (card.value.isEmpty()) { card.value = text; card.oldValue = text }
        else if (card.value != text && MotionSettings.raw(now - card.rolledAt, 150, motion) >= 1f) {
            card.oldValue = card.value
            card.value = text
            card.rolledAt = now
        }
    }

    private fun healthText(value: Float): String {
        if (!value.isFinite()) return "—"
        if (value >= 1000f) return value.roundToInt().toString()
        val tenths = (value.coerceAtLeast(0f) * 10f).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }

    private fun membrane(graphics: GuiGraphicsExtractor, from: Placement, to: Placement, progress: Float) {
        val dx = to.center.x - from.center.x
        val dy = to.center.y - from.center.y
        val length = hypot(dx, dy)
        if (length < 2f || progress > .89f) return
        val width = (minOf(from.size.h * from.scale, to.size.h * to.scale) * .44f * (1f - MotionCurve.SMOOTH.ease(progress))).roundToInt()
        if (width < 2) return
        val pose = graphics.pose()
        pose.pushMatrix().translate(from.center.x, from.center.y).rotate(atan2(dy, dx))
        try {
            Ui.faded(minOf(from.visibility, to.visibility)) {
                val path = Box(0, -width / 2, length.roundToInt(), width)
                Ui.box(graphics, path, HudEffects.solidColor(), width / 2)
            }
        } finally { pose.popMatrix() }
    }

    private fun drawCard(graphics: GuiGraphicsExtractor, card: Card, placement: Placement, styles: Map<TargetStyle, Float>, primary: Boolean, now: Long) {
        val pose = graphics.pose()
        val size = placement.size
        pose.pushMatrix().translate(placement.center.x, placement.center.y).scale(placement.scale).translate(-size.w / 2f, -size.h / 2f)
        try {
            Ui.faded(placement.visibility) {
                val name = card.entity.displayName.string
                val damage = damageAmount(card, now)
                val death = deathAmount(card, now)
                val roll = MotionSettings.progress(now - card.rolledAt, 130, motion)
                HudEffects.solid(graphics, Box(0, 0, size.w, size.h), 4, primary)
                for ((style, alpha) in styles) if (alpha > .004f) Ui.faded(alpha) {
                    surface(graphics, Box(0, 0, size.w, size.h), style, name, card.entity, card.health, card.trail, card.absorb,
                        card.value, card.oldValue, roll, damage, death, now, false)
                }
            }
        } finally { pose.popMatrix() }
    }

    private fun mobIcon(graphics: GuiGraphicsExtractor, type: EntityType<*>, box: Box): Boolean {
        if (Travelto.state.hud.targetHudOptions.mobIcon == TargetMobIcon.SPAWN_EGG && EntityType.getKey(type).namespace == "minecraft") {
            val egg = spawnEggs.getOrPut(type) { SpawnEggItem.byId(type).map { it.value().defaultInstance }.orElse(ItemStack.EMPTY) }
            if (!egg.isEmpty) {
                HudEffects.item(graphics, egg, box)
                return true
            }
        }
        return MobPortraits.draw(graphics, type, box)
    }

    private fun surface(graphics: GuiGraphicsExtractor, bounds: Box, style: TargetStyle, name: String, entity: LivingEntity?,
                        health: Float, trail: Float, absorb: Float, hp: String, oldHp: String, roll: Float,
                        damage: Float, death: Float, now: Long, preview: Boolean) {
        if (preview) HudEffects.solid(graphics, bounds, 4)
        val travelto = style == TargetStyle.TRAVELTO
        val left = bounds.x + if (travelto) 34 else 6
        val right = bounds.right - 6
        if (right <= left) return
        val foreground = Ui.mix(Ui.TEXT, HudEffects.ASH, death * .63f)
        if (travelto) {
            val avatar = Box(bounds.x + 5, bounds.y + (bounds.h - 25) / 2, 25, 25)
            val pose = graphics.pose()
            val cx = avatar.x + avatar.w / 2f
            val cy = avatar.y + avatar.h / 2f
            pose.pushMatrix().translate(cx, cy).scale(1f - damage * .075f).translate(-cx, -cy)
            try {
                Ui.box(graphics, avatar, Ui.CONTROL, 6)
                val skin = (entity as? AbstractClientPlayer)?.skin ?: if (entity == null) DefaultPlayerSkin.getDefaultSkin() else null
                if (skin != null) HudEffects.face(graphics, skin, avatar.inset(2))
                else if (entity == null || !mobIcon(graphics, entity.type, avatar.inset(2))) {
                    val glyph = entity?.type?.description?.string?.let { it.codePoints().findFirst().orElse(63) } ?: 63
                    val icon = HudEffects.bold(String(Character.toChars(glyph)))
                    Ui.soften(graphics, icon, avatar.x + (avatar.w - Ui.body.width(icon)) / 2,
                        avatar.y + (avatar.h - Ui.textHeight) / 2, Travelto.state.hud.glow, Ui.ACCENT)
                }
                if (damage > 0f) Ui.box(graphics, avatar, Ui.alpha(HudEffects.DAMAGE, .25f * damage), 5)
                if (death > 0f) Ui.box(graphics, avatar, Ui.alpha(HudEffects.ASH, .42f * death), 5)
            } finally { pose.popMatrix() }
        }
        val fitted = HudEffects.bold(Ui.fit(name, right - left - 2))
        Ui.text(graphics, fitted, left, bounds.y + 6, foreground)
        val bar = Box(left, bounds.bottom - if (travelto) 9 else 11, right - left, if (travelto) 4 else 3)
        val normalization = 1f + absorb
        val healthW = (bar.w * health / normalization).roundToInt()
        val goldW = (bar.w * absorb / normalization).roundToInt().coerceAtMost(bar.w - healthW)
        val trailW = (bar.w * trail / normalization).roundToInt().coerceIn(0, bar.w)
        val start = Ui.mix(Ui.ACCENT, HudEffects.ASH, death)
        val end = Ui.mix(start, -1, .31f * (1f - death))
        Ui.box(graphics, bar, Ui.CONTROL, 2)
        HudEffects.gradient(graphics, bar.copy(w = trailW), 2f, Ui.alpha(start, .31f), Ui.alpha(end, .43f))
        val fill = bar.copy(w = healthW)
        Ui.bloom(graphics, fill, start, 2, .36f * (1f - death))
        HudEffects.gradient(graphics, fill, 2f, start, end)
        HudEffects.gradient(graphics, Box(bar.x + healthW, bar.y, goldW, bar.h), 2f,
            Ui.alpha(HudEffects.GOLD, 1f - death), Ui.alpha(0xffffe67e.toInt(), 1f - death))
        if (healthW > 6 && now != 0L && !Travelto.state.reducedMotion) {
            val shineW = minOf(healthW, 17)
            val travel = MotionSettings.phase(now, 1500, motion) * (healthW - shineW)
            val shine = Box(bar.x + travel.roundToInt(), bar.y, shineW, bar.h)
            HudEffects.gradient(graphics, shine.copy(w = shineW / 2), 2f, 0x00ffffff, Ui.alpha(0x49ffffff, 1f - death))
            HudEffects.gradient(graphics, Box(shine.x + shineW / 2, shine.y, shineW - shineW / 2, shine.h), 2f,
                Ui.alpha(0x49ffffff, 1f - death), 0x00ffffff)
        }
        if (travelto) {
            val top = bar.y - Ui.textHeight - 1
            if (roll < 1f && hp != oldHp) RoundedRect.clipped(graphics, Box(left, top, bar.w, Ui.textHeight + 1)) {
                Ui.faded(1f - roll) { HudEffects.small(graphics, oldHp, left, top - (roll * 6).roundToInt(), foreground) }
                Ui.faded(roll) { HudEffects.small(graphics, hp, left, top + ((1f - roll) * 6).roundToInt(), foreground) }
            } else HudEffects.small(graphics, hp, left, top, foreground)
        } else {
            val ratio = (((health + absorb) / normalization) * 100).roundToInt()
            val label = if (absorb > .001f) "$hp · $ratio%" else "$ratio%"
            val width = HudEffects.smallWidth(label)
            val x = (bar.x + healthW + goldW - width).coerceIn(bar.x, (bar.right - width).coerceAtLeast(bar.x))
            HudEffects.small(graphics, label, x, bar.y - Ui.textHeight - 1, foreground)
        }
    }
}
