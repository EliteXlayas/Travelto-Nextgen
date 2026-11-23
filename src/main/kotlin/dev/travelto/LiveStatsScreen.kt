package dev.travelto

import dev.travelto.mixin.StatsCounterAccess
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.achievement.StatsScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket
import net.minecraft.stats.Stat
import net.minecraft.stats.StatType
import net.minecraft.stats.Stats
import net.minecraft.stats.StatsCounter
import net.minecraft.world.item.ItemStack

internal class LiveStatsScreen(parent: Screen, private val counter: StatsCounter) : StatsScreen(parent, counter) {
    private enum class Page(val label: String) {
        GENERAL("travelto.stats.general"), ITEMS("travelto.stats.items"), BLOCKS("travelto.stats.blocks"), MOBS("travelto.stats.mobs")
    }

    private val itemColumns = listOf(Stats.ITEM_CRAFTED, Stats.ITEM_USED, Stats.ITEM_BROKEN, Stats.ITEM_PICKED_UP, Stats.ITEM_DROPPED)
    private val blockColumns = listOf(Stats.BLOCK_MINED)
    private val mobColumns = listOf(Stats.ENTITY_KILLED, Stats.ENTITY_KILLED_BY)

    private data class Data(val label: Component, val icon: ItemStack?, val stats: List<Stat<*>>)
    private val scrolls = mutableMapOf<Page, Double>()
    private var page = Page.GENERAL
    private var sortColumn = -1
    private var ascending = true
    private var loaded = false
    private var tillRequest = 0
    private lateinit var list: StatList
    private var tabs = emptyList<GlassButton>()
    private var headings = emptyList<GlassButton>()
    private lateinit var done: GlassButton
    private var header = Box(0, 0, 0, 0)

    override fun init() {
        list = addRenderableWidget(StatList(minecraft))
        tabs = Page.entries.map { type -> addRenderableWidget(GlassButton(Box(0, 0, 1, 18), Ui.label(type.label)) { select(type) }) }
        done = addRenderableWidget(GlassButton(Box(0, 0, 1, 18), Ui.label("travelto.action.done"), true) { onClose() })
        setupColumns()
        repositionElements()
        createRows(restoreScroll = true)
        tillRequest = 0
        requestStats()
    }

    private fun select(type: Page) {
        if (type == page) return
        scrolls[page] = list.scrollAmount()
        page = type
        sortColumn = -1
        ascending = true
        setupColumns()
        repositionElements()
        createRows(restoreScroll = true)
    }

    private fun setupColumns() {
        headings.forEach { removeWidget(it) }
        val keys = when (page) {
            Page.GENERAL -> listOf("travelto.stats.value")
            Page.ITEMS -> listOf("travelto.stats.crafted", "travelto.stats.used", "travelto.stats.broken", "travelto.stats.picked_up", "travelto.stats.dropped")
            Page.BLOCKS -> listOf("travelto.stats.mined")
            Page.MOBS -> listOf("travelto.stats.kills", "travelto.stats.deaths")
        }
        headings = (listOf("travelto.stats.name") + keys).mapIndexed { index, key ->
            val column = index - 1
            addRenderableWidget(GlassButton(Box(0, 0, 1, 16), Ui.label(key)) {
                if (column == sortColumn) ascending = !ascending else {
                    sortColumn = column
                    ascending = column < 0
                }
                createRows()
            })
        }
    }

    override fun repositionElements() {
        if (!::list.isInitialized) return
        val span = (width - 18).coerceIn(1, 460)
        val left = (width - span) / 2
        val tabWidth = (span / tabs.size).coerceAtLeast(1)
        tabs.forEachIndexed { index, tab -> tab.setRectangle((tabWidth - 3).coerceAtLeast(1), 18, left + index * tabWidth, 8) }
        val listTop = 51
        list.updateSizeAndPosition(width, (height - listTop - 31).coerceAtLeast(1), listTop)
        header = Box((width - list.rowWidth) / 2, 31, list.rowWidth, 17)
        headings.forEachIndexed { index, button ->
            val leftEdge = columnEdge(header.x, header.w, index)
            val rightEdge = columnEdge(header.x, header.w, index + 1)
            button.setRectangle((rightEdge - leftEdge - 2).coerceAtLeast(1), header.h, leftEdge, header.y)
        }
        val doneWidth = minOf(112, span)
        done.setRectangle(doneWidth, 18, (width - doneWidth) / 2, height - 24)
    }

