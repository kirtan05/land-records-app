package com.landrecords.app.ui.site

import android.Manifest
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.landrecords.app.LandRecordsApp
import com.landrecords.app.data.storage.SiteLocation
import com.landrecords.app.data.storage.SiteStore
import com.landrecords.app.ui.components.PillButton
import com.landrecords.app.ui.components.SquareIconButton
import com.landrecords.app.ui.landApp
import com.landrecords.app.ui.theme.L
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandShape
import com.landrecords.app.ui.theme.LandSize
import com.landrecords.app.ui.theme.LandType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Loads a survey's on-site captures (site.json) and files new GPS fixes / geo-tagged photos. */
class SiteVisitViewModel(
    private val app: LandRecordsApp,
    private val surveyId: Long,
) : ViewModel() {

    data class SiteUi(
        val loaded: Boolean,
        val surveyNo: String,
        val district: String,
        val taluka: String,
        val village: String,
        val villageLatin: String,
        val visits: List<SiteStore.SiteVisit>,
    )

    val ui = MutableStateFlow<SiteUi?>(null)
    val busy = MutableStateFlow(false)
    /** Transient one-line status (permission denied, no fix, saved-without-fix). Cleared on next action. */
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val snap = app.repository.snapshot(surveyId)
            if (snap == null) {
                ui.value = SiteUi(true, "", "", "", "", "", emptyList())
                return@launch
            }
            val (survey, prop) = snap
            val visits = SiteStore.read(app, prop.district, prop.taluka, prop.village, survey.surveyNo)
            ui.value = SiteUi(
                loaded = true,
                surveyNo = survey.surveyNo,
                district = prop.district, taluka = prop.taluka, village = prop.village,
                villageLatin = prop.village,
                visits = visits,
            )
        }
    }

    private suspend fun reload() {
        val u = ui.value ?: return
        ui.value = u.copy(visits = SiteStore.read(app, u.district, u.taluka, u.village, u.surveyNo))
    }

    /** Save the current GPS fix as a location-only visit. Assumes location permission is granted. */
    fun saveCurrentLocation(context: Context) {
        val u = ui.value ?: return
        viewModelScope.launch {
            busy.value = true; message.value = null
            val loc = SiteLocation.current(context)
            if (loc == null) {
                message.value = "__NOFIX__"
            } else {
                SiteStore.append(
                    app, u.district, u.taluka, u.village, u.surveyNo,
                    SiteStore.SiteVisit(
                        ts = System.currentTimeMillis(),
                        lat = loc.latitude, lng = loc.longitude,
                        accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    ),
                )
                reload()
            }
            busy.value = false
        }
    }

    /** A photo was captured to [photoPath]; grab a fix, geo-tag the JPEG, then file the visit. */
    fun onPhotoCaptured(context: Context, ts: Long, photoPath: String) {
        val u = ui.value ?: return
        viewModelScope.launch {
            busy.value = true; message.value = null
            val loc = SiteLocation.current(context)
            withContext(Dispatchers.IO) {
                if (loc != null) SiteStore.writeExifGps(photoPath, loc.latitude, loc.longitude, ts)
            }
            SiteStore.append(
                app, u.district, u.taluka, u.village, u.surveyNo,
                SiteStore.SiteVisit(
                    ts = ts,
                    lat = loc?.latitude, lng = loc?.longitude,
                    accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
                    photo = File(photoPath).name,
                ),
            )
            reload()
            if (loc == null) message.value = "__SAVED_NOFIX__"
            busy.value = false
        }
    }

    fun deleteVisit(v: SiteStore.SiteVisit) {
        val u = ui.value ?: return
        viewModelScope.launch {
            SiteStore.delete(app, u.district, u.taluka, u.village, u.surveyNo, v.ts)
            reload()
        }
    }

    fun setMessage(text: String?) { message.value = text }
}

private data class PendingPhoto(val ts: Long, val file: File)

/**
 * On-site capture for one survey: save the plot's GPS fix, take a geo-tagged photo, and review what
 * was captured. Basic functional layout on the Cadastre design system — the visuals will be reworked.
 */
