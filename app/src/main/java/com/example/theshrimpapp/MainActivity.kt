package com.example.theshrimpapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.example.theshrimpapp.core.navigation.ShrimpNavHost
import com.example.theshrimpapp.ui.theme.TheShrimpAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingRoute.value = intent?.getStringExtra("navigate_to")

        setContent {
            TheShrimpAppTheme {
                ShrimpNavHost(
                    pendingRoute = pendingRoute.value,
                    onRouteConsumed = { pendingRoute.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("navigate_to")?.let { route ->
            pendingRoute.value = route
        }
    }
}
