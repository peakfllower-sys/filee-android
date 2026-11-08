package sh.filee.android.engines

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.filee.android.core.conversion.ConversionEdge
import sh.filee.android.core.conversion.ConversionStep
import sh.filee.android.core.conversion.EngineStatus
import sh.filee.android.core.conversion.IConverter
import sh.filee.android.core.presets.ImageFlip
import sh.filee.android.core.presets.ImageOptions
import sh.filee.android.core.presets.ImageRotation
import sh.filee.android.core.presets.ResizeMode
import sh.filee.android.core.presets.TracingMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Built-in raster image, vector SVG, and image-tracing converter for Android.
 * Ported from Filee.Engines.Magick + Filee.Engines.Vector.
 */
class ImageAndVectorConverter : IConverter {
    override val id: String = "image-vector"
    override val displayName: String = "Image & Vector (Built-in)"

    companion object {
        val RASTER_SOURCES = listOf("png", "jpg", "webp", "bmp", "gif", "ico", "heic", "wbmp", "ppm")
        val RASTER_TARGETS = listOf("png", "jpg", "webp", "bmp", "ico", "gif", "ppm")
        val VECTOR_SOURCES = listOf("svg", "svgz")
    }

    override val edges: List<ConversionEdge> = buildList {
        // Raster <-> Raster (including self-edges for resize, rotate, grayscale, compress)
        for (src in RASTER_SOURCES) {
            for (dst in RASTER_TARGETS) {
                add(ConversionEdge(src, dst, cost = 5))
            }
            // Raster -> SVG (Tracing, cost = 40, imageSourcesOnly = true, matching Filee.Engines.Vector)
            add(ConversionEdge(src, "svg", cost = 40, imageSourcesOnly = true))
        }
        // Vector -> Raster & Vector
        for (vSrc in VECTOR_SOURCES) {
            for (dst in RASTER_TARGETS) {
                add(ConversionEdge(vSrc, dst, cost = 8))
            }
            add(ConversionEdge(vSrc, "svg", cost = 4))
        }
    }

    override fun getStatus(): EngineStatus = EngineStatus(
        isAvailable = true,
        summary = "PNG, JPG, WEBP, BMP, ICO, GIF, HEIC, PPM, SVG/SVGZ ↔ each other & SVG Tracing",
        version = "Android Skia + AndroidSVG"
    )

    override suspend fun convert(
        step: ConversionStep,
        onProgress: (Double) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        onProgress(0.1)

        // Special case: Merge multiple images into one ("merge-images")
        if (step.preset.joinsImages && step.allInputFiles.size > 1) {
            val bitmaps = step.allInputFiles.map { decodeBitmap(it, null) }
            onProgress(0.5)
            val merged = joinBitmaps(bitmaps, step.preset.image.joinHorizontal)
            bitmaps.forEach { if (it != merged) it.recycle() }
            val transformed = applyImageOptions(merged, step.preset.image)
            val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()
            encodeBitmap(transformed, step.to, step.preset.image.quality, outFile)
            onProgress(1.0)
            return@withContext listOf(outFile)
        }

        // SVG -> SVG / SVGZ decompression
        if (step.from in VECTOR_SOURCES && step.to == "svg") {
            val svgText = readSvgText(step.inputFile, step.from == "svgz")
            val outFile = step.output.allocate("svg") ?: return@withContext emptyList()
            outFile.writeText(svgText, Charsets.UTF_8)
            onProgress(1.0)
            return@withContext listOf(outFile)
        }

        // Decode source into Bitmap
        val sourceBitmap = decodeBitmap(step.inputFile, step.from)
        onProgress(0.4)

        val processed = applyImageOptions(sourceBitmap, step.preset.image)
        if (processed != sourceBitmap) {
            sourceBitmap.recycle()
        }
        onProgress(0.7)

        val outFile = step.output.allocate(step.to) ?: return@withContext emptyList()

        if (step.to == "svg") {
            val svgXml = traceBitmapToSvg(
                bitmap = processed,
                mode = step.preset.trace.mode,
                colorCount = step.preset.trace.colors.coerceIn(2, 32)
            )
            outFile.writeText(svgXml, Charsets.UTF_8)
        } else {
            encodeBitmap(processed, step.to, step.preset.image.quality, outFile)
        }

        processed.recycle()
        onProgress(1.0)
        listOf(outFile)
    }

