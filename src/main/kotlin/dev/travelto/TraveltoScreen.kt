package dev.travelto

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.PreeditEvent
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

internal abstract class TraveltoScreen(private val titleKey: String, private val back: Screen? = null) : Screen(Ui.label(titleKey)) {
    private val reveal = Motion(0f)
    private var continuation: (() -> Unit)? = null
    private var reversingClose = false
    private var opacity = 0f
    private var offset = 0f
    private var frameTime = 0L
    protected val leaving get() = continuation != null
    private val acceptsPointer get() = !leaving && opacity > .18f

    final override fun getTitle(): Component = Ui.label(titleKey)

    override fun added() {
        super.added()
        continuation = null
        reversingClose = false
        opacity = if (Travelto.state.reducedMotion) 1f else 0f
        offset = 0f
        reveal.reset(opacity)
        reveal.toward(1f, System.nanoTime())
    }

    protected fun leave(action: () -> Unit) {
        if (leaving) return
        if (Travelto.state.reducedMotion) { action(); return }
        continuation = action
        reveal.toward(0f, System.nanoTime())
    }

    protected fun next(screen: () -> Screen) = leave { minecraft.gui.setScreen(screen()) }

    protected fun toggleClose() {
        if (leaving) {
            if (reversingClose) {
                continuation = null
                reversingClose = false
                reveal.toward(1f, System.nanoTime())
            }
        } else onClose()
    }

    final override fun onClose() {
        if (leaving) return
        leave { minecraft.gui.setScreen(back) }
        reversingClose = leaving
    }
    final override fun isPauseScreen() = false

    override fun tick() {
        super.tick()
        val action = continuation ?: return
        if (reveal.finished(System.nanoTime())) {
            continuation = null
            reversingClose = false
            action()
        }
    }

    final override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        frameTime = System.nanoTime()
        opacity = reveal.toward(if (leaving) 0f else 1f, frameTime)
        offset = (1f - opacity) * 7f
        if (minecraft.level == null) MenuBackground.draw(graphics, width, height) else Ui.worldOverlay(graphics, opacity)
    }

    final override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (opacity <= 0f) return
        graphics.pose().pushMatrix()
        graphics.pose().translate(0f, offset)
        try {
            Ui.faded(opacity) { draw(graphics, mouseX, (mouseY - offset).roundToInt(), delta, frameTime) }
        } finally { graphics.pose().popMatrix() }
    }

    protected open fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float, now: Long) =
        super.extractRenderState(graphics, mouseX, mouseY, delta)

    private fun local(event: MouseButtonEvent) = MouseButtonEvent(event.x(), event.y() - offset, event.buttonInfo())
    final override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val position = local(event)
        return mouseShortcut(position) || !acceptsPointer || click(position, doubleClick)
    }
    final override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double) = !acceptsPointer || drag(local(event), dx, dy)
    final override fun mouseReleased(event: MouseButtonEvent) = !acceptsPointer || release(local(event))
    final override fun mouseScrolled(x: Double, y: Double, horizontal: Double, vertical: Double) = !acceptsPointer || scroll(x, y - offset, horizontal, vertical)
    protected open fun mouseShortcut(event: MouseButtonEvent) = false
    protected open fun click(event: MouseButtonEvent, doubleClick: Boolean) = super.mouseClicked(event, doubleClick)
    protected open fun drag(event: MouseButtonEvent, dx: Double, dy: Double) = super.mouseDragged(event, dx, dy)
    protected open fun release(event: MouseButtonEvent) = super.mouseReleased(event)
    protected open fun scroll(x: Double, y: Double, horizontal: Double, vertical: Double) = super.mouseScrolled(x, y, horizontal, vertical)
    override fun keyPressed(event: KeyEvent) = leaving || super.keyPressed(event)
    override fun keyReleased(event: KeyEvent) = leaving || super.keyReleased(event)
    override fun charTyped(event: CharacterEvent) = leaving || super.charTyped(event)
    override fun preeditUpdated(event: PreeditEvent?) = leaving || super.preeditUpdated(event)
}
