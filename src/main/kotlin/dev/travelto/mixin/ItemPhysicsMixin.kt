package dev.travelto.mixin

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.travelto.ItemPhysics
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.ItemEntityRenderer
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

private const val SUBMIT = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"

@Mixin(ItemEntity::class)
internal abstract class ItemPhysicsStateMixin : ItemPhysics.Tracked {
    @field:Unique override var traveltoTumble: ItemPhysics.Tumble? = null
}

@Mixin(ItemEntityRenderer::class)
abstract class ItemPhysicsMixin {
    @Inject(method = ["extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V"], at = [At("TAIL")])
    private fun traveltoExtractItemPhysics(entity: ItemEntity, state: ItemEntityRenderState, partial: Float, callback: CallbackInfo) {
        ItemPhysics.extract(entity, state)
    }

    @Redirect(method = [SUBMIT], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V")])
    private fun traveltoItemBob(
        poses: PoseStack, x: Float, y: Float, z: Float,
        state: ItemEntityRenderState, stack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState
    ) {
        if (!ItemPhysics.active(state)) poses.translate(x, y, z)
    }

    @Redirect(method = [SUBMIT], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotate(Lcom/mojang/math/Axis;F)V")])
    private fun traveltoItemPose(
        poses: PoseStack, axis: Axis, angle: Float,
        state: ItemEntityRenderState, stack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState
    ) {
        if (!ItemPhysics.active(state)) poses.rotate(axis, angle)
    }

    @Redirect(method = [SUBMIT], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V")])
    private fun traveltoItemStack(poses: PoseStack, collector: SubmitNodeCollector, light: Int, state: ItemClusterRenderState, random: RandomSource, bounds: AABB) {
        ItemPhysics.transform(state as ItemEntityRenderState, poses, bounds, random)
        ItemEntityRenderer.submitMultipleFromCount(poses, collector, light, state, random, bounds)
    }
}
