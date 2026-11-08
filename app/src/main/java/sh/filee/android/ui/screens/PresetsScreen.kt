package sh.filee.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.filee.android.core.localization.Localizer
import sh.filee.android.core.presets.ConflictPolicy
import sh.filee.android.core.presets.Preset
import sh.filee.android.core.presets.ResizeMode
import sh.filee.android.ui.MainViewModel
import sh.filee.android.ui.theme.LocalFileeColors
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment.Companion.BottomEnd

@Composable
fun PresetsScreen(viewModel: MainViewModel) {
    val colors = LocalFileeColors.current
    val presets by viewModel.presets.collectAsState()
    val lang = viewModel.settings.collectAsState().value.language
    var editing by remember { mutableStateOf<Preset?>(null) }
    val corner = RoundedCornerShape(colors.cornerRadiusDp.dp)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(viewModel.tr("presets.title"), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            val sections = listOf(
                "presets.section.convert" to presets.filter { !it.isGroup && !it.isTool },
                "presets.section.group" to presets.filter { it.isGroup },
                "presets.section.tool" to presets.filter { it.isTool }
            )
            sections.forEach { (key, list) ->
                if (list.isNotEmpty()) {
                    item { Text(viewModel.tr(key), color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    items(list, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(colors.surface, corner)
                                .border(1.dp, colors.border, corner)
                                .clickable { editing = p.deepCopy() }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(Localizer.presetName(p, lang), color = colors.textPrimary, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            if (!p.isGroup && p.targetFormat != "*") {
                                Text(p.targetFormat.uppercase(), color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { editing = Preset(name = viewModel.tr("presets.new_name"), targetFormat = "png") },
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            modifier = Modifier.align(BottomEnd).padding(20.dp)
        ) { Icon(Icons.Outlined.Add, viewModel.tr("presets.add")) }
    }

    editing?.let { PresetEditorSheet(it, viewModel) { editing = null } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PresetEditorSheet(preset: Preset, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val colors = LocalFileeColors.current
    val lang = viewModel.settings.collectAsState().value.language
    val draft = remember(preset.id) { preset.deepCopy() }
    var name by remember { mutableStateOf(Localizer.presetName(draft, lang)) }
    var target by remember { mutableStateOf(draft.targetFormat) }
    var quality by remember { mutableStateOf(draft.image.quality.toFloat()) }
    var resize by remember { mutableStateOf(draft.image.resize) }
    var resizeValue by remember {
        mutableStateOf(
            when (draft.image.resize) {
                ResizeMode.Percent -> draft.image.resizePercent
                ResizeMode.MaxEdge -> draft.image.maxEdge
                ResizeMode.Width -> draft.image.width
                ResizeMode.Height -> draft.image.height
                else -> 50
            }.toString()
        )
    }
    var gray by remember { mutableStateOf(draft.image.grayscale) }
    var pattern by remember { mutableStateOf(draft.output.fileNamePattern) }
    var conflict by remember { mutableStateOf(draft.output.conflict) }
    val editable = !draft.isGroup && !draft.isTool

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.page
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(viewModel.tr("presets.edit"), color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it }, label = { Text(viewModel.tr("presets.name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (editable) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    target, { target = it.trim().lowercase() },
                    label = { Text(viewModel.tr("presets.target")) },
                    supportingText = { Text("png / jpg / webp / pdf / docx / md / csv / srt / zip …") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("${viewModel.tr("presets.quality")}: ${quality.toInt()}", color = colors.textSecondary, fontSize = 13.sp)
                Slider(quality, { quality = it }, valueRange = 10f..100f)

                Text(viewModel.tr("presets.resize"), color = colors.textSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResizeMode.values().forEach { m ->
                        FilterChip(resize == m, { resize = m }, label = { Text(viewModel.tr("presets.resize.${m.name}")) })
                    }
                }
                if (resize != ResizeMode.None) {
                    OutlinedTextField(
                        resizeValue, { resizeValue = it.filter(Char::isDigit).take(5) },
                        label = { Text(viewModel.tr("presets.resize_value")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(viewModel.tr("presets.grayscale"), color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Switch(gray, { gray = it })
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pattern, { pattern = it },
                label = { Text(viewModel.tr("presets.pattern")) },
                supportingText = { Text(viewModel.tr("presets.pattern_hint")) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text(viewModel.tr("presets.conflict"), color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConflictPolicy.values().forEach { c ->
                    FilterChip(conflict == c, { conflict = c }, label = { Text(viewModel.tr("presets.conflict.${c.name}")) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (viewModel.presets.value.any { it.id == draft.id }) {
                    TextButton(onClick = { viewModel.deletePreset(draft.id); onDismiss() }) {
                        Text(viewModel.tr("presets.delete"), color = colors.danger)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(viewModel.tr("presets.cancel")) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        draft.name = name.trim()
                        if (editable) {
                            draft.targetFormat = target.ifBlank { draft.targetFormat }
                            draft.image.quality = quality.toInt()
                            draft.image.resize = resize
                            val v = resizeValue.toIntOrNull() ?: 0
                            if (v > 0) when (resize) {
                                ResizeMode.Percent -> draft.image.resizePercent = v.coerceIn(1, 100)
                                ResizeMode.MaxEdge -> draft.image.maxEdge = v
                                ResizeMode.Width -> draft.image.width = v
                                ResizeMode.Height -> draft.image.height = v
                                else -> {}
                            }
                            draft.image.grayscale = gray
                        }
                        draft.output.fileNamePattern = pattern.ifBlank { "{name}" }
                        draft.output.conflict = conflict
                        viewModel.savePreset(draft)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                ) { Text(viewModel.tr("presets.save")) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
