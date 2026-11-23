package dev.travelto.mixin

import dev.travelto.FreeCam
import dev.travelto.FreeCamCancel
import net.minecraft.client.Camera
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Hud
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.ClientInput
import net.minecraft.client.player.KeyboardInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.client.renderer.state.level.PlayerRenderState
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.player.Input
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyVariable
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Camera::class)
abstract class FreeCamCameraMixin {
    @Shadow private var detached = false
    @Shadow protected abstract fun setRotation(yaw: Float, pitch: Float)
    @Shadow protected abstract fun setPosition(position: Vec3)

    @Inject(method = ["alignWithEntity"], at = [At("HEAD")], cancellable = true)
    private fun traveltoFreeCamView(partial: Float, callback: CallbackInfo) {
        val view = FreeCam.view(Minecraft.getInstance(), partial) ?: return
        setRotation(view.yaw, view.pitch)
        setPosition(view.position)
        detached = true
        callback.cancel()
    }

    @Inject(method = ["extractRenderState"], at = [At("TAIL")])
    private fun traveltoFreeCamPerspective(state: CameraRenderState, delta: DeltaTracker, callback: CallbackInfo) {
        val client = Minecraft.getInstance()
        if (!FreeCam.active(client)) return
        state.isFirstPerson = false
        state.entityRenderState.isLiving = false
        state.entityRenderState.isPlayer = false
        if (client.level?.getBlockState(state.blockPos)?.isSolidRender == true) state.smartCull = false
    }
}

@Mixin(MouseHandler::class)
abstract class FreeCamMouseMixin {
    @Redirect(method = ["turnPlayer"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V")])
    private fun traveltoFreeCamTurn(player: LocalPlayer, dx: Double, dy: Double) {
        if (!FreeCam.turn(Minecraft.getInstance(), dx, dy)) player.turn(dx, dy)
    }
}

@Mixin(KeyboardInput::class)
abstract class FreeCamKeyboardMixin : ClientInput() {
    @Inject(method = ["tick"], at = [At("HEAD")], cancellable = true)
    private fun traveltoFreeCamKeyboard(callback: CallbackInfo) {
        val client = Minecraft.getInstance()
        if (client.player?.input !== this || !FreeCam.active(client)) return
        keyPresses = Input.EMPTY
        moveVector = Vec2.ZERO
        callback.cancel()
    }
}

@Mixin(ClientInput::class)
abstract class FreeCamAutoJumpMixin {
    @Inject(method = ["makeJump"], at = [At("HEAD")], cancellable = true)
    private fun traveltoFreeCamAutoJump(callback: CallbackInfo) {
        val client = Minecraft.getInstance()
        val localInput: Any? = client.player?.input
        if (localInput === this && FreeCam.active(client)) callback.cancel()
    }
}

@Mixin(Minecraft::class)
abstract class FreeCamInteractionsMixin {
    @Inject(method = ["startAttack"], at = [At("HEAD")], cancellable = true)
    private fun traveltoFreeCamAttack(callback: CallbackInfoReturnable<Boolean>) {
        if (FreeCam.active(Minecraft.getInstance())) callback.returnValue = false
    }

    @ModifyVariable(method = ["continueAttack"], at = [At("HEAD")], argsOnly = true)
    private fun traveltoFreeCamDig(held: Boolean) = held && !FreeCam.active(Minecraft.getInstance())

    @Inject(method = ["startUseItem", "pickBlockOrEntity"], at = [At("HEAD")], cancellable = true)
    private fun traveltoFreeCamInteract(callback: CallbackInfo) {
        if (FreeCam.active(Minecraft.getInstance())) callback.cancel()
    }
}

@Mixin(GameRenderer::class)
abstract class FreeCamRenderOptionsMixin {
    @Redirect(method = ["extractOptions"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCameraType()Lnet/minecraft/client/CameraType;")])
    private fun traveltoFreeCamRenderOptions(options: Options): CameraType =
        if (FreeCam.active(Minecraft.getInstance())) CameraType.THIRD_PERSON_BACK else options.cameraType
}

@Mixin(LevelExtractor::class)
abstract class FreeCamLevelRenderMixin {
    @Inject(method = ["extractBlockOutline"], at = [At("HEAD")], cancellable = true)
    private fun traveltoFreeCamOutline(camera: Camera, state: LevelRenderState, callback: CallbackInfo) {
        if (!FreeCam.active(Minecraft.getInstance())) return
        state.blockOutlineRenderState = null
        callback.cancel()
    }

    @Inject(method = ["extractPlayerState"], at = [At("TAIL")])
    private fun traveltoFreeCamOverlays(camera: Camera, delta: DeltaTracker, partial: Float, state: PlayerRenderState, callback: CallbackInfo) {
        if (!FreeCam.active(Minecraft.getInstance())) return
        state.blockOverlay = null
        state.waterOverlay = null
        state.isOnFire = false
    }
}

@Mixin(Hud::class)
abstract class FreeCamCrosshairMixin {
    @Redirect(method = ["extractCameraOverlays"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCameraType()Lnet/minecraft/client/CameraType;")])
    private fun traveltoFreeCamOverlayPerspective(options: Options): CameraType =
        if (FreeCam.active(Minecraft.getInstance())) CameraType.THIRD_PERSON_BACK else options.cameraType

    @Inject(method = ["extractCrosshair"], at = [At("HEAD")], cancellable = true)
    private fun traveltoFreeCamCrosshair(graphics: GuiGraphicsExtractor, delta: DeltaTracker, callback: CallbackInfo) {
        if (FreeCam.active(Minecraft.getInstance())) callback.cancel()
    }
}

@Mixin(ClientPacketListener::class)
abstract class FreeCamPacketsMixin {
    @Inject(method = ["handleMovePlayer"], at = [At("TAIL")])
    private fun traveltoFreeCamTeleport(packet: ClientboundPlayerPositionPacket, callback: CallbackInfo) {
        FreeCam.cancelOn(Minecraft.getInstance(), FreeCamCancel.TELEPORT)
    }

    @Inject(method = ["handleDamageEvent"], at = [At("TAIL")])
    private fun traveltoFreeCamDamage(packet: ClientboundDamageEventPacket, callback: CallbackInfo) {
        val client = Minecraft.getInstance()
        if (packet.entityId() == client.player?.id) FreeCam.cancelOn(client, FreeCamCancel.DAMAGE)
    }
}
