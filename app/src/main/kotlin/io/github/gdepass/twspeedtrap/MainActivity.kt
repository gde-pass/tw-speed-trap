package io.github.gdepass.twspeedtrap

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import io.github.gdepass.twspeedtrap.ui.AboutScreen
import io.github.gdepass.twspeedtrap.ui.MainScreen
import io.github.gdepass.twspeedtrap.ui.MapScreen
import io.github.gdepass.twspeedtrap.ui.SettingsScreen
import io.github.gdepass.twspeedtrap.ui.theme.TwSpeedTrapTheme
import io.github.gdepass.twspeedtrap.util.LocaleOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Apply the app-language override before any resources are resolved.
        val tag = runBlocking { SettingsRepository(newBase).settings.first().languageTag }
        super.attachBaseContext(LocaleOverride.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TwSpeedTrapTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenMap = { navController.navigate("map") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenAbout = { navController.navigate("about") },
                        )
                    }
                    composable("map") {
                        MapScreen(onBack = { navController.popBackStack() })
                    }
                    composable("about") {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
