package dev.travelto.mixin

import com.mojang.blaze3d.vertex.VertexConsumer
import dev.travelto.drawGlyphEffect
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import org.joml.Matrix3x2fc
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

private const val GUI_EFFECT = "(Lorg/joml/Matrix3x2fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFIIII)V"
private const val WORLD_EFFECT = "(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFIIIII)V"
private const val EFFECT = "Licyllis/modernui/mc/text/TextRenderEffect;"

@Mixin(targets = ["icyllis.modernui.mc.text.TextEffectRenderState"], remap = false)
abstract class ModernGuiTextEffectsMixin {
    @Redirect(method = ["buildPass"], at = [At(value = "INVOKE", target = EFFECT + "drawUnderline" + GUI_EFFECT)])
    private fun traveltoGuiUnderline(pose: Matrix3x2fc, vertices: VertexConsumer, start: Float, end: Float, baseline: Float,
                                     red: Int, green: Int, blue: Int, alpha: Int) {
        drawGlyphEffect(Matrix4f().mul(pose), vertices, start, end, baseline, ARGB.color(alpha, red, green, blue), LightCoordsUtil.FULL_BRIGHT, true, true)
    }

    @Redirect(method = ["buildPass"], at = [At(value = "INVOKE", target = EFFECT + "drawStrikethrough" + GUI_EFFECT)])
    private fun traveltoGuiStrikethrough(pose: Matrix3x2fc, vertices: VertexConsumer, start: Float, end: Float, baseline: Float,
                                         red: Int, green: Int, blue: Int, alpha: Int) {
        drawGlyphEffect(Matrix4f().mul(pose), vertices, start, end, baseline, ARGB.color(alpha, red, green, blue), LightCoordsUtil.FULL_BRIGHT, false, true)
    }
}

@Mixin(targets = ["icyllis.modernui.mc.text.TextLayout"], remap = false)
abstract class ModernWorldTextEffectsMixin {
    @Redirect(method = ["drawText"], at = [At(value = "INVOKE", target = EFFECT + "drawUnderline" + WORLD_EFFECT)])
    private fun traveltoWorldUnderline(pose: Matrix4fc, vertices: VertexConsumer, start: Float, end: Float, baseline: Float,
                                       red: Int, green: Int, blue: Int, alpha: Int, packedLight: Int) {
        drawGlyphEffect(pose, vertices, start, end, baseline, ARGB.color(alpha, red, green, blue), packedLight, true, false)
    }

    @Redirect(method = ["drawText"], at = [At(value = "INVOKE", target = EFFECT + "drawStrikethrough" + WORLD_EFFECT)])
    private fun traveltoWorldStrikethrough(pose: Matrix4fc, vertices: VertexConsumer, start: Float, end: Float, baseline: Float,
                                           red: Int, green: Int, blue: Int, alpha: Int, packedLight: Int) {
        drawGlyphEffect(pose, vertices, start, end, baseline, ARGB.color(alpha, red, green, blue), packedLight, false, false)
    }
}
