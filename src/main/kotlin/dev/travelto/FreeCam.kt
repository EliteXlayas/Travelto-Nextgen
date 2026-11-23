package dev.travelto

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

internal val FREE_CAM_SPEED = 2..40

internal enum class FreeCamCancel(val key: String) {
    DAMAGE("damage"), TELEPORT("teleport"), MOVE("move"), LIQUID("liquid")
}

internal data class FreeCamOptions(var speed: Int = 20, var cancelOn: MutableSet<FreeCamCancel> = linkedSetOf())

internal object FreeCam {
    data class View(val position: Vec3, val yaw: Float, val pitch: Float)

    private class Flight(val level: ClientLevel, val player: LocalPlayer) {
        var position: Vec3 = player.eyePosition
        var previous: Vec3 = position
        var yaw: Float = player.yRot
        var pitch: Float = player.xRot
        var body: Vec3 = player.position()
        var health: Float = player.health

        fun matches(client: Minecraft) = level === client.level && player === client.player && player === client.cameraEntity
    }

    private var flight: Flight? = null

    private fun current(client: Minecraft) = flight?.takeIf { it.matches(client) }

    fun active(client: Minecraft) = current(client) != null

    fun setActive(client: Minecraft, enabled: Boolean): Boolean {
        if (!enabled) {
            flight = null
            return false
        }
        if (active(client)) return true
        val level = client.level
        val player = client.player
        flight = if (level != null && player != null && player.isAlive && client.cameraEntity === player) Flight(level, player) else null
        return flight != null
    }

    fun view(client: Minecraft, partial: Float) = current(client)?.let {
        View(it.previous.lerp(it.position, partial.toDouble()), it.yaw, it.pitch)
    }

    fun turn(client: Minecraft, dx: Double, dy: Double): Boolean {
        val camera = current(client) ?: return false
        camera.yaw = Mth.wrapDegrees(camera.yaw + dx.toFloat() * .15f)
        camera.pitch = (camera.pitch + dy.toFloat() * .15f).coerceIn(-90f, 90f)
        return true
    }

    fun cancelOn(client: Minecraft, reason: FreeCamCancel) {
        if (active(client) && reason in Travelto.state.freeCam.cancelOn) Travelto.stopFreeCam()
    }

    fun tick(client: Minecraft, playing: Boolean) {
        val camera = flight ?: return
        val options = Travelto.state.freeCam
        val player = camera.player
        val body = player.position()
        if (!camera.matches(client) || !player.isAlive ||
            FreeCamCancel.DAMAGE in options.cancelOn && player.health < camera.health ||
            FreeCamCancel.MOVE in options.cancelOn && (body.x != camera.body.x || body.z != camera.body.z) ||
            FreeCamCancel.LIQUID in options.cancelOn && (player.isInWater || player.isInLava)) {
            Travelto.stopFreeCam()
            return
        }
        camera.body = body
        camera.health = player.health
        camera.previous = camera.position
        if (!playing || !client.mouseHandler.isMouseGrabbed) return

        val controls = client.options
        fun axis(positive: Boolean, negative: Boolean) = if (positive == negative) 0.0 else if (positive) 1.0 else -1.0
        val forward = axis(controls.keyUp.isDown, controls.keyDown.isDown)
        val left = axis(controls.keyLeft.isDown, controls.keyRight.isDown)
        val up = axis(controls.keyJump.isDown, controls.keyShift.isDown)
        val speed = options.speed / 20.0
        val horizontal = Vec3(left, 0.0, forward).normalize().yRot(-camera.yaw * Mth.DEG_TO_RAD).scale(speed)
        camera.position = camera.position.add(horizontal.x, up * speed, horizontal.z)
    }
}
