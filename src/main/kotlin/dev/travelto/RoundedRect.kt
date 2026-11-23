package dev.travelto

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import dev.travelto.mixin.GuiGraphicsAccess
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.server.packs.resources.ResourceManager
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.roundToInt

internal object RoundedRect {
    private val pipeline by lazy { shapePipeline("rounded_rect") }
    private val wheelPipeline by lazy { shapePipeline("theme_wheel") }

    private fun shapePipeline(name: String): RenderPipeline {
        val gui = RenderPipelines.GUI
        return RenderPipeline.builder()
            .withLocation(Travelto.id("pipeline/$name"))
            .withVertexShader(Travelto.id("core/rounded_rect"))
            .withFragmentShader(Travelto.id("core/$name"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)
            .withPrimitiveTopology(gui.primitiveTopology)
            .withColorTargetState(requireNotNull(gui.colorTargetStates[0]))
            .withCull(false)
            .apply { gui.bindGroupLayouts.forEach { withBindGroupLayout(it) } }
            .build()
    }
    private var resources: ResourceManager? = null
    private var available = false
    private var wheelAvailable: Boolean? = null

    fun clipped(graphics: GuiGraphicsExtractor, box: Box, draw: () -> Unit) {
        if (box.w <= 0 || box.h <= 0) return
        val viewport = ScreenRectangle(0, 0, graphics.guiWidth(), graphics.guiHeight())
        val screen = ScreenRectangle(box.x, box.y, box.w, box.h).transformAxisAligned(graphics.pose())
            .intersection(viewport) ?: return
        val stack = graphics.scissorStack
        val parent = stack.peek()
        val bounds = if (parent == null) screen else screen.intersection(parent) ?: return
        stack.push(bounds)
        try { draw() } finally { stack.pop() }
    }

    private fun compiled(program: RenderPipeline) = try { RenderSystem.getCompiledPipelineNullable(program) != null }
        catch (_: RuntimeException) { false }

    private fun refresh() {
        val source = Minecraft.getInstance().resourceManager
        if (resources === source) return
        resources = source
        available = compiled(pipeline)
        wheelAvailable = null
        if (available) Travelto.log.info("Travelto rounded UI pipeline compiled")
        else Travelto.log.warn("Travelto rounded UI pipeline unavailable; using rectangular surfaces")
    }

    fun draw(graphics: GuiGraphicsExtractor, box: Box, radius: Float, color: Int, effect: Float = 0f,
             endColor: Int = color, vertical: Boolean = false, squareRight: Boolean = false) {
        if (box.w <= 0 || box.h <= 0 || (color or endColor) ushr 24 == 0) return
        refresh()
        if (!available) {
            if (effect > 0f) graphics.outline(box.x, box.y, box.w, box.h, color)
            else if (effect == 0f && vertical) graphics.fillGradient(box.x, box.y, box.right, box.bottom, color, endColor)
            else if (effect == 0f) graphics.fill(box.x, box.y, box.right, box.bottom, color)
            return
        }
        submit(graphics, box, if (squareRight) -radius else radius, color, effect, pipeline, endColor, vertical)
    }

    fun wheel(graphics: GuiGraphicsExtractor, box: Box, hueColor: Int): Boolean {
        refresh()
        val ready = wheelAvailable ?: compiled(wheelPipeline).also { wheelAvailable = it }
        if (ready) submit(graphics, box, 0f, hueColor, 0f, wheelPipeline)
        return ready
    }

    private fun submit(graphics: GuiGraphicsExtractor, box: Box, radius: Float, color: Int, effect: Float, program: RenderPipeline,
                       endColor: Int = color, vertical: Boolean = false) {
        if ((color or endColor) ushr 24 == 0) return
        val pad = if (effect < 0f) ceil(-effect).toInt() + 2 else 2
        val pose = Matrix3x2f(graphics.pose())
        val extents = ScreenRectangle(box.x - pad, box.y - pad, box.w + pad * 2, box.h + pad * 2).transformMaxBounds(pose)
        val scissor = graphics.scissorStack.peek()
        val visible = if (scissor == null) extents else scissor.intersection(extents) ?: return
        val quad = Quad(box, pad, pose, (radius.coerceIn(-minOf(box.w, box.h) / 2f, minOf(box.w, box.h) / 2f) * 16).roundToInt(),
            (effect * 16).roundToInt(), color, endColor, vertical, scissor, visible, program)
        (graphics as GuiGraphicsAccess).`travelto$renderState`().addGuiElement(quad)
    }

    private class Quad(
        private val box: Box, private val pad: Int, private val pose: Matrix3x2f, private val radius: Int, private val effect: Int,
        private val color: Int, private val endColor: Int, private val vertical: Boolean,
        private val scissor: ScreenRectangle?, private val visible: ScreenRectangle?, private val program: RenderPipeline
    ) : GuiElementRenderState {
        override fun pipeline() = program
        override fun textureSetup() = TextureSetup.noTexture()
        override fun bounds() = visible
        override fun scissorArea() = scissor

        override fun buildVertices(vertices: VertexConsumer) {
            val halfWidth = box.w / 2f + pad
            val halfHeight = box.h / 2f + pad
            fun point(x: Int, y: Int, localX: Float, localY: Float, shade: Int) {
                vertices.addVertexWith2DPose(pose, x.toFloat(), y.toFloat()).setUv(localX, localY).setUv2(radius, effect).setColor(shade)
            }
            point(box.x - pad, box.y - pad, -halfWidth, -halfHeight, color)
            point(box.x - pad, box.bottom + pad, -halfWidth, halfHeight, if (vertical) endColor else color)
            point(box.right + pad, box.bottom + pad, halfWidth, halfHeight, endColor)
            point(box.right + pad, box.y - pad, halfWidth, -halfHeight, if (vertical) color else endColor)
        }
    }
}
