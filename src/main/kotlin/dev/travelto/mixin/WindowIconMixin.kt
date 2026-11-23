package dev.travelto.mixin

import com.mojang.blaze3d.platform.IconSet
import dev.travelto.Travelto
import net.minecraft.server.packs.PackMetadataResources
import net.minecraft.server.packs.resources.IoSupplier
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.io.InputStream

@Mixin(IconSet::class)
abstract class WindowIconMixin {
    @Inject(method = ["getStandardIcons"], at = [At("HEAD")], cancellable = true)
    private fun traveltoWindowIcons(resources: PackMetadataResources, callback: CallbackInfoReturnable<List<IoSupplier<InputStream>>>) {
        val paths = listOf(256, 128, 48, 32, 16).map { "/assets/travelto/branding/logo8-$it.png" }
        val icons = paths.map { path -> Travelto::class.java.getResource(path) ?: return }
        callback.returnValue = icons.map { icon -> IoSupplier { icon.openStream() } }
    }
}
