package sh.filee.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.filee.android.core.localization.Localizer
import sh.filee.android.core.settings.ThemeMode
import sh.filee.android.core.settings.ThemeStyle
import sh.filee.android.ui.MainViewModel
import sh.filee.android.ui.theme.LocalFileeColors
import sh.filee.android.ui.theme.SoftPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val colors = LocalFileeColors.current
    val s by viewModel.settings.collectAsState()
    val corner = RoundedCornerShape(colors.cornerRadiusDp.dp)

    @Composable
    fun Label(text: String) {
        Text(text, color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(viewModel.tr("nav.theme"), color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Label(viewModel.tr("settings.language"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Localizer.supportedLanguages.forEach { l ->
                FilterChip(s.language == l.code,
                    { viewModel.updateSettings { it.copy(language = l.code) } },
                    label = { Text(l.nativeName) })
            }
        }

        Label(viewModel.tr("settings.theme_style"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeStyle.values().forEach { st ->
                FilterChip(s.theme.style == st,
                    { viewModel.updateSettings { it.copy(theme = it.theme.copy(style = st)) } },
                    label = { Text(viewModel.tr("settings.style.${st.name}")) })
            }
        }

        Label(viewModel.tr("settings.theme_mode"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ThemeMode.System to "system", ThemeMode.Light to "light", ThemeMode.Dark to "dark").forEach { (m, k) ->
                FilterChip(s.theme.mode == m,
                    { viewModel.updateSettings { it.copy(theme = it.theme.copy(mode = m)) } },
                    label = { Text(viewModel.tr("settings.theme_mode.$k")) })
            }
        }

        Label(viewModel.tr("settings.accent"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftPalette.presets.forEach { p ->
                val selected = s.theme.presetKey == p.key
                Box(
                    Modifier.size(40.dp)
                        .background(if (colors.isDark) p.darkColor else p.lightColor, CircleShape)
                        .border(if (selected) 3.dp else 1.dp, if (selected) colors.textPrimary else colors.border, CircleShape)
                        .clickable {
                            viewModel.updateSettings {
                                it.copy(theme = it.theme.copy(presetKey = p.key, accent = p.lightHex))
                            }
                        }
                )
            }
        }

        Label("${viewModel.tr("settings.corner_radius")}: ${s.theme.cornerRadius}")
        Slider(s.theme.cornerRadius.toFloat(),
            { v -> viewModel.updateSettings { it.copy(theme = it.theme.copy(cornerRadius = v.toInt())) } },
            valueRange = 6f..28f)

        Label(viewModel.tr("settings.output_folder"))
        OutlinedTextField(
            value = s.defaultOutputSubfolder,
            onValueChange = { v -> viewModel.updateSettings { it.copy(defaultOutputSubfolder = v) } },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(viewModel.tr("settings.vibrate"), color = colors.textPrimary, modifier = Modifier.weight(1f))
            Switch(s.vibrateOnSliceHover, { v -> viewModel.updateSettings { it.copy(vibrateOnSliceHover = v) } })
        }

        Label(viewModel.tr("settings.engines_title"))
        Column(
            Modifier.fillMaxWidth().background(colors.surface, corner).border(1.dp, colors.border, corner).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            viewModel.getEngineList().forEach { e ->
                val st = e.getStatus()
                Row {
                    Text(e.displayName, color = colors.textPrimary, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Text(if (st.isAvailable) "✓ ${st.version}" else "✗", color = if (st.isAvailable) colors.success else colors.danger, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { viewModel.resetDefaults() }) { Text(viewModel.tr("settings.reset")) }
        Spacer(Modifier.height(12.dp))
        Text(viewModel.tr("settings.about") + " 2.3.0", color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(viewModel.tr("settings.privacy"), color = colors.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }
}
