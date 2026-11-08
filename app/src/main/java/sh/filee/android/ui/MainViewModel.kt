package sh.filee.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import sh.filee.android.FileeApplication
import sh.filee.android.UriFileHelper
import sh.filee.android.core.conversion.ConversionJobState
import sh.filee.android.core.conversion.JobStatus
import sh.filee.android.core.formats.FormatRegistry
import sh.filee.android.core.localization.Localizer
import sh.filee.android.core.presets.Preset
import sh.filee.android.core.profiles.ProfileSelector
import sh.filee.android.core.profiles.ToolbarProfile
import sh.filee.android.core.settings.AppSettings
import sh.filee.android.core.settings.HistoryEntry
import sh.filee.android.ui.controls.DonutItem
import java.io.File
import java.io.FileOutputStream

class MainViewModel : ViewModel() {
    private val app = FileeApplication.instance
    private val dataStore = app.dataStore
    private val engineRegistry = app.engineRegistry
    private val jobQueue = app.jobQueue

    private val _settings = MutableStateFlow(dataStore.settings)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _presets = MutableStateFlow(dataStore.presets.toList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _profiles = MutableStateFlow(dataStore.profiles.toList())
    val profiles: StateFlow<List<ToolbarProfile>> = _profiles.asStateFlow()

    private val _history = MutableStateFlow(dataStore.history.toList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _selectedFiles = MutableStateFlow<List<File>>(emptyList())
    val selectedFiles: StateFlow<List<File>> = _selectedFiles.asStateFlow()

    private val _isDonutOpen = MutableStateFlow(false)
    val isDonutOpen: StateFlow<Boolean> = _isDonutOpen.asStateFlow()

    private val _isBackFace = MutableStateFlow(false)
    val isBackFace: StateFlow<Boolean> = _isBackFace.asStateFlow()

    private val _activeJob = MutableStateFlow<ConversionJobState?>(null)
    val activeJob: StateFlow<ConversionJobState?> = _activeJob.asStateFlow()

    private val _showCropDialog = MutableStateFlow(false)
    val showCropDialog: StateFlow<Boolean> = _showCropDialog.asStateFlow()

    private var runningJob: Job? = null

    fun tr(key: String, vararg args: Any): String =
        Localizer.get(key, _settings.value.language, *args)

    fun selectUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val appContext = context.applicationContext
            val files = withContext(Dispatchers.IO) { UriFileHelper.copyUrisToCache(appContext, uris) }
            if (files.isNotEmpty()) {
                _selectedFiles.value = files
                _isBackFace.value = false
                _isDonutOpen.value = true
            }
        }
    }

    fun selectLocalFiles(files: List<File>) {
        if (files.isEmpty()) return
        _selectedFiles.value = files
        _isBackFace.value = false
        _isDonutOpen.value = true
    }

    fun closeDonut() {
        _isDonutOpen.value = false
        _isBackFace.value = false
    }

    fun flipDonutFace() {
        val profile = activeProfileForSelectedFiles() ?: return
        if (profile.backPresetIds.isNotEmpty()) {
            _isBackFace.value = !_isBackFace.value
        }
    }

    fun activeProfileForSelectedFiles(): ToolbarProfile? =
        ProfileSelector.select(_profiles.value, _selectedFiles.value.map { it.name })

    /**
     * Builds the [DonutItem] list for the currently selected files and face (Front or Back).
     */
    fun buildDonutItemsForSelection(backFace: Boolean = _isBackFace.value): List<DonutItem> {
        val profile = activeProfileForSelectedFiles() ?: return emptyList()
        val ids = if (backFace) profile.backPresetIds else profile.presetIds
        val sourceFormats = ProfileSelector.detectFormats(_selectedFiles.value.map { it.name }).map { it.id }
        val lang = _settings.value.language

        return ids.mapNotNull { id ->
            val preset = _presets.value.firstOrNull { it.id == id } ?: return@mapNotNull null
            toDonutItem(preset, sourceFormats, _selectedFiles.value.size, lang)
        }
    }

    /**
     * Builds the [DonutItem] list for the Donut Toolbar Editor page.
     */
    fun buildEditorDonutItems(profile: ToolbarProfile, backFace: Boolean): List<DonutItem> {
        val ids = if (backFace) profile.backPresetIds else profile.presetIds
        val lang = _settings.value.language
        return ids.mapNotNull { id ->
            val preset = _presets.value.firstOrNull { it.id == id } ?: return@mapNotNull null
            DonutItem(
                id = preset.id,
                label = Localizer.presetName(preset, lang),
                caption = if (preset.isGroup) {
                    tr("donut.pick_in_group")
                } else {
                    preset.targetFormat.uppercase().takeIf { it != "*" }
                },
                isEnabled = true
            )
        }
    }

