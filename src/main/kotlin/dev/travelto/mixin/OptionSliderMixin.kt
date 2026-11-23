package dev.travelto.mixin

import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.input.MouseButtonEvent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(AbstractSliderButton::class)
interface SliderDragAccess {
    @Accessor("dragging")
    fun traveltoIsDragging(): Boolean
}

@Mixin(targets = ["net.minecraft.client.OptionInstance\$OptionInstanceSliderButton"])
abstract class OptionSliderMixin {
    @Unique private var traveltoPending = false
    @Shadow abstract fun applyUnsavedValue()

    @Inject(method = ["applyValue"], at = [At("HEAD")], cancellable = true)
    private fun traveltoSaveAfterDrag(callback: CallbackInfo) {
        if ((this as SliderDragAccess).traveltoIsDragging()) traveltoPending = true
        else applyUnsavedValue()
        callback.cancel()
    }

    @Inject(method = ["onRelease"], at = [At("HEAD")])
    private fun traveltoCommitDrag(event: MouseButtonEvent, callback: CallbackInfo) {
        if (!traveltoPending) return
        traveltoPending = false
        applyUnsavedValue()
    }
}
