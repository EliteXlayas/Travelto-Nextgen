package dev.travelto

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin

internal object ItemPhysics {
    interface Tracked {
        var traveltoTumble: Tumble?
    }

    private data class Pose(val pitch: Float, val floorLift: Double)
    private val pose = RenderStateDataKey.create<Pose> { "travelto:item_physics_pose" }
    private const val FLAT_DEPTH = 1f / 16f
    private const val FLOOR_CLEARANCE = .002
    private const val AIR_SPIN = .36
    private const val DAMPING = .62

    class Tumble(age: Double, seed: Float, grounded: Boolean, flat: Boolean) {
        private class Spin(val pitch: Double, val speed: Double, val since: Double, val rest: Double?) {
            private val slope = if (rest != null) speed + DAMPING * (pitch - rest) else speed - AIR_SPIN

            fun at(age: Double): Double {
                val time = (age - since).coerceAtLeast(0.0)
                val decay = exp(-DAMPING * time)
                return if (rest != null) rest + (pitch - rest + slope * time) * decay
                else pitch + AIR_SPIN * time + slope * (1 - decay) / DAMPING
            }

            fun rate(age: Double): Double {
                val time = (age - since).coerceAtLeast(0.0)
                val decay = exp(-DAMPING * time)
                return if (rest != null) (speed - DAMPING * slope * time) * decay else AIR_SPIN + slope * decay
            }
        }

        private var startFlat = flat
        private var spin = if (grounded) {
            val rest = if (flat) -PI / 2 else 0.0
            Spin(rest, 0.0, age, rest)
        } else Spin(age * AIR_SPIN + seed, AIR_SPIN, age, null)

        fun sample(age: Double, grounded: Boolean, flat: Boolean): Float {
            val pitch = spin.at(age)
            if (grounded != (spin.rest != null) || grounded && flat != startFlat) {
                val speed = spin.rate(age)
                val faceStep = if (flat) PI else PI / 2
                val firstFace = if (flat) PI / 2 else 0.0
                val rest = if (grounded) firstFace + round((pitch + speed / DAMPING - firstFace) / faceStep) * faceStep else null
                spin = Spin(pitch, speed, age, rest)
                startFlat = flat
            }
            return ((pitch % (2 * PI)).toFloat())
        }
    }

    fun extract(entity: ItemEntity, state: ItemEntityRenderState) {
        val tracked = entity as Tracked
        if (!Travelto.state.enabled(Module.ITEM_PHYSICS) || state.item.isEmpty) {
            tracked.traveltoTumble = null
            state.setData(pose, null)
            return
        }
        val age = state.ageInTicks.toDouble()
        val grounded = entity.onGround()
        val flat = state.item.modelBoundingBox.getZsize().toFloat() <= FLAT_DEPTH
        val tumble = tracked.traveltoTumble ?: Tumble(age, state.bobOffset, grounded, flat).also { tracked.traveltoTumble = it }
        val floorLift = if (grounded) (entity.boundingBox.minY - state.y).coerceAtLeast(0.0) else 0.0
        state.setData(pose, Pose(tumble.sample(age, grounded, flat), floorLift))
    }

    fun active(state: ItemEntityRenderState) = state.getData(pose) != null

    fun transform(state: ItemEntityRenderState, poses: PoseStack, bounds: AABB, random: RandomSource) {
        val visual = state.getData(pose) ?: return
        val upY = cos(visual.pitch)
        val upZ = -sin(visual.pitch)
        val modelBottom = minOf(bounds.minY * upY, bounds.maxY * upY) + minOf(bounds.minZ * upZ, bounds.maxZ * upZ)
        val stackBottom = stackBottom(state, bounds.getZsize().toFloat(), random, upY, upZ)
        poses.translate(0.0, visual.floorLift + FLOOR_CLEARANCE - modelBottom - stackBottom, 0.0)
        poses.rotate(Axis.YP, state.bobOffset)
        poses.rotate(Axis.XP, visual.pitch)
    }

    private fun stackBottom(state: ItemEntityRenderState, depth: Float, random: RandomSource, upY: Float, upZ: Float): Float {
        if (state.count <= 1) return 0f
        random.setSeed(state.seed.toLong())
        val flat = depth <= FLAT_DEPTH
        val step = if (flat) depth * 1.5f else 0f
        var z = -(step * (state.count - 1) / 2f)
        var bottom = upZ * z
        repeat(state.count - 1) {
            random.nextFloat()
            val y = (random.nextFloat() * 2f - 1f) * .15f
            z = if (flat) z + step else (random.nextFloat() * 2f - 1f) * .15f
            bottom = minOf(bottom, upY * (if (flat) y * .5f else y) + upZ * z)
        }
        return bottom
    }
}
