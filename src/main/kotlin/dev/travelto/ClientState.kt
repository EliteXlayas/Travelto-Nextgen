package dev.travelto

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.InputConstants.*
import org.lwjgl.sdl.SDLMouse.SDL_GetMouseState
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

internal enum class Category(val label: String) { CLIENT("travelto.client"), RENDER("travelto.render") }
internal enum class Module(val id: String, val category: Category, val defaultEnabled: Boolean = true) {
    HUD("HUD", Category.CLIENT), MENU_SHADER("MenuShader", Category.RENDER),
    WATER_MARK("WaterMark", Category.RENDER), TARGET_HUD("TargetHUD", Category.RENDER), PING("Ping", Category.RENDER),
    EFFECTS("Effects", Category.RENDER), FREE_CAM("FreeCam", Category.RENDER, defaultEnabled = false),
    ITEM_PHYSICS("ItemPhysics", Category.RENDER, defaultEnabled = false);
    companion object { fun find(name: String) = entries.firstOrNull { it.id.equals(name, true) } }
}
internal enum class BindMode { TOGGLE, HOLD, SMART }
internal data class Bind(var code: Int = -1, var mouse: Boolean = false, var modifiers: Int = 0, var mode: BindMode = BindMode.TOGGLE) {
    fun display(): String {
        if (code < 0) return TraveltoLanguage.label("travelto.bind.none").string
        val key = (if (mouse) InputConstants.Type.MOUSE else InputConstants.Type.KEYBOARD).getOrCreate(code)
        return (modifierKeys.filter { modifiers and it.first != 0 }.map { it.second } + key.displayName.string).joinToString(" + ")
    }
    fun pressed(): Boolean {
        if (code !in validCodes(mouse)) return false
        if (mouse && SDL_GetMouseState(null, null) and (1 shl (code - 1)) == 0) return false
        if (!mouse && !isKeyDown(code)) return false
        var mask = 0
        for ((flag, _, keys) in modifierKeys) if (keys.any { isKeyDown(it) }) mask = mask or flag
        return normalizeModifiers(code, mouse, mask) == modifiers
    }
    companion object {
        private val modifierKeys = listOf(
            Triple(MOD_CONTROL, "Ctrl", listOf(KEY_LCONTROL, KEY_RCONTROL)), Triple(MOD_ALT, "Alt", listOf(KEY_LALT, KEY_RALT)),
            Triple(MOD_SHIFT, "Shift", listOf(KEY_LSHIFT, KEY_RSHIFT)), Triple(MOD_SUPER, "Super", listOf(KEY_LGUI, KEY_RGUI))
        )
        fun validCodes(mouse: Boolean) = if (mouse) MOUSE_BUTTON_LEFT..MOUSE_BUTTON_8 else 1..511
        fun isModifier(code: Int) = modifierKeys.any { code in it.third }
        fun normalizeModifiers(code: Int, mouse: Boolean, mask: Int): Int = modifierKeys.fold(0) { result, (flag, _, keys) ->
            if (mask and flag != 0 && (mouse || code !in keys)) result or flag else result
        }
    }
}
internal data class PanelState(var x: Int = 0, var y: Int = 0, var open: Boolean = true, var scroll: Int = 0, var layer: Int = 0)
internal data class HudPart(
    var x: Float = 0f, var y: Float = 0f, var scale: Float = 1f, var visible: Boolean = true, var motionSpeed: Int = 100,
    var offsetX: Float = 0f, var offsetY: Float = 0f
)
internal enum class WaterMarkStyle(val label: String) {
    LOGO("Logo"), DYNAMIC_ISLAND("DynamicIsland");
    fun next() = entries[(ordinal + 1) % entries.size]
}
internal enum class WaterMarkPosition(val anchorX: Float, val anchorY: Float) {
    TOP_LEFT(0f, 0f), TOP_CENTER(.5f, 0f), TOP_RIGHT(1f, 0f),
    BOTTOM_LEFT(0f, 1f), BOTTOM_CENTER(.5f, 1f), BOTTOM_RIGHT(1f, 1f);

