package com.cadence.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cadence.music.ui.AppNav
import com.cadence.music.ui.theme.CadenceTheme

class MainActivity : ComponentActivity() {

    private var openAbout by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openAbout = intent?.getBooleanExtra("open_about", false) == true

        setContent {
            CadenceTheme((applicationContext as CadenceApp).container) {
                AppNav(initialSettingsTab = if (openAbout) 4 else 0) // 4 = About tab
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_about", false)) openAbout = true
    }
}
