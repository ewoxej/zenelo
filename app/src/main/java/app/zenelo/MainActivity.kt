package app.zenelo

import android.os.Bundle
import android.view.KeyEvent
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

    // While another device is followed, the volume keys set its volume (this phone's is left alone).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_STEP
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_STEP
            else -> return super.onKeyDown(keyCode, event)
        }
        return container.devices.adjustFollowedVolume(delta) || super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        container.player.release()
        super.onStop()
    }

    private companion object {
        const val VOLUME_STEP = 5
    }
}
