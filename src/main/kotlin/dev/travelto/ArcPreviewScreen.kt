package dev.travelto

import icyllis.arc3d.engine.Engine.BackendApi
import icyllis.modernui.ModernUI
import icyllis.modernui.animation.TimeInterpolator
import icyllis.modernui.animation.ValueAnimator
import icyllis.modernui.core.Context
import icyllis.modernui.core.Core
import icyllis.modernui.fragment.Fragment
import icyllis.modernui.graphics.Canvas
import icyllis.modernui.graphics.Paint
import icyllis.modernui.graphics.drawable.ShapeDrawable
import icyllis.modernui.markflow.Markflow
import icyllis.modernui.mc.ScreenCallback
import icyllis.modernui.mc.SimpleScreen
import icyllis.modernui.text.Editable
import icyllis.modernui.text.InputFilter
import icyllis.modernui.text.TextWatcher
import icyllis.modernui.util.DataSet
import icyllis.modernui.view.Gravity
import icyllis.modernui.view.LayoutInflater
import icyllis.modernui.view.View
import icyllis.modernui.view.ViewGroup
import icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT
import icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT
import icyllis.modernui.widget.EditText
import icyllis.modernui.widget.FrameLayout
import icyllis.modernui.widget.LinearLayout
import icyllis.modernui.widget.ScrollView
import icyllis.modernui.widget.TextView
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen

internal class ArcPreviewScreen private constructor(parent: Screen, preview: ArcPreview) : SimpleScreen(
    preview, object : ScreenCallback {
        override fun isPauseScreen() = false
        override fun shouldBlurBackground() = false
    }, parent, Ui.label("travelto.font.canvas")
) {
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (minecraft.level == null) MenuBackground.draw(graphics, width, height) else Ui.worldOverlay(graphics, 1f)
    }

    companion object {
        fun open(parent: Screen, client: Minecraft): Screen {
            val engine = when (Core.peekImmediateContext()?.backend) {
                BackendApi.kOpenGL -> "OpenGL"
                BackendApi.kVulkan -> "Vulkan"
                else -> return parent
            }
            val state = Travelto.state
            val duration = if (state.reducedMotion) 0L else MotionSettings.duration(210L)
            return ArcPreviewScreen(parent, ArcPreview(client, state.typography.copy(), duration, state.motion.curve, engine))
        }
    }
}

