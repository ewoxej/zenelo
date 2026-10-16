package app.zenelo.ui.nowplaying

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.player.SoundTap
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The visualizer, full screen over everything: the web player's butterchurn presets and their
 * music drive (`assets/viz`, copied from the mStream fork's webapp) in a WebView, hearing the
 * player's output through [SoundTap]. Tap for the controls (they hide again), swipe for the next /
 * previous preset; a new preset every 20 s of music unless that's off.
 */
@Composable
fun VisualizerOverlay(onDismiss: () -> Unit) {
    // Drawn in the activity's own window (the root puts it over everything): in a dialog's window
    // the WebView's canvases never showed.
    BackHandler(onBack = onDismiss)
    // The picture gets the whole screen: no status / navigation bars (a swipe shows them).
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // Nothing below takes the touches.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) { Content(onDismiss) }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Content(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val player by container.player.state.collectAsStateWithLifecycle()
    val session by container.audioSession.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.RECORD_AUDIO) }

    var preset by remember { mutableStateOf(PresetInfo(-1, 0, "")) }
    val bridge = remember { VizBridge { preset = it; lastPreset = it.index } }
    bridge.playing = player.isPlaying && player.remoteDevice == null
    var deaf by remember { mutableStateOf(false) }
    DisposableEffect(session, granted) {
        val tap = if (granted && session != 0) runCatching { SoundTap(session) }.getOrNull() else null
        deaf = granted && session != 0 && tap == null
        bridge.tap = tap
        onDispose {
            bridge.tap = null
            tap?.close()
        }
    }

    var web by remember { mutableStateOf<WebView?>(null) }
    fun js(code: String) = web?.evaluateJavascript(code, null)
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var cycle by remember { mutableStateOf(cycleOn) }
    var showTrack by remember { mutableStateOf(trackOn) }
    LaunchedEffect(controls, touched) {
        if (controls) {
            delay(CONTROLS_MS)
            controls = false
        }
    }

    if (granted) {
        @SuppressLint("SetJavaScriptEnabled")
        AndroidView(
            factory = { ctx ->
                if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(bridge, "Zenelo")
                    // The page's errors (no WebGL 2, a preset that won't compile) in `adb logcat -s Zenelo`.
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.i("Zenelo", "viz: ${message.message()} (${message.sourceId().substringAfterLast('/')}:${message.lineNumber()})")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("VIZ.setCycle($cycleOn); VIZ.start($lastPreset)", null)
                        }
                    }
                    loadUrl("file:///android_asset/viz/index.html")
                    web = this
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // Taps show / hide the controls, horizontal swipes change the preset.
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { controls = !controls; touched++ } }
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (abs(dx) > 80.dp.toPx()) {
                            js(if (dx < 0) "VIZ.next()" else "VIZ.prev()")
                            // The new preset's name shows for a moment.
                            controls = true
                            touched++
                        }
                    },
                ) { _, amount -> dx += amount }
            },
    )

    val problem = when {
        !granted -> "The visualizer listens to what this player plays. Android allows that only with the microphone permission — nothing is recorded or leaves the phone."
        player.remoteDevice != null -> "Playing on ${player.remoteDevice?.name}: the visualizer hears only this phone."
        session == 0 || player.mediaId == null -> "Play something: the picture follows the music."
        deaf -> "Android won't let the app listen to its player on this device."
        else -> null
    }
    if (problem != null) {
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(problem, style = MaterialTheme.typography.bodyMedium, color = ZeneloColors.TextSecondary)
            if (!granted) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow", color = ZeneloColors.Mustard) }
                    // Refused for good: only the app's settings can change it.
                    if (asked) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }) { Text("App settings", color = ZeneloColors.TextSecondary) }
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = ZeneloColors.TextSecondary) }
                }
            }
        }
    }

    AnimatedVisibility(controls || !granted, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .safeDrawingPadding().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 24.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "VISUALIZER · PRESET ${if (preset.count > 0) preset.index + 1 else "–"} / ${preset.count.takeIf { it > 0 } ?: "–"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZeneloColors.TextMuted,
                    )
                    Text(preset.name, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Control(Icons.Rounded.ChevronLeft, "Previous preset") { js("VIZ.prev()"); touched++ }
                    Control(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "Pause" else "Play", big = true) {
                        container.player.togglePlay(); touched++
                    }
                    Control(Icons.Rounded.ChevronRight, "Next preset") { js("VIZ.next()"); touched++ }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Toggle(Icons.Outlined.Timer, "Every 20 s", cycle) { cycle = !cycle; cycleOn = cycle; js("VIZ.setCycle($cycle)"); touched++ }
                    Spacer(Modifier.width(8.dp))
                    Toggle(Icons.Outlined.Subtitles, "Show track", showTrack) { showTrack = !showTrack; trackOn = showTrack; touched++ }
                }
            }
        }
    }

    // The track, small in a corner, while the controls are away.
    if (showTrack && !controls && problem == null && player.title != null) {
        Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(20.dp)) {
            Text(player.title.orEmpty(), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            player.artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Control(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (big) 64.dp else 48.dp).clip(RoundedCornerShape(50))
            .background(if (big) ZeneloColors.Mustard else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (big) ZeneloColors.Background else Color.White, modifier = Modifier.size(if (big) 32.dp else 26.dp))
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(9.dp))
            .background(if (on) ZeneloColors.CeladonTint else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) ZeneloColors.Celadon else Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) ZeneloColors.Celadon else Color.White.copy(alpha = 0.8f))
    }
}

private data class PresetInfo(val index: Int, val count: Int, val name: String)

/** The page's way to the app (`Zenelo` in `assets/viz/viz.js`); called on the WebView's own thread. */
private class VizBridge(private val onPreset: (PresetInfo) -> Unit) {
    @Volatile
    var tap: SoundTap? = null

    @Volatile
    var playing = false

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun frame(): String = runCatching { tap?.frame() }.getOrNull().orEmpty()

    @JavascriptInterface
    fun playing(): Boolean = playing

    @JavascriptInterface
    fun onPreset(index: Int, count: Int, name: String) {
        main.post { onPreset(PresetInfo(index, count, name)) }
    }
}

// Kept for the app's lifetime: the next opening goes on from here.
private var lastPreset = -1
private var cycleOn = true
private var trackOn = true

private const val CONTROLS_MS = 4000L
