package io.github.gdepass.twspeedtrap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.gdepass.twspeedtrap.ui.MainScreen
import io.github.gdepass.twspeedtrap.ui.theme.TwSpeedTrapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TwSpeedTrapTheme {
                MainScreen()
            }
        }
    }
}
