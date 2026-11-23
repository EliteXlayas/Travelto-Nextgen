package dev.travelto

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult

internal object AimRay {
    fun pick(client: Minecraft, range: Int, partial: Float, canPick: (Entity) -> Boolean = { it.isPickable }): HitResult? {
        if (FreeCam.active(client)) return null
        val level = client.level ?: return null
        val viewer = client.cameraEntity ?: return null
        val start = viewer.getEyePosition(partial)
        val offset = viewer.getViewVector(partial).scale(range.toDouble())
        val end = start.add(offset)
        val block = level.clip(ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer))
        val limit = if (block.type == HitResult.Type.MISS) range.toDouble() * range else start.distanceToSqr(block.location)
        val entity = ProjectileUtil.getEntityHitResult(viewer, start, end, viewer.boundingBox.expandTowards(offset).inflate(1.0),
            { candidate -> !candidate.isSpectator && canPick(candidate) }, limit)
        val distance = entity?.let { start.distanceToSqr(it.location) }
        return if (distance != null && (distance < limit || block.type == HitResult.Type.MISS && distance <= limit)) entity
        else if (block.type != HitResult.Type.MISS) block else null
    }
}
