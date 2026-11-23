package dev.travelto.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
abstract class ScreenBackgroundMixin {
    @Shadow @Final protected Minecraft minecraft;

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V", at = @At("HEAD"), cancellable = true)
    private void travelto$uncoverMenuField(GuiGraphicsExtractor graphics, int x, int y, int width, int height, CallbackInfo callback) {
        if (minecraft.level == null) {
            graphics.fillGradient(x, y, x + width, y + height, 0x6608090d, 0xa008090d);
            callback.cancel();
        }
    }
}