@Composable
fun SiteVisitScreen(
    surveyId: Long,
    onBack: () -> Unit,
) {
    val app = landApp()
    val context = LocalContext.current
    val vm: SiteVisitViewModel = viewModel(
        factory = viewModelFactory { initializer { SiteVisitViewModel(app, surveyId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val rawMessage by vm.message.collectAsStateWithLifecycle()

    var pendingPhoto by remember { mutableStateOf<PendingPhoto?>(null) }

    val locationPerms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val photoPerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val p = pendingPhoto
        pendingPhoto = null
        if (success && p != null) vm.onPhotoCaptured(context, p.ts, p.file.absolutePath)
    }

    fun startPhoto() {
        val u = ui ?: return
        val ts = System.currentTimeMillis()
        val file = SiteStore.newPhotoFile(context, u.district, u.taluka, u.village, u.surveyNo, ts)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingPhoto = PendingPhoto(ts, file)
        takePicture.launch(uri)
    }

    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) vm.saveCurrentLocation(context) else vm.setMessage("__LOC_DENIED__")
    }
    val requestPhoto = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Camera is required for the photo; location is optional (no fix → an un-geotagged photo).
        if (result[Manifest.permission.CAMERA] == true) startPhoto() else vm.setMessage("__CAM_DENIED__")
    }

    // Resolve the transient status codes to localized copy at render time (so language mode applies).
    val statusText: String? = when (rawMessage) {
        "__LOC_DENIED__" -> L("સ્થાનની પરવાનગી નથી", "Location permission denied")
        "__CAM_DENIED__" -> L("કૅમેરાની પરવાનગી નથી", "Camera permission denied")
        "__NOFIX__" -> L("સ્થાન મળ્યું નહીં — ખુલ્લામાં ફરી પ્રયાસ કરો", "No location fix — try again in the open")
        "__SAVED_NOFIX__" -> L("ફોટો સચવાયો — સ્થાન વગર", "Photo saved — without a location")
        null -> null
        else -> rawMessage
    }

    Column(Modifier.fillMaxSize().background(Land.colors.bg)) {
        // ── Header ──────────────────────────────────────────────────────────────────────
        Column(Modifier.background(Land.colors.surface).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareIconButton(Icons.Outlined.ChevronLeft, onBack, "Back", size = LandSize.backButton)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(L("સાઇટ મુલાકાત", "Site visit"), style = LandType.bodyStrong, color = Land.colors.ink)
                    val sub = ui?.let { "${it.surveyNo} · ${it.villageLatin}" } ?: ""
                    Text(sub, style = LandType.label, color = Land.colors.ink3)
                }
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
            // Two field actions.
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WideAction(
                    text = L("હાલનું સ્થાન સાચવો", "Save current location"),
                    icon = Icons.Outlined.MyLocation,
                    filled = true,
                    enabled = !busy && ui?.loaded == true,
                    onClick = { requestLocation.launch(locationPerms) },
                )
                WideAction(
                    text = L("જીઓ-ટૅગ ફોટો લો", "Take geo-tagged photo"),
                    icon = Icons.Outlined.PhotoCamera,
                    filled = false,
                    enabled = !busy && ui?.loaded == true,
                    onClick = { requestPhoto.launch(photoPerms) },
                )
                if (busy) {
                    Text(L("સ્થાન મેળવી રહ્યાં છીએ…", "Getting location…"), style = LandType.metaMono, color = Land.colors.ink3)
                }
                if (statusText != null) {
                    Text(statusText, style = LandType.meta, color = Land.colors.accent)
                }
            }
            HorizontalDivider(thickness = 1.dp, color = Land.colors.line)
        }

        // ── Body ────────────────────────────────────────────────────────────────────────
        val visits = ui?.visits ?: emptyList()
        when {
            ui?.loaded != true -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(L("ખૂલી રહ્યું છે…", "Opening…"), style = LandType.metaMono, color = Land.colors.ink3)
            }
            visits.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    L("હજી કોઈ સાઇટ મુલાકાત નથી", "No site visits yet"),
                    style = LandType.bodyStrong, color = Land.colors.ink2, textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visits, key = { it.ts }) { v ->
                    VisitRow(
                        visit = v,
                        onViewPhoto = {
                            val u = ui ?: return@VisitRow
                            val path = SiteStore.filePath(context, u.district, u.taluka, u.village, u.surveyNo, v.photo)
                            if (!openImage(context, path)) {
                                Toast.makeText(context, "Can't open this photo", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { vm.deleteVisit(v) },
                    )
                }
            }
        }
    }
}

/** One captured visit: time, coordinates (mono), accuracy, an optional photo, and a delete. */
@Composable
private fun VisitRow(
    visit: SiteStore.SiteVisit,
    onViewPhoto: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LandShape.card)
            .background(Land.colors.surface)
            .border(1.dp, Land.colors.line, LandShape.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(formatTime(visit.ts), style = LandType.metaMono, color = Land.colors.ink3)
        // Coordinates are field data → shown untranslated; unknown renders "—".
        val coords = if (visit.lat != null && visit.lng != null) {
            String.format(Locale.US, "%.5f, %.5f", visit.lat, visit.lng)
        } else "—"
        Text(coords, style = LandType.metaMono, color = Land.colors.ink)
        val acc = visit.accuracy?.let { "±${it.toInt()} m" } ?: "—"
        Text("${L("ચોકસાઈ", "Accuracy")} $acc", style = LandType.meta, color = Land.colors.ink2)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (visit.photo.isNotBlank()) {
                PillButton(L("ફોટો જુઓ", "View photo"), onClick = onViewPhoto)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(LandShape.pill)
                    .border(1.dp, if (confirmDelete) Land.colors.accent else Land.colors.line, LandShape.pill)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (confirmDelete) onDelete() else confirmDelete = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (confirmDelete) L("કાઢી નાખું?", "Delete?") else L("કાઢી નાખો", "Delete"),
                    style = LandType.meta,
                    color = if (confirmDelete) Land.colors.accent else Land.colors.ink3,
                )
            }
        }
    }
}

/** Full-width action button: [filled] = accent fill, else 1dp outline. */
@Composable
private fun WideAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (filled) Land.colors.accent else Land.colors.surface
    val fg = when {
        !enabled -> Land.colors.ink3
        filled -> Land.colors.onAccent
        else -> Land.colors.ink
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(LandSize.field)
            .clip(LandShape.field)
            .background(if (enabled) bg else Land.colors.surfaceAlt)
            .then(if (filled) Modifier else Modifier.border(1.dp, Land.colors.line, LandShape.field))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.height(20.dp))
            Text(text, style = LandType.bodyStrong, color = fg)
        }
    }
}

/** Open a stored JPEG in an external viewer via the app FileProvider. Returns false on any failure. */
private fun openImage(context: Context, path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    val f = File(path)
    if (!f.exists()) return false
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(intent); true }.getOrDefault(false)
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(ts)
