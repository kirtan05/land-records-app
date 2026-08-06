package com.landrecords.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            val appState = (application as LandRecordsApp).appState
            LandTheme(themeMode = appState.themeMode, lang = appState.lang) {
                Surface(modifier = Modifier.fillMaxSize(), color = Land.colors.bg) {
                    AppNav()
                }
            }
        }
    }
}
