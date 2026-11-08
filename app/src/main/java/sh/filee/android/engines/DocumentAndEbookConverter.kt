package sh.filee.android.engines

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import sh.filee.android.core.conversion.ConversionEdge
import sh.filee.android.core.conversion.ConversionStep
import sh.filee.android.core.conversion.EngineStatus
import sh.filee.android.core.conversion.IConverter
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * In-process Document, Text, Markdown, Presentation, and E-book converter for Android.
 * Ported from Filee.Engines.Office.DocxWriterConverter + Filee.Engines.Text.MarkdownConverter + Filee.Engines.Ebooks.
 */
class DocumentAndEbookConverter : IConverter {
    override val id: String = "document-ebook"
    override val displayName: String = "Documents, Markdown & E-books (Built-in)"

    companion object {
        val DOC_SOURCES = listOf(
            "docx", "docm", "dotx", "odt", "rtf", "hwpx",
            "pptx", "pptm", "ppsx", "odp",
            "txt", "md", "html", "rst", "tex",
            "epub", "kepub", "fb2", "htmlz", "txtz"
        )
        val DOC_TARGETS = listOf("pdf", "docx", "epub", "html", "md", "txt")
    }

    override val edges: List<ConversionEdge> = buildList {
        for (src in DOC_SOURCES) {
            for (dst in DOC_TARGETS) {
                add(ConversionEdge(src, dst, cost = if (src == dst) 4 else 6))
            }
        }
    }

    override fun getStatus(): EngineStatus = EngineStatus(
        isAvailable = true,
        summary = "DOCX, ODT, PPTX, HWPX, EPUB, FB2, Markdown, HTML, TXT ↔ PDF, DOCX, EPUB, HTML, MD, TXT",
        version = "Built-in OpenXML & EPUB 3 Engine"
    )

    data class DocBlock(
        val text: String,
        val headingLevel: Int = 0, // 0 = normal, 1..3 = H1..H3
        val isCode: Boolean = false,
        val isQuote: Boolean = false,
        val isListItem: Boolean = false
    )

    data class ParsedDoc(
        val title: String,
        val blocks: List<DocBlock>
    )

    override suspend fun convert(
        step: ConversionStep,
        onProgress: (Double) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        onProgress(0.15)
        val doc = readDocument(step.inputFile, step.from)
        onProgress(0.55)

        val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()
        when (step.to.lowercase()) {
            "txt" -> outFile.writeText(writePlainText(doc), Charsets.UTF_8)
            "md" -> outFile.writeText(writeMarkdown(doc), Charsets.UTF_8)
            "html" -> outFile.writeText(writeHtml(doc), Charsets.UTF_8)
            "docx" -> writeDocx(doc, outFile)
            "epub" -> writeEpub(doc, outFile)
            "pdf" -> writePdf(doc, outFile)
            else -> outFile.writeText(writePlainText(doc), Charsets.UTF_8)
        }
        onProgress(1.0)
        listOf(outFile)
    }

    fun readDocument(file: File, format: String): ParsedDoc {
        val defaultTitle = file.nameWithoutExtension
        return when (format.lowercase()) {
            "docx", "docm", "dotx" -> readDocx(file, defaultTitle)
            "odt", "odp" -> readOdf(file, defaultTitle)
            "pptx", "pptm", "ppsx" -> readPptx(file, defaultTitle)
            "hwpx" -> readHwpx(file, defaultTitle)
            "epub", "kepub", "htmlz", "txtz" -> readEpubOrZipText(file, defaultTitle)
            "fb2" -> readXmlText(file.readText(Charsets.UTF_8), defaultTitle)
            "html", "htm" -> readHtml(file.readText(Charsets.UTF_8), defaultTitle)
            "md", "markdown" -> readMarkdown(file.readText(Charsets.UTF_8), defaultTitle)
            "rtf" -> readRtf(file.readText(Charsets.UTF_8), defaultTitle)
            else -> readPlainText(file.readText(Charsets.UTF_8), defaultTitle)
        }
    }

    private fun readDocx(file: File, defaultTitle: String): ParsedDoc {
        val blocks = mutableListOf<DocBlock>()
        runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return@use
                val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(xml))

