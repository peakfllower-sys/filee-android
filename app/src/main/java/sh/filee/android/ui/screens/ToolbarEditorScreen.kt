package sh.filee.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.filee.android.core.localization.Localizer
import sh.filee.android.core.presets.Preset
import sh.filee.android.core.profiles.ToolbarProfile
import sh.filee.android.ui.MainViewModel
import sh.filee.android.ui.controls.DonutMenuComposable
import sh.filee.android.ui.theme.LocalFileeColors

/**
 * Donut Toolbar customizer: choose a file-type profile, flip between Front / Back face,
 * tap a slice twice to remove it, tap ✎ to edit its preset, and add presets from the palette.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolbarEditorScreen(viewModel: MainViewModel) {
    val colors = LocalFileeColors.current
    val profiles by viewModel.profiles.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val lang = settings.language

    var profileId by rememberSaveable { mutableStateOf(profiles.firstOrNull()?.id ?: "") }
    var backFace by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Preset?>(null) }

    val profile: ToolbarProfile? = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull()
    val faceIds = profile?.let { if (backFace) it.backPresetIds else it.presetIds } ?: emptyList()
    val items = remember(profile, backFace, presets, lang) {
        profile?.let { viewModel.buildEditorDonutItems(it, backFace) } ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(viewModel.tr("nav.toolbar"), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        // Step 1 — profile selection
        Text(viewModel.tr("toolbar.step1"), color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            profiles.forEach { p ->
                FilterChip(
                    selected = p.id == profile?.id,
                    onClick = { profileId = p.id },
                    label = { Text(Localizer.profileName(p, lang)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accentSoft,
                        selectedLabelColor = colors.accent
                    )
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(viewModel.tr("toolbar.step2"), color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !backFace,
                onClick = { backFace = false },
                label = { Text(viewModel.tr("toolbar.front")) }
            )
            FilterChip(
                selected = backFace,
                onClick = { backFace = true },
                label = { Text(viewModel.tr("toolbar.back")) }
            )
            Spacer(Modifier.weight(1f))
            Text(viewModel.tr("toolbar.slices_count", faceIds.size), color = colors.textSecondary, fontSize = 12.sp)
        }

        // Interactive donut in edit mode
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DonutMenuComposable(
                items = items,
                centerTitle = if (backFace) viewModel.tr("donut.back_face") else viewModel.tr("donut.front_face"),
                centerSubtitle = if (items.isEmpty()) viewModel.tr("donut.empty").take(18) else profile?.let { Localizer.profileName(it, lang) } ?: "",
                isBackFace = backFace,
                isEditMode = true,
                vibrateEnabled = settings.vibrateOnSliceHover,
                outerRadiusDp = 124.dp,
                onSliceInvoked = { item ->
                    profile?.let { viewModel.removePresetFromProfile(it.id, item.id, backFace) }
                },
                onSliceEditRequested = { _, item ->
                    editing = presets.firstOrNull { it.id == item.id }?.deepCopy()
                }
            )
        }

        // Palette
        Text(viewModel.tr("toolbar.palette"), color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (faceIds.size >= ToolbarProfile.MAX_SLICES) {
            Text(viewModel.tr("donut.max_slices"), color = colors.warning, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        val available = presets.filter { it.id !in faceIds }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            available.forEach { preset ->
                AssistChip(
                    enabled = faceIds.size < ToolbarProfile.MAX_SLICES,
                    onClick = { profile?.let { viewModel.addPresetToProfile(it.id, preset.id, backFace) } },
                    label = {
                        Text(
                            Localizer.presetName(preset, lang) +
                                if (!preset.isGroup && preset.targetFormat != "*") " · ${preset.targetFormat.uppercase()}" else ""
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp), tint = colors.accent) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = colors.surface, labelColor = colors.textPrimary)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { viewModel.resetDefaults() }) { Text(viewModel.tr("toolbar.reset")) }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { preset ->
        PresetEditorSheet(
            preset = preset,
            viewModel = viewModel,
            onDismiss = { editing = null }
        )
    }
}
