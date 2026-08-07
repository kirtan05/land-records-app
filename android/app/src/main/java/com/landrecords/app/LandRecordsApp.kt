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
        // pdfbox needs its resource loader wired to a context before any merge.
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        appScope.launch {
            repository.seedIfEmpty()
            // Backfill the already-generated desktop records (pushed to the app's files dir).
            com.landrecords.app.data.storage.SeedImporter.run(this@LandRecordsApp, repository, libraryWriter)
        }
    }
}
