package dev.travelto

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines

object MenuBackground {
    private val fragment = Travelto.id("shaders/core/menu_background.fsh")
    private val pipeline by lazy {
        val gui = RenderPipelines.GUI
        RenderPipeline.builder()
            .withLocation(Travelto.id("pipeline/menu_background"))
            .withVertexShader(Travelto.id("core/menu_background"))
            .withFragmentShader(Travelto.id("core/menu_background"))
            .withVertexBinding(0, requireNotNull(gui.getVertexFormatBinding(0)))
            .withPrimitiveTopology(gui.primitiveTopology)
            .withColorTargetState(requireNotNull(gui.colorTargetStates[0]))
            .withCull(false)
            .apply { gui.bindGroupLayouts.forEach { withBindGroupLayout(it) } }
            .build()
    }
    private var lastFrame = 0L
    private var playheadMillis = 0.0
    private var resourceManager: Any? = null
    private var failed = false
    private var announced = false

    @JvmStatic fun draw(graphics: GuiGraphicsExtractor, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (!Travelto.state.enabled(Module.MENU_SHADER) || !RenderSystem.isOnRenderThread()) {
            graphics.fill(0, 0, width, height, Ui.CANVAS)
            return
        }
        try {
            val source = Minecraft.getInstance().resourceManager
            if (resourceManager !== source) {
                resourceManager = source
                failed = false
                announced = false
            }
            if (failed) {
                graphics.fill(0, 0, width, height, Ui.CANVAS)
                return
            }
            if ((!announced && source.getResource(fragment).isEmpty) || RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
                failed = true
                Travelto.log.warn("Travelto MenuShader unavailable; using a solid background")
                graphics.fill(0, 0, width, height, Ui.CANVAS)
                return
            }
            val now = System.nanoTime()
            if (!announced) {
                announced = true
                Travelto.log.info("Travelto MenuShader pipeline compiled (menu_background.fsh)")
            }
            if (!Travelto.state.reducedMotion && lastFrame != 0L) {
                val step = (now - lastFrame).coerceIn(0L, 100_000_000L)
                playheadMillis = (playheadMillis + MotionSettings.scaled(step) / 1_000_000.0) % 16_777_216.0
            }
            lastFrame = now
            val elapsedMillis = if (Travelto.state.reducedMotion) 0 else playheadMillis.toInt()
            graphics.fill(pipeline, 0, 0, width, height, 0xff000000.toInt() or elapsedMillis)
        } catch (error: RuntimeException) {
            failed = true
            Travelto.log.warn("Travelto MenuShader unavailable; using a solid background", error)
            graphics.fill(0, 0, width, height, Ui.CANVAS)
        }
    }
}
