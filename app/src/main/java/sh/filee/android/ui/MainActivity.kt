package sh.filee.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.filee.android.R
import sh.filee.android.UriFileHelper
import sh.filee.android.core.conversion.JobStatus
import sh.filee.android.core.formats.FormatCategory
import sh.filee.android.core.formats.FormatRegistry
import sh.filee.android.core.settings.HistoryEntry
import sh.filee.android.ui.controls.ImageCropDialog
import sh.filee.android.ui.screens.ConversionResultCard
import sh.filee.android.ui.screens.DonutConvertPanel
import sh.filee.android.ui.screens.EmptyHint
import sh.filee.android.ui.screens.PresetsScreen
import sh.filee.android.ui.screens.SettingsScreen
import sh.filee.android.ui.screens.ToolbarEditorScreen
import sh.filee.android.ui.theme.FileeTheme
import sh.filee.android.ui.theme.LocalFileeColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main window: Home (pick files + recent conversions), Donut Toolbar editor, Presets, Theme & Settings.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsState()
            FileeTheme(themeSettings = settings.theme) {
                FileeMainScreen(viewModel)
            }
        }
    }
}

private data class NavTab(val key: String, val icon: ImageVector)

private val tabs = listOf(
    NavTab("nav.home", Icons.Outlined.Home),
    NavTab("nav.toolbar", Icons.Outlined.DonutLarge),
    NavTab("nav.presets", Icons.Outlined.Tune),
    NavTab("nav.theme", Icons.Outlined.Palette)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileeMainScreen(viewModel: MainViewModel) {
    val colors = LocalFileeColors.current
    val settings by viewModel.settings.collectAsState()
    val isDonutOpen by viewModel.isDonutOpen.collectAsState()
    val showCrop by viewModel.showCropDialog.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Re-key the whole tree on language change so every viewModel.tr(...) label refreshes
    key(settings.language) {
    Scaffold(
        containerColor = colors.page,
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                tabs.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = {
                            Text(
                                viewModel.tr(t.key),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.accent,
                            selectedTextColor = colors.accent,
                            indicatorColor = colors.accentSoft,
                            unselectedIconColor = colors.textSecondary,
                            unselectedTextColor = colors.textSecondary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomeScreen(viewModel)
                1 -> ToolbarEditorScreen(viewModel)
                2 -> PresetsScreen(viewModel)
                else -> SettingsScreen(viewModel)
            }
        }
    }

    // Donut menu bottom sheet — pops up automatically after files are picked
    if (isDonutOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeDonut() },
            sheetState = sheetState,
            containerColor = colors.page
        ) {
            DonutConvertPanel(viewModel = viewModel, onDismiss = { viewModel.closeDonut() })
        }
    }

    if (showCrop) {
        val images = remember(selectedFiles) {
            selectedFiles.filter { FormatRegistry.detect(it.name)?.category == FormatCategory.Image }
        }
        if (images.isEmpty()) {
            LaunchedEffect(Unit) { viewModel.dismissCropDialog() }
        } else {
            ImageCropDialog(
                imageFiles = images,
                outputDir = remember { viewModel.outputDirectory() },
                onDismiss = { viewModel.dismissCropDialog() },
                onCropSaved = { viewModel.onCropCompleted(it) }
            )
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val colors = LocalFileeColors.current
    val context = LocalContext.current
    val history by viewModel.history.collectAsState()
    val job by viewModel.activeJob.collectAsState()
    val isDonutOpen by viewModel.isDonutOpen.collectAsState()
    val corner = RoundedCornerShape(colors.cornerRadiusDp.dp)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.selectUris(context, uris)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_filee),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Filee", color = colors.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(viewModel.tr("app.tagline"), color = colors.textSecondary, fontSize = 13.sp)
                }
            }
        }

        // Pick files card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, corner)
                    .border(1.5.dp, colors.accent.copy(alpha = 0.45f), corner)
                    .clickable { picker.launch(arrayOf("*/*")) }
                    .padding(vertical = 28.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(64.dp).background(colors.accentSoft, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.FileUpload, null, tint = colors.accent, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(viewModel.tr("home.drop_title"), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(viewModel.tr("home.drop_select"), color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                ) { Text(viewModel.tr("home.pick_button")) }
                Spacer(Modifier.height(12.dp))
                Text(viewModel.tr("home.drop_hint"), color = colors.textSecondary, fontSize = 11.5.sp)
            }
        }

        // Quick demo samples
        item {
            Column {
                Text(viewModel.tr("home.quick_demo"), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("image", "home.demo.image", Icons.Outlined.Image),
                        Triple("markdown", "home.demo.markdown", Icons.Outlined.Description),
                        Triple("spreadsheet", "home.demo.spreadsheet", Icons.Outlined.TableChart),
                        Triple("subtitle", "home.demo.subtitle", Icons.Outlined.Subtitles),
                        Triple("data", "home.demo.data", Icons.Outlined.DataObject)
                    ).forEach { (kind, key, icon) ->
                        AssistChip(
                            onClick = { viewModel.generateAndSelectDemoFile(context, kind) },
                            label = { Text(viewModel.tr(key)) },
                            leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = colors.accent) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = colors.surface, labelColor = colors.textPrimary)
                        )
                    }
                }
            }
        }

        // Active / finished job
        job?.let { j ->
            item {
                if (j.status == JobStatus.Running) {
                    if (!isDonutOpen) {
                        Column(
                            Modifier.fillMaxWidth().background(colors.surface, corner).padding(16.dp)
                        ) {
                            Text(
                                viewModel.tr("toast.converting", j.sourceFiles.size, j.presetDisplayName),
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { j.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.accent,
                                trackColor = colors.surfaceAlt
                            )
                        }
                    }
                } else {
                    ConversionResultCard(job = j, viewModel = viewModel, onDone = { viewModel.dismissActiveJobBanner() })
                }
            }
        }

        // Recent conversions
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    viewModel.tr("home.recent"),
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearHistory() }) {
                        Text(viewModel.tr("home.clear_history"), color = colors.textSecondary)
                    }
                }
            }
        }
        if (history.isEmpty()) {
            item { EmptyHint(viewModel.tr("home.recent_empty")) }
        } else {
            items(history, key = { it.id }) { entry ->
                HistoryRow(entry, viewModel)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, viewModel: MainViewModel) {
    val colors = LocalFileeColors.current
    val context = LocalContext.current
    val outputs = remember(entry) { entry.outputPaths.map { File(it) }.filter { it.exists() } }
    val time = remember(entry) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestampMillis))
    }
    val corner = RoundedCornerShape(colors.cornerRadiusDp.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, corner)
            .border(1.dp, colors.border, corner)
            .clickable(enabled = outputs.isNotEmpty()) { UriFileHelper.openFile(context, outputs.first()) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .background(if (entry.failCount > 0 && entry.successCount == 0) colors.danger.copy(alpha = 0.15f) else colors.accentSoft, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                entry.targetFormat.take(6),
                color = if (entry.failCount > 0 && entry.successCount == 0) colors.danger else colors.accent,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.sourceFileNames.joinToString("、"),
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${entry.sourceFormat} → ${entry.targetFormat} · ${entry.presetName} · $time",
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            entry.errorSummary?.let {
                Text(it, color = colors.danger, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (outputs.isNotEmpty()) {
            IconButton(onClick = { UriFileHelper.openFile(context, outputs.first()) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, viewModel.tr("home.open_file"), tint = colors.textSecondary)
            }
            IconButton(onClick = { UriFileHelper.shareFiles(context, outputs) }) {
                Icon(Icons.Outlined.Share, viewModel.tr("home.share_file"), tint = colors.accent)
            }
        }
    }
}
