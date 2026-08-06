package com.landrecords.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.landrecords.app.LandRecordsApp

/** The Application, for reaching the repository and app-state from composables. */
@Composable
fun landApp(): LandRecordsApp = LocalContext.current.applicationContext as LandRecordsApp
