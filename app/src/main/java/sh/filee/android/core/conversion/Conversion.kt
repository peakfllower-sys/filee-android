package sh.filee.android.core.conversion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import sh.filee.android.core.formats.FormatCategory
import sh.filee.android.core.formats.FormatRegistry
import sh.filee.android.core.presets.BuiltInData
import sh.filee.android.core.presets.OutputPathResolver
import sh.filee.android.core.presets.Preset
import java.io.File
import java.util.Date
import java.util.PriorityQueue
import java.util.UUID

/**
 * One directed conversion arc supported by an [IConverter].
 * Ported directly from Filee.Core.Conversion.ConversionEdge.
 */
data class ConversionEdge(
    val from: String,
    val to: String,
    val cost: Int = 10,
    val imageSourcesOnly: Boolean = false,
    val directOnly: Boolean = false
)

data class EngineStatus(
    val isAvailable: Boolean = true,
    val summary: String = "",
    val version: String = "Built-in"
)

/**
 * Allocates output paths for one step of a conversion route.
 */
fun interface StepOutputAllocator {
    fun allocate(extension: String, suffix: String?): File?
    fun allocate(extension: String): File? = allocate(extension, null)
}

/**
 * Input passed to a single step of a conversion route.
 */
data class ConversionStep(
    val inputFile: File,
    val from: String,
    val to: String,
    val preset: Preset,
    val output: StepOutputAllocator,
    val allInputFiles: List<File> = listOf(inputFile)
)

/**
 * Implemented by each conversion engine in Filee.Engines.
 * Ported directly from Filee.Core.Conversion.IConverter.
 */
interface IConverter {
    val id: String
    val displayName: String
    val edges: List<ConversionEdge>
    fun getStatus(): EngineStatus = EngineStatus(true, "", "Built-in")

    suspend fun convert(
        step: ConversionStep,
        onProgress: (Double) -> Unit = {}
    ): List<File>
}

data class RouteStep(
    val converter: IConverter,
    val from: String,
    val to: String
)

data class Route(
    val steps: List<RouteStep>,
    val cost: Int
)

/**
 * Finds the cheapest chain of converters from a source format to a target format using Dijkstra's algorithm.
 * Ported directly from Filee.Core.Conversion.RoutePlanner.
 */
class RoutePlanner(
    converters: Iterable<IConverter>,
    priorityPenalty: (IConverter) -> Int = { 0 }
) {
    companion object {
        const val MAX_STEPS = 3
    }

    private data class WeightedEdge(
        val converter: IConverter,
        val edge: ConversionEdge,
        val cost: Int
    )

    private val edgesFrom: Map<String, List<WeightedEdge>> =
        converters.flatMap { conv ->
            val penalty = priorityPenalty(conv)
            conv.edges.map { e ->
                WeightedEdge(
                    conv,
                    e.copy(from = e.from.lowercase(), to = e.to.lowercase()),
                    e.cost + penalty
                )
            }
        }.groupBy { it.edge.from }

    fun plan(fromFormat: String, toFormat: String): Route? {
        val from = fromFormat.lowercase()
        val to = toFormat.lowercase()

        // Same-format conversions (resize, rotate, grayscale, split, compress) use an explicit self-edge
        if (from == to) {
            val best = (edgesFrom[from] ?: emptyList())
                .filter { it.edge.to == to }
                .minByOrNull { it.cost } ?: return null
            return Route(listOf(RouteStep(best.converter, from, to)), best.cost)
        }

        val imageSource = FormatRegistry.findById(from)?.category == FormatCategory.Image

        val dist = mutableMapOf(from to 0)
        val prev = mutableMapOf<String, Pair<String, RouteStep>>()
        val hops = mutableMapOf(from to 0)
        val queue = PriorityQueue<Pair<String, Int>>(compareBy { it.second })
        queue.add(from to 0)

        while (queue.isNotEmpty()) {
            val (node, cost) = queue.poll() ?: break
            if (cost > (dist[node] ?: Int.MAX_VALUE)) continue
            if (node == to) break
            val currentHops = hops[node] ?: 0
            if (currentHops >= MAX_STEPS) continue

            for (weighted in edgesFrom[node] ?: emptyList()) {
                val edge = weighted.edge
                if (edge.to == edge.from) continue
                if (edge.imageSourcesOnly && !imageSource) continue
                if (edge.directOnly && (node != from || edge.to != to)) continue

                val nextCost = cost + weighted.cost
                val known = dist[edge.to]
                if (known != null && known <= nextCost) continue

                dist[edge.to] = nextCost
                hops[edge.to] = currentHops + 1
                prev[edge.to] = node to RouteStep(weighted.converter, edge.from, edge.to)
                queue.add(edge.to to nextCost)
            }
        }

        if (!prev.containsKey(to)) return null

        val steps = mutableListOf<RouteStep>()
        var curr = to
        while (curr != from) {
            val p = prev[curr] ?: return null
            steps.add(p.second)
            curr = p.first
        }
        steps.reverse()
        return Route(steps, dist[to] ?: 0)
    }

    fun canConvert(from: String, to: String): Boolean = plan(from, to) != null

    /**
     * Checks if a preset can convert all given source format ids.
     * Returns null if available, or a disabled reason key if unavailable.
     */
    fun checkPresetAvailability(preset: Preset, sourceFormats: List<String>, fileCount: Int): String? {
        if (sourceFormats.isEmpty()) return "error.unsupported_source"
        if (preset.packsIntoOneArchive) return null
        if (preset.joinsImages) {
            return if (sourceFormats.all { FormatRegistry.findById(it)?.category == FormatCategory.Image }) {
                null
            } else {
                "error.images_only"
            }
        }
        if (preset.pdf.mergeIntoSingle) {
            val canAllToPdf = sourceFormats.all { it == "pdf" || canConvert(it, "pdf") }
            return if (canAllToPdf) null else "error.no_route"
        }
        if (preset.isTool) {
            return if (sourceFormats.all { FormatRegistry.findById(it)?.category == FormatCategory.Image }) {
                null
            } else {
                "error.images_only"
            }
        }

        val allSupported = sourceFormats.all { src ->
            val target = preset.effectiveTargetFormat(src)
            canConvert(src, target)
        }
        return if (allSupported) null else "error.no_route"
    }
}

