package dev.travelto.mixin;

import dev.travelto.MenuBackground;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.Panorama;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Panorama.class)
abstract class PanoramaMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void travelto$replacePanorama(GuiGraphicsExtractor graphics, int width, int height, CallbackInfo callback) {
        MenuBackground.draw(graphics, width, height);
        callback.cancel();
    }
}
