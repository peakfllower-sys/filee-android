package sh.filee.android.engines

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import sh.filee.android.core.conversion.ConversionEdge
import sh.filee.android.core.conversion.ConversionStep
import sh.filee.android.core.conversion.EngineStatus
import sh.filee.android.core.conversion.IConverter
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.min

/**
 * In-process Spreadsheet (XLSX, ODS, CSV, TSV) and Structured Data (JSON, JSONL, YAML, TOML, XML, vCard, iCalendar)
 * converter for Android.
 * Ported from Filee.Engines.Office.SpreadsheetConverter + Filee.Engines.Data.DataConverter.
 */
class SpreadsheetAndDataConverter : IConverter {
    override val id: String = "spreadsheet-data"
    override val displayName: String = "Spreadsheets & Data (Built-in)"

    companion object {
        val SHEET_FORMATS = listOf("xlsx", "xlsm", "ods", "csv", "tsv")
        val DATA_TREE_FORMATS = listOf("json", "jsonl", "yaml", "toml", "xml")
    }

    override val edges: List<ConversionEdge> = buildList {
        // Spreadsheets <-> Spreadsheets, HTML, Markdown, PDF
        for (src in SHEET_FORMATS) {
            for (dst in listOf("xlsx", "csv", "tsv", "html", "md", "pdf", "json", "yaml", "xml")) {
                if (src != dst) add(ConversionEdge(src, dst, cost = 5))
            }
        }
        // Data trees <-> Data trees & CSV/XLSX
        for (src in DATA_TREE_FORMATS) {
            for (dst in DATA_TREE_FORMATS + listOf("csv", "xlsx", "html", "md")) {
                if (src != dst) add(ConversionEdge(src, dst, cost = 5))
            }
        }
        // vCard & iCalendar <-> CSV
        add(ConversionEdge("vcf", "csv", cost = 5))
        add(ConversionEdge("csv", "vcf", cost = 5))
        add(ConversionEdge("ics", "csv", cost = 5))
        add(ConversionEdge("csv", "ics", cost = 5))
    }

    override fun getStatus(): EngineStatus = EngineStatus(
        isAvailable = true,
        summary = "XLSX, ODS, CSV, TSV, JSON, YAML, TOML, XML, vCard, iCalendar ↔ each other & PDF/HTML",
        version = "Built-in Table & DataTree Engine"
    )

    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun convert(
        step: ConversionStep,
        onProgress: (Double) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        onProgress(0.15)
        val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()

        // 1. vCard / iCalendar special cases
        if (step.from == "vcf" && step.to == "csv") {
            val rows = readVCardToTable(step.inputFile.readText(Charsets.UTF_8))
            writeCsv(rows, ',', outFile)
            onProgress(1.0)
            return@withContext listOf(outFile)
        }
        if (step.from == "csv" && step.to == "vcf") {
            val rows = readCsv(step.inputFile.readText(Charsets.UTF_8), ',')
            outFile.writeText(writeTableToVCard(rows), Charsets.UTF_8)
            onProgress(1.0)
            return@withContext listOf(outFile)
        }
        if (step.from == "ics" && step.to == "csv") {
            val rows = readICalendarToTable(step.inputFile.readText(Charsets.UTF_8))
            writeCsv(rows, ',', outFile)
            onProgress(1.0)
            return@withContext listOf(outFile)
        }
        if (step.from == "csv" && step.to == "ics") {
            val rows = readCsv(step.inputFile.readText(Charsets.UTF_8), ',')
            outFile.writeText(writeTableToICalendar(rows), Charsets.UTF_8)
            onProgress(1.0)
            return@withContext listOf(outFile)
        }

        // 2. Read into unified Table (List<List<String>>)
        val rows: List<List<String>> = when (step.from.lowercase()) {
            "xlsx", "xlsm" -> readXlsx(step.inputFile)
            "ods" -> readOds(step.inputFile)
            "csv" -> readCsv(step.inputFile.readText(Charsets.UTF_8), ',')
            "tsv" -> readCsv(step.inputFile.readText(Charsets.UTF_8), '\t')
            "json", "jsonl", "yaml", "toml", "xml" -> {
                val tree = readDataTree(step.inputFile.readText(Charsets.UTF_8), step.from)
                if (step.to in DATA_TREE_FORMATS) {
                    val outText = writeDataTree(tree, step.to)
                    outFile.writeText(outText, Charsets.UTF_8)
                    onProgress(1.0)
                    return@withContext listOf(outFile)
                }
                treeToTable(tree)
            }
            else -> readCsv(step.inputFile.readText(Charsets.UTF_8), ',')
        }
        onProgress(0.6)

        // 3. Write Table to Target Format
        when (step.to.lowercase()) {
            "csv" -> writeCsv(rows, ',', outFile)
            "tsv" -> writeCsv(rows, '\t', outFile)
            "xlsx" -> writeXlsx(rows, step.inputFile.nameWithoutExtension, outFile)
            "html" -> outFile.writeText(writeHtmlTable(rows, step.inputFile.nameWithoutExtension), Charsets.UTF_8)
            "md" -> outFile.writeText(writeMarkdownTable(rows), Charsets.UTF_8)
            "pdf" -> writePdfTable(rows, step.inputFile.nameWithoutExtension, outFile)
            "json", "jsonl", "yaml", "toml", "xml" -> {
                val tree = tableToTree(rows)
                outFile.writeText(writeDataTree(tree, step.to), Charsets.UTF_8)
            }
            else -> writeCsv(rows, ',', outFile)
        }

        onProgress(1.0)
        listOf(outFile)
    }

