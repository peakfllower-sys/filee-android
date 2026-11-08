package sh.filee.android.ui.controls

import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.filee.android.ui.theme.LocalFileeColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One slice of the Donut Toolbar.
 * Ported directly from Filee.App.Controls.DonutItem.
 */
data class DonutItem(
    val id: String,
    val label: String,
    val caption: String? = null,
    val isEnabled: Boolean = true,
    val disabledReason: String? = null,
    val children: List<DonutItem>? = null
) {
    val isGroup: Boolean get() = children != null
    val canExpand: Boolean get() = !children.isNullOrEmpty()
}

enum class DonutHitKind {
    Outside,
    Center,
    Segment,
    EmptyRing,
    EditButton,
    SubSegment
}

data class DonutHit(
    val kind: DonutHitKind,
    val index: Int = -1,
    val sub: Int = -1
)

/**
 * Pure math for the donut: slice angles, hit testing and wedge paths.
 * Angles are measured clockwise from 12 o'clock, in radians. Slice i is centred on i * span.
 * Ported directly from Filee.App.Controls.DonutGeometry.
 */
object DonutGeometry {
    private const val SUB_OVERHANG = 0.25
    private const val MIN_SUB_WIDTH = 52.0

    fun span(count: Int): Double = if (count <= 0) 2 * PI else 2 * PI / count

    fun midAngle(index: Int, count: Int): Double = index * span(count)

    fun pointAt(center: Offset, radius: Float, angleRad: Double): Offset = Offset(
        x = center.x + (radius * sin(angleRad)).toFloat(),
        y = center.y - (radius * cos(angleRad)).toFloat()
    )

    fun angleOf(center: Offset, p: Offset): Double {
        val a = atan2((p.x - center.x).toDouble(), (center.y - p.y).toDouble())
        return if (a < 0) a + 2 * PI else a
    }

    fun indexAt(angleRad: Double, count: Int): Int {
        if (count <= 0) return -1
        val sp = span(count)
        return (floor((angleRad + sp / 2.0) / sp).toInt()).mod(count)
    }

    fun subSpan(parentSpan: Double, count: Int, midRadius: Double): Double {
        if (count <= 0) return 0.0
        var sp = parentSpan * (1.0 + 2.0 * SUB_OVERHANG) / count
        if (midRadius > 0) {
            sp = max(sp, MIN_SUB_WIDTH / midRadius)
        }
        return min(sp, 2 * PI / count)
    }

    fun subMidAngle(parentMid: Double, sub: Int, count: Int, subSpan: Double): Double =
        parentMid + (sub - (count - 1) / 2.0) * subSpan

    fun subIndexAt(
        center: Offset,
        p: Offset,
        innerRadius: Float,
        outerRadius: Float,
        parentMid: Double,
        count: Int,
        subSpan: Double
    ): Int {
        val dist = hypot((p.x - center.x).toDouble(), (p.y - center.y).toDouble())
        if (count <= 0 || dist < innerRadius || dist > outerRadius) return -1
        var delta = angleOf(center, p) - parentMid
        while (delta <= -PI) delta += 2 * PI
        while (delta > PI) delta -= 2 * PI
        val sub = floor(delta / subSpan + count / 2.0).toInt()
        return if (sub in 0 until count) sub else -1
    }

    fun hitTest(
        center: Offset,
        p: Offset,
        innerRadius: Float,
        outerRadius: Float,
        count: Int,
        slack: Float = 0f
    ): DonutHit {
        val dist = hypot((p.x - center.x).toDouble(), (p.y - center.y).toDouble())
        if (dist < innerRadius) return DonutHit(DonutHitKind.Center)
        if (dist > outerRadius + slack) return DonutHit(DonutHitKind.Outside)
        if (count == 0) return DonutHit(DonutHitKind.EmptyRing)
        return DonutHit(DonutHitKind.Segment, indexAt(angleOf(center, p), count))
    }

