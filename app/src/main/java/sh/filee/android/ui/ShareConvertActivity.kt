package sh.filee.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.filee.android.core.conversion.JobStatus
import sh.filee.android.ui.screens.ConversionResultCard
import sh.filee.android.ui.screens.DonutConvertPanel
import sh.filee.android.ui.theme.FileeTheme
import sh.filee.android.ui.theme.LocalFileeColors

/**
 * Translucent overlay launched from the Android "Share" / "Open with" menu.
 * Shows the Donut Menu floating over the calling app; afterwards a compact result card.
 */
class ShareConvertActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()
            FileeTheme(themeSettings = settings.theme) {
                val colors = LocalFileeColors.current
                val isOpen by viewModel.isDonutOpen.collectAsState()
                val job by viewModel.activeJob.collectAsState()
                val files by viewModel.selectedFiles.collectAsState()
                val finished = job != null && job!!.status != JobStatus.Running

                Box(
                    Modifier.fillMaxSize().clickable(
                        indication = null,
                        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()
                    ) { if (job?.status != JobStatus.Running) finish() },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp)
                            .background(colors.page, RoundedCornerShape(28.dp))
                            .clickable(enabled = false) {}
                            .padding(8.dp)
                    ) {
                        when {
                            finished -> ConversionResultCard(job!!, viewModel, onDone = { finish() })
                            isOpen -> DonutConvertPanel(viewModel, onDismiss = { finish() })
                            files.isEmpty() -> Text(
                                viewModel.tr("share.loading"),
                                color = colors.textSecondary,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> uris += streamListExtra(intent)
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        }
        if (uris.isEmpty()) {
            finish()
            return
        }
        viewModel.selectUris(this, uris.toList())
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
}