    fun applyTo(part: HudPart) { part.x = anchorX; part.y = anchorY; part.offsetX = 0f; part.offsetY = 0f }
    fun next() = entries[(ordinal + 1) % entries.size]
    companion object {
        fun of(part: HudPart) = entries.firstOrNull { it.anchorX == part.x && it.anchorY == part.y && part.offsetX == 0f && part.offsetY == 0f }
    }
}
internal enum class TargetStyle(val label: String) {
    TRAVELTO("Travelto"), SKYLINE("Skyline");
    fun next() = entries[(ordinal + 1) % entries.size]
}
internal data class WaterMarkOptions(
    var style: WaterMarkStyle = WaterMarkStyle.LOGO,
    var notices: Boolean = true,
    var blockStock: Boolean = false,
    var chat: Boolean = true
)
internal enum class TargetMobIcon { PORTRAIT, SPAWN_EGG }
internal data class TargetHudOptions(
    var style: TargetStyle = TargetStyle.TRAVELTO,
    var followTarget: Boolean = false,
    var cellMitosis: Boolean = true,
    var aimRange: Int = 6,
    var mobIcon: TargetMobIcon = TargetMobIcon.PORTRAIT
)
internal const val DEFAULT_PING_SOUND = "minecraft:block.note_block.pling"
internal val PING_RANGE = 16..192
internal val PING_LIFETIME = 2..20
internal val PING_VOLUME = 0..100
internal data class PingOptions(var range: Int = 96, var seconds: Int = 6, var sound: String = DEFAULT_PING_SOUND, var volume: Int = 65)

internal const val DEFAULT_ACCENT = 0x7c5cff
internal const val DEFAULT_LIST_COLOR = 0xedeef2
internal val BLOOM_SIZE = 1..12
internal val CLICK_GUI_SCALE = 65..160
internal val HUD_SCALE = 65..200
internal val TARGET_AIM_RANGE = 1..32

internal enum class ListColorMode {
    FIXED, THEME, RAINBOW;
    fun next() = entries[(ordinal + 1) % entries.size]
}

internal data class ListAppearance(var mode: ListColorMode = ListColorMode.FIXED, var color: Int = DEFAULT_LIST_COLOR)
internal data class BloomOptions(@Volatile var enabled: Boolean = true, @Volatile var size: Int = 4)

internal data class HudStyle(
    @Volatile var accent: Int = DEFAULT_ACCENT, var glow: Boolean = true,
    var watermark: HudPart = HudPart(0f, 0f), var fps: HudPart = HudPart(.5f, 0f), var arrayList: HudPart = HudPart(1f, 0f),
    var island: HudPart = HudPart(.5f, .18f), var target: HudPart = HudPart(.5f, .66f),
    var waterMarkOptions: WaterMarkOptions = WaterMarkOptions(), var targetHudOptions: TargetHudOptions = TargetHudOptions(),
    var listAppearance: ListAppearance = ListAppearance(), var bloom: BloomOptions = BloomOptions(),
    var effects: HudPart = HudPart(0f, .28f)
) {
    fun watermarkPart() = if (waterMarkOptions.style == WaterMarkStyle.LOGO) watermark else island
}
internal data class ClientState(
    var modules: MutableMap<String, Boolean> = defaultModules(),
    var binds: MutableMap<String, Bind> = linkedMapOf(),
    var panels: MutableMap<String, PanelState> = linkedMapOf(),
    var expanded: MutableSet<String> = linkedSetOf(),
    var hud: HudStyle = HudStyle(),
    var blur: Boolean = true,
    var reducedMotion: Boolean = false,
    var typography: Typography = Typography(),
    var clickGuiScale: Int = 100,
    var motion: MotionOptions = MotionOptions(),
    var ping: PingOptions = PingOptions(),
    var freeCam: FreeCamOptions = FreeCamOptions()
) {
    fun panel(category: Category) = panels.getOrPut(category.name) { PanelState(12 + category.ordinal * 150, 46, layer = category.ordinal) }
    fun binding(module: Module) = binds.getOrPut(module.id) { Bind() }
    fun enabled(module: Module) = modules[module.id] ?: module.defaultEnabled
    companion object { fun defaultModules() = Module.entries.associate { it.id to it.defaultEnabled }.toMutableMap() }
}

