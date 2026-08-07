package com.landrecords.app.ui.fetch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * A full-screen layer that swallows every touch and the Back gesture while [active], so the
 * fetch state machine can drive the page without a stray tap or Back derailing it. When not
 * active it's an inert pass-through (used for the error state, where Back should still work).
 */
@Composable
fun InputBlocker(active: Boolean, content: @Composable BoxScope.() -> Unit) {
    if (active) BackHandler(enabled = true) { /* swallow — the run owns the screen */ }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (active) Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        // Consume on the Initial pass so nothing reaches the WebView beneath.
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                } else Modifier,
            ),
        content = content,
    )
}

/**
 * A live step line: the new step slides up + fades in; the old one slides up while shrinking and
 * greying out — so the user sees continuous motion instead of a frozen screen.
 */
@Composable
fun AnimatedStep(text: String, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn()) togetherWith
                (slideOutVertically { -it } + fadeOut() + scaleOut(targetScale = 0.8f))
        },
        label = "step",
        modifier = modifier,
    ) { t ->
        Text(t, style = LandType.metaMono, color = Land.colors.ink2, textAlign = TextAlign.Center)
    }
}

/**
 * Auto-fill buffer: kept deliberately light — the AnyRoR form stays VISIBLE behind it (the
 * enclosing [InputBlocker] already swallows taps), with just a small chip up top so the user
 * knows the app is filling the cascade and shouldn't touch yet.
 */
@Composable
fun BoxScope.FillingIndicator() {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Land.colors.accent))
        Text(
            "ફોર્મ ભરાઈ રહ્યું છે…  ·  Filling the form",
            style = LandType.stamp, color = Land.colors.ink2,
        )
    }
}

/**
 * The buffer shown after the user taps Get Record Detail, until the capture pipeline takes
 * over. Blocks the dead air the user reported between the tap and "Saving…".
 */
@Composable
fun PreparingOverlay(fetching: Boolean) {
    Column(
        Modifier.fillMaxSize().background(Land.colors.bg.copy(alpha = 0.92f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ParcelPlate {
            Text(
                if (fetching) "રેકોર્ડ મેળવી રહ્યા છીએ  ·  Fetching the record…"
                else "ફોર્મ ભરાઈ રહ્યું છે  ·  Filling the form…",
                style = LandType.metaMono, color = Land.colors.ink,
            )
        }
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            color = Land.colors.accent,
            trackColor = Land.colors.surfaceAlt,
            modifier = Modifier.fillMaxWidth().height(LandSize.progressBar).clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "કૃપા કરીને રાહ જુઓ  ·  Please wait",
            style = LandType.stamp, color = Land.colors.ink3, textAlign = TextAlign.Center,
        )
    }
}
