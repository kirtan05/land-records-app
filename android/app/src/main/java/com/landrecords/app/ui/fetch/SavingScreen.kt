package com.landrecords.app.ui.fetch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.landrecords.app.R
import com.landrecords.app.ui.components.ParcelPlate
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr

/**
 * The "Saving to your library…" overlay, shown over the Fetch WebView while the real
 * capture runs. [step] ticks the three visible stages as the pipeline advances:
 * 0 reading the page · 1 building the PDF · 2 filing. [error] shows a plain-language
 * failure with a Retry that returns to the CAPTCHA.
 */
@Composable
fun SavingOverlay(
    surveyNo: String,
    village: String,
    destinationPath: String,
    step: Int,
    error: String?,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Land.colors.bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ParcelPlate {
            Text("$surveyNo · $village", style = LandType.metaMono, color = Land.colors.ink)
        }
        Spacer(Modifier.height(20.dp))

        if (error != null) {
            Text(Lr(R.string.save_failed_gu, R.string.save_failed_en), style = LandType.screenTitle, color = Land.colors.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(error, style = LandType.meta, color = Land.colors.ink2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Land.colors.accent)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) { Text(Lr(R.string.action_retry_gu, R.string.action_retry_en), style = LandType.bodyStrong, color = Land.colors.onAccent) }
            return
        }

        Text(Lr(R.string.fetch_saving_gu, R.string.fetch_saving_en), style = LandType.screenTitle, color = Land.colors.ink)
        Spacer(Modifier.height(14.dp))

        val progress = ((step + 1).coerceIn(1, 3)) / 3f
        Box(Modifier.fillMaxWidth().height(LandSize.progressBar).clip(RoundedCornerShape(2.dp)).background(Land.colors.surfaceAlt)) {
            Box(Modifier.fillMaxWidth(progress).height(LandSize.progressBar).background(Land.colors.accent))
        }
        Spacer(Modifier.height(18.dp))

        val steps = listOf(
            Lr(R.string.fetch_step_read_gu, R.string.fetch_step_read_en),
            Lr(R.string.fetch_step_pdf_gu, R.string.fetch_step_pdf_en),
            Lr(R.string.fetch_step_file_gu, R.string.fetch_step_file_en),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { i, label ->
                val done = i <= step
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(5.dp).background(if (done) Land.colors.accent else Land.colors.line))
                    Text(label, style = LandType.meta, color = if (done) Land.colors.ink else Land.colors.ink3)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(destinationPath, style = LandType.stamp, color = Land.colors.ink3)
    }
}
