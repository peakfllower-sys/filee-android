package sh.filee.android.engines

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import sh.filee.android.core.conversion.ConversionEdge
import sh.filee.android.core.conversion.ConversionStep
import sh.filee.android.core.conversion.EngineStatus
import sh.filee.android.core.conversion.IConverter
import sh.filee.android.core.conversion.RoutePlanner
import sh.filee.android.core.formats.FormatRegistry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Built-in Subtitle converter supporting SRT, WebVTT, ASS/SSA, SAMI, LRC, TTML, and MicroDVD.
 * Ported directly from Filee.Engines.Subtitles.
 */
class SubtitleConverter : IConverter {
    override val id: String = "subtitle"
    override val displayName: String = "Subtitles (Built-in)"

    companion object {
        val READ_FORMATS = listOf("srt", "ass", "ssa", "vtt", "smi", "lrc", "ttml", "sub")
        val WRITE_FORMATS = listOf("srt", "ass", "vtt", "smi", "lrc", "ttml", "sub")
    }

    override val edges: List<ConversionEdge> = buildList {
        for (src in READ_FORMATS) {
            for (dst in WRITE_FORMATS) {
                add(ConversionEdge(src, dst, cost = 5))
            }
        }
    }

    override fun getStatus(): EngineStatus = EngineStatus(
        isAvailable = true,
        summary = "SRT, ASS/SSA, WebVTT, SAMI, LRC, TTML, MicroDVD ↔ each other (UTF-8)",
        version = "Built-in"
    )

    data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