    // ───────────────────────── XLSX & CSV Readers/Writers ─────────────────────────

    private fun readXlsx(file: File): List<List<String>> {
        val sharedStrings = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                    val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(StringReader(xml))
                    var currentSi = StringBuilder()
                    var inSi = false
                    var ev = parser.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && parser.name == "si") {
                            inSi = true
                            currentSi = StringBuilder()
                        } else if (ev == XmlPullParser.START_TAG && inSi && parser.name == "t") {
                            currentSi.append(parser.nextText())
                        } else if (ev == XmlPullParser.END_TAG && parser.name == "si") {
                            sharedStrings.add(currentSi.toString())
                            inSi = false
                        }
                        ev = parser.next()
                    }
                }

                val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml")
                    ?: zip.entries().asSequence().firstOrNull { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                if (sheetEntry != null) {
                    val xml = zip.getInputStream(sheetEntry).bufferedReader(Charsets.UTF_8).readText()
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(StringReader(xml))
                    var currentRow = mutableListOf<String>()
                    var cellType = ""
                    var cellValue = ""
                    var ev = parser.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "row" -> currentRow = mutableListOf()
                                "c" -> {
                                    cellType = parser.getAttributeValue(null, "t") ?: ""
                                    cellValue = ""
                                }
                                "v", "t" -> cellValue = parser.nextText()
                            }
                            XmlPullParser.END_TAG -> when (parser.name) {
                                "c" -> {
                                    val resolved = if (cellType == "s") {
                                        val idx = cellValue.toIntOrNull() ?: -1
                                        sharedStrings.getOrElse(idx) { cellValue }
                                    } else {
                                        cellValue
                                    }
                                    currentRow.add(resolved)
                                }
                                "row" -> if (currentRow.any { it.isNotEmpty() }) rows.add(currentRow)
                            }
                        }
                        ev = parser.next()
                    }
                }
            }
        }
        return rows
    }

    private fun readOds(file: File): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("content.xml") ?: return@use
                val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(xml))
                var currentRow = mutableListOf<String>()
                var currentCell = StringBuilder()
                var inCell = false
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> when {
                            parser.name.endsWith("table-row") -> currentRow = mutableListOf()
                            parser.name.endsWith("table-cell") -> {
                                inCell = true
                                currentCell = StringBuilder()
                            }
                            inCell && parser.name.endsWith("p") -> {
                                if (currentCell.isNotEmpty()) currentCell.append(" ")
                                currentCell.append(parser.nextText())
                            }
                        }
                        XmlPullParser.END_TAG -> when {
                            parser.name.endsWith("table-cell") -> {
                                currentRow.add(currentCell.toString())
                                inCell = false
                            }
                            parser.name.endsWith("table-row") -> {
                                if (currentRow.any { it.isNotBlank() }) rows.add(currentRow)
                            }
                        }
                    }
                    ev = parser.next()
                }
            }
        }
        return rows
    }

    private fun readCsv(text: String, delimiter: Char): List<List<String>> {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < clean.length && clean[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    delimiter -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        if (currentRow.any { it.isNotEmpty() }) rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    else -> currentField.append(c)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            if (currentRow.any { it.isNotEmpty() }) rows.add(currentRow)
        }
        return rows
    }

    private fun writeCsv(rows: List<List<String>>, delimiter: Char, outFile: File) {
        val sb = StringBuilder("\uFEFF") // UTF-8 BOM for Excel compatibility
        for (row in rows) {
            val line = row.joinToString(delimiter.toString()) { cell ->
                if (cell.contains(delimiter) || cell.contains('"') || cell.contains('\n')) {
                    "\"${cell.replace("\"", "\"\"")}\""
                } else {
                    cell
                }
            }
            sb.append(line).append("\r\n")
        }
        outFile.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun writeXlsx(rows: List<List<String>>, sheetName: String, outFile: File) {
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            fun putXml(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            putXml(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""
            )

            putXml(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            )

            putXml(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""
            )

            val safeSheet = escapeXml(sheetName.take(31).ifBlank { "Sheet1" })
            putXml(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="$safeSheet" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""
            )

            val sheetData = StringBuilder()
            for ((rIdx, row) in rows.withIndex()) {
                val rNum = rIdx + 1
                sheetData.append("""<row r="$rNum">""")
                for ((cIdx, cell) in row.withIndex()) {
                    val colRef = columnRef(cIdx) + rNum
                    val num = cell.toDoubleOrNull()
                    if (num != null && !cell.startsWith("0")) {
                        sheetData.append("""<c r="$colRef"><v>$cell</v></c>""")
                    } else {
                        sheetData.append("""<c r="$colRef" t="inlineStr"><is><t>${escapeXml(cell)}</t></is></c>""")
                    }
                }
                sheetData.append("</row>\n")
            }

            putXml(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    $sheetData
  </sheetData>
</worksheet>"""
            )
        }
    }

    private fun columnRef(colIndex: Int): String {
        var n = colIndex
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A'.code + (n % 26)).toChar())
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun writeHtmlTable(rows: List<List<String>>, title: String): String {
        val trs = rows.mapIndexed { idx, row ->
            val tag = if (idx == 0) "th" else "td"
            "<tr>" + row.joinToString("") { "<$tag>${escapeXml(it)}</$tag>" } + "</tr>"
        }.joinToString("\n")
        return """<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>${escapeXml(title)}</title>
<style>
body{font-family:system-ui,sans-serif;margin:1.5rem;background:#F6F5F1;color:#1F1E1D}
table{border-collapse:collapse;background:#fff;width:100%}
th,td{border:1px solid #ddd;padding:8px 10px;text-align:left;font-size:13px}
th{background:#E3E1FA;color:#3C3489;font-weight:600}
tr:nth-child(even){background:#FAF9F6}
</style>
</head>
<body><h2>${escapeXml(title)}</h2><table>$trs</table></body>
</html>"""
    }

    private fun writeMarkdownTable(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val sb = StringBuilder()
        val header = (0 until cols).map { rows[0].getOrElse(it) { "" }.replace("|", "\\|") }
        sb.appendLine("| " + header.joinToString(" | ") + " |")
        sb.appendLine("| " + (0 until cols).joinToString(" | ") { "---" } + " |")
        for (r in 1 until rows.size) {
            val cells = (0 until cols).map { rows[r].getOrElse(it) { "" }.replace("|", "\\|") }
            sb.appendLine("| " + cells.joinToString(" | ") + " |")
        }
        return sb.toString()
    }

    private fun writePdfTable(rows: List<List<String>>, title: String, outFile: File) {
        val pageW = 842 // A4 Landscape for tables
        val pageH = 595
        val margin = 36f
        val pdf = PdfDocument()
        try {
            var pageNum = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
            var canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(60, 52, 137)
                textSize = 15f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(31, 30, 29)
                textSize = 10f
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(210, 208, 200)
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            val headerFill = Paint().apply {
                color = Color.rgb(227, 225, 250)
                style = Paint.Style.FILL
            }

            var y = margin + 16f
            canvas.drawText(title, margin, y, titlePaint)
            y += 18f

            val maxCols = min(10, rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
            val colW = (pageW - margin * 2) / maxCols
            val rowH = 22f

            for ((rIdx, row) in rows.withIndex()) {
                if (y + rowH > pageH - margin) {
                    pdf.finishPage(page)
                    pageNum++
                    page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                    canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    y = margin
                }
                for (cIdx in 0 until maxCols) {
                    val x = margin + cIdx * colW
                    if (rIdx == 0) {
                        canvas.drawRect(x, y, x + colW, y + rowH, headerFill)
                    }
                    canvas.drawRect(x, y, x + colW, y + rowH, borderPaint)
                    val text = row.getOrElse(cIdx) { "" }.take(24)
                    canvas.drawText(text, x + 5f, y + 15f, cellPaint)
                }
                y += rowH
            }

            pdf.finishPage(page)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    // ───────────────────────── Structured Data Tree ─────────────────────────

    private fun readDataTree(text: String, format: String): JsonElement {
        val clean = text.trim()
        return when (format.lowercase()) {
            "json" -> runCatching { prettyJson.parseToJsonElement(clean) }.getOrElse { JsonPrimitive(clean) }
            "jsonl" -> {
                val items = clean.lines().filter { it.isNotBlank() }.map { line ->
                    runCatching { prettyJson.parseToJsonElement(line) }.getOrElse { JsonPrimitive(line) }
                }
                JsonArray(items)
            }
            "yaml", "toml" -> parseKeyValueLinesToJson(clean)
            "xml" -> parseXmlToJson(clean)
            else -> JsonPrimitive(clean)
        }
    }

    private fun parseKeyValueLinesToJson(text: String): JsonElement {
        val map = linkedMapOf<String, JsonElement>()
        val list = mutableListOf<JsonElement>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("- ")) {
                list.add(JsonPrimitive(line.removePrefix("- ").trim().trim('"', '\'')))
                continue
            }
            val sep = line.indexOf(':').takeIf { it > 0 } ?: line.indexOf('=').takeIf { it > 0 } ?: continue
            val key = line.substring(0, sep).trim().trim('"', '\'')
            val value = line.substring(sep + 1).trim().trim('"', '\'')
            val elem = when {
                value.equals("true", true) -> JsonPrimitive(true)
                value.equals("false", true) -> JsonPrimitive(false)
                value.toLongOrNull() != null -> JsonPrimitive(value.toLong())
                value.toDoubleOrNull() != null -> JsonPrimitive(value.toDouble())
                else -> JsonPrimitive(value)
            }
            map[key] = elem
        }
        return if (map.isNotEmpty()) JsonObject(map) else JsonArray(list)
    }

    private fun parseXmlToJson(xml: String): JsonElement {
        val map = linkedMapOf<String, MutableList<JsonElement>>()
        runCatching {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var ev = parser.eventType
            var currentTag = ""
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    currentTag = parser.name
                } else if (ev == XmlPullParser.TEXT) {
                    val t = parser.text.trim()
                    if (t.isNotEmpty() && currentTag.isNotEmpty()) {
                        map.getOrPut(currentTag) { mutableListOf() }.add(JsonPrimitive(t))
                    }
                }
                ev = parser.next()
            }
        }
        val obj = map.mapValues { (_, v) -> if (v.size == 1) v.first() else JsonArray(v) }
        return JsonObject(obj)
    }

    private fun writeDataTree(tree: JsonElement, format: String): String {
        return when (format.lowercase()) {
            "json" -> prettyJson.encodeToString(JsonElement.serializer(), tree)
            "jsonl" -> when (tree) {
                is JsonArray -> tree.joinToString("\n") { it.toString() }
                else -> tree.toString()
            }
            "yaml" -> jsonToYaml(tree, 0)
            "toml" -> jsonToToml(tree)
            "xml" -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n${jsonToXml(tree, "item", 1)}</root>\n"
            else -> prettyJson.encodeToString(JsonElement.serializer(), tree)
        }
    }

    private fun jsonToYaml(elem: JsonElement, indent: Int): String {
        val pad = "  ".repeat(indent)
        return when (elem) {
            is JsonObject -> elem.entries.joinToString("\n") { (k, v) ->
                when (v) {
                    is JsonObject, is JsonArray -> "$pad$k:\n${jsonToYaml(v, indent + 1)}"
                    else -> "$pad$k: ${primitiveToString(v)}"
                }
            }
            is JsonArray -> elem.joinToString("\n") { item ->
                when (item) {
                    is JsonObject -> {
                        val inner = jsonToYaml(item, indent + 1).trimStart()
                        "$pad- $inner"
                    }
                    else -> "$pad- ${primitiveToString(item)}"
                }
            }
            else -> "$pad${primitiveToString(elem)}"
        }
    }

    private fun jsonToToml(elem: JsonElement): String {
        val sb = StringBuilder()
        when (elem) {
            is JsonObject -> {
                for ((k, v) in elem.entries) {
                    if (v is JsonPrimitive) {
                        sb.appendLine("$k = ${if (v.isString) "\"${v.content}\"" else v.content}")
                    }
                }
                for ((k, v) in elem.entries) {
                    if (v is JsonObject) {
                        sb.appendLine("\n[$k]")
                        for ((sk, sv) in v.entries) {
                            sb.appendLine("$sk = \"${primitiveToString(sv)}\"")
                        }
                    } else if (v is JsonArray) {
                        for (item in v) {
                            sb.appendLine("\n[[$k]]")
                            if (item is JsonObject) {
                                for ((sk, sv) in item.entries) {
                                    sb.appendLine("$sk = \"${primitiveToString(sv)}\"")
                                }
                            } else {
                                sb.appendLine("value = \"${primitiveToString(item)}\"")
                            }
                        }
                    }
                }
            }
            is JsonArray -> {
                for (item in elem) {
                    sb.appendLine("[[items]]")
                    if (item is JsonObject) {
                        for ((k, v) in item.entries) {
                            sb.appendLine("$k = \"${primitiveToString(v)}\"")
                        }
                    } else {
                        sb.appendLine("value = \"${primitiveToString(item)}\"")
                    }
                }
            }
            else -> sb.appendLine("value = \"${primitiveToString(elem)}\"")
        }
        return sb.toString().trim() + "\n"
    }

    private fun jsonToXml(elem: JsonElement, tagName: String, indent: Int): String {
        val pad = "  ".repeat(indent)
        val safeTag = tagName.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "item" }
        return when (elem) {
            is JsonObject -> {
                val inner = elem.entries.joinToString("") { (k, v) -> jsonToXml(v, k, indent + 1) }
                "$pad<$safeTag>\n$inner$pad</$safeTag>\n"
            }
            is JsonArray -> elem.joinToString("") { jsonToXml(it, safeTag, indent) }
            else -> "$pad<$safeTag>${escapeXml(primitiveToString(elem))}</$safeTag>\n"
        }
    }

    private fun treeToTable(tree: JsonElement): List<List<String>> {
        return when (tree) {
            is JsonArray -> {
                val objects = tree.filterIsInstance<JsonObject>()
                if (objects.isNotEmpty()) {
                    val keys = objects.flatMap { it.keys }.distinct()
                    val rows = mutableListOf(keys)
                    for (obj in objects) {
                        rows.add(keys.map { k -> obj[k]?.let { primitiveToString(it) } ?: "" })
                    }
                    rows
                } else {
                    listOf(listOf("value")) + tree.map { listOf(primitiveToString(it)) }
                }
            }
            is JsonObject -> {
                listOf(listOf("key", "value")) + tree.entries.map { (k, v) -> listOf(k, primitiveToString(v)) }
            }
            else -> listOf(listOf("value"), listOf(primitiveToString(tree)))
        }
    }

    private fun tableToTree(rows: List<List<String>>): JsonElement {
        if (rows.isEmpty()) return JsonArray(emptyList())
        if (rows.size == 1) return JsonArray(rows[0].map { JsonPrimitive(it) })
        val headers = rows[0].mapIndexed { idx, h -> h.ifBlank { "col_${idx + 1}" } }
        val items = rows.drop(1).map { row ->
            val map = linkedMapOf<String, JsonElement>()
            for ((idx, key) in headers.withIndex()) {
                val cell = row.getOrElse(idx) { "" }
                map[key] = JsonPrimitive(cell)
            }
            JsonObject(map)
        }
        return JsonArray(items)
    }

    private fun primitiveToString(elem: JsonElement): String = when (elem) {
        is JsonNull -> ""
        is JsonPrimitive -> elem.content
        else -> elem.toString()
    }

    // ───────────────────────── vCard & iCalendar ─────────────────────────

    private fun readVCardToTable(vcf: String): List<List<String>> {
        val rows = mutableListOf(listOf("Full Name", "Phone", "Email", "Organization"))
        var fn = ""
        var tel = ""
        var email = ""
        var org = ""
        for (raw in vcf.lines()) {
            val line = raw.trim()
            when {
                line.equals("BEGIN:VCARD", true) -> { fn = ""; tel = ""; email = ""; org = "" }
                line.startsWith("FN", true) -> fn = line.substringAfter(':').trim()
                line.startsWith("TEL", true) -> tel = line.substringAfter(':').trim()
                line.startsWith("EMAIL", true) -> email = line.substringAfter(':').trim()
                line.startsWith("ORG", true) -> org = line.substringAfter(':').trim()
                line.equals("END:VCARD", true) -> rows.add(listOf(fn, tel, email, org))
            }
        }
        return rows
    }

    private fun writeTableToVCard(rows: List<List<String>>): String {
        val sb = StringBuilder()
        for (row in rows.drop(1)) {
            val fn = row.getOrElse(0) { "Contact" }
            val tel = row.getOrElse(1) { "" }
            val email = row.getOrElse(2) { "" }
            val org = row.getOrElse(3) { "" }
            sb.appendLine("BEGIN:VCARD")
            sb.appendLine("VERSION:3.0")
            sb.appendLine("FN:$fn")
            if (tel.isNotBlank()) sb.appendLine("TEL:$tel")
            if (email.isNotBlank()) sb.appendLine("EMAIL:$email")
            if (org.isNotBlank()) sb.appendLine("ORG:$org")
            sb.appendLine("END:VCARD")
        }
        return sb.toString()
    }

    private fun readICalendarToTable(ics: String): List<List<String>> {
        val rows = mutableListOf(listOf("Summary", "Start", "End", "Location", "Description"))
        var summary = ""
        var dtStart = ""
        var dtEnd = ""
        var location = ""
        var desc = ""
        for (raw in ics.lines()) {
            val line = raw.trim()
            when {
                line.equals("BEGIN:VEVENT", true) -> { summary = ""; dtStart = ""; dtEnd = ""; location = ""; desc = "" }
                line.startsWith("SUMMARY", true) -> summary = line.substringAfter(':').trim()
                line.startsWith("DTSTART", true) -> dtStart = line.substringAfter(':').trim()
                line.startsWith("DTEND", true) -> dtEnd = line.substringAfter(':').trim()
                line.startsWith("LOCATION", true) -> location = line.substringAfter(':').trim()
                line.startsWith("DESCRIPTION", true) -> desc = line.substringAfter(':').trim()
                line.equals("END:VEVENT", true) -> rows.add(listOf(summary, dtStart, dtEnd, location, desc))
            }
        }
        return rows
    }

    private fun writeTableToICalendar(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//Filee Android//EN")
        for (row in rows.drop(1)) {
            sb.appendLine("BEGIN:VEVENT")
            sb.appendLine("SUMMARY:${row.getOrElse(0) { "Event" }}")
            if (row.getOrElse(1) { "" }.isNotBlank()) sb.appendLine("DTSTART:${row[1]}")
            if (row.getOrElse(2) { "" }.isNotBlank()) sb.appendLine("DTEND:${row[2]}")
            if (row.getOrElse(3) { "" }.isNotBlank()) sb.appendLine("LOCATION:${row[3]}")
            if (row.getOrElse(4) { "" }.isNotBlank()) sb.appendLine("DESCRIPTION:${row[4]}")
            sb.appendLine("END:VEVENT")
        }
        sb.appendLine("END:VCALENDAR")
        return sb.toString()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