    /**
     * Builds a smooth Compose [Path] for a wedge slice centred on [midRad] with a constant pixel [gapPx].
     */
    fun createWedgePath(
        center: Offset,
        innerRadius: Float,
        outerRadius: Float,
        midRad: Double,
        spanRad: Double,
        gapPx: Float
    ): Path {
        val path = Path()
        if (spanRad >= 2 * PI - 0.01) {
            path.addOval(Rect(center.x - outerRadius, center.y - outerRadius, center.x + outerRadius, center.y + outerRadius))
            path.addOval(Rect(center.x - innerRadius, center.y - innerRadius, center.x + innerRadius, center.y + innerRadius))
            path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            return path
        }

        val outerHalf = max(0.004, spanRad / 2.0 - gapPx / 2.0 / outerRadius)
        val innerHalf = max(0.004, spanRad / 2.0 - gapPx / 2.0 / innerRadius)

        // Convert clockwise-from-12-o'clock radians to Android Canvas degrees (clockwise from 3 o'clock)
        fun toCanvasDeg(rad: Double): Float = Math.toDegrees(rad - PI / 2.0).toFloat()

        val outerStartDeg = toCanvasDeg(midRad - outerHalf)
        val outerSweepDeg = Math.toDegrees(outerHalf * 2.0).toFloat()
        val innerEndDeg = toCanvasDeg(midRad + innerHalf)
        val innerSweepDeg = -Math.toDegrees(innerHalf * 2.0).toFloat()

        val outerRect = Rect(center.x - outerRadius, center.y - outerRadius, center.x + outerRadius, center.y + outerRadius)
        val innerRect = Rect(center.x - innerRadius, center.y - innerRadius, center.x + innerRadius, center.y + innerRadius)

        path.arcTo(outerRect, outerStartDeg, outerSweepDeg, forceMoveTo = true)
        path.arcTo(innerRect, innerEndDeg, innerSweepDeg, forceMoveTo = false)
        path.close()
        return path
    }
}

/**
 * Interactive Jetpack Compose implementation of Filee's signature Donut Toolbar.
 * Supports:
 * - Touch tap & continuous drag-across-slices selection with haptic feedback
 * - Spring pop-out on active slice
 * - 3D Y-axis flip animation between Front Face and Back Face
 * - Outer sub-ring fan-out for Group slices (e.g., Rotate group, PDF Edit group)
 * - Edit Mode with ✎ edit button on active slice
 * - Live conversion progress ring in the center hub
 */
