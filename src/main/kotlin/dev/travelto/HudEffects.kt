package dev.travelto

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

internal object HudEffects {
    val logo = Travelto.id("textures/gui/logo3.png")
    const val LOGO_SIZE = 14
    const val GOLD = 0xffffbe37.toInt()
    const val DAMAGE = 0xffff4848.toInt()
    const val ASH = 0xff80848a.toInt()

    fun blend(from: Float, to: Float, amount: Float) = from + (to - from) * amount
    fun gradient(graphics: GuiGraphicsExtractor, box: Box, radius: Float, start: Int, end: Int, vertical: Boolean = false) =
        RoundedRect.draw(graphics, box, radius, Ui.tint(start), endColor = Ui.tint(end), vertical = vertical)

    fun solidColor(primary: Boolean = true) = if (primary) Ui.PANEL else Ui.INSET

    fun solid(graphics: GuiGraphicsExtractor, box: Box, radius: Int, primary: Boolean = true) {
        if (box.w <= 1 || box.h <= 1) return
        Ui.shadow(graphics, box.copy(y = box.y + 2), 0x66000000, radius, 5f)
        Ui.box(graphics, box, solidColor(primary), radius)
        Ui.stroke(graphics, box, Ui.LINE, radius)
    }

    fun face(graphics: GuiGraphicsExtractor, skin: PlayerSkin, box: Box) {
        val color = Ui.tint(-1)
        if (color ushr 24 > 3 && box.w > 0) PlayerFaceExtractor.extractRenderState(graphics, skin, box.x, box.y, box.w, color)
    }

    fun item(graphics: GuiGraphicsExtractor, stack: ItemStack, box: Box) {
        val visibility = (Ui.tint(-1) ushr 24) / 255f
        if (stack.isEmpty || box.w < 1 || box.h < 1 || visibility <= .01f) return
        val scale = minOf(box.w, box.h) / 16f * visibility
        val pose = graphics.pose()
        pose.pushMatrix().translate(box.x + box.w / 2f, box.y + box.h / 2f).scale(scale)
        try { graphics.fakeItem(stack, -8, -8) } finally { pose.popMatrix() }
    }

    fun small(graphics: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int = Ui.MUTED) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat()).scale(.84f)
        try { Ui.text(graphics, text, 0, 0, color, shadow = false) } finally { pose.popMatrix() }
    }

    fun smallWidth(text: String) = (Ui.body.width(text) * .84f).roundToInt()
    fun bold(text: String) = Component.literal(text).withStyle(net.minecraft.ChatFormatting.BOLD)
}