    private fun toDonutItem(
        preset: Preset,
        sourceFormats: List<String>,
        fileCount: Int,
        lang: String
    ): DonutItem {
        val label = Localizer.presetName(preset, lang)
        if (preset.isGroup) {
            val children = preset.subPresetIds.mapNotNull { subId ->
                val subPreset = _presets.value.firstOrNull { it.id == subId } ?: return@mapNotNull null
                toDonutItem(subPreset, sourceFormats, fileCount, lang)
            }
            val anyEnabled = children.any { it.isEnabled }
            return DonutItem(
                id = preset.id,
                label = label,
                isEnabled = anyEnabled,
                children = children
            )
        }

        val disabledKey = engineRegistry.routePlanner.checkPresetAvailability(preset, sourceFormats, fileCount)
        return DonutItem(
            id = preset.id,
            label = label,
            caption = preset.targetFormat.uppercase().takeIf { it != "*" && !it.equals(label, true) },
            isEnabled = disabledKey == null,
            disabledReason = disabledKey?.let { tr(it) }
        )
    }

    /**
     * Invokes a preset slice on the currently selected files.
     */
    fun invokePresetById(presetId: String, onFinished: ((ConversionJobState) -> Unit)? = null) {
        val preset = _presets.value.firstOrNull { it.id == presetId } ?: return
        val files = _selectedFiles.value
        if (files.isEmpty()) return

        if (preset.id == "tool-crop") {
            _showCropDialog.value = true
            return
        }

        val lang = _settings.value.language
        val presetDisplayName = Localizer.presetName(preset, lang)
        val outDir = dataStore.getDefaultOutputDirectory()

        runningJob?.cancel()
        runningJob = viewModelScope.launch {
            val finalState = jobQueue.executeJob(
                sourceFiles = files,
                preset = preset,
                presetDisplayName = presetDisplayName,
                outputDirectory = outDir
            ) { stateUpdate ->
                _activeJob.value = stateUpdate
            }

            // Record in history
            val outputs = finalState.allOutputFiles
            val srcFmt = FormatRegistry.detect(files.first().name)?.displayName ?: "FILE"
            val dstFmt = if (preset.targetFormat == "*") srcFmt else preset.targetFormat.uppercase()
            val entry = HistoryEntry(
                sourceFileNames = files.map { it.name },
                outputPaths = outputs.map { it.absolutePath },
                sourceFormat = srcFmt,
                targetFormat = dstFmt,
                presetName = presetDisplayName,
                successCount = finalState.results.count { it.outputFiles.isNotEmpty() || it.skipped },
                failCount = finalState.results.count { it.error != null },
                errorSummary = finalState.results.firstOrNull { it.error != null }?.error?.let { tr(it) }
            )
            dataStore.addHistory(entry)
            _history.value = dataStore.history.toList()
            _isDonutOpen.value = false
            onFinished?.invoke(finalState)
        }
    }

    fun dismissCropDialog() {
        _showCropDialog.value = false
    }

    fun onCropCompleted(savedFiles: List<File>) {
        _showCropDialog.value = false
        _isDonutOpen.value = false
        if (savedFiles.isNotEmpty()) {
            val entry = HistoryEntry(
                sourceFileNames = _selectedFiles.value.map { it.name },
                outputPaths = savedFiles.map { it.absolutePath },
                sourceFormat = "IMAGE",
                targetFormat = "PNG (Cropped)",
                presetName = tr("preset.crop"),
                successCount = savedFiles.size,
                failCount = 0
            )
            dataStore.addHistory(entry)
            _history.value = dataStore.history.toList()
        }
    }

    fun dismissActiveJobBanner() {
        if (_activeJob.value?.status != JobStatus.Running) {
            _activeJob.value = null
        }
    }

