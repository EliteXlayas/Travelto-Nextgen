package dev.travelto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Locale

internal enum class LanguageMode(val id: String) { SYSTEM("system"), CHINESE("zh_cn"), ENGLISH("en_us") }

internal object TraveltoLanguage {
    @Volatile var mode = LanguageMode.SYSTEM
        private set

    private val preference: Path get() = ConfigFiles.directory.resolve("preferences/language.json")
    private val english by lazy { readMessages(LanguageMode.ENGLISH) }
    private val chinese by lazy { readMessages(LanguageMode.CHINESE) }

    private fun readMessages(language: LanguageMode): Map<String, String> =
        TraveltoLanguage::class.java.getResourceAsStream("/assets/travelto/lang/${language.id}.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                JsonParser.parseReader(reader).asJsonObject.entrySet().associate { it.key to it.value.asString }
            }.orEmpty()

    fun load() {
        mode = try {
            val id = Files.newBufferedReader(preference).use { JsonParser.parseReader(it).asJsonObject.get("language")?.asString }
            LanguageMode.entries.firstOrNull { it.id == id } ?: LanguageMode.SYSTEM
        } catch (_: NoSuchFileException) {
            LanguageMode.SYSTEM
        } catch (error: Exception) {
            Travelto.log.warn("Could not read the Travelto language preference", error)
            LanguageMode.SYSTEM
        }
    }

    fun select(choice: LanguageMode): Boolean {
        if (choice == mode) return true
        return try {
            val data = JsonObject().apply { addProperty("language", choice.id) }
            ConfigFiles.write(preference) { it.write(data.toString()) }
            mode = choice
            true
        } catch (error: Exception) {
            Travelto.log.warn("Could not save the Travelto language preference", error)
            false
        }
    }

    fun label(key: String, vararg arguments: Any): Component {
        val useChinese = when (mode) {
            LanguageMode.SYSTEM -> Locale.getDefault(Locale.Category.DISPLAY).language == "zh"
            LanguageMode.CHINESE -> true
            LanguageMode.ENGLISH -> false
        }
        val pattern = (if (useChinese) chinese[key] else null) ?: english[key]
            ?: return Component.translatable(key, *arguments)
        if (arguments.isEmpty()) return Component.literal(pattern)
        val values = arguments.map { if (it is Component) it.string else it }.toTypedArray()
        return Component.literal(String.format(Locale.ROOT, pattern, *values))
    }
}
