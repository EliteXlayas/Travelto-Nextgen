package dev.travelto

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.world.entity.EntityType

internal object MobPortraits {
    private const val TILE = 16
    private const val COLUMNS = 16
    private val atlas = Travelto.id("textures/gui/mob_portraits.png")
    private val cells by lazy {
        MobPortraits::class.java.getResourceAsStream("/META-INF/travelto/mob-portraits.txt")
            ?.bufferedReader()?.useLines { lines ->
                lines.filter { it.isNotBlank() }.mapIndexed { index, line -> line.substringBefore(' ') to index }.toMap()
            }.orEmpty()
    }

    fun draw(graphics: GuiGraphicsExtractor, type: EntityType<*>, box: Box): Boolean {
        val id = EntityType.getKey(type)
        if (id.namespace != "minecraft") return false
        val cell = cells[id.path] ?: return false
        val tint = Ui.tint(-1)
        if (tint ushr 24 > 3 && box.w > 0 && box.h > 0) {
            val rows = (cells.size + COLUMNS - 1) / COLUMNS
            graphics.blit(RenderPipelines.GUI_TEXTURED, atlas, box.x, box.y,
                (cell % COLUMNS * TILE).toFloat(), (cell / COLUMNS * TILE).toFloat(), box.w, box.h,
                TILE, TILE, COLUMNS * TILE, rows * TILE, tint)
        }
        return true
    }
}
