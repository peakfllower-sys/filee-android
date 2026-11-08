package sh.filee.android.core.presets

import kotlinx.serialization.Serializable
import sh.filee.android.core.formats.FormatCategory
import sh.filee.android.core.formats.FormatRegistry
import sh.filee.android.core.profiles.ToolbarProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
enum class PresetKind {
    Convert,
    Group,
    Tool
}

@Serializable
enum class ResizeMode {
    None,
    Percent,
    MaxEdge,
    Width,
    Height
}

@Serializable
enum class ImageRotation {
    None,
    Left90,
    Right90,
    Rotate180
}

@Serializable
enum class ImageFlip {
    None,
    Horizontal,
    Vertical
}

@Serializable
enum class PdfRotation {
    None,
    Left90,
    Right90,
    Rotate180
}

@Serializable
enum class PdfCompression {
    None,
    Ebook,
    Screen
}

@Serializable
enum class TracingMode {
    Color,
    BlackAndWhite
}

@Serializable
data class ImageOptions(
    var quality: Int = 90,
    var resize: ResizeMode = ResizeMode.None,
    var resizePercent: Int = 50,
    var maxEdge: Int = 1920,
    var width: Int = 1280,
    var height: Int = 720,
    var dpi: Int = 0,
    var grayscale: Boolean = false,
    var trimTransparent: Boolean = false,
    var removeBackground: Boolean = false,
    var keepMetadata: Boolean = true,
    var joinIntoOne: Boolean = false,
    var joinHorizontal: Boolean = false,
    var rotation: ImageRotation = ImageRotation.None,
    var flip: ImageFlip = ImageFlip.None
)

@Serializable
data class PdfOptions(
    var renderDpi: Int = 150,
    var mergeIntoSingle: Boolean = false,
    var splitPages: Boolean = false,
    var combinePages: Boolean = false,
    var pageRange: String = "",
    var rotation: PdfRotation = PdfRotation.None,
    var compression: PdfCompression = PdfCompression.None,
    var removeMetadata: Boolean = false
)

@Serializable
data class ArchiveOptions(
    var combineIntoOne: Boolean = false
)

@Serializable
data class TracingOptions(
    var mode: TracingMode = TracingMode.Color,
    var colors: Int = 12,
    var detail: Int = 50
)

@Serializable
enum class OutputLocation {
    Default,
    SameFolder,
    Subfolder,
    CustomFolder
}

@Serializable
enum class ConflictPolicy {
    Rename,
    Overwrite,
    Skip
}

@Serializable
enum class RenameKind {
    Replace,
    Remove,
    Prefix,
    Suffix,
    SpacesToUnderscores,
    RemoveCopyNumber,
    RemoveBrackets,
    RemoveLeadingNumber,
    RemoveSymbols,
    Lowercase,
    Uppercase
}

@Serializable
data class RenameStep(
    var kind: RenameKind = RenameKind.Replace,
    var find: String = "",
    var replace: String = ""
) {
    val isBlank: Boolean
        get() = when (kind) {
            RenameKind.Replace, RenameKind.Remove -> find.isEmpty()
            RenameKind.Prefix, RenameKind.Suffix -> replace.isEmpty()
            else -> false
        }

    fun apply(name: String): String {
        if (isBlank) return name
        return when (kind) {
            RenameKind.Replace -> name.replace(find, replace, ignoreCase = true)
            RenameKind.Remove -> name.replace(find, "", ignoreCase = true)
            RenameKind.Prefix -> replace + name
            RenameKind.Suffix -> name + replace
            RenameKind.SpacesToUnderscores -> name.replace(Regex("\\s+"), "_")
            RenameKind.RemoveCopyNumber -> name.replace(Regex("\\s*\\(\\d+\\)$"), "")
            RenameKind.RemoveBrackets -> name.replace(Regex("\\s*(\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\})"), "").trim()
            RenameKind.RemoveLeadingNumber -> name.replace(Regex("^\\d+[\\s._-]*"), "")
            RenameKind.RemoveSymbols -> name.replace(Regex("[^\\p{L}\\p{N}\\s._-]"), "")
            RenameKind.Lowercase -> name.lowercase()
            RenameKind.Uppercase -> name.uppercase()
        }
    }
}

