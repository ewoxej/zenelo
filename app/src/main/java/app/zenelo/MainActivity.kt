package app.zenelo

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.zenelo.ui.ZeneloRoot
import app.zenelo.work.LibraryWork
import app.zenelo.ui.theme.ZeneloTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as ZeneloApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark: force light system bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ZeneloTheme {
                ZeneloRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
        LibraryWork.syncServerIfStale(this)
    }

    override fun onStop() {
        container.player.release()
        super.onStop()
    }
}