    /**
     * Generates a set of realistic demo files (PNG illustration, Markdown note, CSV spreadsheet, SRT subtitle)
     * so the user can test the Donut Menu and offline engines with one tap!
     */
    fun generateAndSelectDemoFile(context: Context, kind: String) {
        viewModelScope.launch {
            val demoDir = File(context.cacheDir, "demo_samples").apply { mkdirs() }
            val file = withContext(Dispatchers.IO) { when (kind) {
                "image" -> {
                    val f = File(demoDir, "Filee_Poster.png")
                    val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.parseColor("#F6F5F1"))
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)
                    p.color = Color.parseColor("#7F77DD")
                    canvas.drawCircle(320f, 240f, 160f, p)
                    p.color = Color.parseColor("#1D9E75")
                    canvas.drawCircle(420f, 160f, 70f, p)
                    p.color = Color.WHITE
                    canvas.drawCircle(320f, 240f, 72f, p)
                    p.color = Color.parseColor("#3C3489")
                    p.textSize = 36f
                    p.textAlign = Paint.Align.CENTER
                    canvas.drawText("Filee", 320f, 252f, p)
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    f
                }
                "markdown" -> {
                    val f = File(demoDir, "Release_Notes.md")
                    f.writeText(
                        """
                        # Filee 2.3.0 Android 版发布说明
                        
                        欢迎使用 **Filee 安卓手机端**！所有格式转换均在您的手机本地离线完成，绝不上传任何文件。
                        
                        ## 核心亮点
                        - 标志性环形工具栏（Donut Menu），支持正反面切换与子环展开
                        - 支持系统全局“分享 / 打开方式”一键唤起转换
                        - 内置图片、矢量图、PDF、办公文档、电子表格、字幕、压缩包与数据转换引擎
                        
                        > 拖一拖，点一点，转换完成。
                        """.trimIndent(),
                        Charsets.UTF_8
                    )
                    f
                }
                "spreadsheet" -> {
                    val f = File(demoDir, "Monthly_Report.csv")
                    f.writeText(
                        "Department,Project,FilesConverted,SavedHours\n" +
                                "Design,Brand Assets,420,18.5\n" +
                                "Engineering,Architecture Docs,310,14.0\n" +
                                "Operations,Invoices PDF,890,32.0\n",
                        Charsets.UTF_8
                    )
                    f
                }
                "subtitle" -> {
                    val f = File(demoDir, "Demo_Movie.srt")
                    f.writeText(
                        "1\r\n00:00:01,000 --> 00:00:04,000\r\n欢迎使用 Filee 手机端文件格式转换器\r\n\r\n" +
                                "2\r\n00:00:04,500 --> 00:00:08,200\r\n支持 SRT、WebVTT、ASS、LRC、SAMI 等字幕互转\r\n\r\n",
                        Charsets.UTF_8
                    )
                    f
                }
                else -> {
                    val f = File(demoDir, "App_Config.json")
                    f.writeText(
                        """{"app":"Filee","version":"2.3.0","offline":true,"formats":270}""",
                        Charsets.UTF_8
                    )
                    f
                }
            } }
            selectLocalFiles(listOf(file))
        }
    }

    // ───────────────────────── Toolbar & Preset & Settings Mutations ─────────────────────────

    fun addPresetToProfile(profileId: String, presetId: String, toBackFace: Boolean) {
        val list = _profiles.value.map { it.deepCopy() }.toMutableList()
        val profile = list.firstOrNull { it.id == profileId } ?: return
        val targetList = if (toBackFace) profile.backPresetIds else profile.presetIds
        if (targetList.size >= ToolbarProfile.MAX_SLICES || presetId in targetList) return
        targetList.add(presetId)
        dataStore.saveProfiles(list)
        _profiles.value = list
    }

    fun removePresetFromProfile(profileId: String, presetId: String, fromBackFace: Boolean) {
        val list = _profiles.value.map { it.deepCopy() }.toMutableList()
        val profile = list.firstOrNull { it.id == profileId } ?: return
        val targetList = if (fromBackFace) profile.backPresetIds else profile.presetIds
        targetList.remove(presetId)
        dataStore.saveProfiles(list)
        _profiles.value = list
    }

    fun savePreset(updatedPreset: Preset) {
        val list = _presets.value.map { it.deepCopy() }.toMutableList()
        val idx = list.indexOfFirst { it.id == updatedPreset.id }
        if (idx >= 0) {
            list[idx] = updatedPreset.deepCopy()
        } else {
            list.add(updatedPreset.deepCopy())
        }
        dataStore.savePresets(list)
        _presets.value = list
    }

    fun deletePreset(presetId: String) {
        val list = _presets.value.filterNot { it.id == presetId }
        dataStore.savePresets(list)
        _presets.value = list
        val updatedProfiles = _profiles.value.map { p ->
            p.deepCopy().apply {
                presetIds.remove(presetId)
                backPresetIds.remove(presetId)
            }
        }
        dataStore.saveProfiles(updatedProfiles)
        _profiles.value = updatedProfiles
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value.copy(theme = _settings.value.theme.copy()))
        dataStore.saveSettings(updated)
        _settings.value = updated
    }

    fun clearHistory() {
        dataStore.clearHistory()
        _history.value = emptyList()
    }

    fun resetDefaults() {
        dataStore.resetToDefaults()
        _presets.value = dataStore.presets.toList()
        _profiles.value = dataStore.profiles.toList()
    }

    fun getEngineList() = engineRegistry.allConverters

    fun outputDirectory(): File = dataStore.getDefaultOutputDirectory()

    fun removeHistoryEntry(entryId: String) {
        dataStore.removeHistory(entryId)
        _history.value = dataStore.history.toList()
    }
}
