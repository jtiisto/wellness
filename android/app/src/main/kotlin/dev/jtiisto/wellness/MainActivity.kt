package dev.jtiisto.wellness

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.jtiisto.wellness.ui.WellnessApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars, no scrims: the graphite canvas runs the full height
        // of the window and the content keeps itself clear of the bars with
        // insets. `auto` flips the bar icons with the system theme, which is
        // also what WellnessTheme follows.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            WellnessApp()
        }
    }
}
