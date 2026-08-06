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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { repository.seedIfEmpty() }
    }
}
