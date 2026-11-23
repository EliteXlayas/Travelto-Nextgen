package dev.travelto.mixin

import dev.travelto.FilteredKeyBindsScreen
import net.minecraft.client.KeyMapping
import net.minecraft.client.Options
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.controls.ControlsScreen
import net.minecraft.client.gui.screens.options.controls.KeyBindsList
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(KeyBindsList.KeyEntry::class)
interface KeyEntryAccess {
    @Accessor("key")
    fun traveltoMapping(): KeyMapping
}

@Mixin(ControlsScreen::class)
abstract class ControlsScreenMixin {
    @Redirect(
        method = ["lambda\$addOptions\$1"],
        at = [At(value = "NEW", target = "(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Options;)Lnet/minecraft/client/gui/screens/options/controls/KeyBindsScreen;")]
    )
    private fun traveltoKeyBinds(parent: Screen, options: Options): KeyBindsScreen = FilteredKeyBindsScreen(parent, options)
}