                var currentPara = StringBuilder()
                var heading = 0
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "p", "w:p" -> {
                                currentPara = StringBuilder()
                                heading = 0
                            }
                            "pStyle", "w:pStyle" -> {
                                val styleVal = parser.getAttributeValue(null, "w:val")
                                    ?: parser.getAttributeValue(null, "val") ?: ""
                                if (styleVal.startsWith("Heading", ignoreCase = true)) {
                                    heading = styleVal.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 3) ?: 1
                                }
                            }
                            "t", "w:t" -> {
                                currentPara.append(parser.nextText())
                            }
                            "tab", "w:tab" -> currentPara.append("\t")
                            "br", "w:br" -> currentPara.append("\n")
                        }
                    } else if (event == XmlPullParser.END_TAG && (parser.name == "p" || parser.name == "w:p")) {
                        val text = currentPara.toString().trim()
                        if (text.isNotEmpty()) {
                            blocks.add(DocBlock(text = text, headingLevel = heading))
                        }
                    }
                    event = parser.next()
                }
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(DocBlock(defaultTitle, headingLevel = 1))
        }
        return ParsedDoc(defaultTitle, blocks)
    }

    private fun readOdf(file: File, defaultTitle: String): ParsedDoc {
        val blocks = mutableListOf<DocBlock>()
        runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("content.xml") ?: return@use
                val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                return readXmlText(xml, defaultTitle)
            }
        }
        return ParsedDoc(defaultTitle, blocks.ifEmpty { listOf(DocBlock(defaultTitle)) })
    }

    private fun readPptx(file: File, defaultTitle: String): ParsedDoc {
        val blocks = mutableListOf<DocBlock>()
        runCatching {
            ZipFile(file).use { zip ->
                val slideEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                    .sortedBy { it.name.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                    .toList()
                for ((idx, entry) in slideEntries.withIndex()) {
                    blocks.add(DocBlock("Slide ${idx + 1}", headingLevel = 2))
                    val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                    val parsed = readXmlText(xml, "")
                    blocks.addAll(parsed.blocks)
                }
            }
        }
        return ParsedDoc(defaultTitle, blocks.ifEmpty { listOf(DocBlock(defaultTitle)) })
    }

    private fun readHwpx(file: File, defaultTitle: String): ParsedDoc {
        val blocks = mutableListOf<DocBlock>()
        runCatching {
            ZipFile(file).use { zip ->
                val sectionEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("Contents/section") && it.name.endsWith(".xml") }
                    .sortedBy { it.name }
                    .toList()
                for (entry in sectionEntries) {
                    val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                    blocks.addAll(readXmlText(xml, "").blocks)
                }
            }
        }
        return ParsedDoc(defaultTitle, blocks.ifEmpty { listOf(DocBlock(defaultTitle)) })
    }

    private fun readEpubOrZipText(file: File, defaultTitle: String): ParsedDoc {
        val blocks = mutableListOf<DocBlock>()
        runCatching {
            ZipFile(file).use { zip ->
                val htmlEntries = zip.entries().asSequence()
                    .filter {
                        !it.isDirectory && (it.name.endsWith(".xhtml", true) ||
                                it.name.endsWith(".html", true) ||
                                it.name.endsWith(".htm", true) ||
                                it.name.endsWith(".txt", true))
                    }
                    .sortedBy { it.name }
                    .toList()
                for (entry in htmlEntries) {
                    val content = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                    val parsed = if (entry.name.endsWith(".txt", true)) {
                        readPlainText(content, "")
                    } else {
                        readHtml(content, "")
                    }
                    blocks.addAll(parsed.blocks)
                }
            }
        }
        return ParsedDoc(defaultTitle, blocks.ifEmpty { listOf(DocBlock(defaultTitle)) })
    }

    private fun readXmlText(xml: String, defaultTitle: String): ParsedDoc {
        val cleaned = xml
            .replace(Regex("<(p|h1|h2|h3|div|br|tr|li)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        return readPlainText(cleaned, defaultTitle)
    }

    private fun readHtml(html: String, defaultTitle: String): ParsedDoc {
        val titleMatch = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.trim()
        val bodyHtml = html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val blocks = mutableListOf<DocBlock>()
        val lines = bodyHtml
            .replace(Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n# $1\n")
            .replace(Regex("<h2[^>]*>(.*?)</h2>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n## $1\n")
            .replace(Regex("<h3[^>]*>(.*?)</h3>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n### $1\n")
            .replace(Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n- $1\n")
            .replace(Regex("<(p|div|br|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")

        return readMarkdown(lines, if (!titleMatch.isNullOrBlank()) titleMatch else defaultTitle)
    }

    private fun readMarkdown(md: String, defaultTitle: String): ParsedDoc {
        val blocks = mutableListOf<DocBlock>()
        var detectedTitle = defaultTitle
        var inCodeBlock = false
        val codeBuffer = StringBuilder()

        for (rawLine in md.replace("\r\n", "\n").split('\n')) {
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    blocks.add(DocBlock(codeBuffer.toString().trimEnd(), isCode = true))
                    codeBuffer.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }
            if (inCodeBlock) {
                codeBuffer.appendLine(rawLine)
                continue
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("# ") -> {
                    val h1 = trimmed.removePrefix("# ").trim()
                    if (detectedTitle == defaultTitle && blocks.isEmpty()) detectedTitle = h1
                    blocks.add(DocBlock(cleanInlineMarkdown(h1), headingLevel = 1))
                }
                trimmed.startsWith("## ") -> {
                    blocks.add(DocBlock(cleanInlineMarkdown(trimmed.removePrefix("## ").trim()), headingLevel = 2))
                }
                trimmed.startsWith("### ") -> {
                    blocks.add(DocBlock(cleanInlineMarkdown(trimmed.removePrefix("### ").trim()), headingLevel = 3))
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    blocks.add(DocBlock(cleanInlineMarkdown(trimmed.substring(2).trim()), isListItem = true))
                }
                trimmed.startsWith("> ") -> {
                    blocks.add(DocBlock(cleanInlineMarkdown(trimmed.removePrefix("> ").trim()), isQuote = true))
                }
                else -> {
                    blocks.add(DocBlock(cleanInlineMarkdown(trimmed)))
                }
            }
        }
        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            blocks.add(DocBlock(codeBuffer.toString().trimEnd(), isCode = true))
        }
        return ParsedDoc(detectedTitle, blocks)
    }

    private fun cleanInlineMarkdown(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")

    private fun readRtf(rtf: String, defaultTitle: String): ParsedDoc {
        val plain = rtf
            .replace("\\par", "\n")
            .replace("\\line", "\n")
            .replace("\\tab", "\t")
            .replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .replace("{", "")
            .replace("}", "")
        return readPlainText(plain, defaultTitle)
    }

    private fun readPlainText(text: String, defaultTitle: String): ParsedDoc {
        val blocks = text.replace("\r\n", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { DocBlock(it) }
        return ParsedDoc(defaultTitle, blocks)
    }

    // ───────────────────────── Writers ─────────────────────────

    private fun writePlainText(doc: ParsedDoc): String =
        doc.blocks.joinToString("\n\n") { block ->
            if (block.isListItem) "• ${block.text}" else block.text
        }

    private fun writeMarkdown(doc: ParsedDoc): String {
        val sb = StringBuilder()
        for (block in doc.blocks) {
            when {
                block.headingLevel == 1 -> sb.appendLine("# ${block.text}").appendLine()
                block.headingLevel == 2 -> sb.appendLine("## ${block.text}").appendLine()
                block.headingLevel == 3 -> sb.appendLine("### ${block.text}").appendLine()
                block.isCode -> sb.appendLine("```").appendLine(block.text).appendLine("```").appendLine()
                block.isQuote -> sb.appendLine("> ${block.text}").appendLine()
                block.isListItem -> sb.appendLine("- ${block.text}")
                else -> sb.appendLine(block.text).appendLine()
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun writeHtml(doc: ParsedDoc): String {
        val body = StringBuilder()
        for (block in doc.blocks) {
            val escaped = escapeXml(block.text)
            when {
                block.headingLevel == 1 -> body.appendLine("<h1>$escaped</h1>")
                block.headingLevel == 2 -> body.appendLine("<h2>$escaped</h2>")
                block.headingLevel == 3 -> body.appendLine("<h3>$escaped</h3>")
                block.isCode -> body.appendLine("<pre><code>$escaped</code></pre>")
                block.isQuote -> body.appendLine("<blockquote>$escaped</blockquote>")
                block.isListItem -> body.appendLine("<ul><li>$escaped</li></ul>")
                else -> body.appendLine("<p>$escaped</p>")
            }
        }
        return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>${escapeXml(doc.title)}</title>
<style>
body{font-family:'Noto Sans CJK SC','Noto Sans KR',system-ui,sans-serif;max-width:860px;margin:2rem auto;line-height:1.7;padding:0 1rem;color:#1F1E1D;background:#FFFFFF}
h1,h2,h3{color:#3C3489;margin-top:1.4em}
pre,code{background:#F6F5F1;border-radius:6px;font-family:monospace}
pre{padding:.75rem 1rem;overflow:auto}
blockquote{margin-left:0;padding-left:1rem;border-left:3px solid #7F77DD;color:#6B6A66}
</style>
</head>
<body>
$body
</body>
</html>"""
    }

    /**
     * Writes a valid Office Open XML (.docx) package.
     * Ported from Filee.Engines.Office.DocxWriterConverter.
     */
    private fun writeDocx(doc: ParsedDoc, outFile: File) {
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            fun putXml(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            putXml(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
            )

            putXml(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
            )

            val paragraphsXml = doc.blocks.joinToString("\n") { block ->
                val text = escapeXml(if (block.isListItem) "• ${block.text}" else block.text)
                val sizeHalfPt = when (block.headingLevel) {
                    1 -> 36
                    2 -> 28
                    3 -> 24
                    else -> 22
                }
                val boldTag = if (block.headingLevel > 0) "<w:b/>" else ""
                """<w:p>
  <w:r>
    <w:rPr>$boldTag<w:sz w:val="$sizeHalfPt"/><w:szCs w:val="$sizeHalfPt"/></w:rPr>
    <w:t xml:space="preserve">$text</w:t>
  </w:r>
</w:p>"""
            }

            putXml(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    $paragraphsXml
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
    </w:sectPr>
  </w:body>
</w:document>"""
            )
        }
    }

    /**
     * Writes a valid EPUB 3 e-book package.
     * Ported from Filee.Engines.Ebooks.EpubWriter.
     */
    private fun writeEpub(doc: ParsedDoc, outFile: File) {
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            // 1. Uncompressed mimetype entry MUST be first
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                val crc = CRC32()
                crc.update(mimeBytes)
                this.crc = crc.value
            }
            zip.putNextEntry(mimeEntry)
            zip.write(mimeBytes)
            zip.closeEntry()

            fun putEntry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            putEntry(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
            )

            putEntry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:filee-android-epub</dc:identifier>
    <dc:title>${escapeXml(doc.title)}</dc:title>
    <dc:language>zh</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>"""
            )

            putEntry(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="utf-8"/><title>${escapeXml(doc.title)}</title></head>
<body>
  <nav epub:type="toc"><ol><li><a href="chapter1.xhtml">${escapeXml(doc.title)}</a></li></ol></nav>
</body>
</html>"""
            )

            val bodyXml = doc.blocks.joinToString("\n") { b ->
                val text = escapeXml(b.text)
                when (b.headingLevel) {
                    1 -> "<h1>$text</h1>"
                    2 -> "<h2>$text</h2>"
                    3 -> "<h3>$text</h3>"
                    else -> "<p>${if (b.isListItem) "• $text" else text}</p>"
                }
            }
            putEntry(
                "OEBPS/chapter1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><title>${escapeXml(doc.title)}</title></head>
<body>
$bodyXml
</body>
</html>"""
            )
        }
    }

    /**
     * Renders [ParsedDoc] into a multi-page A4 PDF document using Android's PdfDocument and StaticLayout.
     */
    private fun writePdf(doc: ParsedDoc, outFile: File) {
        val pageWidth = 595 // A4 72dpi width
        val pageHeight = 842 // A4 72dpi height
        val margin = 48f
        val contentWidth = (pageWidth - margin * 2).toInt()

        val pdf = PdfDocument()
        try {
            var pageNumber = 1
            var currentPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas: Canvas = currentPage.canvas
            canvas.drawColor(Color.WHITE)
            var cursorY = margin

            fun newPage() {
                pdf.finishPage(currentPage)
                pageNumber++
                currentPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = currentPage.canvas
                canvas.drawColor(Color.WHITE)
                cursorY = margin
            }

            val blocks = doc.blocks.ifEmpty { listOf(DocBlock(doc.title, headingLevel = 1)) }
            for (block in blocks) {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = when {
                        block.headingLevel > 0 -> Color.rgb(31, 30, 29)
                        block.isQuote -> Color.rgb(107, 106, 102)
                        else -> Color.rgb(35, 35, 33)
                    }
                    textSize = when (block.headingLevel) {
                        1 -> 20f
                        2 -> 16f
                        3 -> 14f
                        else -> 11.5f
                    }
                    typeface = when {
                        block.headingLevel > 0 -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        block.isCode -> Typeface.MONOSPACE
                        else -> Typeface.DEFAULT
                    }
                }

                val displayText = if (block.isListItem) "• ${block.text}" else block.text
                val layout = StaticLayout.Builder
                    .obtain(displayText, 0, displayText.length, paint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.25f)
                    .build()

                val blockHeight = layout.height.toFloat()
                if (cursorY + blockHeight > pageHeight - margin && cursorY > margin) {
                    newPage()
                }

                canvas.save()
                canvas.translate(margin, cursorY)
                layout.draw(canvas)
                canvas.restore()

                cursorY += blockHeight + if (block.headingLevel > 0) 14f else 10f
            }

            pdf.finishPage(currentPage)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
