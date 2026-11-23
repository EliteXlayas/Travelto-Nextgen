package dev.travelto

import com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
import dev.travelto.mixin.IslandChatAccess
import dev.travelto.mixin.IslandChatScreenAccess
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.entity.player.ChatVisiblity
import net.minecraft.world.item.BlockItem
import kotlin.math.roundToInt

internal object WaterMark {
    private val motion = MotionScope.WATERMARK
    private const val EDGE = 5
    private const val MAX_CARDS = 3
    private const val CARD_LIFETIME = 8_000_000_000L
    private const val CHAT_LIMIT = 60
    private const val STOCK_TIME = 2_800_000_000L
    private data class Notice(val key: String, val title: Component, val message: Component, val enabled: Boolean?, val posted: Long,
                              val visibility: Motion = Motion(0f, 310, motion), val toggle: Motion = Motion(if (enabled == true) 1f else 0f, 240, motion),
                              var shown: Long? = null)
    private data class Stat(val icon: Int, val text: String)
    private data class Stock(val item: BlockItem, val time: Long)
    private data class ChatLayout(val lines: Box, val input: Box, val send: Box, val scrollbar: Box, val bounds: Box, val scale: Float)
    private enum class Kind { STATUS, NOTICES, BLOCKS, CHAT }
    private data class Scene(val kind: Kind, val w: Int, val h: Int, val radius: Int, val draw: (GuiGraphicsExtractor, Box, Long) -> Unit)
    private val notices = linkedMapOf<String, Notice>()
    private val placements = ArrayDeque<Long>()
    private val switch = Motion(1f, 240, motion)
    private val stockProgress = Motion(0f, 210, motion)
    private val morph = Motion(1f, 230, motion)
    private val bossOffset = Motion(0f, 280, motion)
    private val tabFade = Motion(1f, 175, motion)
    private val bossBars = ArrayList<Box>(8)
    private var previousStyle: WaterMarkStyle? = null
    var tabVisible = false
        private set
    private var world: ClientLevel? = null
    private var state: HudStyle? = null
    private var current: Scene? = null
    private var outgoing: Scene? = null
    private var stock: Stock? = null
    private var lastFrame = 0L
    private var visualW = 0f
    private var visualH = 0f
    private var visualRadius = 7f
    private var startW = 0f
    private var startH = 0f
    private var startRadius = 7f
    private var morphing = false
    private var lastBounds: Box? = null
    private var idleBounds: Box? = null
    private var chat: ChatLayout? = null
    private var chatPanel = false
    private var chatScroll = 0
    private var chatMaxScroll = 0
    private var scrolling = false
    private var editing = false
    private var chatLast: GuiMessage? = null
    private var chatSize = -1
    private var chatWidth = -1
    private var chatLines = emptyList<FormattedCharSequence>()

