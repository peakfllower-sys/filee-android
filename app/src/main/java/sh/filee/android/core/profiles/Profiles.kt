package sh.filee.android.core.profiles

import kotlinx.serialization.Serializable
import sh.filee.android.core.formats.FileFormat
import sh.filee.android.core.formats.FormatRegistry
import java.util.UUID

/**
 * A donut toolbar layout for one family of source files (e.g. "Images" for jpg/png/...).
 * Ported directly from Filee.Core.Profiles.ToolbarProfile.
 */
@Serializable
data class ToolbarProfile(
    var id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    var name: String = "",
    var nameKey: String? = null,
    var extensions: MutableList<String> = mutableListOf(),
    var isFallback: Boolean = false,
    var presetIds: MutableList<String> = mutableListOf(),
    var backPresetIds: MutableList<String> = mutableListOf()
) {
    companion object {
        const val MAX_SLICES = 12
    }

    fun matches(extension: String): Boolean {
        val clean = extension.trimStart('.').lowercase()
        return extensions.any { it.equals(clean, ignoreCase = true) }
    }

    fun deepCopy(): ToolbarProfile = copy(
        extensions = extensions.toMutableList(),
        presetIds = presetIds.toMutableList(),
        backPresetIds = backPresetIds.toMutableList()
    )
}

/**
 * Chooses the profile for a set of selected/shared files.
 * Ported directly from Filee.Core.Profiles.ProfileSelector.
 */
object ProfileSelector {
    /**
     * Picks the profile for the given files, in this order:
     * 1. the first normal profile that has every file's extension;
     * 2. otherwise the first mixed-selection profile whose extensions include every file's;
     * 3. otherwise the catch-all.
     */
    fun select(profiles: List<ToolbarProfile>, filePathsOrNames: Iterable<String>): ToolbarProfile? {
        val extensions = filePathsOrNames
            .map { FormatRegistry.extensionOf(it).lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (extensions.isNotEmpty()) {
            val match = profiles.firstOrNull { p -> !p.isFallback && extensions.all { p.matches(it) } }
                ?: profiles.firstOrNull { p -> p.isFallback && p.extensions.isNotEmpty() && extensions.all { p.matches(it) } }
            if (match != null) return match
        }
        return catchAll(profiles)
    }

    fun catchAll(profiles: List<ToolbarProfile>): ToolbarProfile? =
        profiles.firstOrNull { it.isFallback && it.extensions.isEmpty() }
            ?: profiles.firstOrNull { it.isFallback }
            ?: profiles.firstOrNull()

    fun detectFormats(filePathsOrNames: Iterable<String>): List<FileFormat> =
        filePathsOrNames.mapNotNull { FormatRegistry.detect(it) }.distinctBy { it.id }
}