    fun decodeBitmap(file: File, formatHint: String?): Bitmap {
        val fmt = (formatHint ?: file.extension).lowercase()
        if (fmt == "svg" || fmt == "svgz") {
            val svgText = readSvgText(file, fmt == "svgz")
            return try {
                val svg = SVG.getFromString(svgText)
                val docW = if (svg.documentWidth > 0) svg.documentWidth else 1024f
                val docH = if (svg.documentHeight > 0) svg.documentHeight else 1024f
                val scale = max(1f, 1024f / max(docW, docH))
                val w = (docW * scale).roundToInt().coerceIn(64, 4096)
                val h = (docH * scale).roundToInt().coerceIn(64, 4096)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                svg.renderToCanvas(canvas)
                bmp
            } catch (e: Exception) {
                val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                bmp
            }
        }

        val raw = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalArgumentException("Cannot decode image: ${file.name}")

        // Apply EXIF orientation if present
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated != raw) raw.recycle()
                rotated
            } else {
                raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun readSvgText(file: File, isGzip: Boolean): String {
        FileInputStream(file).use { fis ->
            val stream = if (isGzip) GZIPInputStream(fis) else fis
            return stream.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    fun applyImageOptions(source: Bitmap, options: ImageOptions): Bitmap {
        var current = source

        // 1. Trim transparent borders
        if (options.trimTransparent && current.hasAlpha()) {
            val trimmed = trimTransparentBorders(current)
            if (trimmed != current) {
                current.recycle()
                current = trimmed
            }
        }

        // 2. Rotation & Flip
        val matrix = Matrix()
        when (options.rotation) {
            ImageRotation.Left90 -> matrix.postRotate(-90f)
            ImageRotation.Right90 -> matrix.postRotate(90f)
            ImageRotation.Rotate180 -> matrix.postRotate(180f)
            ImageRotation.None -> {}
        }
        when (options.flip) {
            ImageFlip.Horizontal -> matrix.postScale(-1f, 1f)
            ImageFlip.Vertical -> matrix.postScale(1f, -1f)
            ImageFlip.None -> {}
        }
        if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (transformed != current) current.recycle()
            current = transformed
        }

        // 3. Resize
        val (targetW, targetH) = when (options.resize) {
            ResizeMode.None -> current.width to current.height
            ResizeMode.Percent -> {
                val factor = (options.resizePercent.coerceIn(5, 400)) / 100.0
                max(1, (current.width * factor).roundToInt()) to max(1, (current.height * factor).roundToInt())
            }
            ResizeMode.MaxEdge -> {
                val maxEdge = options.maxEdge.coerceAtLeast(16)
                val longest = max(current.width, current.height)
                if (longest > maxEdge) {
                    val scale = maxEdge.toDouble() / longest
                    max(1, (current.width * scale).roundToInt()) to max(1, (current.height * scale).roundToInt())
                } else {
                    current.width to current.height
                }
            }
            ResizeMode.Width -> {
                val w = options.width.coerceAtLeast(16)
                val scale = w.toDouble() / current.width
                w to max(1, (current.height * scale).roundToInt())
            }
            ResizeMode.Height -> {
                val h = options.height.coerceAtLeast(16)
                val scale = h.toDouble() / current.height
                max(1, (current.width * scale).roundToInt()) to h
            }
        }

        if (targetW != current.width || targetH != current.height) {
            val scaled = Bitmap.createScaledBitmap(current, targetW, targetH, true)
            if (scaled != current) current.recycle()
            current = scaled
        }

        // 4. Grayscale
        if (options.grayscale) {
            val gray = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(gray)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.drawBitmap(current, 0f, 0f, paint)
            current.recycle()
            current = gray
        }

        return current
    }

    private fun trimTransparentBorders(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val alpha = (pixels[rowOffset + x] ushr 24) and 0xFF
                if (alpha > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX >= minX && maxY >= minY && (minX > 0 || minY > 0 || maxX < w - 1 || maxY < h - 1)) {
            Bitmap.createBitmap(bmp, minX, minY, maxX - minX + 1, maxY - minY + 1)
        } else {
            bmp
        }
    }

    private fun joinBitmaps(bitmaps: List<Bitmap>, horizontal: Boolean): Bitmap {
        if (bitmaps.size == 1) return bitmaps.first()
        return if (horizontal) {
            val targetH = bitmaps.maxOf { it.height }.coerceAtMost(4096)
            val scaled = bitmaps.map {
                val w = (it.width.toDouble() * targetH / it.height).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(it, w, targetH, true)
            }
            val totalW = scaled.sumOf { it.width }.coerceAtMost(8192)
            val out = Bitmap.createBitmap(totalW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            var x = 0f
            for (b in scaled) {
                canvas.drawBitmap(b, x, 0f, null)
                x += b.width
            }
            out
        } else {
            val targetW = bitmaps.maxOf { it.width }.coerceAtMost(4096)
            val scaled = bitmaps.map {
                val h = (it.height.toDouble() * targetW / it.width).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(it, targetW, h, true)
            }
            val totalH = scaled.sumOf { it.height }.coerceAtMost(16384)
            val out = Bitmap.createBitmap(targetW, totalH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            var y = 0f
            for (b in scaled) {
                canvas.drawBitmap(b, 0f, y, null)
                y += b.height
            }
            out
        }
    }

    fun encodeBitmap(bitmap: Bitmap, format: String, quality: Int, outFile: File) {
        val q = quality.coerceIn(10, 100)
        FileOutputStream(outFile).use { fos ->
            when (format.lowercase()) {
                "png" -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                "jpg", "jpeg" -> {
                    // Composite over white if bitmap has transparency
                    val opaque = if (bitmap.hasAlpha()) {
                        val bg = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bg)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                        bg
                    } else bitmap
                    opaque.compress(Bitmap.CompressFormat.JPEG, q, fos)
                    if (opaque != bitmap) opaque.recycle()
                }
                "webp" -> {
                    val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (q == 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(compressFormat, q, fos)
                }
                "bmp" -> writeBmp(bitmap, fos)
                "ico" -> writeIco(bitmap, fos)
                "ppm" -> writePpm(bitmap, fos)
                "gif" -> writeSimpleGifOrPngFallback(bitmap, fos)
                else -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        }
    }

    /**
     * Writes a standard 32-bit BGRA BMP file.
     */
    private fun writeBmp(bitmap: Bitmap, fos: FileOutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val rowBytes = w * 4
        val imageSize = rowBytes * h
        val fileSize = 54 + imageSize

        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        header.put('B'.code.toByte()).put('M'.code.toByte())
        header.putInt(fileSize)
        header.putInt(0)
        header.putInt(54)
        header.putInt(40) // BITMAPINFOHEADER
        header.putInt(w)
        header.putInt(h) // bottom-up
        header.putShort(1)
        header.putShort(32)
        header.putInt(0)
        header.putInt(imageSize)
        header.putInt(2835)
        header.putInt(2835)
        header.putInt(0)
        header.putInt(0)
        fos.write(header.array())

        val rowBuf = ByteBuffer.allocate(rowBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (y in (h - 1) downTo 0) {
            rowBuf.clear()
            val offset = y * w
            for (x in 0 until w) {
                rowBuf.putInt(pixels[offset + x])
            }
            fos.write(rowBuf.array())
        }
    }

    /**
     * Writes a standard Windows ICO file (up to 256x256 PNG payload).
     */
    private fun writeIco(bitmap: Bitmap, fos: FileOutputStream) {
        val size = min(256, max(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pngBytes = ByteArrayOutputStream().also {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        if (scaled != bitmap) scaled.recycle()

        val header = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0) // Reserved
        header.putShort(1) // Type 1 = ICO
        header.putShort(1) // 1 image
        header.put(if (size >= 256) 0 else size.toByte())
        header.put(if (size >= 256) 0 else size.toByte())
        header.put(0) // Palette
        header.put(0) // Reserved
        header.putShort(1) // Color planes
        header.putShort(32) // Bits per pixel
        header.putInt(pngBytes.size)
        header.putInt(22) // Offset
        fos.write(header.array())
        fos.write(pngBytes)
    }

    /**
     * Writes binary P6 PPM format.
     */
    private fun writePpm(bitmap: Bitmap, fos: FileOutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        fos.write("P6\n$w $h\n255\n".toByteArray(Charsets.US_ASCII))
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * 3)
        for (y in 0 until h) {
            val offset = y * w
            var idx = 0
            for (x in 0 until w) {
                val c = pixels[offset + x]
                rgb[idx++] = ((c shr 16) and 0xFF).toByte()
                rgb[idx++] = ((c shr 8) and 0xFF).toByte()
                rgb[idx++] = (c and 0xFF).toByte()
            }
            fos.write(rgb)
        }
    }

    /**
     * Writes an uncompressed/LZW single-frame GIF89a image.
     */
    private fun writeSimpleGifOrPngFallback(bitmap: Bitmap, fos: FileOutputStream) {
        // Quantize to 256 web-safe/3-3-2 palette and write valid GIF89a
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        fos.write("GIF89a".toByteArray(Charsets.US_ASCII))
        val lsd = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        lsd.putShort(w.toShort())
        lsd.putShort(h.toShort())
        lsd.put(0xF7.toByte()) // Global color table, 256 colors
        lsd.put(0)
        lsd.put(0)
        fos.write(lsd.array())

        // 3-3-2 RGB Global Color Table (256 * 3 bytes)
        val gct = ByteArray(768)
        for (i in 0 until 256) {
            val r = ((i shr 5) and 0x07) * 255 / 7
            val g = ((i shr 2) and 0x07) * 255 / 7
            val b = (i and 0x03) * 255 / 3
            gct[i * 3] = r.toByte()
            gct[i * 3 + 1] = g.toByte()
            gct[i * 3 + 2] = b.toByte()
        }
        fos.write(gct)

        // Image Descriptor
        val imgDesc = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        imgDesc.put(0x2C.toByte())
        imgDesc.putShort(0)
        imgDesc.putShort(0)
        imgDesc.putShort(w.toShort())
        imgDesc.putShort(h.toShort())
        imgDesc.put(0)
        fos.write(imgDesc.array())

        // LZW Minimum Code Size = 8
        fos.write(8)
        val indices = ByteArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) * 7 / 255
            val g = ((c shr 8) and 0xFF) * 7 / 255
            val b = (c and 0xFF) * 3 / 255
            indices[i] = ((r shl 5) or (g shl 2) or b).toByte()
        }
        encodeGifLzwSubBlocks(indices, fos)
        fos.write(0) // Block terminator
        fos.write(0x3B) // GIF Trailer
    }

    private fun encodeGifLzwSubBlocks(indices: ByteArray, fos: FileOutputStream) {
        val clearCode = 256
        val eoiCode = 257
        var bitBuffer = 0
        var bitCount = 0
        val block = ByteArray(255)
        var blockLen = 0

        fun flushByte(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) {
                fos.write(255)
                fos.write(block, 0, 255)
                blockLen = 0
            }
        }

        fun writeCode(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += 9
            while (bitCount >= 8) {
                flushByte(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        writeCode(clearCode)
        var countSinceClear = 0
        for (b in indices) {
            writeCode(b.toInt() and 0xFF)
            countSinceClear++
            // Emit ClearCode before table fills past 9-bit codes (258 + 250 <= 511)
            if (countSinceClear == 250) {
                writeCode(clearCode)
                countSinceClear = 0
            }
        }
        writeCode(eoiCode)
        if (bitCount > 0) {
            flushByte(bitBuffer and 0xFF)
        }
        if (blockLen > 0) {
            fos.write(blockLen)
            fos.write(block, 0, blockLen)
        }
    }

    /**
     * Traces a raster Bitmap into vector SVG paths (Line Art Otsu threshold or Color Quantized run-length polygons).
     * Ported from Filee.Engines.Vector.Tracing.
     */
    private fun traceBitmapToSvg(bitmap: Bitmap, mode: TracingMode, colorCount: Int): String {
        val maxDim = 512
        val scale = if (max(bitmap.width, bitmap.height) > maxDim) {
            maxDim.toFloat() / max(bitmap.width, bitmap.height)
        } else 1f
        val w = max(1, (bitmap.width * scale).roundToInt())
        val h = max(1, (bitmap.height * scale).roundToInt())
        val workBmp = if (w != bitmap.width || h != bitmap.height) {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else bitmap

        val pixels = IntArray(w * h)
        workBmp.getPixels(pixels, 0, w, 0, 0, w, h)
        if (workBmp != bitmap) workBmp.recycle()

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${bitmap.width}" height="${bitmap.height}" viewBox="0 0 $w $h">""")
        sb.append("\n")

        if (mode == TracingMode.BlackAndWhite) {
            // Otsu threshold line art
            val threshold = computeOtsuThreshold(pixels)
            val pathData = StringBuilder()
            for (y in 0 until h) {
                var x = 0
                while (x < w) {
                    val p = pixels[y * w + x]
                    val a = (p ushr 24) and 0xFF
                    val lum = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                    val isInk = a > 128 && lum <= threshold
                    if (isInk) {
                        val startX = x
                        while (x < w) {
                            val p2 = pixels[y * w + x]
                            val a2 = (p2 ushr 24) and 0xFF
                            val lum2 = ((p2 shr 16 and 0xFF) * 77 + (p2 shr 8 and 0xFF) * 150 + (p2 and 0xFF) * 29) shr 8
                            if (a2 > 128 && lum2 <= threshold) x++ else break
                        }
                        pathData.append("M$startX,${y}h${x - startX}v1h-${x - startX}z")
                    } else {
                        x++
                    }
                }
            }
            sb.append("""  <path fill="#000000" d="$pathData"/>""").append("\n")
        } else {
            // Color quantized vector layers
            val step = max(1, 256 / max(2, colorCount / 2))
            val colorPaths = linkedMapOf<Int, StringBuilder>()
            for (y in 0 until h) {
                var x = 0
                while (x < w) {
                    val c = quantizeColor(pixels[y * w + x], step)
                    if (c == 0) {
                        x++
                        continue
                    }
                    val startX = x
                    while (x < w && quantizeColor(pixels[y * w + x], step) == c) {
                        x++
                    }
                    colorPaths.getOrPut(c) { StringBuilder() }
                        .append("M$startX,${y}h${x - startX}v1h-${x - startX}z")
                }
            }
            for ((colorInt, d) in colorPaths) {
                val hex = String.format("#%06X", colorInt and 0xFFFFFF)
                sb.append("""  <path fill="$hex" d="$d"/>""").append("\n")
            }
        }

        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun quantizeColor(argb: Int, step: Int): Int {
        val a = (argb ushr 24) and 0xFF
        if (a < 64) return 0
        val r = (((argb shr 16) and 0xFF) / step) * step
        val g = (((argb shr 8) and 0xFF) / step) * step
        val b = ((argb and 0xFF) / step) * step
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun computeOtsuThreshold(pixels: IntArray): Int {
        val hist = IntArray(256)
        var total = 0
        for (p in pixels) {
            if (((p ushr 24) and 0xFF) > 128) {
                val lum = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                hist[lum.coerceIn(0, 255)]++
                total++
            }
        }
        if (total == 0) return 128
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += (t * hist[t]).toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                threshold = t
            }
        }
        return threshold
    }
}