    fun initialize() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is ChatScreen) {
                resetChat()
                ScreenEvents.afterForeground(screen).register { _, graphics, mouseX, mouseY, _ ->
                    drawChat(graphics, Minecraft.getInstance(), screen, mouseX, mouseY)
                }
                ScreenMouseEvents.allowMouseClick(screen).register { _, event -> !chatClick(screen, event) }
                ScreenMouseEvents.allowMouseDrag(screen).register { _, event, _, _ -> !chatDrag(event) }
                ScreenMouseEvents.allowMouseRelease(screen).register { _, event -> !chatRelease(event) }
                ScreenMouseEvents.allowMouseScroll(screen).register { _, x, y, _, amount ->
                    val layout = activeChat()
                    if (layout == null || !layout.bounds.contains(x, y) || amount == 0.0) true
                    else { chatScroll = (chatScroll + if (amount > 0) 3 else -3).coerceIn(0, chatMaxScroll); false }
                }
                ScreenEvents.remove(screen).register { resetChat() }
            }
        }
    }

    private fun resetChat() {
        chat = null; chatPanel = false; chatScroll = 0; scrolling = false; editing = false
        chatLast = null; chatSize = -1; chatLines = emptyList()
    }

    private fun sync(client: Minecraft) {
        val hud = Travelto.state.hud
        if (world === client.level && state === hud) return
        world = client.level; state = hud
        notices.clear(); placements.clear()
        current = null; outgoing = null; stock = null
        lastBounds = null; idleBounds = null; lastFrame = 0L; resetChat()
        bossOffset.reset(0f); tabFade.reset(1f)
    }

    private fun enabled(client: Minecraft, mode: WaterMarkStyle): Boolean =
        client.player != null && client.level != null && !client.gui.hud.isHidden && Travelto.state.enabled(Module.WATER_MARK) &&
            Travelto.state.hud.waterMarkOptions.style == mode && Travelto.state.hud.watermarkPart().visible

    fun islandEnabled(client: Minecraft) = enabled(client, WaterMarkStyle.DYNAMIC_ISLAND)

    fun tick(client: Minecraft) {
        sync(client)
        val now = System.nanoTime()
        while (placements.firstOrNull()?.let { now - it > 1_000_000_000L } == true) placements.removeFirst()
        if (!islandEnabled(client)) { notices.clear(); current = null; outgoing = null; stock = null; return }
        notices.values.removeIf { now - it.posted > CARD_LIFETIME }
    }

    fun moduleChanged(client: Minecraft, module: Module, enabled: Boolean) = announce(client, module.id,
        Ui.label("travelto.watermark.toggle_title", module.id), Ui.label(if (enabled) "travelto.watermark.enabled" else "travelto.watermark.disabled"), enabled)

    fun announce(client: Minecraft, key: String, title: Component, message: Component, enabled: Boolean? = null) {
        sync(client)
        if (!islandEnabled(client) || !Travelto.state.hud.waterMarkOptions.notices) return
        val old = notices.remove(key)
        notices[key] = Notice(key, title, message, enabled, System.nanoTime(), old?.visibility ?: Motion(0f, 310, motion),
            old?.toggle ?: Motion(if (enabled == true) 0f else 1f, 240, motion))
        while (notices.size > MAX_CARDS) notices.remove(notices.keys.first())
    }

    fun placed(client: Minecraft, item: BlockItem) {
        if (!islandEnabled(client) || !Travelto.state.hud.waterMarkOptions.blockStock) return
        val now = System.nanoTime()
        stock = Stock(item, now)
        if (placements.size >= 64) placements.removeFirst()
        placements.addLast(now)
    }

    fun searchBackground(graphics: GuiGraphicsExtractor, box: Box, focused: Boolean): Boolean {
        if (!islandEnabled(Minecraft.getInstance())) return false
        HudEffects.solid(graphics, box, 8)
        if (focused) Ui.themeStroke(graphics, box, Ui.alpha(Ui.ACCENT, .65f), 8)
        return true
    }

    private fun logicalWidth(viewport: Int, part: HudPart) = ((viewport - 2 * EDGE).coerceAtLeast(1) / part.scale).toInt().coerceAtLeast(1)

    fun previewSize(client: Minecraft, width: Int): Pair<Int, Int> {
        val hud = Travelto.state.hud
        if (hud.waterMarkOptions.style == WaterMarkStyle.LOGO) return HudEffects.LOGO_SIZE to HudEffects.LOGO_SIZE
        val island = status(client, logicalWidth(width, hud.island))
        return island.w to island.h
    }

    fun preview(graphics: GuiGraphicsExtractor, client: Minecraft, box: Box) {
        if (Travelto.state.hud.waterMarkOptions.style == WaterMarkStyle.LOGO) Ui.image(graphics, HudEffects.logo, box, 96)
        else {
            val scene = status(client, box.w)
            HudEffects.solid(graphics, box, scene.radius)
            scene.draw(graphics, box, 0L)
        }
    }

    fun beginHudFrame() {
        bossBars.clear()
        tabVisible = false
    }

    fun recordBossBar(x: Int, y: Int, name: Component) {
        val center = x + 91
        val nameWidth = Ui.body.width(name)
        val left = minOf(x, center - nameWidth / 2)
        val right = maxOf(x + 182, center + (nameWidth + 1) / 2)
        bossBars += Box(left, y - 9, right - left, maxOf(14, Ui.textHeight + 1))
    }

    fun beforeTabList(graphics: GuiGraphicsExtractor) {
        tabVisible = true
        draw(graphics, Minecraft.getInstance(), System.nanoTime())
        graphics.nextStratum()
    }

    private fun clearBossBars(layout: Box): Box {
        var placed = layout
        for (bar in bossBars) {
            val padded = bar.inset(-3)
            if (placed.intersection(padded) != null) placed = placed.copy(y = padded.bottom)
        }
        return placed
    }

    private fun avoidBossBars(layout: Box, bottomLimit: Int, now: Long): Box {
        val safe = clearBossBars(layout)
        val maximumY = (bottomLimit - layout.h).coerceAtLeast(EDGE)
        val offset = (minOf(safe.y, maximumY) - layout.y).coerceAtLeast(0)
        return layout.copy(y = minOf(layout.y + bossOffset.toward(offset.toFloat(), now).roundToInt(), maximumY))
    }

    fun draw(graphics: GuiGraphicsExtractor, client: Minecraft, now: Long) {
        sync(client)
        if (client.gui.screen() is HudEditor) return
        val style = Travelto.state.hud.waterMarkOptions.style
        if (style != previousStyle) {
            previousStyle = style
            bossOffset.reset(0f)
            tabFade.reset(1f)
        }
        if (enabled(client, WaterMarkStyle.LOGO)) {
            val part = Travelto.state.hud.watermark
            graphics.nextStratum()
            val layout = Hud.place(graphics.guiWidth(), graphics.guiHeight(), part, HudEffects.LOGO_SIZE, HudEffects.LOGO_SIZE)
            Ui.image(graphics, HudEffects.logo, avoidBossBars(layout, graphics.guiHeight() - EDGE, now), 96)
        } else if (islandEnabled(client) && (client.gui.screen() == null)) {
            val part = Travelto.state.hud.island
            val width = logicalWidth(graphics.guiWidth(), part)
            val scene = noticeScene(width, now) ?: blockScene(client, width, now) ?: status(client, width)
            render(graphics, scene, now)
        }
    }

    private fun render(graphics: GuiGraphicsExtractor, scene: Scene, now: Long, bottomLimit: Int = graphics.guiHeight() - EDGE) {
        val old = current
        if (old?.kind != scene.kind) {
            outgoing = old
            switch.reset(if (old == null) 1f else 0f)
            switch.toward(1f, now)
            startW = visualW; startH = visualH; startRadius = visualRadius
            morphing = old != null
            morph.reset(if (morphing) 0f else 1f)
            morph.toward(1f, now)
        }
        current = scene
        val elapsed = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, .05f)
        lastFrame = now
        val part = Travelto.state.hud.island
        val progress = morph.toward(1f, now)
        if (old == null || Travelto.state.reducedMotion) {
            visualW = scene.w.toFloat(); visualH = scene.h.toFloat(); visualRadius = scene.radius.toFloat()
        } else if (morphing) {
            visualW = HudEffects.blend(startW, scene.w.toFloat(), progress)
            visualH = HudEffects.blend(startH, scene.h.toFloat(), progress)
            visualRadius = HudEffects.blend(startRadius, scene.radius.toFloat(), progress)
            morphing = !morph.finished(now)
        } else {
            visualW = MotionSettings.follow(visualW, scene.w.toFloat(), 15f, elapsed, motion)
            visualH = MotionSettings.follow(visualH, scene.h.toFloat(), 15f, elapsed, motion)
            visualRadius = MotionSettings.follow(visualRadius, scene.radius.toFloat(), 15f, elapsed, motion)
        }
        val box = Box(0, 0, visualW.roundToInt(), visualH.roundToInt())
        val layout = Hud.place(graphics.guiWidth(), graphics.guiHeight(), part, box.w, box.h)
        val base = layout.copy(y = minOf(layout.y, (bottomLimit - layout.h).coerceAtLeast(EDGE)))
        val placed = avoidBossBars(base, bottomLimit, now)
        val opacity = tabFade.toward(if (tabVisible) 0f else 1f, now)
        lastBounds = if (opacity > .01f) placed else null
        if (opacity <= .01f) { chat = null; return }
        val pose = graphics.pose()
        graphics.nextStratum()
        pose.pushMatrix().translate(placed.x.toFloat(), placed.y.toFloat()).scale(part.scale)
        try {
            Ui.faded(opacity) {
                HudEffects.solid(graphics, box, visualRadius.roundToInt())
                val mix = switch.toward(1f, now)
                RoundedRect.clipped(graphics, box.inset(2)) {
                    outgoing?.let { previous -> if (mix < 1f) Ui.faded(1f - mix) { previous.draw(graphics, box, now) } }
                    Ui.faded(mix) { scene.draw(graphics, box, now) }
                }
                if (switch.finished(now)) outgoing = null
            }
        } finally { pose.popMatrix() }
    }

    private fun status(client: Minecraft, width: Int): Scene {
        val title = HudEffects.bold("Travelto")
        val name = client.player?.gameProfile?.name ?: client.user.name
        val ping = client.player?.let { client.connection?.getPlayerInfo(it.uuid)?.latency }
        val server = when {
            client.hasSingleplayerServer() -> Ui.label("travelto.home.singleplayer").string
            client.currentServer != null -> client.currentServer!!.ip
            else -> Ui.label("travelto.watermark.local").string
        }
        val stats = listOf(Stat(0, name), Stat(1, ping?.takeIf { it >= 0 }?.let { "$it ms" } ?: "— ms"), Stat(2, server))
        val start = 31 + Ui.body.width(title)
        val remaining = (width - start - 6).coerceAtLeast(0)
        val shown = mutableListOf<Stat>()
        var spent = 0
        for (stat in stats) {
            val available = (remaining - spent - 16).coerceAtMost(94)
            if (available < 10) break
            val text = Ui.fit(stat.text, available)
            if (text.isEmpty()) break
            shown += stat.copy(text = text)
            spent += 16 + Ui.body.width(text)
        }
        return Scene(Kind.STATUS, minOf(width, start + spent + 5), maxOf(24, Ui.textHeight + 10), 7) { graphics, box, _ ->
            val y = (box.h - Ui.textHeight) / 2
            Ui.image(graphics, HudEffects.logo, Box(6, (box.h - 15) / 2, 15, 15), 96)
            Ui.soften(graphics, title, 25, y, Travelto.state.hud.glow)
            var left = start
            for (stat in shown) {
                statIcon(graphics, stat.icon, left - 1, box.h / 2)
                Ui.text(graphics, stat.text, left + 10, y, Ui.MUTED, shadow = false)
                left += 16 + Ui.body.width(stat.text)
            }
        }
    }

    private fun statIcon(graphics: GuiGraphicsExtractor, kind: Int, x: Int, middle: Int) {
        val color = Ui.mix(Ui.MUTED, Ui.ACCENT, .24f)
        when (kind) {
            0 -> { Ui.themeBox(graphics, Box(x + 2, middle - 4, 4, 4), color, 2); Ui.themeBox(graphics, Box(x, middle + 1, 8, 3), color, 2) }
            1 -> for (bar in 0..2) Ui.themeBox(graphics, Box(x + bar * 3, middle + 2 - (bar + 1) * 2, 2, (bar + 1) * 2), color, 1)
            2 -> { Ui.themeStroke(graphics, Box(x, middle - 4, 8, 8), color, 2); Ui.themeBox(graphics, Box(x + 2, middle - 1, 4, 1), color, 1) }
        }
    }

    private fun noticeScene(width: Int, now: Long): Scene? {
        val shown = mutableListOf<Triple<Notice, Float, Float>>()
        val iterator = notices.values.iterator()
        while (iterator.hasNext()) {
            val card = iterator.next()
            val started = card.shown ?: now.also { card.shown = it }
            val hold = if (notices.size > 1) 1_400_000_000L else 2_250_000_000L
            val opening = now - started < hold
            val visibility = card.visibility.toward(if (opening) 1f else 0f, now)
            if (!opening && visibility < .005f) { iterator.remove(); continue }
            shown += Triple(card, visibility, card.toggle.toward(if (card.enabled == true) 1f else 0f, now))
        }
        if (shown.isEmpty()) return null
        val row = maxOf(32, Ui.textHeight * 2 + 9)
        val desired = shown.maxOf { (entry) -> maxOf(Ui.body.width(entry.title), HudEffects.smallWidth(entry.message.string)) + if (entry.enabled != null) 65 else 39 }
        val span = minOf(width, desired.coerceIn(122, 252))
        val sum = shown.sumOf { (_, alpha) -> (row * alpha).toDouble() }.roundToInt()
        return Scene(Kind.NOTICES, span, maxOf(row, sum) + 6, 9) { graphics, box, _ ->
            var cursor = 4f
            for ((index, triple) in shown.withIndex()) {
                val (entry, amount, toggle) = triple
                val y = (cursor + (1f - amount) * 5f).roundToInt()
                cursor += row * amount
                if (amount < .01f) continue
                Ui.faded(amount) {
                    if (index > 0) Ui.box(graphics, Box(9, y, box.w - 18, 1), Ui.LINE, 1)
                    Ui.image(graphics, HudEffects.logo, Box(9, y + (row - 14) / 2, 14, 14), 96)
                    val space = box.w - if (entry.enabled != null) 61 else 38
                    Ui.text(graphics, HudEffects.bold(Ui.fit(entry.title.string, space)), 29, y + 4)
                    val color = when (entry.enabled) { true -> 0xff8df1c8.toInt(); false -> 0xffffa2a8.toInt(); null -> Ui.MUTED }
                    HudEffects.small(graphics, Ui.fit(entry.message.string, (space / .84f).toInt()), 29, y + 5 + Ui.textHeight, color)
                    if (entry.enabled != null && box.w >= 60) Ui.toggle(graphics, Box(box.w - 29, y + (row - 10) / 2, 22, 10), toggle)
                }
            }
        }
    }

    private fun blockScene(client: Minecraft, width: Int, now: Long): Scene? {
        val recent = stock ?: return null
        if (!Travelto.state.hud.waterMarkOptions.blockStock || now - recent.time > STOCK_TIME) return null
        val player = client.player ?: return null
        val inventory = player.inventory
        var total = 0
        for (index in 0 until inventory.containerSize) {
            val item = inventory.getItem(index)
            if (!item.isEmpty && item.item === recent.item) total += item.count
        }
        val held = sequenceOf(player.mainHandItem, player.offhandItem).firstOrNull { !it.isEmpty && it.item === recent.item }?.count ?: 0
        val title = recent.item.defaultInstance.hoverName.string
        val left = Ui.label("travelto.watermark.blocks", total).string
        val right = Ui.label("travelto.watermark.held", held, placements.size).string
        val bar = stockProgress.toward((total / 64f).coerceIn(0f, 1f), now)
        val color = if (total <= 8) HudEffects.DAMAGE else Ui.ACCENT
        val span = minOf(width, maxOf(150, Ui.body.width(left) + Ui.body.width(right) + 22))
        return Scene(Kind.BLOCKS, span, maxOf(45, Ui.textHeight * 2 + 17), 8) { graphics, box, _ ->
            HudEffects.item(graphics, recent.item.defaultInstance, Box(7, 5, 16, 16))
            Ui.text(graphics, HudEffects.bold(Ui.fit(title, box.w - 37)), 29, 7)
            val rightText = Ui.fit(right, ((box.w / 2 - 7) / .84f).toInt())
            HudEffects.small(graphics, Ui.fit(left, ((box.w - HudEffects.smallWidth(rightText) - 21) / .84f).toInt()), 8, box.h - 19, color)
            HudEffects.small(graphics, rightText, box.w - HudEffects.smallWidth(rightText) - 8, box.h - 19)
            val track = Box(8, box.h - 8, box.w - 16, 4)
            Ui.box(graphics, track, Ui.CONTROL, 2)
            val fill = track.copy(w = (track.w * bar).roundToInt())
            if (total > 8) Ui.bloom(graphics, fill, color, 2)
            HudEffects.gradient(graphics, fill, 2f, color, Ui.mix(color, -1, .31f))
        }
    }

    private fun activeChat(): ChatLayout? = chat?.takeIf { chatPanel && !tabVisible && islandEnabled(Minecraft.getInstance()) }

    private fun drawChat(graphics: GuiGraphicsExtractor, client: Minecraft, screen: ChatScreen, mouseX: Int, mouseY: Int) {
        if (!islandEnabled(client)) return
        sync(client)
        val now = System.nanoTime()
        val part = Travelto.state.hud.island
        val width = logicalWidth(graphics.guiWidth(), part)
        val normal = status(client, width)
        idleBounds = clearBossBars(Hud.place(graphics.guiWidth(), graphics.guiHeight(), part, normal.w, normal.h))
        val available = !tabVisible && Travelto.state.hud.waterMarkOptions.chat && client.options.chatVisibility().get() == ChatVisiblity.FULL
        val pointX = mouseX.toDouble()
        val pointY = mouseY.toDouble()
        chatPanel = available && (idleBounds?.contains(pointX, pointY) == true || chatPanel &&
            (lastBounds?.contains(pointX, pointY) == true || editing || scrolling))
        if (!chatPanel) { chat = null; render(graphics, normal, now); return }
        val panelWidth = minOf(232, width)
        val panelHeight = minOf(168, ((graphics.guiHeight() - 34) / part.scale).toInt()).coerceAtLeast(normal.h)
        if (panelWidth < 90 || panelHeight < 52) { chatPanel = false; chat = null; render(graphics, normal, now); return }
        val messages = client.gui.hud.chat as IslandChatAccess
        val input = (screen as IslandChatScreenAccess).traveltoChatInput()
        val scene = Scene(Kind.CHAT, panelWidth, panelHeight, 11) { context, box, _ ->
            Ui.image(context, HudEffects.logo, Box(8, 5, 13, 13), 96)
            Ui.text(context, Ui.label("travelto.watermark.chat"), 26, 7)
            if (box.w >= 90 && box.h >= 52) {
                val lineHeight = Ui.textHeight + 2
                val lines = Box(8, 24, box.w - 22, box.h - 51)
                val messageList = messages.traveltoIslandMessages()
                if (chatLast !== messageList.firstOrNull() || chatSize != messageList.size || chatWidth != lines.w) {
                    chatLast = messageList.firstOrNull(); chatSize = messageList.size; chatWidth = lines.w
                    val filter = messages.traveltoIslandFilter()
                    chatLines = messageList.asSequence().filter { filter.test(it) }.take(CHAT_LIMIT).flatMap { Ui.body.split(it.content(), lines.w).asReversed().asSequence() }.take(180).toList()
                }
                val limit = (lines.h / lineHeight).coerceAtLeast(1)
                chatMaxScroll = (chatLines.size - limit).coerceAtLeast(0)
                chatScroll = chatScroll.coerceIn(0, chatMaxScroll)
                RoundedRect.clipped(context, lines) {
                    for ((index, message) in chatLines.drop(chatScroll).take(limit).withIndex()) {
                        val ink = Ui.tint(Ui.TEXT)
                        if (ink ushr 24 > 3) context.text(Ui.body, message, lines.x, lines.bottom - (1 + index) * lineHeight, ink, false)
                    }
                }
                val scrollbar = Box(box.w - 9, lines.y, 3, lines.h)
                if (chatMaxScroll > 0) {
                    Ui.box(context, scrollbar, Ui.CONTROL, 2)
                    val knob = (scrollbar.h * limit / chatLines.size).coerceIn(3, scrollbar.h.coerceAtLeast(3))
                    val top = scrollbar.bottom - knob - (scrollbar.h - knob) * chatScroll / chatMaxScroll
                    Ui.themeBox(context, Box(scrollbar.x, top, 3, knob), radius = 2)
                }
                val send = Box(box.w - 49, box.h - 22, 42, 16)
                val field = Box(7, send.y, send.x - 13, send.h)
                Ui.control(context, field, if (editing) .6f else 0f, editing, radius = 4)
                Ui.control(context, send, 0f, active = input.value.isNotBlank(), radius = 4)
                val caption = Ui.label("travelto.watermark.send")
                Ui.text(context, caption, send.x + (send.w - Ui.body.width(caption)) / 2, send.y + (send.h - Ui.textHeight) / 2)
                val fieldText = Ui.fit(input.value.ifBlank { Ui.label("travelto.watermark.message").string }, field.w - 10)
                Ui.text(context, fieldText, field.x + 5, field.y + (field.h - Ui.textHeight) / 2, if (input.value.isEmpty()) Ui.MUTED else Ui.TEXT)
                if (editing && now.mod(1_000_000_000) < 500_000_000) {
                    val caret = field.x + 5 + Ui.body.width(fieldText)
                    Ui.themeBox(context, Box(caret.coerceAtMost(field.right - 3), field.y + 3, 1, field.h - 6), radius = 1)
                }
                val origin = lastBounds ?: return@Scene
                fun screenBox(local: Box) = Box(origin.x + (local.x * part.scale).roundToInt(), origin.y + (local.y * part.scale).roundToInt(),
                    (local.w * part.scale).roundToInt(), (local.h * part.scale).roundToInt())
                if (current?.kind == Kind.CHAT) chat = ChatLayout(screenBox(lines), screenBox(field), screenBox(send), screenBox(scrollbar), origin, part.scale)
            }
        }
        render(graphics, scene, now, graphics.guiHeight() - 19)
    }

    private fun chatClick(screen: ChatScreen, event: MouseButtonEvent): Boolean {
        val layout = activeChat() ?: return false
        if (event.button() != MOUSE_BUTTON_LEFT) return false
        val x = event.x(); val y = event.y()
        if (!layout.bounds.contains(x, y)) { editing = false; return false }
        val input = (screen as IslandChatScreenAccess).traveltoChatInput()
        when {
            layout.input.contains(x, y) -> {
                editing = true
                screen.setFocused(input)
                input.moveCursorToEnd(false)
            }
            layout.send.contains(x, y) && input.value.isNotBlank() -> {
                screen.handleChatInput(input.value, true)
                input.value = ""
                editing = true
            }
            chatMaxScroll > 0 && layout.scrollbar.copy(x = layout.scrollbar.x - 3, w = layout.scrollbar.w + 6).contains(x, y) -> {
                scrolling = true; scrollTo(layout, y)
            }
        }
        return true
    }

    private fun chatDrag(event: MouseButtonEvent): Boolean {
        val layout = activeChat() ?: return false
        if (!scrolling || event.button() != MOUSE_BUTTON_LEFT) return false
        scrollTo(layout, event.y())
        return true
    }

    private fun chatRelease(event: MouseButtonEvent): Boolean {
        if (!scrolling || event.button() != MOUSE_BUTTON_LEFT) return false
        scrolling = false
        return true
    }

    private fun scrollTo(layout: ChatLayout, y: Double) {
        chatScroll = (((layout.scrollbar.bottom - y) / layout.scrollbar.h.coerceAtLeast(1)).coerceIn(0.0, 1.0) * chatMaxScroll).roundToInt()
    }
}
