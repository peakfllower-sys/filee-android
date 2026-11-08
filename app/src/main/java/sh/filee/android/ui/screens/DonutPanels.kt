package sh.filee.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.filee.android.UriFileHelper
import sh.filee.android.core.conversion.ConversionJobState
import sh.filee.android.core.conversion.JobStatus
import sh.filee.android.core.formats.FormatRegistry
import sh.filee.android.core.localization.Localizer
import sh.filee.android.ui.MainViewModel
import sh.filee.android.ui.controls.DonutMenuComposable
import sh.filee.android.ui.theme.LocalFileeColors

/**
 * The donut conversion panel shown after files are picked (bottom sheet in [sh.filee.android.ui.MainActivity])
 * or shared from another app (floating overlay in [sh.filee.android.ui.ShareConvertActivity]).
 */
@Composable
fun DonutConvertPanel(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalFileeColors.current
    val files by viewModel.selectedFiles.collectAsState()
    val isBack by viewModel.isBackFace.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val job by viewModel.activeJob.collectAsState()

    val profile = remember(files, profiles) { viewModel.activeProfileForSelectedFiles() }
    val items = remember(files, isBack, presets, profiles, settings.language) {
        viewModel.buildDonutItemsForSelection(isBack)
    }
    val hasBack = profile?.backPresetIds?.isNotEmpty() == true
    val running = job?.status == JobStatus.Running

    val summary = remember(files, settings.language) {
        val formats = files.mapNotNull { FormatRegistry.detect(it.name)?.displayName }.distinct()
        if (formats.size == 1) viewModel.tr("donut.files_same", files.size, formats.first())
        else viewModel.tr("donut.files_mixed", files.size)
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // File summary badge
        Row(
            modifier = Modifier
                .background(colors.accentSoft, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(summary, color = colors.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            profile?.let {
                Text(
                    "  ·  " + Localizer.profileName(it, settings.language),
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )
            }
        }
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                files.joinToString("、") { it.name },
                color = colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        DonutMenuComposable(
            items = items,
            centerTitle = if (isBack) viewModel.tr("donut.back_face") else viewModel.tr("donut.center_drag"),
            centerSubtitle = viewModel.tr("donut.cancel"),
            centerFlipHint = null,
            isBackFace = isBack,
            progress = if (running) job?.progress else null,
            vibrateEnabled = settings.vibrateOnSliceHover,
            outerRadiusDp = 128.dp,
            onSliceInvoked = { item -> if (!running) viewModel.invokePresetById(item.id) },
            onCenterTapped = { if (!running) onDismiss() }
        )

        if (running) {
            val j = job!!
            Text(
                viewModel.tr("toast.converting", j.sourceFiles.size, j.presetDisplayName),
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { j.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(0.7f),
                color = colors.accent,
                trackColor = colors.surfaceAlt
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasBack) {
                    OutlinedButton(onClick = { viewModel.flipDonutFace() }) {
                        Text(if (isBack) viewModel.tr("donut.flip_to_front") else viewModel.tr("donut.flip_to_back"))
                    }
                }
                TextButton(onClick = onDismiss) { Text(viewModel.tr("presets.cancel")) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Compact card shown when a conversion job has finished, with Open / Share / Done actions.
 */
@Composable
fun ConversionResultCard(
    job: ConversionJobState,
    viewModel: MainViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalFileeColors.current
    val context = LocalContext.current
    val outputs = job.allOutputFiles
    val failed = job.results.count { it.error != null }
    val ok = job.status == JobStatus.Completed || (job.status == JobStatus.CompletedWithErrors && outputs.isNotEmpty())

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(colors.cornerRadiusDp.dp))
            .border(1.dp, colors.border, RoundedCornerShape(colors.cornerRadiusDp.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (ok) colors.success else colors.danger,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        job.status == JobStatus.Cancelled -> viewModel.tr("toast.cancelled")
                        failed > 0 && outputs.isNotEmpty() -> viewModel.tr("toast.done_errors", failed, job.sourceFiles.size)
                        ok -> viewModel.tr("toast.done")
                        else -> viewModel.tr("toast.failed")
                    },
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    job.presetDisplayName + if (outputs.isNotEmpty()) " · " + outputs.joinToString("、") { it.name } else "",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                job.results.firstOrNull { it.error != null }?.error?.let { err ->
                    Text(viewModel.tr(err), color = colors.danger, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (outputs.isNotEmpty()) {
                OutlinedButton(onClick = { UriFileHelper.openFile(context, outputs.first()) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(viewModel.tr("home.open_file"))
                }
                OutlinedButton(onClick = { UriFileHelper.shareFiles(context, outputs) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(viewModel.tr("home.share_file"))
                }
            }
            Button(
                onClick = onDone,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
            ) { Text(viewModel.tr("common.done")) }
        }
    }
}

/** Small round coloured dot used in lists. */
@Composable
fun Dot(color: androidx.compose.ui.graphics.Color, size: Int = 10) {
    Spacer(
        Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}

/** Centered empty-state text. */
@Composable
fun EmptyHint(text: String) {
    val colors = LocalFileeColors.current
    Text(
        text,
        color = colors.textSecondary,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp)
    )
}