    override suspend fun convert(
        step: ConversionStep,
        onProgress: (Double) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        onProgress(0.2)
        val rawText = decodeSubtitleBytes(step.inputFile.readBytes())
        val cues = parseCues(step.from, rawText)
        onProgress(0.6)

        val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()
        val encoded = writeCues(cues, step.to, step.inputFile.nameWithoutExtension)
        outFile.writeText(encoded, Charsets.UTF_8)
        onProgress(1.0)
        listOf(outFile)
    }

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, Charset.forName("GB18030")) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    private fun parseCues(format: String, text: String): List<SubtitleCue> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return when (format.lowercase()) {
            "srt", "vtt" -> parseSrtOrVtt(normalized)
            "ass", "ssa" -> parseAss(normalized)
            "lrc" -> parseLrc(normalized)
            "smi" -> parseSami(normalized)
            "ttml" -> parseTtml(normalized)
            "sub" -> parseMicroDvd(normalized)
            else -> parseSrtOrVtt(normalized)
        }
    }

    private val timingRegex = Regex(
        """(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})"""
    )

    private fun parseSrtOrVtt(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var currentStart: Long? = null
        var currentEnd: Long? = null
        val body = mutableListOf<String>()

        fun flush() {
            val s = currentStart ?: return
            val e = currentEnd ?: return
            while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
            if (body.isNotEmpty() && body.last().trim().all { it.isDigit() }) body.removeAt(body.lastIndex)
            val cueText = body.joinToString("\n").replace(Regex("\\{[^}]*\\}"), "").trim()
            if (cueText.isNotEmpty()) {
                cues.add(SubtitleCue(s, e, cueText))
            }
        }

        for (line in text.split('\n')) {
            val match = timingRegex.find(line)
            if (match != null) {
                flush()
                currentStart = toMs(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
                currentEnd = toMs(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
                body.clear()
            } else if (currentStart != null) {
                body.add(line)
            }
        }
        flush()
        return cues
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val assTime = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,3})""")
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val parts = line.substringAfter(':').split(',', limit = 10)
                if (parts.size >= 10) {
                    val sMatch = assTime.find(parts[1].trim()) ?: continue
                    val eMatch = assTime.find(parts[2].trim()) ?: continue
                    val startMs = toMs(sMatch.groupValues[1], sMatch.groupValues[2], sMatch.groupValues[3], sMatch.groupValues[4])
                    val endMs = toMs(eMatch.groupValues[1], eMatch.groupValues[2], eMatch.groupValues[3], eMatch.groupValues[4])
                    val cleanText = parts[9]
                        .replace("\\N", "\n")
                        .replace("\\n", " ")
                        .replace(Regex("\\{[^}]*\\}"), "")
                        .trim()
                    if (cleanText.isNotEmpty()) {
                        cues.add(SubtitleCue(startMs, endMs, cleanText))
                    }
                }
            }
        }
        return cues
    }

    private fun parseLrc(text: String): List<SubtitleCue> {
        val lrcRegex = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?](.*)""")
        val entries = mutableListOf<Pair<Long, String>>()
        for (raw in text.split('\n')) {
            val match = lrcRegex.matchEntire(raw.trim()) ?: continue
            val ms = toMs("0", match.groupValues[1], match.groupValues[2], match.groupValues[3])
            val lyric = match.groupValues[4].trim()
            entries.add(ms to lyric)
        }
        entries.sortBy { it.first }
        val cues = mutableListOf<SubtitleCue>()
        for (i in entries.indices) {
            val (start, lyric) = entries[i]
            if (lyric.isEmpty()) continue
            val end = if (i + 1 < entries.size) entries[i + 1].first else start + 3000L
            cues.add(SubtitleCue(start, end, lyric))
        }
        return cues
    }

    private fun parseSami(text: String): List<SubtitleCue> {
        val syncRegex = Regex("""<SYNC\s+Start=(\d+)[^>]*>(.*?)(?=<SYNC|</BODY|$)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val matches = syncRegex.findAll(text).toList()
        val cues = mutableListOf<SubtitleCue>()
        for (i in matches.indices) {
            val start = matches[i].groupValues[1].toLongOrNull() ?: continue
            val end = if (i + 1 < matches.size) matches[i + 1].groupValues[1].toLongOrNull() ?: (start + 3000L) else start + 3000L
            val content = matches[i].groupValues[2]
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", "")
                .trim()
            if (content.isNotEmpty()) {
                cues.add(SubtitleCue(start, end, content))
            }
        }
        return cues
    }

    private fun parseTtml(text: String): List<SubtitleCue> {
        val pRegex = Regex("""<p[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cues = mutableListOf<SubtitleCue>()
        for (m in pRegex.findAll(text)) {
            val tLine = "${m.groupValues[1]} --> ${m.groupValues[2]}"
            val tm = timingRegex.find(tLine) ?: continue
            val s = toMs(tm.groupValues[1], tm.groupValues[2], tm.groupValues[3], tm.groupValues[4])
            val e = toMs(tm.groupValues[5], tm.groupValues[6], tm.groupValues[7], tm.groupValues[8])
            val body = m.groupValues[3]
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (body.isNotEmpty()) cues.add(SubtitleCue(s, e, body))
        }
        return cues
    }

    private fun parseMicroDvd(text: String): List<SubtitleCue> {
        val lineRegex = Regex("""^\{(\d+)}\{(\d+)}(.*)$""")
        val fps = 23.976
        val cues = mutableListOf<SubtitleCue>()
        for (raw in text.split('\n')) {
            val m = lineRegex.matchEntire(raw.trim()) ?: continue
            val sFrame = m.groupValues[1].toLongOrNull() ?: continue
            val eFrame = m.groupValues[2].toLongOrNull() ?: continue
            val body = m.groupValues[3].replace("|", "\n").trim()
            cues.add(
                SubtitleCue(
                    startMs = (sFrame * 1000.0 / fps).toLong(),
                    endMs = (eFrame * 1000.0 / fps).toLong(),
                    text = body
                )
            )
        }
        return cues
    }

    private fun writeCues(cues: List<SubtitleCue>, format: String, title: String): String {
        return when (format.lowercase()) {
            "srt" -> buildString {
                for ((i, c) in cues.withIndex()) {
                    append("${i + 1}\r\n")
                    append("${formatSrtTime(c.startMs, ',')} --> ${formatSrtTime(c.endMs, ',')}\r\n")
                    append(c.text.replace("\n", "\r\n")).append("\r\n\r\n")
                }
            }
            "vtt" -> buildString {
                append("WEBVTT\n\n")
                for (c in cues) {
                    append("${formatSrtTime(c.startMs, '.')} --> ${formatSrtTime(c.endMs, '.')}\n")
                    append(c.text).append("\n\n")
                }
            }
            "ass" -> buildString {
                append("[Script Info]\r\nTitle: $title\r\nScriptType: v4.00+\r\nPlayResX: 1920\r\nPlayResY: 1080\r\n\r\n")
                append("[V4+ Styles]\r\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\r\n")
                append("Style: Default,Arial,64,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,1,2,60,60,50,1\r\n\r\n")
                append("[Events]\r\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n")
                for (c in cues) {
                    append("Dialogue: 0,${formatAssTime(c.startMs)},${formatAssTime(c.endMs)},Default,,0,0,0,,${c.text.replace("\n", "\\N")}\r\n")
                }
            }
            "lrc" -> buildString {
                append("[ti:$title]\r\n")
                for (c in cues) {
                    val totalSec = c.startMs / 1000
                    val min = totalSec / 60
                    val sec = totalSec % 60
                    val cs = (c.startMs % 1000) / 10
                    append(String.format(Locale.US, "[%02d:%02d.%02d]%s\r\n", min, sec, cs, c.text.replace("\n", " ")))
                }
            }
            "smi" -> buildString {
                append("<SAMI>\r\n<HEAD><TITLE>$title</TITLE></HEAD>\r\n<BODY>\r\n")
                for (c in cues) {
                    append("<SYNC Start=${c.startMs}><P Class=ZHCC>${c.text.replace("\n", "<br>")}\r\n")
                    append("<SYNC Start=${c.endMs}><P Class=ZHCC>&nbsp;\r\n")
                }
                append("</BODY>\r\n</SAMI>\r\n")
            }
            "ttml" -> buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\">\n  <body>\n    <div>\n")
                for (c in cues) {
                    append("      <p begin=\"${formatSrtTime(c.startMs, '.')}\" end=\"${formatSrtTime(c.endMs, '.')}\">${c.text.replace("\n", "<br/>")}</p>\n")
                }
                append("    </div>\n  </body>\n</tt>\n")
            }
            "sub" -> buildString {
                val fps = 23.976
                append("{1}{1}23.976\r\n")
                for (c in cues) {
                    val s = Math.round(c.startMs / 1000.0 * fps)
                    val e = Math.round(c.endMs / 1000.0 * fps).coerceAtLeast(s + 1)
                    append("{$s}{$e}${c.text.replace("\n", "|")}\r\n")
                }
            }
            else -> ""
        }
    }

    private fun toMs(hStr: String, mStr: String, sStr: String, fStr: String): Long {
        val h = hStr.toLongOrNull() ?: 0L
        val m = mStr.toLongOrNull() ?: 0L
        val s = sStr.toLongOrNull() ?: 0L
        val fracPadded = fStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((h * 3600 + m * 60 + s) * 1000) + fracPadded
    }

    private fun formatSrtTime(ms: Long, sep: Char): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val millis = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", h, m, s, sep, millis)
    }

    private fun formatAssTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val cs = (ms % 1000) / 10
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }
}