enum class JobStatus {
    Running,
    Completed,
    CompletedWithErrors,
    Failed,
    Cancelled
}

data class FileConversionResult(
    val sourceFile: File,
    val outputFiles: List<File> = emptyList(),
    val error: String? = null,
    val skipped: Boolean = false
)

data class ConversionJobState(
    val id: String = UUID.randomUUID().toString(),
    val preset: Preset,
    val presetDisplayName: String,
    val sourceFiles: List<File>,
    val progress: Float = 0f,
    val status: JobStatus = JobStatus.Running,
    val results: List<FileConversionResult> = emptyList()
) {
    val allOutputFiles: List<File>
        get() = results.flatMap { it.outputFiles }
}

/**
 * Executes conversion jobs over the [RoutePlanner] and manages intermediate temp files.
 * Ported from Filee.Core.Conversion.JobQueue.
 */
class JobQueue(
    private val routePlanner: RoutePlanner,
    private val tempRootDir: File,
    private val defaultOutputDir: File
) {
    suspend fun executeJob(
        sourceFiles: List<File>,
        preset: Preset,
        presetDisplayName: String,
        outputDirectory: File = defaultOutputDir,
        onStateUpdate: (ConversionJobState) -> Unit
    ): ConversionJobState = withContext(Dispatchers.IO) {
        val jobId = UUID.randomUUID().toString().take(8)
        val jobWorkDir = File(tempRootDir, "job_$jobId").apply { mkdirs() }
        val reservedPaths = mutableSetOf<String>()
        val results = mutableListOf<FileConversionResult>()
        val now = Date()

        var state = ConversionJobState(
            id = jobId,
            preset = preset,
            presetDisplayName = presetDisplayName,
            sourceFiles = sourceFiles,
            progress = 0.02f,
            status = JobStatus.Running
        )
        onStateUpdate(state)

        try {
            fun allocateFinal(sourceFile: File, index: Int, ext: String, suffix: String?): File? {
                val tokens = OutputPathResolver.Tokens(
                    sourcePath = sourceFile.absolutePath,
                    presetName = presetDisplayName,
                    index = index + 1,
                    now = now
                )
                val resolved = OutputPathResolver.resolve(
                    rule = preset.output,
                    tokens = tokens,
                    targetExtension = ext,
                    outputDirectory = outputDirectory,
                    exists = { candidate ->
                        candidate.exists() || reservedPaths.contains(candidate.absolutePath.lowercase())
                    },
                    suffix = suffix
                )
                if (resolved != null) {
                    reservedPaths.add(resolved.absolutePath.lowercase())
                }
                return resolved
            }

            // Special Case 1: Combine all files into one ZIP archive ("zip-all")
            if (preset.packsIntoOneArchive && sourceFiles.isNotEmpty()) {
                val archiveConv = routePlanner.plan("zip", "zip")?.steps?.firstOrNull()?.converter
                if (archiveConv != null) {
                    val step = ConversionStep(
                        inputFile = sourceFiles.first(),
                        from = "zip",
                        to = preset.targetFormat,
                        preset = preset,
                        output = { ext, suffix -> allocateFinal(sourceFiles.first(), 0, ext, suffix) },
                        allInputFiles = sourceFiles
                    )
                    val outputs = archiveConv.convert(step) { p ->
                        state = state.copy(progress = p.toFloat().coerceIn(0f, 1f))
                        onStateUpdate(state)
                    }
                    results.add(FileConversionResult(sourceFiles.first(), outputs))
                    state = state.copy(
                        progress = 1f,
                        status = if (outputs.isNotEmpty()) JobStatus.Completed else JobStatus.Failed,
                        results = results
                    )
                    onStateUpdate(state)
                    return@withContext state
                }
            }

            // Special Case 2: Merge multiple images into one ("merge-images")
            if (preset.joinsImages && sourceFiles.isNotEmpty()) {
                val firstFormat = FormatRegistry.detect(sourceFiles.first().name)?.id ?: "png"
                val targetFmt = if (preset.targetFormat == BuiltInData.SAME_AS_SOURCE) firstFormat else preset.targetFormat
                val imgConv = routePlanner.plan(firstFormat, targetFmt)?.steps?.firstOrNull()?.converter
                if (imgConv != null) {
                    val step = ConversionStep(
                        inputFile = sourceFiles.first(),
                        from = firstFormat,
                        to = targetFmt,
                        preset = preset,
                        output = { ext, suffix -> allocateFinal(sourceFiles.first(), 0, ext, suffix) },
                        allInputFiles = sourceFiles
                    )
                    val outputs = imgConv.convert(step) { p ->
                        state = state.copy(progress = p.toFloat().coerceIn(0f, 1f))
                        onStateUpdate(state)
                    }
                    results.add(FileConversionResult(sourceFiles.first(), outputs))
                    state = state.copy(
                        progress = 1f,
                        status = if (outputs.isNotEmpty()) JobStatus.Completed else JobStatus.Failed,
                        results = results
                    )
                    onStateUpdate(state)
                    return@withContext state
                }
            }

            // Special Case 3: Merge multiple files into one PDF ("merge-pdf")
            if (preset.pdf.mergeIntoSingle && sourceFiles.isNotEmpty()) {
                val intermediatePdfFiles = mutableListOf<File>()
                for ((idx, srcFile) in sourceFiles.withIndex()) {
                    ensureActive()
                    val srcFormat = FormatRegistry.detect(srcFile.name)?.id
                        ?: throw IllegalArgumentException("Unsupported format: ${srcFile.name}")
                    if (srcFormat == "pdf") {
                        intermediatePdfFiles.add(srcFile)
                    } else {
                        val route = routePlanner.plan(srcFormat, "pdf")
                            ?: throw IllegalStateException("Cannot convert $srcFormat to PDF")
                        val converted = runRoute(
                            sourceFile = srcFile,
                            route = route,
                            preset = preset,
                            workDir = jobWorkDir,
                            finalAllocator = StepOutputAllocator { ext, _ ->
                                File(jobWorkDir, "merge_part_${idx}.$ext")
                            }
                        )
                        intermediatePdfFiles.addAll(converted)
                    }
                    state = state.copy(progress = ((idx + 1).toFloat() / (sourceFiles.size + 1)))
                    onStateUpdate(state)
                }

                val pdfConv = routePlanner.plan("pdf", "pdf")?.steps?.firstOrNull()?.converter
                    ?: throw IllegalStateException("PDF converter unavailable")
                val mergeStep = ConversionStep(
                    inputFile = intermediatePdfFiles.first(),
                    from = "pdf",
                    to = "pdf",
                    preset = preset,
                    output = { ext, suffix -> allocateFinal(sourceFiles.first(), 0, ext, suffix) },
                    allInputFiles = intermediatePdfFiles
                )
                val mergedOutputs = pdfConv.convert(mergeStep) { p ->
                    state = state.copy(progress = 0.8f + 0.2f * p.toFloat())
                    onStateUpdate(state)
                }
                results.add(FileConversionResult(sourceFiles.first(), mergedOutputs))
                state = state.copy(
                    progress = 1f,
                    status = if (mergedOutputs.isNotEmpty()) JobStatus.Completed else JobStatus.Failed,
                    results = results
                )
                onStateUpdate(state)
                return@withContext state
            }

            // Standard per-file conversion
            for ((index, sourceFile) in sourceFiles.withIndex()) {
                ensureActive()
                try {
                    if (!sourceFile.exists() || sourceFile.length() == 0L) {
                        results.add(FileConversionResult(sourceFile, error = "error.empty_file"))
                        continue
                    }
                    val srcFormat = FormatRegistry.detect(sourceFile.name)?.id
                    if (srcFormat == null) {
                        results.add(FileConversionResult(sourceFile, error = "error.unsupported_source"))
                        continue
                    }
                    val targetFormat = preset.effectiveTargetFormat(srcFormat)
                    val route = routePlanner.plan(srcFormat, targetFormat)
                    if (route == null) {
                        results.add(FileConversionResult(sourceFile, error = "error.no_route"))
                        continue
                    }

                    var wasSkipped = false
                    val outputs = runRoute(
                        sourceFile = sourceFile,
                        route = route,
                        preset = preset,
                        workDir = jobWorkDir,
                        finalAllocator = { ext, suffix ->
                            val allocated = allocateFinal(sourceFile, index, ext, suffix)
                            if (allocated == null) wasSkipped = true
                            allocated
                        },
                        onStepProgress = { stepProg ->
                            val overall = (index + stepProg.toFloat()) / sourceFiles.size.coerceAtLeast(1)
                            state = state.copy(progress = overall.coerceIn(0.02f, 0.99f))
                            onStateUpdate(state)
                        }
                    )

                    if (wasSkipped && outputs.isEmpty()) {
                        results.add(FileConversionResult(sourceFile, skipped = true))
                    } else if (outputs.isEmpty()) {
                        results.add(FileConversionResult(sourceFile, error = "error.conversion_failed"))
                    } else {
                        // Apply OnlyIfSmaller rule for same-format compression presets
                        if (preset.output.onlyIfSmaller && targetFormat == srcFormat && outputs.size == 1) {
                            val out = outputs.first()
                            if (out.length() >= sourceFile.length()) {
                                sourceFile.copyTo(out, overwrite = true)
                            }
                        }
                        if (preset.output.keepDates) {
                            val lastMod = sourceFile.lastModified()
                            outputs.forEach { it.setLastModified(lastMod) }
                        }
                        results.add(FileConversionResult(sourceFile, outputFiles = outputs))
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    results.add(FileConversionResult(sourceFile, error = e.message ?: "error.conversion_failed"))
                }
            }

            val errorCount = results.count { it.error != null }
            val successCount = results.count { it.outputFiles.isNotEmpty() || it.skipped }
            val finalStatus = when {
                errorCount == 0 -> JobStatus.Completed
                successCount > 0 -> JobStatus.CompletedWithErrors
                else -> JobStatus.Failed
            }

            state = state.copy(
                progress = 1f,
                status = finalStatus,
                results = results
            )
            onStateUpdate(state)
            state
        } catch (ce: CancellationException) {
            state = state.copy(status = JobStatus.Cancelled, results = results)
            onStateUpdate(state)
            throw ce
        } finally {
            jobWorkDir.deleteRecursively()
        }
    }

    private suspend fun runRoute(
        sourceFile: File,
        route: Route,
        preset: Preset,
        workDir: File,
        finalAllocator: StepOutputAllocator,
        onStepProgress: (Double) -> Unit = {}
    ): List<File> {
        var currentInputs = listOf(sourceFile)
        for ((stepIdx, routeStep) in route.steps.withIndex()) {
            val isLast = stepIdx == route.steps.lastIndex
            val nextInputs = mutableListOf<File>()

            for ((fileIdx, inputFile) in currentInputs.withIndex()) {
                val allocator = StepOutputAllocator { ext, suffix ->
                    if (isLast) {
                        val multiSuffix = if (currentInputs.size > 1 && suffix == null) "_p${fileIdx + 1}" else suffix
                        finalAllocator.allocate(ext, multiSuffix)
                    } else {
                        val stepDir = File(workDir, "step_${stepIdx}_$fileIdx").apply { mkdirs() }
                        val sfx = suffix ?: ""
                        File(stepDir, "temp$sfx.$ext")
                    }
                }

                val step = ConversionStep(
                    inputFile = inputFile,
                    from = routeStep.from,
                    to = routeStep.to,
                    preset = preset,
                    output = allocator
                )

                val stepOutputs = routeStep.converter.convert(step) { p ->
                    val combined = (stepIdx + (fileIdx + p) / currentInputs.size) / route.steps.size
                    onStepProgress(combined)
                }
                nextInputs.addAll(stepOutputs)
            }
            if (nextInputs.isEmpty()) return emptyList()
            currentInputs = nextInputs
        }
        return currentInputs
    }
}