@Serializable
data class OutputRule(
    var location: OutputLocation = OutputLocation.Default,
    var subfolderName: String = "converted",
    var customFolder: String = "",
    var fileNamePattern: String = "{name}",
    var renames: MutableList<RenameStep> = mutableListOf(),
    var conflict: ConflictPolicy = ConflictPolicy.Rename,
    var keepDates: Boolean = false,
    var onlyIfSmaller: Boolean = false
)

/**
 * A named conversion recipe. The donut toolbar shows presets; tapping or dropping files on one runs it.
 * Ported directly from Filee.Core.Presets.Preset.
 */
@Serializable
data class Preset(
    var id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    var name: String = "",
    var nameKey: String? = null,
    var targetFormat: String = "png",
    var image: ImageOptions = ImageOptions(),
    var pdf: PdfOptions = PdfOptions(),
    var archive: ArchiveOptions = ArchiveOptions(),
    var trace: TracingOptions = TracingOptions(),
    var output: OutputRule = OutputRule(),
    var kind: PresetKind = PresetKind.Convert,
    var subPresetIds: MutableList<String> = mutableListOf()
) {
    companion object {
        const val MAX_SUB_PRESETS = 6
        const val GROUP_TARGET = "group"
    }

    val isGroup: Boolean get() = kind == PresetKind.Group
    val isTool: Boolean get() = kind == PresetKind.Tool

    val packsIntoOneArchive: Boolean
        get() = archive.combineIntoOne &&
                FormatRegistry.findById(targetFormat)?.let {
                    it.category == FormatCategory.Archive && it.id != FormatRegistry.FOLDER
                } == true

    val joinsImages: Boolean
        get() = image.joinIntoOne &&
                (targetFormat == BuiltInData.SAME_AS_SOURCE ||
                        FormatRegistry.findById(targetFormat)?.category == FormatCategory.Image)

    fun effectiveTargetFormat(sourceFormatId: String): String =
        if (targetFormat == BuiltInData.SAME_AS_SOURCE) sourceFormatId else targetFormat.lowercase()

    fun deepCopy(): Preset = copy(
        image = image.copy(),
        pdf = pdf.copy(),
        archive = archive.copy(),
        trace = trace.copy(),
        output = output.copy(renames = output.renames.map { it.copy() }.toMutableList()),
        subPresetIds = subPresetIds.toMutableList()
    )
}

/**
 * Resolves output file paths from [OutputRule]s.
 * Ported directly from Filee.Core.Presets.OutputPathResolver.
 */
object OutputPathResolver {
    data class Tokens(
        val sourcePath: String,
        val presetName: String,
        val index: Int,
        val now: Date = Date()
    )

    private val tokenRegex = Regex("\\{(name|ext|preset|date|time|index)\\}", RegexOption.IGNORE_CASE)

