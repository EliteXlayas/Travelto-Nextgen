package dev.travelto.mixin

import com.mojang.blaze3d.resource.CrossFrameResourcePool
import dev.travelto.ArcPreviewScreen
import dev.travelto.LiveStatsScreen
import dev.travelto.Travelto
import dev.travelto.TraveltoScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.resources.Identifier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(GameRenderer::class)
interface BlurResourcePoolAccess {
    @Accessor("resourcePool")
    fun traveltoBlurResourcePool(): CrossFrameResourcePool
}

@Mixin(GuiRenderer::class)
abstract class GaussianBlurMixin {
    @Unique private val traveltoGaussian = Identifier.fromNamespaceAndPath("modernui", "gaussian_blur")
    @Unique private val traveltoBoxBlur = Identifier.withDefaultNamespace("blur")
    @Unique private var traveltoBlurReported = false

    @Redirect(
        method = ["draw"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V")]
    )
    private fun traveltoGaussianBlur(renderer: GameRenderer) {
        val client = Minecraft.getInstance()
        val screen = client.gui.screen()
        if (client.level == null || screen !is TraveltoScreen && screen !is ArcPreviewScreen && screen !is LiveStatsScreen) {
            renderer.processBlurEffect()
            return
        }
        if (!Travelto.state.blur || client.options.menuBackgroundBlurriness <= 0) return
        val shaderManager = client.shaderManager
        val targets = LevelTargetBundle.MAIN_TARGETS
        val gaussian = shaderManager.getPostChain(traveltoGaussian, targets)
        val blur = gaussian ?: shaderManager.getPostChain(traveltoBoxBlur, targets) ?: return
        blur.process(renderer.mainRenderTarget(), (renderer as BlurResourcePoolAccess).traveltoBlurResourcePool())
        if (!traveltoBlurReported) {
            traveltoBlurReported = true
            if (gaussian != null) Travelto.log.info("Travelto ModernUI Gaussian blur active")
            else Travelto.log.warn("Travelto Gaussian blur unavailable; using Minecraft box blur")
        }
    }
}
