package dev.travelto

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT
import com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_5
import com.mojang.blaze3d.platform.InputConstants.Type
import org.slf4j.LoggerFactory

object Travelto : ClientModInitializer {
    internal val log = LoggerFactory.getLogger("Travelto")
    @Volatile internal var state = ClientState()
        private set
    private var protectDefault = false
    private data class Press(var down: Boolean = false, var started: Long = 0, var restore: Boolean? = null)
    private val presses = Module.entries.associateWith { Press() }
    private var wasPlaying = false
    private var pendingGui = false
    internal lateinit var menuKey: KeyMapping
        private set
    internal lateinit var pingKey: KeyMapping
        private set

    override fun onInitializeClient() {
        TraveltoLanguage.load()
        try { state = Profiles.loadDefault() }
        catch (error: Exception) { protectDefault = true; log.warn("Preserving unreadable default configuration", error) }
        ModernTypography.refresh()
        val controls = KeyMapping.Category.register(id("controls"))
        menuKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.travelto.modules", KEY_RSHIFT, controls))
        pingKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.travelto.ping", Type.MOUSE, MOUSE_BUTTON_5, controls))
        WaterMark.initialize()
        TargetAbsorption.initialize()
        HudElementRegistry.replaceElement(VanillaHudElements.MOB_EFFECTS) { vanilla ->
            HudElement { graphics, delta ->
                val client = Minecraft.getInstance()
                val screen = client.gui.screen()
                if (screen !is HudEditor) {
                    if (!state.enabled(Module.EFFECTS)) vanilla.extractRenderState(graphics, delta)
                    else if (state.hud.effects.visible && screen?.showsActiveEffects() != true) {
                        EffectsHud.draw(graphics, client, System.nanoTime())
                    }
                }
            }
        }
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("hud")) { graphics, _ ->
            val client = Minecraft.getInstance()
            if (client.level != null && client.player != null && !client.gui.hud.isHidden && client.gui.screen() !is HudEditor) {
                if (state.enabled(Module.HUD)) Hud.draw(graphics, client)
            }
        }
        HudElementRegistry.attachElementAfter(VanillaHudElements.PLAYER_LIST, id("watermark")) { graphics, _ ->
            if (!WaterMark.tabVisible) WaterMark.draw(graphics, Minecraft.getInstance(), System.nanoTime())
        }
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, id("target_hud")) { graphics, delta ->
            val client = Minecraft.getInstance()
            if (client.level != null && client.player != null && !client.gui.hud.isHidden && client.gui.screen() == null) {
                val partial = delta.getGameTimeDeltaPartialTick(false)
                val now = System.nanoTime()
                TargetHud.draw(graphics, client, partial, now)
                PingMarker.draw(graphics, client, partial, now)
            }
        }
        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen is ChatScreen) ScreenEvents.afterForeground(screen).register { _, graphics, _, _, partial ->
                if (!client.gui.hud.isHidden) TargetHud.draw(graphics, client, partial, System.nanoTime())
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            WaterMark.tick(client)
            TargetHud.tick(client)
            EffectsHud.tick(client)
            if (client.gui.overlay() == null) ModernTypography.update()
            val screen = client.gui.screen()
            if (screen?.javaClass == TitleScreen::class.java && client.gui.overlay() == null) client.gui.setScreen(HomeScreen())
            val playing = client.level != null && client.player != null && client.gui.screen() == null && client.gui.overlay() == null && client.isWindowActive
            var open = pendingGui && client.player != null
            pendingGui = false
            while (menuKey.consumeClick()) if (playing) open = true
            if (open) client.gui.setScreen(ClickGuiScreen(null, menuKey.isDown))
            var ping = false
            while (pingKey.consumeClick()) ping = true
            updateBindings(playing && !open)
            FreeCam.tick(client, playing && !open)
            PingMarker.tick(client, ping && playing && !open)
        }
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ -> stopFreeCam() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> stopFreeCam() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { saveConfig() }
        registerCommands()
    }

    private fun stateForSave(): ClientState {
        val held = presses.filterValues { it.restore != null }
        if (held.isEmpty()) return state
        val modules = state.modules.toMutableMap()
        held.forEach { (module, press) -> modules[module.id] = requireNotNull(press.restore) }
        return state.copy(modules = modules)
    }

    internal fun saveConfig() {
        if (protectDefault) return
        try { Profiles.save(Profiles.DEFAULT, stateForSave()) }
        catch (error: Exception) { log.warn("Could not save the default Travelto profile", error) }
    }

    internal fun toggle(module: Module): Boolean {
        presses.getValue(module).restore = null
        val enabled = setEnabled(module, !state.enabled(module))
        saveConfig()
        return enabled
    }

    internal fun stopFreeCam() {
        presses.getValue(Module.FREE_CAM).restore = null
        setEnabled(Module.FREE_CAM, false)
    }

    private fun setEnabled(module: Module, enabled: Boolean): Boolean {
        val client = Minecraft.getInstance()
        val applied = if (module == Module.FREE_CAM) FreeCam.setActive(client, enabled) else enabled
        val changed = state.enabled(module) != applied
        state.modules[module.id] = applied
        if (changed) WaterMark.moduleChanged(client, module, applied)
        return applied
    }

    private fun cancelGestures() {
        for ((module, press) in presses) {
            press.restore?.let { setEnabled(module, it) }
            press.restore = null
            press.down = false
        }
        wasPlaying = false
    }

    private fun updateBindings(playing: Boolean) {
        if (!playing) { if (wasPlaying) cancelGestures(); return }
        val now = System.nanoTime()
        for ((module, press) in presses) {
            val bind = state.binding(module)
            val down = bind.pressed()
            if (!wasPlaying) { press.down = down; continue }
            if (down && !press.down) {
                press.started = now
                when (bind.mode) {
                    BindMode.TOGGLE -> toggle(module)
                    BindMode.HOLD -> { press.restore = state.enabled(module); setEnabled(module, true) }
                    BindMode.SMART -> { press.restore = state.enabled(module); setEnabled(module, !state.enabled(module)) }
                }
            } else if (!down && press.down) {
                val previous = press.restore
                press.restore = null
                if (previous != null) {
                    if (bind.mode == BindMode.SMART && now - press.started < 250_000_000) saveConfig()
                    else setEnabled(module, previous)
                }
            }
            press.down = down
        }
        wasPlaying = true
    }

    private fun moduleStatus(module: Module, enabled: Boolean) = Ui.label("travelto.command.module_status", module.id, Ui.switchLabel(enabled))

    private fun registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = literal("travelto")
                .then(literal("modules").executes { context ->
                    context.source.sendFeedback(Component.literal(Module.entries.joinToString("  •  ") {
                        moduleStatus(it, state.enabled(it)).string
                    })); 1
                })
                .then(literal("toggle").then(argument("module", StringArgumentType.word()).suggests { _, builder ->
                    Module.entries.filter { it.id.startsWith(builder.remaining, true) }.forEach { builder.suggest(it.id) }
                    builder.buildFuture()
                }.executes { context ->
                    val name = StringArgumentType.getString(context, "module")
                    val module = Module.find(name)
                    if (module == null) { context.source.sendError(Ui.label("travelto.command.module_unknown", name)); 0 }
                    else { context.source.sendFeedback(moduleStatus(module, toggle(module))); 1 }
                }))
                .then(literal("gui").executes { pendingGui = true; 1 })
            val config = literal("config")
            for (action in listOf("save", "load")) config.then(literal(action).then(argument("name", StringArgumentType.word()).executes { context ->
                profile(context.source, action, StringArgumentType.getString(context, "name"))
            }))
            dispatcher.register(root.then(config))
        }
    }

    private fun profile(source: FabricClientCommandSource, action: String, name: String): Int = try {
        val isDefault = name.equals(Profiles.DEFAULT, true)
        if (action == "save") {
            Profiles.save(name, stateForSave())
            if (isDefault) protectDefault = false
        } else {
            val loaded = Profiles.load(name)
            stopFreeCam()
            cancelGestures()
            state = loaded
            if (isDefault) protectDefault = false
            saveConfig()
        }
        source.sendFeedback(Ui.label(if (action == "save") "travelto.command.profile_saved" else "travelto.command.profile_loaded", name)); 1
    } catch (error: Exception) {
        source.sendError(Ui.label("travelto.command.profile_error", error.message ?: error.javaClass.simpleName)); 0
    }

    internal fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("travelto", path)
}
