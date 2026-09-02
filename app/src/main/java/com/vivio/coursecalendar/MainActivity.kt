package com.vivio.coursecalendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vivio.coursecalendar.ui.navigation.AppNavHost
import com.vivio.coursecalendar.ui.theme.VivioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VivioTheme {
                AppNavHost()
            }
        }
    }
}