    private fun columnEdge(left: Int, width: Int, edge: Int): Int {
        if (edge == 0) return left
        val cells = headings.size - 1
        val first = width * (if (cells > 2) 33 else 65) / 100
        return left + first + (width - first) * (edge - 1) / cells
    }

    private fun createRows(restoreScroll: Boolean = false) {
        val scroll = if (restoreScroll) scrolls[page] ?: 0.0 else list.scrollAmount()
        val selected = list.selected?.data?.stats
        val records = if (!loaded) emptyList() else when (page) {
            Page.GENERAL -> BuiltInRegistries.CUSTOM_STAT.map { id ->
                Data(Component.translatable("stat.${id.toString().replace(':', '.')}"), null, listOf(Stats.CUSTOM.get(id)))
            }
            Page.ITEMS -> records(itemColumns, { item -> ItemStack(item).hoverName }, { item -> ItemStack(item) })
            Page.BLOCKS -> records(blockColumns, { block -> block.name }, { block -> ItemStack(block.asItem()) })
            Page.MOBS -> records(mobColumns, { mob -> mob.description }, { null })
        }
        val byName = compareBy<Data> { it.label.string.lowercase(icyllis.modernui.ModernUI.getSelectedLocale()) }
        val comparator = if (sortColumn < 0) byName else compareBy<Data> { row ->
            row.stats.getOrNull(sortColumn)?.let(counter::getValue) ?: 0
        }.then(byName)
        val sorted = records.sortedWith(if (ascending) comparator else comparator.reversed())
        if (list.children().map { it.data.stats } != sorted.map { it.stats }) {
            val values = sorted.map(::Row)
            list.setSelected(null)
            list.replaceEntries(values)
            list.setSelected(values.firstOrNull { it.data.stats == selected })
        }
        list.setScrollAmount(scroll)
    }

    private fun <T : Any> records(columns: List<StatType<T>>, name: (T) -> Component, icon: (T) -> ItemStack?): List<Data> {
        val keys = (counter as StatsCounterAccess).traveltoStats()
        val values = LinkedHashSet<T>()
        for (column in columns) for (stat in column) if (keys.getInt(stat) > 0) values.add(stat.value)
        return values.map { value -> Data(name(value), icon(value), columns.map { it.get(value) }) }
    }

    override fun onStatsUpdated() {
        if (!::list.isInitialized) return
        loaded = true
        createRows()
    }

    override fun isPauseScreen() = false

    override fun tick() {
        if (--tillRequest <= 0) requestStats()
    }

    private fun requestStats() {
        tillRequest = 40
        minecraft.connection?.send(ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS))
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        Ui.worldOverlay(graphics, 1f)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        tabs.forEachIndexed { index, button ->
            if (index == page.ordinal) Ui.themeStroke(graphics, Box(button.x - 1, button.y - 1, button.width + 2, button.height + 2))
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta)
        if (list.children().isEmpty()) {
            val message = Ui.label(if (loaded) "travelto.stats.empty" else "travelto.stats.loading").string
            Ui.text(graphics, message, (width - Ui.body.width(message)) / 2, list.y + 12, Ui.MUTED)
        }
    }

    private inner class StatList(client: Minecraft) : ObjectSelectionList<Row>(client, 1, 1, 51, 23) {
        override fun getRowWidth() = (this@LiveStatsScreen.width - 30).coerceIn(1, 450)
    }

    private inner class Row(val data: Data) : ObjectSelectionList.Entry<Row>() {
        override fun getNarration(): Component = data.stats.fold(data.label.copy()) { narration, stat ->
            narration.append(" · ").append(stat.type.displayName).append(": ").append(stat.format(counter.getValue(stat)))
        }

        override fun extractContent(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            if (data.icon?.isEmpty == false) graphics.item(data.icon, x + 3, y + 2)
            val left = x + if (data.icon == null) 6 else 23
            val textY = y + (height - Ui.textHeight) / 2
            Ui.text(graphics, Ui.fit(data.label.string, columnEdge(x, width, 1) - left - 5), left, textY)
            data.stats.forEachIndexed { index, stat ->
                val from = columnEdge(x, width, index + 1)
                val to = columnEdge(x, width, index + 2)
                val text = Ui.fit(stat.format(counter.getValue(stat)), to - from - 6)
                Ui.text(graphics, text, from + (to - from - Ui.body.width(text)) / 2, textY, Ui.MUTED)
            }
        }
    }
}
