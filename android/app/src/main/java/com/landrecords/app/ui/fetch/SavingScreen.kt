package com.landrecords.app.ui.fetch

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.R
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.ui.components.ParcelPlate
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import com.landrecords.app.ui.theme.Lr
import kotlinx.coroutines.delay

/**
 * The post-CAPTCHA "Saving to your library…" state. Steps tick off as the real work
 * (read page → build PDF → file) completes; on completion it navigates to Survey
 * detail with the new record marked Just added.
 */
@Composable
fun SavingScreen(
    surveyId: Long,
    recordType: RecordType,
    onSaved: () -> Unit,
) {
    val app = landApp()
    val vm: FetchViewModel = viewModel(
        factory = viewModelFactory { initializer { FetchViewModel(app.repository, surveyId) } },
    )
    val info by vm.info.collectAsStateWithLifecycle()

    var step by remember { mutableIntStateOf(0) }
    val progress by animateFloatAsState(
        targetValue = (step + 1) / 3f,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "progress",
    )

    LaunchedEffect(surveyId, recordType) {
        // Advance the three visible steps in step with the (stubbed) capture pipeline.
        delay(500); step = 1
        delay(600); step = 2
        vm.fileResult(surveyId, recordType) {}
        delay(600)
        onSaved()
    }

    val steps = listOf(
        Lr(R.string.fetch_step_read_gu, R.string.fetch_step_read_en),
        Lr(R.string.fetch_step_pdf_gu, R.string.fetch_step_pdf_en),
        Lr(R.string.fetch_step_file_gu, R.string.fetch_step_file_en),
    )

    Column(
        Modifier.fillMaxSize().background(Land.colors.bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ParcelPlate {
            Text(
                "${info?.surveyNo ?: ""} · ${info?.village ?: ""}",
                style = LandType.metaMono, color = Land.colors.ink,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(Lr(R.string.fetch_saving_gu, R.string.fetch_saving_en), style = LandType.screenTitle, color = Land.colors.ink)
        Spacer(Modifier.height(14.dp))

        // Progress bar.
        Box(
            Modifier.fillMaxWidth().height(LandSize.progressBar).clip(RoundedCornerShape(2.dp)).background(Land.colors.surfaceAlt),
        ) {
            Box(Modifier.fillMaxWidth(progress).height(LandSize.progressBar).background(Land.colors.accent))
        }
        Spacer(Modifier.height(18.dp))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { i, label ->
                val done = i <= step
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(5.dp).background(if (done) Land.colors.accent else Land.colors.line),
                    )
                    Text(label, style = LandType.meta, color = if (done) Land.colors.ink else Land.colors.ink3)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Documents/LandRecords/…/Survey ${info?.surveyNo ?: ""}",
            style = LandType.stamp, color = Land.colors.ink3,
        )
    }
}
