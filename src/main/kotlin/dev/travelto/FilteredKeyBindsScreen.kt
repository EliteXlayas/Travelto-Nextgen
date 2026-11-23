package dev.travelto

import com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE
import com.mojang.blaze3d.platform.InputConstants.KEY_DELETE
import com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
import dev.travelto.mixin.KeyEntryAccess
import net.minecraft.client.KeyMapping
import net.minecraft.client.Options
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.controls.KeyBindsList
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

internal class FilteredKeyBindsScreen(parent: Screen, private val clientOptions: Options) : KeyBindsScreen(parent, clientOptions) {
    private lateinit var search: EditBox
    private lateinit var filter: Button
    private lateinit var locate: Button
    private lateinit var keyList: KeyBindsList
    private var rows = emptyList<KeyBindsList.Entry>()
    private var query = ""
    private var onlyConflicts = false
    private var conflicts = emptySet<KeyMapping>()
    private var lastBindings = emptyList<String>()
    private var lastConflict: KeyMapping? = null

    override fun addTitle() {
        layout.headerHeight = 53
        val header = LinearLayout.vertical().spacing(6)
        header.addChild(StringWidget(title, font)) { it.alignHorizontallyCenter() }
        val tools = LinearLayout.horizontal().spacing(4)
        val searchLabel = Ui.label("travelto.keys.search")
        search = EditBox(font, (width - 182).coerceIn(56, 160), 20, searchLabel).apply {
            setHint(searchLabel)
            setMaxLength(64)
            value = query
            setResponder {
                query = it
                if (::keyList.isInitialized) applyFilter(resetScroll = true)
            }
        }
        tools.addChild(search)
        filter = tools.addChild(Button.builder(filterLabel()) {
            onlyConflicts = !onlyConflicts
            applyFilter(resetScroll = true)
        }.width(88).build())
        locate = tools.addChild(Button.builder(Ui.label("travelto.keys.locate")) { locateNext() }.width(68).build())
        header.addChild(tools)
        layout.addToHeader(header)
    }

    override fun init() {
        super.init()
        keyList = children().filterIsInstance<KeyBindsList>().single()
        rows = keyList.children().toList()
        lastBindings = emptyList()
        updateConflicts()
    }

    private fun mapping(entry: KeyBindsList.Entry) = (entry as? KeyEntryAccess)?.traveltoMapping()

    private fun filterLabel() = Ui.label(if (onlyConflicts) "travelto.keys.conflicts" else "travelto.keys.all", conflicts.size)

    private fun updateConflicts() {
        val keys = clientOptions.keyMappings
        val bindings = keys.map { it.saveString() }
        if (bindings == lastBindings) return
        lastBindings = bindings
        conflicts = keys.asSequence().filterNot(KeyMapping::isUnbound).groupBy { it.saveString() }
            .values.asSequence().filter { it.size > 1 }.flatten().toSet()
        if (lastConflict !in conflicts) lastConflict = null
        rows.forEach { it.refreshEntry() }
        applyFilter(resetScroll = false)
    }

    private fun matches(key: KeyMapping, words: List<String>): Boolean {
        if (onlyConflicts && key !in conflicts) return false
        if (words.isEmpty()) return true
        val labels = listOf(Component.translatable(key.name).string, key.translatedKeyMessage.string, key.category.label().string, key.name, key.saveString())
        return words.all { term -> labels.any { it.contains(term, ignoreCase = true) } }
    }

    private fun applyFilter(resetScroll: Boolean) {
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val visible = ArrayList<KeyBindsList.Entry>()
        var section: KeyBindsList.Entry? = null
        for (row in rows) {
            val key = mapping(row)
            if (key == null) section = row
            else if (matches(key, words)) {
                section?.let { visible.add(it) }
                section = null
                visible.add(row)
            }
        }
        val previous = keyList.scrollAmount()
        if (keyList.children() != visible) {
            keyList.setFocused(null)
            keyList.replaceEntries(visible)
        }
        keyList.setScrollAmount(if (resetScroll) 0.0 else previous)
        filter.message = filterLabel()
        locate.active = conflicts.isNotEmpty()
    }

    private fun locateNext() {
        val candidates = rows.filter { mapping(it) in conflicts }
        if (candidates.isEmpty()) return
        val current = candidates.indexOfFirst { mapping(it) === lastConflict }
        val entry = candidates[(current + 1) % candidates.size]
        lastConflict = mapping(entry)
        if (entry !in keyList.children()) search.value = ""
        setFocused(locate)
        val relative = entry.y - keyList.y + keyList.scrollAmount()
        keyList.setScrollAmount(relative - (keyList.height - entry.height) / 2.0)
        keyList.setSelected(entry)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (selectedKey != null && (event.key() == KEY_DELETE || event.key() == KEY_BACKSPACE)) {
            return super.keyPressed(KeyEvent(KEY_ESCAPE, 0, 0))
        }
        return super.keyPressed(event)
    }

    override fun tick() {
        super.tick()
        if (selectedKey == null) updateConflicts()
    }
}
