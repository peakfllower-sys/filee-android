package sh.filee.android.core.formats

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Broad family a format belongs to. Toolbar profiles and the UI group formats by this.
 * Ported directly from Filee.Core.Formats.FormatCategory.
 */
@Serializable
enum class FormatCategory {
    Image,
    Pdf,
    Document,
    Spreadsheet,
    Presentation,
    Hwp,
    Text,
    Video,
    Audio,
    Ebook,
    Archive,
    Vector,
    Cad,
    Font,
    Subtitle,
    Data,
    Model3D,
    Geo
}

/**
 * A file format known to Filee.
 * Ported directly from Filee.Core.Formats.FileFormat.
 */
@Serializable
data class FileFormat(
    val id: String,
    val displayName: String,
    val category: FormatCategory,
    val extensions: List<String>,
    val writable: Boolean = true
) {
    val primaryExtension: String
        get() = extensions.firstOrNull() ?: ""
}

/**
 * Lookup table of every format Filee knows about.
 * Ported directly from Filee.Core.Formats.FormatRegistry.
 */
object FormatRegistry {
    const val FOLDER = "folder"

    val known: List<FileFormat> = listOf(
        // Raster images
        FileFormat("png", "PNG", FormatCategory.Image, listOf("png", "png_large")),
        FileFormat("jpg", "JPG", FormatCategory.Image, listOf("jpg", "jpeg", "jpe", "jfif", "jpg_large")),
        FileFormat("webp", "WEBP", FormatCategory.Image, listOf("webp")),
        FileFormat("bmp", "BMP", FormatCategory.Image, listOf("bmp", "dib")),
        FileFormat("gif", "GIF", FormatCategory.Image, listOf("gif")),
        FileFormat("ico", "ICO", FormatCategory.Image, listOf("ico")),
        FileFormat("tiff", "TIFF", FormatCategory.Image, listOf("tiff", "tif")),
        FileFormat("avif", "AVIF", FormatCategory.Image, listOf("avif")),
        FileFormat("heic", "HEIC", FormatCategory.Image, listOf("heic", "heif", "hif"), writable = false),
        FileFormat("ppm", "PPM", FormatCategory.Image, listOf("ppm", "pgm", "pbm", "pnm")),
        FileFormat("tga", "TGA", FormatCategory.Image, listOf("tga")),
        FileFormat("qoi", "QOI", FormatCategory.Image, listOf("qoi")),
        FileFormat("wbmp", "WBMP", FormatCategory.Image, listOf("wbmp")),
        FileFormat("raw", "RAW", FormatCategory.Image, listOf("dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "pef", "raw"), writable = false),

        // Vector graphics
        FileFormat("svg", "SVG", FormatCategory.Vector, listOf("svg")),
        FileFormat("svgz", "SVGZ", FormatCategory.Vector, listOf("svgz")),

        // PDF
        FileFormat("pdf", "PDF", FormatCategory.Pdf, listOf("pdf")),

        // Word processing & Office documents
        FileFormat("docx", "DOCX", FormatCategory.Document, listOf("docx")),
        FileFormat("docm", "DOCM", FormatCategory.Document, listOf("docm")),
        FileFormat("dotx", "DOTX", FormatCategory.Document, listOf("dotx")),
        FileFormat("odt", "ODT", FormatCategory.Document, listOf("odt")),
        FileFormat("rtf", "RTF", FormatCategory.Document, listOf("rtf")),
        FileFormat("eml", "EML", FormatCategory.Document, listOf("eml")),

        // Korean word processor (Hancom)
        FileFormat("hwpx", "HWPX", FormatCategory.Hwp, listOf("hwpx")),
        FileFormat("hwp", "HWP", FormatCategory.Hwp, listOf("hwp"), writable = false),

        // Spreadsheets
        FileFormat("xlsx", "XLSX", FormatCategory.Spreadsheet, listOf("xlsx")),
        FileFormat("xlsm", "XLSM", FormatCategory.Spreadsheet, listOf("xlsm")),
        FileFormat("ods", "ODS", FormatCategory.Spreadsheet, listOf("ods")),
        FileFormat("csv", "CSV", FormatCategory.Spreadsheet, listOf("csv")),
        FileFormat("tsv", "TSV", FormatCategory.Spreadsheet, listOf("tsv", "tab")),

        // Presentations
        FileFormat("pptx", "PPTX", FormatCategory.Presentation, listOf("pptx")),
        FileFormat("pptm", "PPTM", FormatCategory.Presentation, listOf("pptm")),
        FileFormat("ppsx", "PPSX", FormatCategory.Presentation, listOf("ppsx")),
        FileFormat("odp", "ODP", FormatCategory.Presentation, listOf("odp")),

        // Plain text / markup
        FileFormat(
            "txt", "TXT", FormatCategory.Text,
            listOf(
                "txt", "log", "py", "pac", "bat", "cmd", "command", "lua", "css", "ps1", "sql",
                "ini", "c", "h", "cpp", "cc", "cxx", "hpp", "cs", "kt", "java", "js", "ts", "sh"
            )
        ),
        FileFormat("md", "Markdown", FormatCategory.Text, listOf("md", "markdown")),
        FileFormat("html", "HTML", FormatCategory.Text, listOf("html", "htm", "xhtml")),
        FileFormat("rst", "reStructuredText", FormatCategory.Text, listOf("rst")),
        FileFormat("tex", "LaTeX", FormatCategory.Text, listOf("tex", "latex")),

        // Structured data
        FileFormat("json", "JSON", FormatCategory.Data, listOf("json")),
        FileFormat("jsonl", "JSON Lines", FormatCategory.Data, listOf("jsonl", "ndjson")),
        FileFormat("yaml", "YAML", FormatCategory.Data, listOf("yaml", "yml")),
        FileFormat("toml", "TOML", FormatCategory.Data, listOf("toml")),
        FileFormat("xml", "XML", FormatCategory.Data, listOf("xml")),
        FileFormat("vcf", "vCard", FormatCategory.Data, listOf("vcf", "vcard")),
        FileFormat("ics", "iCalendar", FormatCategory.Data, listOf("ics", "ical")),
        FileFormat("har", "HAR", FormatCategory.Data, listOf("har"), writable = false),

        // E-books
        FileFormat("epub", "EPUB", FormatCategory.Ebook, listOf("epub")),
        FileFormat("kepub", "KEPUB", FormatCategory.Ebook, listOf("kepub.epub", "kepub")),
        FileFormat("fb2", "FB2", FormatCategory.Ebook, listOf("fb2")),
        FileFormat("cbz", "CBZ", FormatCategory.Ebook, listOf("cbz")),
        FileFormat("htmlz", "HTMLZ", FormatCategory.Ebook, listOf("htmlz")),
        FileFormat("txtz", "TXTZ", FormatCategory.Ebook, listOf("txtz")),

        // Archives
        FileFormat("zip", "ZIP", FormatCategory.Archive, listOf("zip", "wbpz")),
        FileFormat("7z", "7Z", FormatCategory.Archive, listOf("7z")),
        FileFormat("tar", "TAR", FormatCategory.Archive, listOf("tar")),
        FileFormat("tgz", "TAR.GZ", FormatCategory.Archive, listOf("tar.gz", "tgz", "taz")),
        FileFormat("tbz2", "TAR.BZ2", FormatCategory.Archive, listOf("tar.bz2", "tbz2")),
        FileFormat("txz", "TAR.XZ", FormatCategory.Archive, listOf("tar.xz", "txz")),
        FileFormat("gz", "GZ", FormatCategory.Archive, listOf("gz")),
        FileFormat("bz2", "BZ2", FormatCategory.Archive, listOf("bz2", "bz")),
        FileFormat("xz", "XZ", FormatCategory.Archive, listOf("xz")),
        FileFormat("jar", "JAR", FormatCategory.Archive, listOf("jar"), writable = false),
        FileFormat("mcpack", "MCPACK", FormatCategory.Archive, listOf("mcpack")),
        FileFormat("mcaddon", "MCADDON", FormatCategory.Archive, listOf("mcaddon")),
        FileFormat("mcworld", "MCWORLD", FormatCategory.Archive, listOf("mcworld")),
        FileFormat("mrpack", "MRPACK", FormatCategory.Archive, listOf("mrpack")),
        FileFormat(FOLDER, "Folder", FormatCategory.Archive, listOf("")),

        // Subtitles
        FileFormat("srt", "SRT", FormatCategory.Subtitle, listOf("srt")),
        FileFormat("ass", "ASS", FormatCategory.Subtitle, listOf("ass")),
        FileFormat("ssa", "SSA", FormatCategory.Subtitle, listOf("ssa"), writable = false),
        FileFormat("vtt", "WebVTT", FormatCategory.Subtitle, listOf("vtt")),
        FileFormat("smi", "SAMI", FormatCategory.Subtitle, listOf("smi", "sami")),
        FileFormat("lrc", "LRC", FormatCategory.Subtitle, listOf("lrc")),
        FileFormat("ttml", "TTML", FormatCategory.Subtitle, listOf("ttml", "dfxp")),
        FileFormat("sub", "MicroDVD", FormatCategory.Subtitle, listOf("sub")),

        // Geospatial vector data
        FileFormat("geojson", "GeoJSON", FormatCategory.Geo, listOf("geojson")),
        FileFormat("kml", "KML", FormatCategory.Geo, listOf("kml")),
        FileFormat("gpx", "GPX", FormatCategory.Geo, listOf("gpx"))
    )

    private val byId: Map<String, FileFormat> = known.associateBy { it.id.lowercase() }

    private val byExtension: Map<String, FileFormat> = buildMap {
        for (format in known) {
            for (ext in format.extensions) {
                if (ext.isNotEmpty()) {
                    put(ext.lowercase(), format)
                }
            }
        }
    }

    private val compoundExtensions: List<String> =
        byExtension.keys.filter { it.contains('.') }.sortedByDescending { it.length }

    fun findById(id: String): FileFormat? = byId[id.lowercase()]

    fun get(id: String): FileFormat =
        findById(id) ?: throw NoSuchElementException("Unknown format id '$id'.")

    fun findByExtension(extension: String): FileFormat? {
        val ext = extension.trimStart('.').lowercase()
        return if (ext.isNotEmpty()) byExtension[ext] else null
    }

    fun detect(pathOrName: String): FileFormat? = findByExtension(extensionOf(pathOrName))

    /**
     * The extension of [pathOrName] without the dot, including known two-part extensions
     * ("archive.tar.gz" -> "tar.gz", "photo.jpg" -> "jpg").
     */
    fun extensionOf(pathOrName: String): String {
        val name = File(pathOrName).name
        for (compound in compoundExtensions) {
            if (name.length > compound.length + 1 &&
                name.endsWith(".$compound", ignoreCase = true)
            ) {
                return name.substring(name.length - compound.length).lowercase()
            }
        }
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) {
            name.substring(dot + 1).lowercase()
        } else {
            ""
        }
    }

    /**
     * The file name without its (possibly two-part) extension: "backup.tar.gz" -> "backup".
     */
    fun nameWithoutExtension(pathOrName: String): String {
        val name = File(pathOrName).name
        val ext = extensionOf(name)
        return if (ext.isEmpty()) name else name.substring(0, name.length - ext.length - 1)
    }

    fun extensionsOf(category: FormatCategory): List<String> =
        known.filter { it.category == category }
            .flatMap { it.extensions }
            .filter { it.isNotEmpty() }
}
