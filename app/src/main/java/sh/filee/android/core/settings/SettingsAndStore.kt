package sh.filee.android.core.settings

import android.content.Context
import android.os.Environment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sh.filee.android.core.presets.BuiltInData
import sh.filee.android.core.presets.Preset
import sh.filee.android.core.profiles.ToolbarProfile
import java.io.File
import java.util.UUID

@Serializable
enum class ThemeMode {
    System,
    Light,
    Dark
}

@Serializable
enum class ThemeStyle {
    Soft,
    MaterialYou
}

@Serializable
data class ThemeSettings(
    var mode: ThemeMode = ThemeMode.System,
    var style: ThemeStyle = ThemeStyle.Soft,
    var accent: String = "#7F77DD",
    var presetKey: String = "lavender",
    var cornerRadius: Int = 16,
    var sliceOpacity: Float = 0.96f,
    var reduceMotion: Boolean = false
)

@Serializable
data class AppSettings(
    var schemaVersion: Int = 63,
    var language: String = "zh-CN",
    var theme: ThemeSettings = ThemeSettings(),
    var defaultOutputSubfolder: String = "Filee",
    var vibrateOnSliceHover: Boolean = true,
    var autoOpenShareSheetAfterConvert: Boolean = false
)

@Serializable
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val sourceFileNames: List<String>,
    val outputPaths: List<String>,
    val sourceFormat: String,
    val targetFormat: String,
    val presetName: String,
    val successCount: Int,
    val failCount: Int,
    val errorSummary: String? = null
)

/**
 * Atomic JSON store for `settings.json`, `presets.json`, `profiles.json`, and `history.json`.
 * Ported directly from Filee.Core.Settings.UserDataStore.
 */
class UserDataStore(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dataDir: File
        get() = File(context.filesDir, "filee_data").apply { mkdirs() }

    private val settingsFile: File get() = File(dataDir, "settings.json")
    private val presetsFile: File get() = File(dataDir, "presets.json")
    private val profilesFile: File get() = File(dataDir, "profiles.json")
    private val historyFile: File get() = File(dataDir, "history.json")

    var settings: AppSettings = AppSettings()
        private set

    var presets: MutableList<Preset> = mutableListOf()
        private set

    var profiles: MutableList<ToolbarProfile> = mutableListOf()
        private set

    var history: MutableList<HistoryEntry> = mutableListOf()
        private set

    init {
        loadAll()
    }

    fun loadAll() {
        settings = readAtomic(settingsFile) ?: AppSettings()
        presets = readAtomic<MutableList<Preset>>(presetsFile) ?: BuiltInData.createPresets()
        profiles = readAtomic<MutableList<ToolbarProfile>>(profilesFile) ?: BuiltInData.createProfiles()
        history = readAtomic<MutableList<HistoryEntry>>(historyFile) ?: mutableListOf()
        ensureConsistency()
    }

    private fun ensureConsistency() {
        if (presets.isEmpty()) {
            presets = BuiltInData.createPresets()
        }
        // Make sure all built-in presets exist in presets list
        val existingIds = presets.map { it.id }.toSet()
        for (builtIn in BuiltInData.createPresets()) {
            if (builtIn.id !in existingIds) {
                presets.add(builtIn)
            }
        }
        if (profiles.isEmpty()) {
            profiles = BuiltInData.createProfiles()
        }
    }

    fun saveSettings(updated: AppSettings = settings) {
        settings = updated
        writeAtomic(settingsFile, settings)
    }

    fun savePresets(updated: List<Preset> = presets) {
        presets = updated.map { it.deepCopy() }.toMutableList()
        writeAtomic(presetsFile, presets)
    }

    fun saveProfiles(updated: List<ToolbarProfile> = profiles) {
        profiles = updated.map { it.deepCopy() }.toMutableList()
        writeAtomic(profilesFile, profiles)
    }

    fun addHistory(entry: HistoryEntry) {
        history.add(0, entry)
        if (history.size > 100) {
            history = history.take(100).toMutableList()
        }
        writeAtomic(historyFile, history)
    }

    fun removeHistory(entryId: String) {
        history.removeAll { it.id == entryId }
        writeAtomic(historyFile, history)
    }

    fun clearHistory() {
        history.clear()
        writeAtomic(historyFile, history)
    }

    fun resetToDefaults() {
        presets = BuiltInData.createPresets()
        profiles = BuiltInData.createProfiles()
        writeAtomic(presetsFile, presets)
        writeAtomic(profilesFile, profiles)
    }

    fun getDefaultOutputDirectory(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = File(downloads, settings.defaultOutputSubfolder.ifBlank { "Filee" })
        if (!target.exists()) {
            target.mkdirs()
        }
        return if (target.canWrite()) {
            target
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "Converted").apply {
                mkdirs()
            }
        }
    }

    private inline fun <reified T> readAtomic(file: File): T? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<T>(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            val broken = File(file.parentFile, "${file.name}.broken")
            runCatching { file.copyTo(broken, overwrite = true) }
            null
        }
    }

    private inline fun <reified T> writeAtomic(file: File, value: T) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(value), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
