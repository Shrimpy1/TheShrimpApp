package com.example.theshrimpapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.theshrimpapp.core.navigation.ShrimpNavHost
import com.example.theshrimpapp.ui.theme.TheShrimpAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TheShrimpAppTheme {
                ShrimpNavHost()
            }
        }
    }
}
