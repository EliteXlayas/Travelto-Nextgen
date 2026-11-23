package dev.travelto

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.decoration.painting.Painting
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

internal object PingMarker {
    private const val MAX_ENTITY_MARKS = 6
    private data class Mark(val started: Long, val expires: Long, val name: Component)
    private data class BlockMark(val mark: Mark, val position: Vec3)
    private data class EntityMark(val mark: Mark, val entity: Entity)
    private var world: ClientLevel? = null
    private var settings: PingOptions? = null
    private var blockMark: BlockMark? = null
    private val entityMarks = linkedMapOf<UUID, EntityMark>()
    private val projection = Matrix4f()
    private val clip = Vector4f()

    fun resolveSound(client: Minecraft, name: String): Identifier? =
        name.trim().takeIf { it.isNotEmpty() && it.length <= 128 }?.let(Identifier::tryParse)
            ?.takeIf { it in client.soundManager.availableSounds }

    fun playSound(client: Minecraft, sound: Identifier, volume: Int) {
        if (volume > 0) client.soundManager.play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(sound), 1f, volume / 100f))
    }

    fun tick(client: Minecraft, pressed: Boolean) {
        val options = Travelto.state.ping
        val level = client.level
        val player = client.player
        val enabled = Travelto.state.enabled(Module.PING)
        if (level !== world || settings !== options || player == null || !enabled) {
            blockMark = null
            entityMarks.clear()
        }
        world = level
        settings = options
        if (level == null || player == null || !enabled) return
        val now = System.nanoTime()
        if (blockMark?.let { now >= it.mark.expires } == true) blockMark = null
        entityMarks.values.removeIf { now >= it.mark.expires || !it.entity.isAlive }
        if (!pressed || !player.isAlive || player.isSpectator) return
        val expires = now + options.seconds * 1_000_000_000L
        val hit = AimRay.pick(client, options.range, 1f) { entity ->
            entity.isPickable || entity is ItemEntity && entity.isAlive && !entity.item.isEmpty
        }
        when (hit) {
            is BlockHitResult -> {
                val face = hit.direction
                val point = hit.location.add(face.stepX * .03, face.stepY * .03, face.stepZ * .03)
                blockMark = BlockMark(Mark(now, expires, level.getBlockState(hit.blockPos).block.name), point)
            }
            is EntityHitResult -> {
                val entity = hit.entity
                if (!entity.isAlive || entity is Painting || entity is ItemFrame) return
                entityMarks.remove(entity.uuid)
                if (entityMarks.size == MAX_ENTITY_MARKS) entityMarks.remove(entityMarks.keys.first())
                val name = (entity as? ItemEntity)?.item?.hoverName ?: entity.displayName
                entityMarks[entity.uuid] = EntityMark(Mark(now, expires, name), entity)
            }
            else -> return
        }
        val sound = resolveSound(client, options.sound) ?: resolveSound(client, DEFAULT_PING_SOUND)
        if (sound != null) playSound(client, sound, options.volume)
    }

    private fun anchor(entity: Entity, partial: Float) = entity.getPosition(partial).add(0.0, entity.bbHeight * .7, 0.0)

    fun draw(graphics: GuiGraphicsExtractor, client: Minecraft, partial: Float, now: Long) {
        if (client.level !== world || !Travelto.state.enabled(Module.PING)) return
        if (blockMark == null && entityMarks.isEmpty()) return
        val camera = client.gameRenderer.mainCamera()
        if (!camera.isInitialized || graphics.guiWidth() < 56 || graphics.guiHeight() < 56) return
        camera.getViewRotationProjectionMatrix(projection)
        val origin = camera.position()
        val reference = client.player?.position() ?: origin
        val badges = ArrayList<Box>(entityMarks.size + 1)
        graphics.nextStratum()
        blockMark?.let { drawMark(graphics, it.mark, it.position, origin, reference, badges, now) }
        for ((mark, entity) in entityMarks.values) {
            if (entity.isAlive && now < mark.expires) {
                drawMark(graphics, mark, anchor(entity, partial), origin, reference, badges, now, (entity as? ItemEntity)?.item)
            }
        }
    }

    private fun drawMark(graphics: GuiGraphicsExtractor, mark: Mark, position: Vec3, origin: Vec3,
                         reference: Vec3, badges: MutableList<Box>, now: Long, item: ItemStack? = null) {
        if (now >= mark.expires) return
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val relative = position.subtract(origin)
        projection.transform(clip.set(relative.x.toFloat(), relative.y.toFloat(), relative.z.toFloat(), 1f))
        if (!clip.x.isFinite() || !clip.y.isFinite() || !clip.w.isFinite()) return
        val centerX = width / 2f
        val centerY = height / 2f
        val limitX = centerX - 19f
        val limitY = centerY - 22f
        val denom = abs(clip.w).coerceAtLeast(.001f)
        var dx = clip.x / denom * centerX
        var dy = -clip.y / denom * centerY
        if (clip.w <= 0f && abs(dx) + abs(dy) < 1f) dy = limitY
        val extent = maxOf(abs(dx) / limitX, abs(dy) / limitY)
        val atEdge = clip.w <= .001f || extent > 1f
        if (atEdge && extent > .0001f) { dx /= extent; dy /= extent }
        val x = (centerX + dx).roundToInt()
        val y = (centerY + dy).roundToInt()
        val meters = reference.distanceTo(position).roundToInt()
        val remaining = mark.expires - now
        val outro = MotionSettings.duration(260L) * 1_000_000L
        val visibility = if (Travelto.state.reducedMotion) 1f else MotionSettings.progress(now - mark.started, 175) *
            if (remaining >= outro) 1f else 1f - MotionSettings.progress(outro - remaining, 260)
        if (visibility <= 0f) return
        Ui.faded(visibility) {
            val pose = graphics.pose()
            pose.pushMatrix().translate(x.toFloat(), y.toFloat())
            try {
                if (item != null && !item.isEmpty) {
                    HudEffects.item(graphics, item, Box(-8, -8, 16, 16))
                } else if (atEdge) {
                    pose.rotate(atan2(dy, dx))
                    for (side in listOf(-1, 1)) {
                        pose.pushMatrix().translate(1f, 0f).rotate((side * PI / 4).toFloat())
                        Ui.box(graphics, Box(-7, -2, 9, 4), Ui.INSET, 2)
                        Ui.themeBox(graphics, Box(-6, -1, 8, 2), radius = 1)
                        pose.popMatrix()
                    }
                } else {
                    pose.rotate((PI / 4).toFloat())
                    val diamond = Box(-5, -5, 10, 10)
                    Ui.box(graphics, diamond, Ui.INSET, 2)
                    Ui.themeStroke(graphics, diamond.inset(1), radius = 1, thickness = 1.5f)
                }
            } finally { pose.popMatrix() }
            val maxWidth = minOf(150, width - 12)
            val distance = Ui.label("travelto.unit.blocks", meters).string
            val caption = Ui.fit(if (atEdge) distance else "${mark.name.string} · $distance", maxWidth - 12)
            if (caption.isEmpty()) return@faded
            val span = Ui.body.width(caption) + 12
            val badge = placeBadge(Box((x - span / 2).coerceIn(6, width - span - 6), y, span, Ui.textHeight + 6), height, badges)
                ?: return@faded
            badges += badge
            Ui.box(graphics, badge, HudEffects.solidColor(), 4)
            Ui.stroke(graphics, badge, Ui.LINE, 4)
            Ui.text(graphics, caption, badge.x + 6, badge.y + 3, shadow = false)
        }
    }

    private fun placeBadge(base: Box, height: Int, occupied: List<Box>): Box? {
        val below = base.y + 10
        val above = base.y - base.h - 10
        val stride = base.h + 2
        fun candidate(top: Int): Box? {
            if (top < 4 || top + base.h > height - 4) return null
            val box = base.copy(y = top)
            val margin = box.inset(-2)
            return box.takeIf { occupied.none { margin.intersection(it) != null } }
        }
        for (step in 0..occupied.size) {
            val badge = candidate(below + step * stride) ?: candidate(above - step * stride)
            if (badge != null) return badge
        }
        return null
    }
}