    fun resolve(
        rule: OutputRule,
        tokens: Tokens,
        targetExtension: String,
        outputDirectory: File,
        exists: (File) -> Boolean = { it.exists() },
        suffix: String? = null
    ): File? {
        val dir = when (rule.location) {
            OutputLocation.Subfolder -> {
                val sub = sanitize(expandPattern(rule.subfolderName, tokens), "converted")
                File(outputDirectory, sub)
            }
            OutputLocation.CustomFolder -> {
                if (rule.customFolder.isNotBlank()) File(rule.customFolder) else outputDirectory
            }
            else -> outputDirectory
        }
        dir.mkdirs()

        val sourceName = FormatRegistry.nameWithoutExtension(tokens.sourcePath)
        val expanded = expandPattern(rule.fileNamePattern, tokens)
        val renamed = rule.renames.fold(expanded) { acc, step -> step.apply(acc) }
        val baseName = sanitize(renamed, sourceName) + (suffix ?: "")
        val ext = targetExtension.trimStart('.')

        fun fileFor(name: String): File =
            File(dir, if (ext.isEmpty()) name else "$name.$ext")

        var candidate = fileFor(baseName)
        val isSource = candidate.absolutePath.equals(File(tokens.sourcePath).absolutePath, ignoreCase = true)

        if (!isSource && !exists(candidate)) {
            return candidate
        }
        if (!isSource && rule.conflict == ConflictPolicy.Overwrite) {
            return candidate
        }
        if (!isSource && rule.conflict == ConflictPolicy.Skip) {
            return null
        }

        var n = 2
        while (true) {
            candidate = fileFor("$baseName ($n)")
            val sameAsSource = candidate.absolutePath.equals(File(tokens.sourcePath).absolutePath, ignoreCase = true)
            if (!exists(candidate) && !sameAsSource) {
                return candidate
            }
            n++
        }
    }

    fun expandPattern(pattern: String, tokens: Tokens): String {
        val pat = pattern.ifBlank { "{name}" }
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val timeFormat = SimpleDateFormat("HHmmss", Locale.US)
        return tokenRegex.replace(pat) { match ->
            when (match.groupValues[1].lowercase()) {
                "name" -> FormatRegistry.nameWithoutExtension(tokens.sourcePath)
                "ext" -> FormatRegistry.extensionOf(tokens.sourcePath)
                "preset" -> tokens.presetName
                "date" -> dateFormat.format(tokens.now)
                "time" -> timeFormat.format(tokens.now)
                else -> tokens.index.toString()
            }
        }
    }

    fun sanitize(name: String, fallback: String): String {
        val invalid = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        val sb = StringBuilder(name.length)
        for (c in name) {
            sb.append(if (c in invalid || c.isISOControl()) '_' else c)
        }
        val result = sb.toString().trim().trimEnd('.')
        return result.ifEmpty { fallback }
    }
}

/**
 * Factory defaults: presets and toolbar profiles created on first run or "Reset to defaults".
 * Ported from Filee.Core.Presets.BuiltInData.
 */
object BuiltInData {
    const val SAME_AS_SOURCE = "*"

