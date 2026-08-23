package com.landrecords.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.landrecords.app.ui.nav.AppNav
import com.landrecords.app.ui.theme.Land
import com.landrecords.app.ui.theme.LandTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        resumeFetchQueue()
        setContent {
            val appState = (application as LandRecordsApp).appState
            LandTheme(themeMode = appState.themeMode, lang = appState.lang) {
                Surface(modifier = Modifier.fillMaxSize(), color = Land.colors.bg) {
                    AppNav()
                }
            }
        }
    }

    /**
     * Resume the durable fetch queue (spec §6: "survives reboot and app death; resumes").
     *
     * Triggered from the ACTIVITY, not Application.onCreate: a foreground service may only be
     * started while the app is itself foreground on Android 12+, and Application.onCreate races
     * the activity coming up — a race we lost intermittently, leaving a queue full of pending
     * work idle until the user re-triggered it by hand. onCreate here runs with the activity
     * already foreground, so the start is always permitted.
     */
    private fun resumeFetchQueue() {
        val app = application as LandRecordsApp
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // Migrate to the unified schema automatically, ONCE. It is re-runnable and backs the
            // DB up first, so there is nothing for the user to decide — hence no Settings button.
            // Guarded by a prefs flag so it runs a single time per install rather than every open.
            runCatching {
                val prefs = app.getSharedPreferences("migration", android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean("v1_done", false)) {
                    com.landrecords.app.data.sync.LegacyMigration.backup(app)
                    val report = com.landrecords.app.data.sync.LegacyMigration.run(app)
                    prefs.edit().putBoolean("v1_done", true).apply()
                    android.util.Log.i("LR", "auto-migration done: $report")
                }
            }.onFailure { android.util.Log.w("LR", "auto-migration failed: ${it.message}") }

            // One-time repair for records an earlier build wrongly flipped to "not available"
            // (INTEGRATED NOTFOUND→Empty clobber). Restores docCount from the PDF still on disk —
            // no network, no re-fetch. Guarded like the migration so it runs a single time.
            runCatching {
                val prefs = app.getSharedPreferences("migration", android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean("record_repair_v1", false)) {
                    val restored = app.repository.repairClobberedIntegratedRecords()
                    prefs.edit().putBoolean("record_repair_v1", true).apply()
                    android.util.Log.i("LR", "record repair done: restored=$restored")
                }
            }.onFailure { android.util.Log.w("LR", "record repair failed: ${it.message}") }

            // Resume the durable fetch queue (spec §6). Must run while the app is foreground.
            runCatching {
                val n = com.landrecords.app.fetch.FetchQueue.pendingCount(app)
                android.util.Log.i("LR", "resumeFetchQueue: pending=$n")
                if (n > 0) com.landrecords.app.fetch.FetchService.start(app)
            }.onFailure { android.util.Log.w("LR", "queue resume failed: ${it.message}") }
        }
    }
}
