package dev.travelto.mixin

import dev.travelto.TargetHud
import dev.travelto.WaterMark
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Hud
import net.minecraft.client.gui.components.BossHealthOverlay
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.world.BossEvent
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.function.Predicate

@Mixin(ChatComponent::class)
interface IslandChatAccess {
    @Accessor("allMessages")
    fun traveltoIslandMessages(): List<GuiMessage>

    @Accessor("visibleMessageFilter")
    fun traveltoIslandFilter(): Predicate<GuiMessage>
}

@Mixin(ChatScreen::class)
interface IslandChatScreenAccess {
    @Accessor("input")
    fun traveltoChatInput(): EditBox
}

@Mixin(Hud::class)
abstract class VanillaHudLayoutMixin {
    @Inject(method = ["extractRenderState"], at = [At("HEAD")])
    private fun traveltoBeginHud(graphics: GuiGraphicsExtractor, delta: DeltaTracker, callback: CallbackInfo) = WaterMark.beginHudFrame()

    @Inject(method = ["extractTabList"], at = [At(value = "INVOKE", target =
        "Lnet/minecraft/client/gui/components/PlayerTabOverlay;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V")])
    private fun traveltoBeforePlayerList(graphics: GuiGraphicsExtractor, delta: DeltaTracker, callback: CallbackInfo) = WaterMark.beforeTabList(graphics)
}

@Mixin(BossHealthOverlay::class)
abstract class BossBarLayoutMixin {
    @Inject(method = ["extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V"], at = [At("HEAD")])
    private fun traveltoRecordBossBar(graphics: GuiGraphicsExtractor, x: Int, y: Int, event: BossEvent, callback: CallbackInfo) =
        WaterMark.recordBossBar(x, y, event.name)
}

@Mixin(MultiPlayerGameMode::class)
abstract class TargetAttackMixin {
    @Inject(method = ["attack"], at = [At("TAIL")])
    private fun traveltoObserveAttack(player: Player, entity: Entity, callback: CallbackInfo) {
        val client = Minecraft.getInstance()
        if (player === client.player && !player.isSpectator && entity is LivingEntity) TargetHud.attacked(client, entity)
    }
}

@Mixin(BlockItem::class)
abstract class IslandBlockStockMixin {
    @Inject(method = ["place"], at = [At("RETURN")])
    private fun traveltoObservePlacement(context: BlockPlaceContext, callback: CallbackInfoReturnable<InteractionResult>) {
        if (!context.level.isClientSide || !callback.returnValue.consumesAction()) return
        val client = Minecraft.getInstance()
        if (context.player === client.player) WaterMark.placed(client, this as BlockItem)
    }
}