    fun createPresets(): MutableList<Preset> = mutableListOf(
        // Images
        Preset(id = "to-png", name = "PNG", targetFormat = "png"),
        Preset(id = "to-jpg", name = "JPG", targetFormat = "jpg", image = ImageOptions(quality = 90)),
        Preset(
            id = "jpg-70", name = "JPG 70%", targetFormat = "jpg",
            image = ImageOptions(quality = 70), output = OutputRule(fileNamePattern = "{name}_70")
        ),
        Preset(id = "to-webp", name = "WEBP", targetFormat = "webp", image = ImageOptions(quality = 85)),
        Preset(id = "to-bmp", name = "BMP", targetFormat = "bmp"),
        Preset(id = "to-ico", name = "ICO", targetFormat = "ico"),
        Preset(id = "to-gif", name = "GIF", targetFormat = "gif"),
        Preset(
            id = "half-size", nameKey = "preset.half_size", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(resize = ResizeMode.Percent, resizePercent = 50),
            output = OutputRule(fileNamePattern = "{name}_50")
        ),
        Preset(
            id = "grayscale", nameKey = "preset.grayscale", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(grayscale = true),
            output = OutputRule(fileNamePattern = "{name}_gray")
        ),
        Preset(
            id = "trim-transparent", nameKey = "preset.trim_transparent", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(trimTransparent = true),
            output = OutputRule(fileNamePattern = "{name}_trimmed")
        ),
        Preset(
            id = "compress-image", nameKey = "preset.compress_image", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(quality = 75),
            output = OutputRule(fileNamePattern = "{name}_compressed", onlyIfSmaller = true)
        ),
        Preset(
            id = "merge-images", nameKey = "preset.merge_images", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(joinIntoOne = true, quality = 90),
            output = OutputRule(fileNamePattern = "{name}_merged")
        ),
        Preset(
            id = "tool-crop", nameKey = "preset.crop", targetFormat = SAME_AS_SOURCE,
            kind = PresetKind.Tool, output = OutputRule(fileNamePattern = "{name}_cropped")
        ),
        // Image rotation & flip tools
        Preset(
            id = "rotate-left", nameKey = "preset.rotate_left", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(quality = 95, rotation = ImageRotation.Left90),
            output = OutputRule(fileNamePattern = "{name}_left")
        ),
        Preset(
            id = "rotate-right", nameKey = "preset.rotate_right", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(quality = 95, rotation = ImageRotation.Right90),
            output = OutputRule(fileNamePattern = "{name}_right")
        ),
        Preset(
            id = "rotate-180", nameKey = "preset.rotate_180", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(quality = 95, rotation = ImageRotation.Rotate180),
            output = OutputRule(fileNamePattern = "{name}_180")
        ),
        Preset(
            id = "flip-horizontal", nameKey = "preset.flip_horizontal", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(quality = 95, flip = ImageFlip.Horizontal),
            output = OutputRule(fileNamePattern = "{name}_mirrored")
        ),
        Preset(
            id = "flip-vertical", nameKey = "preset.flip_vertical", targetFormat = SAME_AS_SOURCE,
            image = ImageOptions(quality = 95, flip = ImageFlip.Vertical),
            output = OutputRule(fileNamePattern = "{name}_flipped")
        ),
        Preset(
            id = "rotate", nameKey = "preset.group_rotate", targetFormat = Preset.GROUP_TARGET,
            kind = PresetKind.Group,
            subPresetIds = mutableListOf("rotate-left", "rotate-right", "rotate-180")
        ),
        Preset(
            id = "flip", nameKey = "preset.group_flip", targetFormat = Preset.GROUP_TARGET,
            kind = PresetKind.Group,
            subPresetIds = mutableListOf("flip-horizontal", "flip-vertical")
        ),

        // Vector & Tracing
        Preset(id = "to-svg", name = "SVG", targetFormat = "svg"),
        Preset(id = "trace-svg", nameKey = "preset.trace_svg", targetFormat = "svg"),
        Preset(
            id = "trace-line-art", nameKey = "preset.trace_line_art", targetFormat = "svg",
            trace = TracingOptions(mode = TracingMode.BlackAndWhite)
        ),

        // PDF
        Preset(id = "to-pdf", name = "PDF", targetFormat = "pdf"),
        Preset(
            id = "merge-pdf", nameKey = "preset.merge_pdf", targetFormat = "pdf",
            pdf = PdfOptions(mergeIntoSingle = true), output = OutputRule(fileNamePattern = "{name}_merged")
        ),
        Preset(
            id = "pdf-long-image", nameKey = "preset.long_image", targetFormat = "png",
            pdf = PdfOptions(combinePages = true), output = OutputRule(fileNamePattern = "{name}_long")
        ),
        Preset(
            id = "pdf-split", nameKey = "preset.split_pdf", targetFormat = "pdf",
            pdf = PdfOptions(splitPages = true),
            output = OutputRule(location = OutputLocation.Subfolder, subfolderName = "{name}_pages")
        ),
        Preset(
            id = "compress-pdf", nameKey = "preset.compress_pdf", targetFormat = "pdf",
            pdf = PdfOptions(compression = PdfCompression.Ebook),
            output = OutputRule(fileNamePattern = "{name}_compressed")
        ),
        Preset(
            id = "pdf-rotate-left", nameKey = "preset.rotate_left", targetFormat = "pdf",
            pdf = PdfOptions(rotation = PdfRotation.Left90), output = OutputRule(fileNamePattern = "{name}_left")
        ),
        Preset(
            id = "pdf-rotate-right", nameKey = "preset.rotate_right", targetFormat = "pdf",
            pdf = PdfOptions(rotation = PdfRotation.Right90), output = OutputRule(fileNamePattern = "{name}_right")
        ),
        Preset(
            id = "pdf-edit", nameKey = "preset.group_pdf_edit", targetFormat = Preset.GROUP_TARGET,
            kind = PresetKind.Group,
            subPresetIds = mutableListOf("pdf-rotate-left", "pdf-rotate-right", "pdf-split", "compress-pdf")
        ),
        Preset(id = "pdf-slides", nameKey = "preset.pdf_slides", targetFormat = "pptx", image = ImageOptions(quality = 90)),

        // Documents & Text & E-books
        Preset(id = "to-docx", name = "DOCX", targetFormat = "docx"),
        Preset(id = "to-txt", name = "TXT", targetFormat = "txt"),
        Preset(id = "to-md", name = "Markdown", targetFormat = "md"),
        Preset(id = "to-html", name = "HTML", targetFormat = "html"),
        Preset(id = "to-epub", name = "EPUB", targetFormat = "epub"),
        Preset(id = "to-pptx", name = "PPTX", targetFormat = "pptx"),

        // Spreadsheets
        Preset(id = "to-xlsx", name = "XLSX", targetFormat = "xlsx"),
        Preset(id = "to-csv", name = "CSV", targetFormat = "csv"),
        Preset(id = "to-tsv", name = "TSV", targetFormat = "tsv"),

        // Structured Data
        Preset(id = "to-json", name = "JSON", targetFormat = "json"),
        Preset(id = "to-yaml", name = "YAML", targetFormat = "yaml"),
        Preset(id = "to-toml", name = "TOML", targetFormat = "toml"),
        Preset(id = "to-xml", name = "XML", targetFormat = "xml"),
        Preset(id = "to-vcf", name = "vCard", targetFormat = "vcf"),
        Preset(id = "to-ics", name = "iCalendar", targetFormat = "ics"),

        // Subtitles
        Preset(id = "to-srt", name = "SRT", targetFormat = "srt"),
        Preset(id = "to-vtt", name = "WebVTT", targetFormat = "vtt"),
        Preset(id = "to-ass", name = "ASS", targetFormat = "ass"),
        Preset(id = "to-smi", name = "SAMI", targetFormat = "smi"),
        Preset(id = "to-lrc", name = "LRC", targetFormat = "lrc"),
        Preset(id = "to-ttml", name = "TTML", targetFormat = "ttml"),

        // Archives
        Preset(id = "extract", nameKey = "preset.extract", targetFormat = FormatRegistry.FOLDER),
        Preset(
            id = "zip-all", nameKey = "preset.zip_all", targetFormat = "zip",
            archive = ArchiveOptions(combineIntoOne = true), output = OutputRule(fileNamePattern = "{name}_files")
        ),
        Preset(id = "to-zip", name = "ZIP", targetFormat = "zip"),
        Preset(id = "to-tar", name = "TAR", targetFormat = "tar"),
        Preset(id = "to-tgz", name = "TAR.GZ", targetFormat = "tgz")
    )

