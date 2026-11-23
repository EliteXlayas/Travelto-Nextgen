package dev.travelto.mixin

import it.unimi.dsi.fastutil.ints.IntSet
import it.unimi.dsi.fastutil.objects.Object2IntMap
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(targets = ["icyllis.modernui.graphics.text.EmojiFont"], remap = false)
abstract class EmojiCoverageMixin {
    @Inject(method = ["<init>"], at = [At("RETURN")])
    private fun traveltoIncludeFlagGlyphs(
        name: String, coverage: IntSet, size: Int, ascent: Int, spacing: Int, base: Int,
        sequences: Object2IntMap<CharSequence>, files: List<String>, callback: CallbackInfo
    ) {
        if (name != "Google Noto Color Emoji") return
        for (sequence in sequences.keys) {
            if (sequence.length != 4) continue
            val first = Character.codePointAt(sequence, 0)
            val second = Character.codePointAt(sequence, 2)
            if (first in 0x1f1e6..0x1f1ff && second in 0x1f1e6..0x1f1ff) {
                coverage.add(first)
                coverage.add(second)
            }
        }
    }
}
