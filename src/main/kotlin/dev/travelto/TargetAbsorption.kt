package dev.travelto

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import java.util.UUID

internal object TargetAbsorption {
    private data class Request(val server: IntegratedServer, val dimension: ResourceKey<Level>, val targets: Set<UUID>)
    private data class Sample(val request: Request, val remaining: Map<UUID, Float>)
    @Volatile private var request: Request? = null
    @Volatile private var sample: Sample? = null

    fun initialize() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            val requested = request ?: return@register
            if (server !== requested.server) return@register
            val level = server.getLevel(requested.dimension) ?: return@register
            val remaining = buildMap {
                for (uuid in requested.targets) {
                    val entity = level.getEntity(uuid) as? LivingEntity ?: continue
                    put(uuid, entity.absorptionAmount)
                }
            }
            if (request === requested) sample = Sample(requested, remaining)
        }
    }

    fun track(client: Minecraft, targets: Collection<LivingEntity>) {
        val server = client.singleplayerServer
        val dimension = client.level?.dimension()
        val ids = if (server == null) emptySet() else targets.asSequence().filterNot { it is Player }.map { it.uuid }.toSet()
        val next = if (server != null && dimension != null && ids.isNotEmpty()) Request(server, dimension, ids) else null
        if (request != next) request = next
        if (next == null) sample = null
    }

    fun remaining(entity: LivingEntity): Float {
        val requested = request
        val available = sample
        val local = if (available != null && available.request === requested) available.remaining[entity.uuid] else null
        return (local ?: entity.absorptionAmount).takeIf { it.isFinite() && it > 0f } ?: 0f
    }
}