internal class ArcPreview(
    private val client: Minecraft,
    private val typography: Typography,
    private val duration: Long,
    private val curve: MotionCurve,
    private val backend: String
) : Fragment() {
    private val backLabel = Ui.label("travelto.action.back").string
    private val markdownLabel = Ui.label("travelto.font.markdown").string
    private val htmlLabel = Ui.label("travelto.font.html").string
    private val previewLabel = Ui.label("travelto.font.sample").string
    private val sampleMarkdown = "**Travelto** · 中文 · العربية · ייִדיש\n\nनमस्ते · বাংলা · தமிழ் · ไทย  🙂‍↔️  {{arc}}"
    private val sampleHtml = "<p><b>Travelto</b> · 中文 · العربية · ייִדיש</p><p><i>नमस्ते · বাংলা · தமிழ் · ไทย</i>  🙂‍↔️  {{arc}}</p>"
    private var html = false
    private var markdownInput = sampleMarkdown
    private var htmlInput = sampleHtml
    private var animation: ValueAnimator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: DataSet?): View {
        val context = requireContext()
        val root = FrameLayout(context)
        val scroll = ScrollView(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(15f)
            setPadding(pad, pad, pad, pad)
            background = glass(this, Ui.PANEL, Ui.CARD_RADIUS.toFloat())
        }
        scroll.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER).apply {
            val horizontal = root.dp(26f)
            val vertical = root.dp(12f)
            setMargins(horizontal, vertical, horizontal, vertical)
        })
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        heading.addView(label(context, "Arc3D · $backend", 16f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        heading.addView(button(context, backLabel) { client.execute { (client.gui.screen() as? ArcPreviewScreen)?.onBackPressed() } })
        panel.addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(GeometryView(context, backend), LinearLayout.LayoutParams(MATCH_PARENT, panel.dp(51f)))

        val editor = EditText(context).apply {
            style(this, typography.size + 3f)
            setFilters(InputFilter.LengthFilter(4096))
            setMaxLines(5)
            val pad = dp(9f)
            setPadding(pad, pad, pad, pad)
            background = glass(this, Ui.CONTROL, Ui.CONTROL_RADIUS.toFloat())
            setText(sampleMarkdown)
        }
        val preview = label(context, "", typography.size + 3f).apply { setTextIsSelectable(true) }
        val markflow = Markflow.create(context)
        val render = Runnable {
            val input = editor.text.toString()
            val content = if (html) TraveltoRichText.html(input) else markflow.render(markflow.parse(input))
            preview.text = TraveltoRichText.withEmbeds(content)
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable) {
                editor.removeCallbacks(render)
                editor.postDelayed(render, 100)
            }
        })
        fun mode(next: Boolean) {
            if (next == html) return
            if (html) htmlInput = editor.text.toString() else markdownInput = editor.text.toString()
            html = next
            editor.setText(if (html) htmlInput else markdownInput)
            editor.removeCallbacks(render)
            render.run()
        }
        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(button(context, markdownLabel) { mode(false) })
        tabs.addView(button(context, htmlLabel) { mode(true) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = panel.dp(5f)
        })
        panel.addView(tabs)
        panel.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = panel.dp(6f) })
        panel.addView(label(context, previewLabel, 11f).apply { setTextColor(Ui.MUTED) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = panel.dp(12f)
        })
        panel.addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        render.run()
        if (duration > 0L) {
            panel.alpha = 0f
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                setDuration(duration)
                setInterpolator(TimeInterpolator { curve.ease(it) })
                addUpdateListener { panel.alpha = it.animatedValue as Float }
                start()
            }
        }
        return root
    }

    override fun onDestroyView() {
        animation?.cancel()
        animation = null
        super.onDestroyView()
    }

    private fun style(view: TextView, size: Float) = with(view) {
        typeface = ModernUI.getSelectedTypeface()
        setTextColor(Ui.TEXT)
        setTextSize(size)
        textDirection = typography.direction.engineValue
        textLocale = ModernUI.getSelectedLocale()
    }

    private fun label(context: Context, value: CharSequence, size: Float) = TextView(context).apply {
        style(this, size)
        text = value
    }

    private fun button(context: Context, value: String, action: () -> Unit) = label(context, value, 12f).apply {
        gravity = Gravity.CENTER
        val horizontal = dp(11f)
        val vertical = dp(5f)
        setPadding(horizontal, vertical, horizontal, vertical)
        background = glass(this, Ui.CONTROL, Ui.CONTROL_RADIUS.toFloat())
        setFocusable(true)
        setOnClickListener { action() }
    }

    private fun glass(view: View, color: Int, radius: Float) = ShapeDrawable().apply {
        setColor(color)
        cornerRadius = view.dp(radius).toFloat()
        setStroke(1, Ui.LINE)
    }

    private class GeometryView(context: Context, private val backend: String) : View(context) {
        private val paint = Paint()
        private var reported = false

        override fun onDraw(canvas: Canvas) {
            val gap = dp(7f).toFloat()
            val size = minOf(height - 2f * gap, width / 4f).coerceAtLeast(0f)
            if (size <= 0f) return
            paint.setColor(Ui.CONTROL)
            paint.setStroke(false)
            canvas.drawRoundRect(0f, gap, width.toFloat(), gap + size, gap, paint)
            val centerY = gap + size / 2f
            val radius = size * .29f
            fun shapes(spread: Float, color: Int) {
                paint.setColor(color)
                paint.setStroke(true)
                paint.strokeWidth = dp(2f) + spread * 2f
                canvas.drawCircle(width / 2f, centerY, radius, paint)
                paint.setStroke(false)
                canvas.drawPie(width * .72f, centerY, radius + spread, -65f, 262f, paint)
                val side = radius + spread
                canvas.drawRoundRect(width * .28f - side, centerY - side, width * .28f + side, centerY + side, gap + spread, paint)
            }
            val bloom = Travelto.state.hud.bloom
            val accent = Ui.ACCENT
            if (bloom.enabled) {
                val spread = minOf(dp(bloom.size.toFloat()).toFloat(), size * .28f)
                shapes(spread, Ui.alpha(accent, .08f))
                shapes(spread * .5f, Ui.alpha(accent, .22f))
            }
            shapes(0f, accent)
            if (!reported) {
                reported = true
                Travelto.log.info("Travelto Arc3D preview: backend={}, canvas={}, recordedOnUiThread={}", backend, canvas.javaClass.simpleName, Core.isOnUiThread())
            }
        }
    }
}