/**
 * Built-in Archive converter supporting ZIP, TAR, TAR.GZ (TGZ), GZ, MCPACK, CBZ extraction and packing.
 * Ported from Filee.Engines.Archives.
 */
class ArchiveConverter : IConverter {
    override val id: String = "archive"
    override val displayName: String = "Archives (Built-in ZIP / TAR / GZ)"

    companion object {
        val ARCHIVE_SOURCES = listOf("zip", "tar", "tgz", "gz", "cbz", "mcpack", "mcaddon", "mcworld", "mrpack")
        val ARCHIVE_TARGETS = listOf(FormatRegistry.FOLDER, "zip", "tar", "tgz")
    }

    override val edges: List<ConversionEdge> = buildList {
        for (src in ARCHIVE_SOURCES) {
            for (dst in ARCHIVE_TARGETS) {
                add(ConversionEdge(src, dst, cost = 5))
            }
        }
    }

    override fun getStatus(): EngineStatus = EngineStatus(
        isAvailable = true,
        summary = "ZIP, TAR, TAR.GZ, GZ, CBZ, MCPACK ↔ Extract folder, ZIP, TAR, TAR.GZ",
        version = "Apache Commons Compress + Java Zip"
    )

    override suspend fun convert(
        step: ConversionStep,
        onProgress: (Double) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        onProgress(0.1)

        // 1. "Compress into one archive" (zip-all)
        if (step.preset.packsIntoOneArchive && step.allInputFiles.isNotEmpty()) {
            val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()
            packFiles(step.allInputFiles, step.to, outFile)
            onProgress(1.0)
            return@withContext listOf(outFile)
        }

        // 2. Extract archive to a temp directory first
        val tempExtractDir = File(step.inputFile.parentFile, "extract_${System.nanoTime()}").apply { mkdirs() }
        try {
            extractArchive(step.inputFile, step.from, tempExtractDir)
            onProgress(0.6)

            if (step.to == FormatRegistry.FOLDER) {
                val outFolder = step.output.allocate("") ?: return@withContext emptyList()
                outFolder.mkdirs()
                tempExtractDir.copyRecursively(outFolder, overwrite = true)
                onProgress(1.0)
                val extractedFiles = outFolder.walkTopDown().filter { it.isFile }.toList()
                return@withContext if (extractedFiles.isNotEmpty()) extractedFiles else listOf(outFolder)
            } else {
                val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()
                val topFiles = tempExtractDir.listFiles()?.toList() ?: emptyList()
                packFiles(topFiles, step.to, outFile)
                onProgress(1.0)
                return@withContext listOf(outFile)
            }
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }

    private fun extractArchive(archiveFile: File, format: String, destDir: File) {
        val canonicalDest = destDir.canonicalPath + File.separator
        fun safeTarget(entryName: String): File {
            val target = File(destDir, entryName)
            if (!target.canonicalPath.startsWith(canonicalDest)) {
                throw SecurityException("Blocked Zip-Slip path traversal entry: $entryName")
            }
            return target
        }

        when (format.lowercase()) {
            "zip", "cbz", "mcpack", "mcaddon", "mcworld", "mrpack" -> {
                ZipFile(archiveFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val out = safeTarget(entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(out).use { output -> input.copyTo(output) }
                            }
                        }
                    }
                }
            }
            "tar", "tgz" -> {
                FileInputStream(archiveFile).use { fis ->
                    val stream = if (format.equals("tgz", true) || archiveFile.name.endsWith(".gz", true)) {
                        GZIPInputStream(BufferedInputStream(fis))
                    } else {
                        BufferedInputStream(fis)
                    }
                    TarArchiveInputStream(stream).use { tarIn ->
                        var entry = tarIn.nextEntry
                        while (entry != null) {
                            val out = safeTarget(entry.name)
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { output -> tarIn.copyTo(output) }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
            "gz" -> {
                val baseName = archiveFile.nameWithoutExtension.ifBlank { "extracted_file" }
                val out = safeTarget(baseName)
                GZIPInputStream(FileInputStream(archiveFile)).use { gzIn ->
                    FileOutputStream(out).use { output -> gzIn.copyTo(output) }
                }
            }
        }
    }

    private fun packFiles(files: List<File>, targetFormat: String, outFile: File) {
        when (targetFormat.lowercase()) {
            "tar", "tgz" -> {
                FileOutputStream(outFile).use { fos ->
                    val baseOut = if (targetFormat.equals("tgz", true)) {
                        GZIPOutputStream(BufferedOutputStream(fos))
                    } else {
                        BufferedOutputStream(fos)
                    }
                    TarArchiveOutputStream(baseOut).use { tarOut ->
                        tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        for (f in files) {
                            addFileToTar(tarOut, f, "")
                        }
                    }
                }
            }
            else -> {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zipOut ->
                    for (f in files) {
                        addFileToZip(zipOut, f, "")
                    }
                }
            }
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, parentPath: String) {
        val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> addFileToZip(zipOut, child, entryPath) }
        } else if (file.isFile) {
            zipOut.putNextEntry(ZipEntry(entryPath))
            FileInputStream(file).use { it.copyTo(zipOut) }
            zipOut.closeEntry()
        }
    }

    private fun addFileToTar(tarOut: TarArchiveOutputStream, file: File, parentPath: String) {
        val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> addFileToTar(tarOut, child, entryPath) }
        } else if (file.isFile) {
            val entry = TarArchiveEntry(file, entryPath)
            tarOut.putArchiveEntry(entry)
            FileInputStream(file).use { it.copyTo(tarOut) }
            tarOut.closeArchiveEntry()
        }
    }
}

/**
 * Central registry of all offline conversion engines in Filee for Android.
 */
class EngineRegistry(context: Context) {
    val imageAndVectorConverter = ImageAndVectorConverter()
    val pdfEngineConverter = PdfEngineConverter(context, imageAndVectorConverter)
    val documentAndEbookConverter = DocumentAndEbookConverter()
    val spreadsheetAndDataConverter = SpreadsheetAndDataConverter()
    val subtitleConverter = SubtitleConverter()
    val archiveConverter = ArchiveConverter()

    val allConverters: List<IConverter> = listOf(
        imageAndVectorConverter,
        pdfEngineConverter,
        documentAndEbookConverter,
        spreadsheetAndDataConverter,
        subtitleConverter,
        archiveConverter
    )

    val routePlanner: RoutePlanner = RoutePlanner(allConverters)
}