internal object ConfigFiles {
    val directory: Path get() = FabricLoader.getInstance().configDir.resolve("travelto-fabric")

    fun write(destination: Path, content: (BufferedWriter) -> Unit) {
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".travelto-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use(content)
            try { Files.move(temporary, destination, REPLACE_EXISTING, ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, destination, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}

internal object Profiles {
    const val DEFAULT = "default"
    private const val FORMAT_VERSION = 1
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create()
    private val namePattern = Regex("[A-Za-z0-9_-]{1,64}")
    private var backupBeforeSave = false
    private fun path(name: String): Path {
        require(namePattern.matches(name)) { "Use 1–64 ASCII letters, digits, underscores or hyphens." }
        require(!Regex("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]").matches(name)) { "This profile name is reserved by Windows." }
        val fileName = if (name.equals(DEFAULT, true)) DEFAULT else name
        return ConfigFiles.directory.resolve("$fileName.json")
    }

    fun loadDefault() = load(DEFAULT, allowMissing = true)

    fun load(name: String) = load(name, allowMissing = false)

    private fun load(name: String, allowMissing: Boolean): ClientState {
        val file = path(name)
        val data = try {
            if (Files.size(file) > 128 * 1024) throw IOException("Profile exceeds 128 KiB.")
            Files.newBufferedReader(file).use { gson.fromJson(it, JsonObject::class.java) }
                ?: throw JsonParseException("Empty profile.")
        } catch (error: NoSuchFileException) {
            if (!allowMissing) throw error
            return ClientState().also { loadLegacyAppearance(it.hud) }
        }
        val version = data.get("formatVersion")
        require(version == null || version == JsonPrimitive(FORMAT_VERSION)) { "Unsupported profile format." }
        val state = normalize(if (version == null && data.has("schema")) readAnchoredProfile(data)
            else gson.fromJson(data, ClientState::class.java))
        if (version == null && name.equals(DEFAULT, true)) {
            loadLegacyAppearance(state.hud)
            backupBeforeSave = true
        }
        return state
    }

    fun save(name: String, state: ClientState) {
        val file = path(name)
        val upgrade = name.equals(DEFAULT, true) && backupBeforeSave
        if (upgrade) backUp(file)
        ConfigFiles.write(file) { writer ->
            val data = gson.toJsonTree(state).asJsonObject
            data.getAsJsonObject("modules").addProperty(Module.FREE_CAM.id, false)
            data.addProperty("formatVersion", FORMAT_VERSION)
            gson.toJson(data, writer)
        }
        if (upgrade) backupBeforeSave = false
    }

    private fun backUp(file: Path) {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "-$index"
            val backup = file.resolveSibling("${file.fileName}.pre-upgrade$suffix")
            try { Files.copy(file, backup); return }
            catch (_: FileAlreadyExistsException) {
                if (Files.mismatch(file, backup) == -1L) return
                index++
            }
        }
    }

    private fun readAnchoredProfile(data: JsonObject): ClientState {
        require(data.get("schema") == JsonPrimitive(1)) { "Unsupported legacy profile schema." }
        val oldHud = data.getAsJsonObject("hud")
        val bloom = oldHud?.get("bloom")
        if (bloom is JsonPrimitive && bloom.isBoolean) oldHud.add("bloom", gson.toJsonTree(BloomOptions(bloom.asBoolean)))
        val blur = data.get("blur")
        if (blur is JsonObject) data.addProperty("blur", blur.get("gaussian")?.asBoolean ?: true)
        val bindings = data.getAsJsonObject("bindings")
        if (!data.has("binds") && bindings != null) {
            val compatible = JsonObject()
            bindings.entrySet().forEach { (name, binding) ->
                val module = Module.find(name)
                if (module != null && binding is JsonObject && binding.has("code")) compatible.add(module.id, binding)
            }
            data.add("binds", compatible)
        }
        val state = gson.fromJson(data, ClientState::class.java)
        val hud = state.hud
        oldHud?.get("mainColor")?.asString?.removePrefix("#")?.toIntOrNull(16)?.let {
            hud.accent = it
            hud.listAppearance.color = it
        }
        if (oldHud?.get("rainbow")?.asBoolean == true) hud.listAppearance.mode = ListColorMode.RAINBOW
        val layout = oldHud?.getAsJsonObject("layout")
        fun restore(key: String, part: HudPart) {
            val entry = layout?.getAsJsonObject(key) ?: return
            part.x = entry.get("anchorX")?.asInt?.div(2f) ?: part.x
            part.y = entry.get("anchorY")?.asInt?.div(2f) ?: part.y
            part.offsetX = entry.get("offsetX")?.asFloat ?: 0f
            part.offsetY = entry.get("offsetY")?.asFloat ?: 0f
            part.scale = entry.get("scale")?.asFloat ?: part.scale
            part.visible = entry.get("visible")?.asBoolean ?: part.visible
        }
        restore("WATERMARK", hud.watermark)
        restore("MODULES", hud.arrayList)
        data.getAsJsonObject("clickGui")?.entrySet()?.forEach { (name, value) ->
            val entry = value.asJsonObject
            entry.getAsJsonArray("expanded")?.forEach { Module.find(it.asString)?.let { module -> state.expanded.add(module.id) } }
            val category = Category.entries.firstOrNull { it.name.equals(name, true) } ?: return@forEach
            val panel = state.panel(category)
            entry.get("x")?.asInt?.takeIf { it >= 0 }?.let { panel.x = it }
            entry.get("y")?.asInt?.takeIf { it >= 0 }?.let { panel.y = it }
            entry.get("open")?.asBoolean?.let { panel.open = it }
            entry.get("offset")?.asInt?.let { panel.scroll = it }
            entry.get("order")?.asInt?.let { panel.layer = it }
        }
        return state
    }

    private data class Appearance(val accent: Int?, val listAppearance: ListAppearance?, val bloom: BloomOptions?)

    private fun loadLegacyAppearance(hud: HudStyle) {
        val file = ConfigFiles.directory.resolve("appearance.settings.json")
        val appearance = try {
            if (Files.size(file) > 4096) throw IOException("Appearance configuration exceeds 4 KiB.")
            Files.newBufferedReader(file).use { gson.fromJson(it, Appearance::class.java) }
                ?: throw JsonParseException("Empty appearance configuration.")
        } catch (_: NoSuchFileException) { return }
        catch (error: Exception) { Travelto.log.warn("Could not read the legacy Travelto appearance", error); return }
        appearance.accent?.let { hud.accent = it and 0xffffff }
        @Suppress("SENSELESS_COMPARISON")
        appearance.listAppearance?.takeIf { it.mode != null }?.let {
            hud.listAppearance = it.apply { color = color and 0xffffff }
        }
        appearance.bloom?.let { hud.bloom = it.apply { size = size.coerceIn(BLOOM_SIZE) } }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun normalize(state: ClientState): ClientState {
        require(state.modules != null && state.binds != null && state.panels != null && state.expanded != null && state.hud != null) { "Profile contains null fields." }
        state.modules[Module.FREE_CAM.id] = false
        state.binds.keys.retainAll(Module.entries.map { it.id }.toSet())
        state.expanded.retainAll(Module.entries.map { it.id }.toSet())
        state.panels.keys.retainAll(Category.entries.map { it.name }.toSet())
        for (module in Module.entries) {
            val bind = state.binding(module)
            require(bind != null && bind.mode != null) { "Invalid binding for ${module.id}." }
            if (bind.code !in Bind.validCodes(bind.mouse)) bind.code = -1
            bind.modifiers = Bind.normalizeModifiers(bind.code, bind.mouse, bind.modifiers)
        }
        Category.entries.map { state.panel(it) }.sortedBy { it.layer }.forEachIndexed { index, panel ->
            panel.layer = index
            panel.x = panel.x.coerceIn(0, 10000); panel.y = panel.y.coerceIn(0, 10000); panel.scroll = panel.scroll.coerceIn(0, 10000)
        }
        val typography = state.typography
        require(typography != null && typography.font != null && typography.direction != null &&
            typography.size.isFinite() && typography.baseline.isFinite()) { "Invalid typography configuration." }
        typography.size = typography.size.coerceIn(7f, 12f)
        typography.baseline = typography.baseline.coerceIn(4f, 10f)
        state.clickGuiScale = state.clickGuiScale.coerceIn(CLICK_GUI_SCALE)
        val motion = state.motion
        require(motion != null && motion.curve != null) { "Invalid animation configuration." }
        motion.speed = motion.speed.coerceIn(MOTION_SPEED)
        val ping = state.ping
        require(ping != null) { "Invalid ping configuration." }
        ping.range = ping.range.coerceIn(PING_RANGE)
        ping.seconds = ping.seconds.coerceIn(PING_LIFETIME)
        ping.volume = ping.volume.coerceIn(PING_VOLUME)
        if (ping.sound.isNullOrBlank() || ping.sound.length > 128 || Identifier.tryParse(ping.sound) == null) ping.sound = DEFAULT_PING_SOUND
        val freeCam = state.freeCam
        require(freeCam != null && freeCam.cancelOn != null) { "Invalid FreeCam configuration." }
        freeCam.speed = freeCam.speed.coerceIn(FREE_CAM_SPEED)
        freeCam.cancelOn.retainAll(FreeCamCancel.entries.toSet())
        val hud = state.hud
        hud.accent = hud.accent and 0xffffff
        if (hud.listAppearance == null || hud.listAppearance.mode == null) hud.listAppearance = ListAppearance()
        hud.listAppearance.color = hud.listAppearance.color and 0xffffff
        if (hud.bloom == null) hud.bloom = BloomOptions()
        hud.bloom.size = hud.bloom.size.coerceIn(BLOOM_SIZE)
        val waterMark = hud.waterMarkOptions
        val targetHud = hud.targetHudOptions
        require(waterMark != null && waterMark.style != null && targetHud != null && targetHud.style != null) { "Invalid HUD style configuration." }
        targetHud.aimRange = targetHud.aimRange.coerceIn(TARGET_AIM_RANGE)
        if (targetHud.mobIcon == null) targetHud.mobIcon = TargetMobIcon.PORTRAIT
        if (hud.effects == null) hud.effects = HudPart(0f, .28f)
        for (part in listOf(hud.watermark, hud.fps, hud.arrayList, hud.island, hud.target, hud.effects)) {
            require(part != null && part.x.isFinite() && part.y.isFinite() && part.scale.isFinite() &&
                part.offsetX.isFinite() && part.offsetY.isFinite()) { "Invalid HUD layout." }
            part.x = part.x.coerceIn(0f, 1f); part.y = part.y.coerceIn(0f, 1f); part.scale = part.scale.coerceIn(HUD_SCALE.first / 100f, HUD_SCALE.last / 100f)
            part.motionSpeed = part.motionSpeed.coerceIn(MOTION_SPEED)
        }
        return state
    }
}
