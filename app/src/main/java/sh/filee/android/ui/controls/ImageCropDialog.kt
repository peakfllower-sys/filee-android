package sh.filee.android.ui.controls

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.filee.android.ui.theme.LocalFileeColors
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive Image Crop Overlay Dialog ported from Filee.App.Views.CropOverlayWindow & Controls.CropCanvas.
 */
@Composable
fun ImageCropDialog(
    imageFiles: List<File>,
    outputDir: File,
    onDismiss: () -> Unit,
    onCropSaved: (List<File>) -> Unit
) {
    val colors = LocalFileeColors.current
    var currentFileIndex by remember { mutableIntStateOf(0) }
    val savedFiles = remember { mutableListOf<File>() }

    val currentFile = imageFiles.getOrNull(currentFileIndex)
    if (currentFile == null) {
        onDismiss()
        return
    }

    val bitmap = remember(currentFile) {
        BitmapFactory.decodeFile(currentFile.absolutePath)
    }

    // Normalized crop box [0f..1f]
    var leftNorm by remember(currentFile) { mutableFloatStateOf(0.1f) }
    var topNorm by remember(currentFile) { mutableFloatStateOf(0.1f) }
    var rightNorm by remember(currentFile) { mutableFloatStateOf(0.9f) }
    var bottomNorm by remember(currentFile) { mutableFloatStateOf(0.9f) }
    var selectedAspect by remember { mutableStateOf("Free") }

    fun applyAspectRatio(ratioName: String) {
        selectedAspect = ratioName
        val ratio = when (ratioName) {
            "1:1" -> 1f
            "4:3" -> 4f / 3f
            "16:9" -> 16f / 9f
            "3:2" -> 3f / 2f
            else -> return
        }
        val bmpRatio = if (bitmap != null && bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 1f
        val normRatio = ratio / bmpRatio
        val w = 0.7f
        val h = (w / normRatio).coerceIn(0.2f, 0.8f)
        val actualW = (h * normRatio).coerceIn(0.2f, 0.8f)
        leftNorm = 0.5f - actualW / 2f
        rightNorm = 0.5f + actualW / 2f
        topNorm = 0.5f - h / 2f
        bottomNorm = 0.5f + h / 2f
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(colors.cornerRadiusDp.dp),
            color = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "裁剪图片 (${currentFileIndex + 1}/${imageFiles.size}) · ${currentFile.name}",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Aspect ratio dial chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Free", "1:1", "4:3", "3:2", "16:9").forEach { ratio ->
                        FilterChip(
                            selected = selectedAspect == ratio,
                            onClick = { applyAspectRatio(ratio) },
                            label = { Text(if (ratio == "Free") "自由" else ratio, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Crop Stage Canvas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.surfaceAlt, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        val imgBmp = remember(bitmap) { bitmap.asImageBitmap() }
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .pointerInput(currentFile) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val dx = dragAmount.x / size.width.coerceAtLeast(1)
                                        val dy = dragAmount.y / size.height.coerceAtLeast(1)
                                        val px = change.position.x / size.width.coerceAtLeast(1)
                                        val py = change.position.y / size.height.coerceAtLeast(1)

                                        // Determine whether user is dragging a corner or moving the whole frame
                                        val cornerDist = 0.12f
                                        when {
                                            kotlin.math.abs(px - leftNorm) < cornerDist && kotlin.math.abs(py - topNorm) < cornerDist -> {
                                                leftNorm = (leftNorm + dx).coerceIn(0f, rightNorm - 0.1f)
                                                topNorm = (topNorm + dy).coerceIn(0f, bottomNorm - 0.1f)
                                            }
                                            kotlin.math.abs(px - rightNorm) < cornerDist && kotlin.math.abs(py - bottomNorm) < cornerDist -> {
                                                rightNorm = (rightNorm + dx).coerceIn(leftNorm + 0.1f, 1f)
                                                bottomNorm = (bottomNorm + dy).coerceIn(topNorm + 0.1f, 1f)
                                            }
                                            else -> {
                                                val w = rightNorm - leftNorm
                                                val h = bottomNorm - topNorm
                                                leftNorm = (leftNorm + dx).coerceIn(0f, 1f - w)
                                                rightNorm = leftNorm + w
                                                topNorm = (topNorm + dy).coerceIn(0f, 1f - h)
                                                bottomNorm = topNorm + h
                                            }
                                        }
                                    }
                                }
                        ) {
                            val scale = min(size.width / bitmap.width, size.height / bitmap.height)
                            val drawW = bitmap.width * scale
                            val drawH = bitmap.height * scale
                            val offX = (size.width - drawW) / 2f
                            val offY = (size.height - drawH) / 2f

                            drawImage(
                                image = imgBmp,
                                dstOffset = IntOffset(offX.roundToInt(), offY.roundToInt()),
                                dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt())
                            )

                            val cLeft = offX + leftNorm * drawW
                            val cTop = offY + topNorm * drawH
                            val cRight = offX + rightNorm * drawW
                            val cBottom = offY + bottomNorm * drawH
                            val cropRect = Rect(cLeft, cTop, cRight, cBottom)

                            // Dimmed veil outside crop box
                            val veilColor = Color.Black.copy(alpha = 0.45f)
                            drawRect(veilColor, Offset(offX, offY), Size(drawW, cTop - offY))
                            drawRect(veilColor, Offset(offX, cBottom), Size(drawW, offY + drawH - cBottom))
                            drawRect(veilColor, Offset(offX, cTop), Size(cLeft - offX, cropRect.height))
                            drawRect(veilColor, Offset(cRight, cTop), Size(offX + drawW - cRight, cropRect.height))

                            // Rule of thirds grid
                            val gridColor = Color.White.copy(alpha = 0.45f)
                            for (i in 1..2) {
                                val gx = cLeft + cropRect.width * (i / 3f)
                                val gy = cTop + cropRect.height * (i / 3f)
                                drawLine(gridColor, Offset(gx, cTop), Offset(gx, cBottom), strokeWidth = 1.5f)
                                drawLine(gridColor, Offset(cLeft, gy), Offset(cRight, gy), strokeWidth = 1.5f)
                            }

                            // Accent Crop Frame & Corner Handles
                            drawRect(
                                color = colors.accent,
                                topLeft = cropRect.topLeft,
                                size = cropRect.size,
                                style = Stroke(width = 5f)
                            )
                            listOf(
                                Offset(cLeft, cTop),
                                Offset(cRight, cTop),
                                Offset(cLeft, cBottom),
                                Offset(cRight, cBottom)
                            ).forEach { corner ->
                                drawCircle(color = colors.accent, radius = 14f, center = corner)
                                drawCircle(color = Color.White, radius = 6f, center = corner)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    OutlinedButton(onClick = {
                        leftNorm = 0f; topNorm = 0f; rightNorm = 1f; bottomNorm = 1f
                        selectedAspect = "Free"
                    }) {
                        Text("重置")
                    }
                    Button(
                        onClick = {
                            if (bitmap != null) {
                                val x = (leftNorm * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
                                val y = (topNorm * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
                                val w = max(1, ((rightNorm - leftNorm) * bitmap.width).roundToInt()).coerceAtMost(bitmap.width - x)
                                val h = max(1, ((bottomNorm - topNorm) * bitmap.height).roundToInt()).coerceAtMost(bitmap.height - y)
                                val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                                outputDir.mkdirs()
                                val outFile = File(outputDir, "${currentFile.nameWithoutExtension}_cropped.png")
                                FileOutputStream(outFile).use {
                                    cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                                if (cropped != bitmap) cropped.recycle()
                                savedFiles.add(outFile)
                            }
                            if (currentFileIndex + 1 < imageFiles.size) {
                                currentFileIndex++
                            } else {
                                onCropSaved(savedFiles)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                    ) {
                        Text(if (currentFileIndex + 1 < imageFiles.size) "裁剪并下一张" else "裁剪并保存")
                    }
                }
            }
        }
    }
}