    fun createProfiles(): MutableList<ToolbarProfile> = mutableListOf(
        ToolbarProfile(
            id = "images",
            nameKey = "profile.images",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Image).toMutableList(),
            presetIds = mutableListOf("to-png", "to-jpg", "to-webp", "to-gif", "to-pdf", "trace-svg"),
            backPresetIds = mutableListOf("tool-crop", "rotate", "flip", "grayscale", "merge-images", "compress-image", "half-size", "zip-all")
        ),
        ToolbarProfile(
            id = "pdf",
            nameKey = "profile.pdf",
            extensions = mutableListOf("pdf"),
            presetIds = mutableListOf("to-png", "to-jpg", "pdf-long-image", "to-docx", "pdf-slides", "to-txt", "to-md", "to-html"),
            backPresetIds = mutableListOf("pdf-edit", "pdf-split", "merge-pdf", "compress-pdf", "to-epub", "zip-all")
        ),
        ToolbarProfile(
            id = "office",
            nameKey = "profile.office",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Document).toMutableList(),
            presetIds = mutableListOf("to-pdf", "to-docx", "to-txt", "to-html", "to-md", "to-epub", "merge-pdf"),
            backPresetIds = mutableListOf("to-png", "pdf-long-image", "zip-all")
        ),
        ToolbarProfile(
            id = "spreadsheets",
            nameKey = "profile.spreadsheets",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Spreadsheet).toMutableList(),
            presetIds = mutableListOf("to-pdf", "to-xlsx", "to-csv", "to-json", "to-html", "merge-pdf"),
            backPresetIds = mutableListOf("to-tsv", "to-md", "to-yaml", "to-xml", "to-vcf", "to-ics", "zip-all")
        ),
        ToolbarProfile(
            id = "presentations",
            nameKey = "profile.presentations",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Presentation).toMutableList(),
            presetIds = mutableListOf("to-pdf", "to-png", "to-jpg", "to-txt", "to-html", "merge-pdf"),
            backPresetIds = mutableListOf("to-md", "zip-all")
        ),
        ToolbarProfile(
            id = "text",
            nameKey = "profile.text",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Text).toMutableList(),
            presetIds = mutableListOf("to-pdf", "to-docx", "to-html", "to-txt", "to-md", "to-epub"),
            backPresetIds = mutableListOf("merge-pdf", "zip-all")
        ),
        ToolbarProfile(
            id = "ebooks",
            nameKey = "profile.ebooks",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Ebook).toMutableList(),
            presetIds = mutableListOf("to-pdf", "to-epub", "to-docx", "to-txt", "to-html", "to-md"),
            backPresetIds = mutableListOf("merge-pdf", "zip-all")
        ),
        ToolbarProfile(
            id = "subtitles",
            nameKey = "profile.subtitles",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Subtitle).toMutableList(),
            presetIds = mutableListOf("to-srt", "to-vtt", "to-ass", "to-smi", "to-lrc", "to-ttml"),
            backPresetIds = mutableListOf("zip-all")
        ),
        ToolbarProfile(
            id = "vector",
            nameKey = "profile.vector",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Vector).toMutableList(),
            presetIds = mutableListOf("to-png", "to-pdf", "to-jpg", "to-webp", "to-svg"),
            backPresetIds = mutableListOf("to-ico", "zip-all")
        ),
        ToolbarProfile(
            id = "archives",
            nameKey = "profile.archives",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Archive).toMutableList(),
            presetIds = mutableListOf("extract", "zip-all", "to-zip", "to-tgz", "to-tar"),
            backPresetIds = mutableListOf()
        ),
        ToolbarProfile(
            id = "data",
            nameKey = "profile.data",
            extensions = FormatRegistry.extensionsOf(FormatCategory.Data).toMutableList(),
            presetIds = mutableListOf("to-json", "to-yaml", "to-toml", "to-xml", "to-csv", "to-xlsx"),
            backPresetIds = mutableListOf("to-html", "to-md", "to-vcf", "to-ics", "zip-all")
        ),
        ToolbarProfile(
            id = "mixed",
            nameKey = "profile.mixed",
            isFallback = true,
            presetIds = mutableListOf("to-pdf", "merge-pdf", "zip-all", "to-png", "to-txt"),
            backPresetIds = mutableListOf("to-jpg", "to-webp", "to-docx", "to-html")
        )
    )
}
