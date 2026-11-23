package dev.travelto

import com.mojang.blaze3d.vertex.VertexConsumer
import icyllis.modernui.mc.text.GlyphManager
import icyllis.modernui.mc.text.TextRenderEffect
import org.joml.Matrix4fc

internal fun drawGlyphEffect(pose: Matrix4fc, vertices: VertexConsumer, start: Float, end: Float, baseline: Float,
                             color: Int, light: Int, underline: Boolean, gui: Boolean) {
    if (end <= start || color ushr 24 == 0) return
    val top = baseline + if (underline) TextRenderEffect.UNDERLINE_OFFSET else TextRenderEffect.STRIKETHROUGH_OFFSET
    val thickness = if (underline) TextRenderEffect.UNDERLINE_THICKNESS else TextRenderEffect.STRIKETHROUGH_THICKNESS
    val depth = if (gui) 0f else TextRenderEffect.EFFECT_DEPTH
    GlyphManager.getInstance().effectGlyph.createEffect(start, top, end, top + thickness, depth, color, 0, 0f)
        .render(pose, vertices, light, gui)
}
