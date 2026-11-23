package dev.travelto

import icyllis.modernui.graphics.text.FontFamily
import icyllis.modernui.mc.Config
import icyllis.modernui.mc.ModernUIClient
import icyllis.modernui.mc.ModernUIMod
import icyllis.modernui.mc.text.ModernTextRenderer
import icyllis.modernui.mc.text.TextLayout
import icyllis.modernui.mc.text.TextLayoutEngine
import icyllis.modernui.mc.text.TextLayoutProcessor
import icyllis.modernui.view.View
import net.fabricmc.loader.api.FabricLoader
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.extension

internal enum class ReadingDirection(val engineValue: Int) {
    AUTO(View.TEXT_DIRECTION_FIRST_STRONG), LOCALE(View.TEXT_DIRECTION_LOCALE),
    LTR(View.TEXT_DIRECTION_LTR), RTL(View.TEXT_DIRECTION_RTL), ANY_RTL(View.TEXT_DIRECTION_ANY_RTL),
    FIRST_LTR(View.TEXT_DIRECTION_FIRST_STRONG_LTR), FIRST_RTL(View.TEXT_DIRECTION_FIRST_STRONG_RTL)
}

internal data class Typography(
    var font: String = "",
    var size: Float = 9f,
    var baseline: Float = 7f,
    var shadow: Boolean = true,
    var bitmap: Boolean = false,
    var direction: ReadingDirection = ReadingDirection.AUTO
)

internal object ModernTypography {
    private const val DEFAULT_NAME = "MiSans"
    private const val DEFAULT_FILE = "MiSans-Regular.ttf"
    private const val BOLD_FILE = "MiSans-Bold.ttf"
    private const val MAX_FONT_BYTES = 32L * 1024 * 1024
    private val directory get() = FabricLoader.getInstance().configDir.resolve("travelto-fabric/fonts")
    private data class Face(val file: String, val selector: String?, val fallback: Boolean)
    private data class Catalog(val files: List<String>, val defaultSelector: String?)
    private var scan: CompletableFuture<Catalog>? = null
    private var catalog: Catalog? = null
    private var requested: String? = null
    private var result: CompletableFuture<Face>? = null
    private var installed: Face? = null
    val files: List<String> get() = catalog?.files.orEmpty()
    val available: Boolean get() = ModernUIMod.isTextEngineEnabled()
    val loading: Boolean get() = scan != null || result != null
    val missing: Boolean get() = installed?.fallback == true
    var activeName: String? = null
        private set

    fun refresh() {
        if (scan != null) return
        val root = directory
        scan = CompletableFuture.supplyAsync {
            try {
                Files.createDirectories(root)
                val candidates = Files.list(root).use { paths ->
                    paths.filter(::fontFile).map { it.fileName.toString() }.sorted(String.CASE_INSENSITIVE_ORDER).limit(256).toList()
                }
                val preferred = candidates.firstOrNull { it.equals(DEFAULT_FILE, true) }
                val bold = candidates.firstOrNull { it.equals(BOLD_FILE, true) }
                val family = if (preferred == null) {
                    FontFamily.getSystemFontWithAlias(DEFAULT_NAME)
                } else {
                    if (bold != null) runCatching {
                        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(Font.createFont(Font.TRUETYPE_FONT, root.resolve(bold).toFile()))
                    }.onFailure { Travelto.log.warn("Could not register the MiSans bold face", it) }
                    runCatching { FontFamily.createFamily(root.resolve(preferred).toFile(), true) }
                        .onFailure { Travelto.log.warn("Could not load the MiSans font", it) }.getOrNull()
                }
                Catalog(candidates, family?.familyName)
            } catch (error: Exception) {
                Travelto.log.warn("Could not scan Travelto fonts", error)
                Catalog(emptyList(), FontFamily.getSystemFontWithAlias(DEFAULT_NAME)?.familyName)
            }
        }
    }

    private fun fontFile(path: Path): Boolean = Files.isRegularFile(path, NOFOLLOW_LINKS) &&
        (path.extension.equals("ttf", true) || path.extension.equals("otf", true)) &&
        runCatching { Files.size(path) in 1..MAX_FONT_BYTES }.getOrDefault(false)

    private fun select(file: String, source: Catalog): CompletableFuture<Face> {
        if (file.isEmpty() || file !in source.files) {
            return CompletableFuture.completedFuture(Face(file, source.defaultSelector, file.isNotEmpty() || source.defaultSelector == null))
        }
        val path = directory.resolve(file)
        return CompletableFuture.supplyAsync {
            try {
                check(fontFile(path))
                FontFamily.createFamily(path.toFile(), false)
                Face(file, path.toAbsolutePath().toString(), false)
            } catch (error: Exception) {
                Travelto.log.warn("Could not load font {}; using the default", file, error)
                Face(file, source.defaultSelector, true)
            }
        }
    }

    fun update() {
        if (!available || Config.CLIENT?.mLoaded != true) return
        val options = Travelto.state.typography
        scan?.takeIf { it.isDone }?.let {
            catalog = it.join()
            scan = null
            requested = null
        }
        val currentCatalog = catalog ?: return
        var faceChanged = false
        if (result == null && options.font != requested && scan == null) {
            requested = options.font
            result = select(options.font, currentCatalog)
        }
        result?.takeIf { it.isDone }?.let {
            val face = it.join()
            result = null
            if (face.file == options.font) {
                faceChanged = installed != face
                installed = face
            }
        }
        val upstreamSelector = Config.CLIENT.mFirstFontFamily.get()
        val face = installed
        val selector = face?.selector ?: upstreamSelector
        val fontChanged = face != null && (faceChanged || ModernUIClient.sFirstFontFamily != selector)
        if (fontChanged) {
            val client = ModernUIClient.getInstance()
            ModernUIClient.sFirstFontFamily = selector
            client.reloadTypeface()
            if (face.selector != null && client.firstFontFamily == null) {
                val fallback = currentCatalog.defaultSelector?.takeUnless { it == selector }
                installed = face.copy(selector = fallback, fallback = true)
                ModernUIClient.sFirstFontFamily = fallback ?: upstreamSelector
                client.reloadTypeface()
            }
            activeName = client.firstFontFamily?.familyName
        }
        val behavior = if (options.bitmap) TextLayoutEngine.DEFAULT_FONT_BEHAVIOR_KEEP_ALL else TextLayoutEngine.DEFAULT_FONT_BEHAVIOR_IGNORE_ALL
        val sizeChanged = TextLayoutProcessor.sBaseFontSize != options.size
        val layoutChanged = fontChanged || TextLayoutEngine.sTextDirection != options.direction.engineValue ||
            TextLayoutEngine.sDefaultFontBehavior != behavior
        TextLayoutProcessor.sBaseFontSize = options.size
        TextLayout.sBaselineOffset = options.baseline
        ModernTextRenderer.sAllowShadow = options.shadow
        TextLayoutEngine.sTextDirection = options.direction.engineValue
        TextLayoutEngine.sDefaultFontBehavior = behavior
        when {
            sizeChanged -> TextLayoutEngine.getInstance().reloadAll()
            layoutChanged -> TextLayoutEngine.getInstance().reload()
        }
        if (fontChanged) Travelto.log.info("Travelto ModernUI font: {}", activeName ?: "upstream fallback")
    }
}
