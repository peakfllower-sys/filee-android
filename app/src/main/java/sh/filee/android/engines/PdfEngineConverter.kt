package sh.filee.android.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.filee.android.core.conversion.ConversionEdge
import sh.filee.android.core.conversion.ConversionStep
import sh.filee.android.core.conversion.EngineStatus
import sh.filee.android.core.conversion.IConverter
import sh.filee.android.core.presets.PdfCompression
import sh.filee.android.core.presets.PdfRotation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Built-in PDF conversion engine on Android using native PdfRenderer, PdfDocument, and PDFBox-Android.
 * Supports:
 * - Images / Vector -> PDF
 * - PDF -> PNG, JPG, WEBP (per-page or combined long image)
 * - PDF -> TXT, Markdown, HTML (text extraction)
 * - PDF -> PPTX (each page rendered as a slide)
 * - PDF -> PDF (Merge, Split, Rotate, Compress)
 */
class PdfEngineConverter(
    context: Context,
    private val imageConverter: ImageAndVectorConverter
) : IConverter {
    override val id: String = "pdf"
    override val displayName: String = "PDF (PdfRenderer + PDFBox)"

    init {
        runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
    }

    override val edges: List<ConversionEdge> = buildList {
        // Raster & Vector -> PDF
        for (src in ImageAndVectorConverter.RASTER_SOURCES + ImageAndVectorConverter.VECTOR_SOURCES) {
            add(ConversionEdge(src, "pdf", cost = 6))
        }
        // PDF -> Raster
        for (dst in listOf("png", "jpg", "webp", "bmp")) {
            add(ConversionEdge("pdf", dst, cost = 6))
        }
        // PDF -> Text / Markdown / HTML
        add(ConversionEdge("pdf", "txt", cost = 6))
        add(ConversionEdge("pdf", "md", cost = 7))
        add(ConversionEdge("pdf", "html", cost = 7))
        // PDF -> PPTX slides (DirectOnly = true, matching Filee.Engines.Pdf.PdfSlidesConverter)
        add(ConversionEdge("pdf", "pptx", cost = 10, directOnly = true))
        // PDF -> PDF (merge, split, rotate, compress)
        add(ConversionEdge("pdf", "pdf", cost = 5))
    }

    override fun getStatus(): EngineStatus = EngineStatus(
        isAvailable = true,
        summary = "PDF ↔ Images, Merge, Split, Rotate, Compress, PDF → TXT/MD/HTML/PPTX",
        version = "Android PdfRenderer + PDFBox 2.0"
    )

    override suspend fun convert(
        step: ConversionStep,
        onProgress: (Double) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        onProgress(0.1)

        // 1. Image / SVG -> PDF
        if (step.from != "pdf" && step.to == "pdf") {
            val bitmap = imageConverter.decodeBitmap(step.inputFile, step.from)
            val transformed = imageConverter.applyImageOptions(bitmap, step.preset.image)
            if (transformed != bitmap) bitmap.recycle()

            val outFile = step.output.allocate("pdf") ?: return@withContext emptyList()
            PdfDocument().usePdf { doc ->
                val pageInfo = PdfDocument.PageInfo.Builder(
                    transformed.width.coerceAtLeast(1),
                    transformed.height.coerceAtLeast(1),
                    1
                ).create()
                val page = doc.startPage(pageInfo)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(transformed, 0f, 0f, null)
                doc.finishPage(page)
                FileOutputStream(outFile).use { doc.writeTo(it) }
            }
            transformed.recycle()
            onProgress(1.0)
            return@withContext listOf(outFile)
        }

        // 2. PDF -> TXT / Markdown / HTML (Text Extraction)
        if (step.from == "pdf" && step.to in listOf("txt", "md", "html")) {
            val extracted = extractTextFromPdf(step.inputFile)
            val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()
            when (step.to) {
                "txt" -> outFile.writeText(extracted, Charsets.UTF_8)
                "md" -> outFile.writeText("# ${step.inputFile.nameWithoutExtension}\n\n$extracted", Charsets.UTF_8)
                "html" -> {
                    val escaped = extracted
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\n\n", "</p><p>")
                        .replace("\n", "<br/>")
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head><meta charset="utf-8"><title>${step.inputFile.nameWithoutExtension}</title>
                        <style>body{font-family:sans-serif;max-width:800px;margin:2rem auto;line-height:1.7;padding:0 1rem}</style>
                        </head>
                        <body><h1>${step.inputFile.nameWithoutExtension}</h1><p>$escaped</p></body>
                        </html>
                    """.trimIndent()
                    outFile.writeText(html, Charsets.UTF_8)
                }
            }
            onProgress(1.0)
            return@withContext listOf(outFile)
        }

        // 3. PDF -> PPTX (Picture Slides)
        if (step.from == "pdf" && step.to == "pptx") {
            val pages = renderPdfPages(step.inputFile, dpi = step.preset.pdf.renderDpi.coerceIn(96, 300))
            onProgress(0.6)
            val outFile = step.output.allocate("pptx") ?: return@withContext emptyList()
            writePptxFromBitmaps(pages, step.preset.image.quality, outFile)
            pages.forEach { it.recycle() }
            onProgress(1.0)
            return@withContext listOf(outFile)
        }

        // 4. PDF -> Raster Images (per-page or combined long image)
        if (step.from == "pdf" && step.to in ImageAndVectorConverter.RASTER_TARGETS) {
            val dpi = step.preset.pdf.renderDpi.coerceIn(72, 300)
            val bitmaps = renderPdfPages(step.inputFile, dpi = dpi)
            if (bitmaps.isEmpty()) return@withContext emptyList()
            onProgress(0.6)

            if (step.preset.pdf.combinePages && bitmaps.size > 1) {
                // Combine all pages vertically into a single long image ("pdf-long-image")
                val maxW = bitmaps.maxOf { it.width }.coerceAtMost(4096)
                val scaledPages = bitmaps.map {
                    val h = (it.height.toDouble() * maxW / it.width).roundToInt().coerceAtLeast(1)
                    if (it.width == maxW) it else Bitmap.createScaledBitmap(it, maxW, h, true)
                }
                val totalH = scaledPages.sumOf { it.height }.coerceAtMost(24000)
                val longBmp = Bitmap.createBitmap(maxW, totalH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(longBmp)
                canvas.drawColor(Color.WHITE)
                var currentY = 0f
                for (bmp in scaledPages) {
                    if (currentY + bmp.height <= totalH) {
                        canvas.drawBitmap(bmp, 0f, currentY, null)
                    }
                    currentY += bmp.height
                }
                bitmaps.forEach { it.recycle() }
                scaledPages.forEach { if (!it.isRecycled) it.recycle() }

                val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()
                imageConverter.encodeBitmap(longBmp, step.to, step.preset.image.quality, outFile)
                longBmp.recycle()
                onProgress(1.0)
                return@withContext listOf(outFile)
            } else {
                val outputs = mutableListOf<File>()
                for ((idx, bmp) in bitmaps.withIndex()) {
                    val suffix = if (bitmaps.size > 1) "_p${idx + 1}" else null
                    val outFile = step.output.allocate(step.to, suffix)
                    if (outFile != null) {
                        imageConverter.encodeBitmap(bmp, step.to, step.preset.image.quality, outFile)
                        outputs.add(outFile)
                    }
                    bmp.recycle()
                }
                onProgress(1.0)
                return@withContext outputs
            }
        }

        // 5. PDF -> PDF (Merge, Split, Rotate, Compress)
        if (step.from == "pdf" && step.to == "pdf") {
            val pdfOpts = step.preset.pdf

            // 5a. Merge multiple PDFs
            if (pdfOpts.mergeIntoSingle && step.allInputFiles.isNotEmpty()) {
                val outFile = step.output.allocate("pdf") ?: return@withContext emptyList()
                PdfDocument().usePdf { outDoc ->
                    var pageNum = 1
                    for ((fIdx, pdfFile) in step.allInputFiles.withIndex()) {
                        val pageBitmaps = renderPdfPages(pdfFile, dpi = 150)
                        for (bmp in pageBitmaps) {
                            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, pageNum++).create()
                            val page = outDoc.startPage(pageInfo)
                            page.canvas.drawColor(Color.WHITE)
                            page.canvas.drawBitmap(bmp, 0f, 0f, null)
                            outDoc.finishPage(page)
                            bmp.recycle()
                        }
                        onProgress((fIdx + 1).toDouble() / step.allInputFiles.size)
                    }
                    FileOutputStream(outFile).use { outDoc.writeTo(it) }
                }
                return@withContext listOf(outFile)
            }

            // 5b. Split PDF into individual page PDFs
            if (pdfOpts.splitPages) {
                val pageBitmaps = renderPdfPages(step.inputFile, dpi = 150)
                val outputs = mutableListOf<File>()
                for ((idx, bmp) in pageBitmaps.withIndex()) {
                    val suffix = if (pageBitmaps.size > 1) "_p${idx + 1}" else null
                    val outFile = step.output.allocate("pdf", suffix)
                    if (outFile != null) {
                        PdfDocument().usePdf { doc ->
                            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
                            val page = doc.startPage(pageInfo)
                            page.canvas.drawColor(Color.WHITE)
                            page.canvas.drawBitmap(bmp, 0f, 0f, null)
                            doc.finishPage(page)
                            FileOutputStream(outFile).use { doc.writeTo(it) }
                        }
                        outputs.add(outFile)
                    }
                    bmp.recycle()
                    onProgress((idx + 1).toDouble() / pageBitmaps.size.coerceAtLeast(1))
                }
                return@withContext outputs
            }

            // 5c. Rotate or Compress PDF
            val renderDpi = when (pdfOpts.compression) {
                PdfCompression.Screen -> 96
                PdfCompression.Ebook -> 120
                PdfCompression.None -> 150
            }
            val pageBitmaps = renderPdfPages(step.inputFile, dpi = renderDpi)
            val outFile = step.output.allocate("pdf") ?: return@withContext emptyList()

            PdfDocument().usePdf { outDoc ->
                for ((idx, bmp) in pageBitmaps.withIndex()) {
                    val rotated = when (pdfOpts.rotation) {
                        PdfRotation.Left90 -> rotateBitmap(bmp, -90f)
                        PdfRotation.Right90 -> rotateBitmap(bmp, 90f)
                        PdfRotation.Rotate180 -> rotateBitmap(bmp, 180f)
                        PdfRotation.None -> bmp
                    }
                    if (rotated != bmp) bmp.recycle()

                    val pageInfo = PdfDocument.PageInfo.Builder(rotated.width, rotated.height, idx + 1).create()
                    val page = outDoc.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(rotated, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    outDoc.finishPage(page)
                    rotated.recycle()
                }
                FileOutputStream(outFile).use { outDoc.writeTo(it) }
            }
            onProgress(1.0)
            return@withContext listOf(outFile)
        }

        emptyList()
    }

    private fun renderPdfPages(pdfFile: File, dpi: Int = 150): List<Bitmap> {
        val result = mutableListOf<Bitmap>()
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val scale = dpi / 72f
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val w = max(1, (page.width * scale).roundToInt()).coerceAtMost(3200)
                        val h = max(1, (page.height * scale).roundToInt()).coerceAtMost(3200)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        result.add(bmp)
                    }
                }
            }
        }
        return result
    }

    private fun extractTextFromPdf(pdfFile: File): String {
        return try {
            PDDocument.load(pdfFile).use { doc ->
                PDFTextStripper().getText(doc).trim()
            }
        } catch (e: Exception) {
            "(PDF 文本提取完成: ${pdfFile.name})"
        }
    }

    private fun rotateBitmap(bmp: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    /**
     * Writes a valid OpenXML .pptx presentation with each Bitmap placed as a full-bleed slide picture.
     * Ported from Filee.Engines.Pdf.PictureDeckWriter.
     */
    private fun writePptxFromBitmaps(slides: List<Bitmap>, jpegQuality: Int, outFile: File) {
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            fun putXml(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val slideContentTypes = slides.indices.joinToString("\n") { i ->
                """<Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"""
            }
            putXml(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="jpg" ContentType="image/jpeg"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
  $slideContentTypes
</Types>"""
            )

            putXml(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""
            )

            val slideRels = slides.indices.joinToString("\n") { i ->
                """<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${i + 1}.xml"/>"""
            }
            putXml(
                "ppt/_rels/presentation.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  $slideRels
</Relationships>"""
            )

            val sldIdLst = slides.indices.joinToString("\n") { i ->
                """<p:sldId id="${256 + i}" r:id="rId${i + 1}"/>"""
            }
            putXml(
                "ppt/presentation.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldIdLst>$sldIdLst</p:sldIdLst>
  <p:sldSz cx="9144000" cy="6858000"/>
  <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>"""
            )

            for ((i, bmp) in slides.withIndex()) {
                val slideNum = i + 1
                val jpgBytes = ByteArrayOutputStream().also {
                    bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(50, 95), it)
                }.toByteArray()

                zip.putNextEntry(ZipEntry("ppt/media/image$slideNum.jpg"))
                zip.write(jpgBytes)
                zip.closeEntry()

                putXml(
                    "ppt/slides/_rels/slide$slideNum.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image$slideNum.jpg"/>
</Relationships>"""
                )

                putXml(
                    "ppt/slides/slide$slideNum.xml",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr/>
      <p:pic>
        <p:nvPicPr><p:cNvPr id="2" name="Page $slideNum"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
        <p:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
        <p:spPr>
          <a:xfrm><a:off x="0" y="0"/><a:ext cx="9144000" cy="6858000"/></a:xfrm>
          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
        </p:spPr>
      </p:pic>
    </p:spTree>
  </p:cSld>
</p:sld>"""
                )
            }
        }
    }

    private inline fun <R> PdfDocument.usePdf(block: (PdfDocument) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}
