package com.landrecords.app

import android.app.Application
import com.landrecords.app.data.LandRecordsRepository
import com.landrecords.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LandRecordsApp : Application() {

    val repository: LandRecordsRepository by lazy { LandRecordsRepository(AppDatabase.get(this)) }

    val appState: AppState by lazy { AppState(this) }

    val libraryWriter: com.landrecords.app.data.storage.LibraryWriter by lazy {
        com.landrecords.app.data.storage.LibraryWriter(this)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Persist fatal crash stacks to a file so the Settings "Report a problem" button can attach
        // them later (this is what would have captured the OOM). Chain the default handler so the
        // app still crashes/reports normally.
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val f = com.landrecords.app.data.storage.DiagnosticsReport.crashLogFile(this)
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                f.appendText("\n=== $ts · thread ${thread.name} ===\n" + android.util.Log.getStackTraceString(ex) + "\n")
            }
            prevHandler?.uncaughtException(thread, ex)
        }
        // pdfbox needs its resource loader wired to a context before any merge.
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        appScope.launch {
            repository.seedIfEmpty()
            // Backfill the already-generated desktop records (pushed to the app's files dir).
            com.landrecords.app.data.storage.SeedImporter.run(this@LandRecordsApp, repository, libraryWriter)
            // Collapse any duplicate village cards (same place stored under different casing).
            repository.mergeDuplicateProperties()
        }
    }
}