@Composable
fun DonutMenuComposable(
    items: List<DonutItem>,
    centerTitle: String,
    centerSubtitle: String,
    centerFlipHint: String? = null,
    isBackFace: Boolean = false,
    isEditMode: Boolean = false,
    progress: Float? = null,
    vibrateEnabled: Boolean = true,
    outerRadiusDp: Dp = 148.dp,
    holeRatio: Float = 0.42f,
    onSliceInvoked: (DonutItem) -> Unit,
    onSliceEditRequested: (Int, DonutItem) -> Unit = { _, _ -> },
    onCenterTapped: () -> Unit = {}
) {
    val colors = LocalFileeColors.current
    val density = LocalDensity.current
    val view = LocalView.current

    var activeIndex by remember(items, isBackFace) { mutableIntStateOf(-1) }
    var expandedGroupIndex by remember(items, isBackFace) { mutableIntStateOf(-1) }
    var activeSubIndex by remember(items, isBackFace) { mutableIntStateOf(-1) }

    // Open entrance animation
    val openAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        openAnim.snapTo(0.4f)
        openAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
        )
    }

    // 3D Flip animation when switching between Front and Back face
    val flipRotationY by animateFloatAsState(
        targetValue = if (isBackFace) 180f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "donutFlip"
    )

    val outerRadiusPx = with(density) { outerRadiusDp.toPx() }
    val innerRadiusPx = outerRadiusPx * holeRatio.coerceIn(0.28f, 0.65f)
    val subInnerRadiusPx = outerRadiusPx + with(density) { 8.dp.toPx() }
    val subOuterRadiusPx = outerRadiusPx + with(density) { 58.dp.toPx() }
    val gapPx = with(density) { 3.5.dp.toPx() }
    val popOutPx = with(density) { 8.dp.toPx() }
    val editBtnRadiusPx = with(density) { 15.dp.toPx() }

    val totalSizeDp = outerRadiusDp * 2 + 124.dp

    fun performHaptic() {
        if (vibrateEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun editButtonCenter(center: Offset, index: Int, count: Int): Offset {
        val mid = DonutGeometry.midAngle(index, count)
        return DonutGeometry.pointAt(center, outerRadiusPx - with(density) { 12.dp.toPx() }, mid)
    }

    Box(
        modifier = Modifier.size(totalSizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = openAnim.value
                    scaleX = scale
                    scaleY = scale
                    // Subtle 3D perspective effect during flip without mirroring text
                    val distFromEdge = kotlin.math.abs((flipRotationY % 180f) - 90f) / 90f
                    scaleX = scale * distFromEdge.coerceAtLeast(0.15f)
                }
                .pointerInput(items, isBackFace, isEditMode, expandedGroupIndex) {
                    detectTapGestures { tapOffset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val count = items.size

                        // 1. In Edit Mode, check if ✎ Edit button on the active slice was tapped
                        if (isEditMode && activeIndex in items.indices) {
                            val btnPos = editButtonCenter(center, activeIndex, count)
                            if (hypot((tapOffset.x - btnPos.x).toDouble(), (tapOffset.y - btnPos.y).toDouble()) <= editBtnRadiusPx * 1.4) {
                                performHaptic()
                                onSliceEditRequested(activeIndex, items[activeIndex])
                                return@detectTapGestures
                            }
                        }

                        // 2. Check if an expanded Group's outer sub-ring slice was tapped
                        if (expandedGroupIndex in items.indices) {
                            val groupItem = items[expandedGroupIndex]
                            val children = groupItem.children ?: emptyList()
                            if (children.isNotEmpty()) {
                                val parentSpan = DonutGeometry.span(count)
                                val parentMid = DonutGeometry.midAngle(expandedGroupIndex, count)
                                val sSpan = DonutGeometry.subSpan(parentSpan, children.size, ((subInnerRadiusPx + subOuterRadiusPx) / 2f).toDouble())
                                val subIdx = DonutGeometry.subIndexAt(
                                    center, tapOffset, subInnerRadiusPx, subOuterRadiusPx, parentMid, children.size, sSpan
                                )
                                if (subIdx in children.indices) {
                                    val child = children[subIdx]
                                    if (child.isEnabled) {
                                        performHaptic()
                                        onSliceInvoked(child)
                                    }
                                    return@detectTapGestures
                                }
                            }
                        }

                        // 3. Hit test main ring & center
                        val hit = DonutGeometry.hitTest(center, tapOffset, innerRadiusPx, outerRadiusPx, count, popOutPx)
                        when (hit.kind) {
                            DonutHitKind.Center -> {
                                performHaptic()
                                onCenterTapped()
                            }
                            DonutHitKind.Segment -> {
                                val idx = hit.index
                                if (idx in items.indices) {
                                    performHaptic()
                                    val item = items[idx]
                                    if (isEditMode) {
                                        if (activeIndex == idx) {
                                            onSliceInvoked(item)
                                        } else {
                                            activeIndex = idx
                                        }
                                    } else if (item.isGroup && item.canExpand) {
                                        expandedGroupIndex = if (expandedGroupIndex == idx) -1 else idx
                                        activeIndex = idx
                                    } else if (item.isEnabled) {
                                        activeIndex = idx
                                        onSliceInvoked(item)
                                    } else {
                                        activeIndex = idx
                                    }
                                }
                            }
                            DonutHitKind.Outside -> {
                                expandedGroupIndex = -1
                                activeIndex = -1
                            }
                            else -> {}
                        }
                    }
                }
                .pointerInput(items, isBackFace, isEditMode) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val hit = DonutGeometry.hitTest(center, pos, innerRadiusPx, outerRadiusPx, items.size, popOutPx)
                            if (hit.kind == DonutHitKind.Segment) {
                                activeIndex = hit.index
                                if (items.getOrNull(hit.index)?.canExpand == true) {
                                    expandedGroupIndex = hit.index
                                }
                                performHaptic()
                            }
                        },
                        onDrag = { change, _ ->
                            val pos = change.position
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val count = items.size

                            // Check outer sub-ring first if a group is expanded
                            if (expandedGroupIndex in items.indices) {
                                val children = items[expandedGroupIndex].children ?: emptyList()
                                val parentSpan = DonutGeometry.span(count)
                                val parentMid = DonutGeometry.midAngle(expandedGroupIndex, count)
                                val sSpan = DonutGeometry.subSpan(parentSpan, children.size, ((subInnerRadiusPx + subOuterRadiusPx) / 2f).toDouble())
                                val subIdx = DonutGeometry.subIndexAt(
                                    center, pos, subInnerRadiusPx - 10f, subOuterRadiusPx + 16f, parentMid, children.size, sSpan
                                )
                                if (subIdx != activeSubIndex) {
                                    activeSubIndex = subIdx
                                    if (subIdx >= 0) performHaptic()
                                }
                                if (subIdx >= 0) return@detectDragGestures
                            }

                            val hit = DonutGeometry.hitTest(center, pos, innerRadiusPx, outerRadiusPx, count, popOutPx)
                            val newIdx = if (hit.kind == DonutHitKind.Segment) hit.index else -1
                            if (newIdx != activeIndex) {
                                activeIndex = newIdx
                                activeSubIndex = -1
                                if (newIdx in items.indices) {
                                    performHaptic()
                                    if (items[newIdx].canExpand) {
                                        expandedGroupIndex = newIdx
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (!isEditMode) {
                                if (expandedGroupIndex in items.indices && activeSubIndex >= 0) {
                                    val child = items[expandedGroupIndex].children?.getOrNull(activeSubIndex)
                                    if (child != null && child.isEnabled) {
                                        onSliceInvoked(child)
                                    }
                                } else if (activeIndex in items.indices) {
                                    val item = items[activeIndex]
                                    if (!item.isGroup && item.isEnabled) {
                                        onSliceInvoked(item)
                                    }
                                }
                            }
                        }
                    )
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val count = items.size

            // 1. Center Hub Shadow & Background Disc
            drawCircle(
                color = colors.surface.copy(alpha = 0.96f),
                radius = innerRadiusPx - gapPx,
                center = center
            )
            drawCircle(
                color = colors.border,
                radius = innerRadiusPx - gapPx,
                center = center,
                style = Stroke(width = 2f)
            )

            // 2. Empty Ring Placeholder if count == 0
            if (count == 0) {
                val midR = (innerRadiusPx + outerRadiusPx) / 2f
                drawCircle(
                    color = colors.surface,
                    radius = midR,
                    center = center,
                    style = Stroke(width = outerRadiusPx - innerRadiusPx)
                )
            }

            // 3. Draw Main Ring Slices
            val spanRad = DonutGeometry.span(count)
            for (i in 0 until count) {
                val item = items[i]
                val midRad = DonutGeometry.midAngle(i, count)
                val isHot = (i == activeIndex) || (i == expandedGroupIndex)
                val offsetDist = if (isHot && count > 1) popOutPx else 0f
                val dx = (sin(midRad) * offsetDist).toFloat()
                val dy = (-cos(midRad) * offsetDist).toFloat()

                val sliceFill = when {
                    !item.isEnabled -> colors.surfaceAlt.copy(alpha = 0.55f)
                    isHot -> colors.accentSoft.compositeOver(colors.surface)
                    else -> colors.surface
                }
                val sliceStroke = if (isHot) colors.accent else colors.border
                val textColor = when {
                    !item.isEnabled -> colors.textSecondary.copy(alpha = 0.45f)
                    isHot -> colors.accent
                    else -> colors.textPrimary
                }

                translate(dx, dy) {
                    val path = DonutGeometry.createWedgePath(
                        center = center,
                        innerRadius = innerRadiusPx,
                        outerRadius = outerRadiusPx,
                        midRad = midRad,
                        spanRad = spanRad,
                        gapPx = gapPx
                    )
                    drawPath(path, color = sliceFill)
                    drawPath(
                        path,
                        color = sliceStroke,
                        style = Stroke(width = if (isHot) 4.5f else 2.5f, join = StrokeJoin.Round)
                    )

                    // Draw Slice Label & Group Indicator
                    val labelRadius = (innerRadiusPx + outerRadiusPx) * 0.52f
                    val labelPos = if (count == 1) {
                        Offset(center.x, center.y - labelRadius)
                    } else {
                        DonutGeometry.pointAt(center, labelRadius, midRad)
                    }
                    val displayLabel = if (item.isGroup) "${item.label} ▾" else item.label
                    drawCenteredText(
                        text = displayLabel,
                        position = labelPos,
                        color = textColor,
                        textSizePx = with(density) { (if (count > 9) 11.5.dp else 13.dp).toPx() },
                        bold = true
                    )

                    if (!item.caption.isNullOrBlank() && item.caption != item.label && count <= 8) {
                        val capPos = Offset(labelPos.x, labelPos.y + with(density) { 13.dp.toPx() })
                        drawCenteredText(
                            text = item.caption,
                            position = capPos,
                            color = colors.textSecondary,
                            textSizePx = with(density) { 9.5.dp.toPx() },
                            bold = false
                        )
                    }

                    // Edit Mode ✎ Button on active slice
                    if (isEditMode && i == activeIndex) {
                        val btnPos = DonutGeometry.pointAt(center, outerRadiusPx - with(density) { 12.dp.toPx() }, midRad)
                        drawCircle(color = colors.accent, radius = editBtnRadiusPx, center = btnPos)
                        drawCenteredText(
                            text = "✎",
                            position = btnPos,
                            color = colors.onAccent,
                            textSizePx = with(density) { 13.dp.toPx() },
                            bold = true
                        )
                    }
                }
            }

            // 4. Draw Outer Sub-Ring Fan-out if a Group Slice is Expanded
            if (expandedGroupIndex in items.indices) {
                val group = items[expandedGroupIndex]
                val children = group.children ?: emptyList()
                if (children.isNotEmpty()) {
                    val parentMid = DonutGeometry.midAngle(expandedGroupIndex, count)
                    val sSpan = DonutGeometry.subSpan(
                        spanRad,
                        children.size,
                        ((subInnerRadiusPx + subOuterRadiusPx) / 2f).toDouble()
                    )
                    for ((subIdx, child) in children.withIndex()) {
                        val subMid = DonutGeometry.subMidAngle(parentMid, subIdx, children.size, sSpan)
                        val isSubHot = subIdx == activeSubIndex
                        val subPath = DonutGeometry.createWedgePath(
                            center = center,
                            innerRadius = subInnerRadiusPx,
                            outerRadius = subOuterRadiusPx,
                            midRad = subMid,
                            spanRad = sSpan,
                            gapPx = gapPx
                        )
                        val subFill = if (isSubHot) colors.accent else colors.surface
                        val subText = if (isSubHot) colors.onAccent else colors.textPrimary
                        drawPath(subPath, color = subFill)
                        drawPath(
                            subPath,
                            color = colors.accent,
                            style = Stroke(width = 3f, join = StrokeJoin.Round)
                        )
                        val subLabelPos = DonutGeometry.pointAt(
                            center,
                            (subInnerRadiusPx + subOuterRadiusPx) / 2f,
                            subMid
                        )
                        drawCenteredText(
                            text = child.label,
                            position = subLabelPos,
                            color = subText,
                            textSizePx = with(density) { 11.dp.toPx() },
                            bold = true
                        )
                    }
                }
            }

            // 5. Conversion Progress Arc around Center Hub
            if (progress != null && progress > 0f) {
                val progRadius = innerRadiusPx - gapPx - 4f
                drawArc(
                    color = colors.accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(center.x - progRadius, center.y - progRadius),
                    size = androidx.compose.ui.geometry.Size(progRadius * 2, progRadius * 2),
                    style = Stroke(width = with(density) { 5.dp.toPx() }, cap = StrokeCap.Round)
                )
            }

            // 6. Center Hub Labels (Title, Subtitle, Flip Hint)
            val activeItem = items.getOrNull(activeIndex)
            val hubTitle = when {
                progress != null -> "${(progress * 100).toInt()}%"
                activeItem != null && !activeItem.isEnabled && !activeItem.disabledReason.isNullOrBlank() -> activeItem.label
                activeItem != null -> activeItem.label
                else -> centerTitle
            }
            val hubSubtitle = when {
                activeItem != null && !activeItem.isEnabled && !activeItem.disabledReason.isNullOrBlank() -> activeItem.disabledReason
                activeItem != null && !isEditMode -> "点击转换 → ${activeItem.label}"
                activeItem != null && isEditMode -> "再次点击移出 · ✎ 编辑"
                else -> centerSubtitle
            }

            drawCenteredText(
                text = hubTitle.take(14),
                position = Offset(center.x, center.y - with(density) { 12.dp.toPx() }),
                color = colors.textPrimary,
                textSizePx = with(density) { 14.dp.toPx() },
                bold = true
            )
            drawCenteredText(
                text = hubSubtitle.take(18),
                position = Offset(center.x, center.y + with(density) { 8.dp.toPx() }),
                color = if (activeItem != null && !activeItem.isEnabled) colors.warning else colors.textSecondary,
                textSizePx = with(density) { 11.dp.toPx() },
                bold = false
            )
            if (!centerFlipHint.isNullOrBlank()) {
                drawCenteredText(
                    text = centerFlipHint,
                    position = Offset(center.x, center.y + with(density) { 26.dp.toPx() }),
                    color = colors.accent,
                    textSizePx = with(density) { 10.5.dp.toPx() },
                    bold = true
                )
            }
        }
    }
}

private fun Color.compositeOver(background: Color): Color {
    val fgA = this.alpha
    val invA = 1f - fgA
    return Color(
        red = this.red * fgA + background.red * invA,
        green = this.green * fgA + background.green * invA,
        blue = this.blue * fgA + background.blue * invA,
        alpha = 1f
    )
}

private fun DrawScope.drawCenteredText(
    text: String,
    position: Offset,
    color: Color,
    textSizePx: Float,
    bold: Boolean
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        this.textSize = textSizePx
        this.textAlign = Paint.Align.CENTER
        this.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
    val fontMetrics = paint.fontMetrics
    val baselineY = position.y - (fontMetrics.ascent + fontMetrics.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, position.x, baselineY, paint)
}
