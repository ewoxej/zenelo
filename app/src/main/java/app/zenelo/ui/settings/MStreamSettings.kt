package app.zenelo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.settings.TranscodeMode
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.library.documentPath
import app.zenelo.mstream.MStreamFiles
import app.zenelo.ui.components.ZeneloSlider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Route
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import kotlin.math.roundToInt
import app.zenelo.mstream.MStreamSync
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.SectionHeader
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.theme.ZeneloColors
import app.zenelo.work.LibraryWork
import kotlinx.coroutines.launch

/**
 * The mStream server: log in (address, user name, password), then its library joins ours
 * (synced in the background); "Sync now" and "Log out".
 */
@Composable
fun MStreamSettings(onMessage: (String) -> Unit) {
    val container = appContainer()
    val settingsFlow = remember { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = ZeneloSettings())
    val account = settings.mstream
    if (account == null) LoginForm(onMessage) else Connected(onMessage)
}

@Composable
private fun LoginForm(onMessage: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionHeader("Connect")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Your mStream server's library joins this one: stream its tracks, their covers and lyrics.",
            style = MaterialTheme.typography.bodySmall,
            color = ZeneloColors.TextSecondary,
        )
        Field(url, { url = it }, "Server address", "192.168.1.10:3000", KeyboardType.Uri)
        Field(user, { user = it }, "User name", "", KeyboardType.Text)
        Field(password, { password = it }, "Password", "", KeyboardType.Password, secret = true)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.Danger) }
        Button(
            enabled = !busy && url.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { container.mstream.login(url, user.trim(), password) }
                        .onSuccess { account ->
                            container.settings.setMStream(account)
                            LibraryWork.syncServer(context, force = true)
                            onMessage("Connected to ${account.url}")
                        }
                        .onFailure {
                            android.util.Log.w("Zenelo", "mStream login to $url as ${user.trim()} failed", it)
                            error = it.message ?: "Couldn't connect"
                        }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = ZeneloColors.Mustard, contentColor = ZeneloColors.OnMustard),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(color = ZeneloColors.OnMustard, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Connect")
        }
        Text(
            "No users set up on the server? Leave the name and password empty.",
            style = MaterialTheme.typography.bodySmall,
            color = ZeneloColors.TextMuted,
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, hint: String, type: KeyboardType, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        placeholder = if (hint.isEmpty()) null else ({ Text(hint) }),
        // No auto-correct / capitals: "ilya" must not turn into "Ilya" (the server's names are case-sensitive).
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZeneloColors.Mustard, cursorColor = ZeneloColors.Mustard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Connected(onMessage: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsFlow = remember { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = ZeneloSettings())
    val account = settings.mstream ?: return
    val countFlow = remember { container.db.remoteTracks().observeCount() }
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    val state by container.mstreamSync.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }

    SectionHeader("Server")
    ListRow(
        title = account.url.removePrefix("http://").removePrefix("https://"),
        subtitle = if (account.username.isEmpty()) "Connected" else "Connected as ${account.username}",
        leading = { IconTile(Icons.Outlined.Cloud, ZeneloColors.Celadon, ZeneloColors.CeladonTint) },
    )
    ListRow(
        title = "Sync now",
        subtitle = when (val s = state) {
            is MStreamSync.State.Running -> "Syncing… ${s.tracks} tracks"
            is MStreamSync.State.Failed -> "Last sync failed: ${s.message}"
            MStreamSync.State.Idle -> "$count tracks in the library"
        },
        onClick = { LibraryWork.syncServer(context, force = true) },
        leading = { IconTile(Icons.Outlined.Sync, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
        trailing = {
            if (state is MStreamSync.State.Running) {
                CircularProgressIndicator(color = ZeneloColors.Mustard, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(18.dp))
            }
        },
    )
    StreamingSettings(settings)
    DownloadSettings(settings, onMessage)
    AutoDjSettings(settings)
    ListRow(
        title = "Log out",
        subtitle = "The server's tracks leave the library",
        onClick = { confirmLogout = true },
        leading = { IconTile(Icons.AutoMirrored.Outlined.Logout, ZeneloColors.Danger, ZeneloColors.Danger.copy(alpha = 0.14f)) },
    )
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = ZeneloColors.Card,
            title = { Text("Log out of mStream?") },
            text = { Text("Its tracks leave the library. Favorites and playlists keep them for when you log in again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        container.settings.setMStream(null)
                        container.mstreamSync.forget()
                        onMessage("Logged out of mStream")
                    }
                }) { Text("Log out", color = ZeneloColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

/** Transcoding: when, which codec and bitrate. */
@Composable
private fun StreamingSettings(settings: ZeneloSettings) {
    val repo = appContainer().settings
    val scope = rememberCoroutineScope()
    SectionHeader("Streaming")
    ChoiceRow("Transcode", settings.transcodeMode, TranscodeMode.entries, { it.label }) {
        scope.launch { repo.setTranscode(it, settings.transcodeCodec, settings.transcodeBitrate) }
    }
    if (settings.transcodeMode != TranscodeMode.OFF) {
        ChoiceRow("Format", settings.transcodeCodec, listOf("mp3", "opus", "aac"), { codecLabel(it) }) {
            scope.launch { repo.setTranscode(settings.transcodeMode, it, settings.transcodeBitrate) }
        }
        ChoiceRow("Bitrate", settings.transcodeBitrate, listOf("64k", "96k", "128k", "192k"), { it.replace("k", " kbps") }) {
            scope.launch { repo.setTranscode(settings.transcodeMode, settings.transcodeCodec, it) }
        }
    }
    Text(
        "Transcoded on the server to save data; downloads are always the original files.",
        style = MaterialTheme.typography.bodySmall,
        color = ZeneloColors.TextMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** Where downloads go, their progress, and the queue auto-download (cache). */
@Composable
private fun DownloadSettings(settings: ZeneloSettings, onMessage: (String) -> Unit) {
    val container = appContainer()
    val repo = container.settings
    val scope = rememberCoroutineScope()
    val downloads = container.mstreamDownloads
    val pending by downloads.pending.collectAsStateWithLifecycle(initialValue = 0)
    val failed by downloads.failed.collectAsStateWithLifecycle(initialValue = 0)
    val current by downloads.current.collectAsStateWithLifecycle()
    val folder = settings.downloadDir ?: MStreamFiles.defaultDownloadDir().path
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let(::documentPath)
        if (uri != null && path == null) onMessage("Pick a folder on the device's storage")
        if (path != null) scope.launch { repo.setDownloadDir(path) }
    }

    SectionHeader("Downloads")
    ListRow(
        title = "Folder",
        subtitle = folder,
        onClick = { picker.launch(null) },
        leading = { IconTile(Icons.Outlined.Folder, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
        trailing = {
            if (settings.downloadDir != null) {
                TextButton(onClick = { scope.launch { repo.setDownloadDir(null) } }) { Text("Default") }
            }
        },
    )
    ListRow(
        title = when {
            current != null -> "Downloading ${current!!.substringAfterLast('/')}"
            pending > 0 -> "$pending waiting"
            else -> "No downloads running"
        },
        subtitle = if (failed > 0) "$failed failed · tap to clear" else "\"Download\" in a track's, folder's or selection's menu",
        onClick = { if (failed > 0) scope.launch { downloads.clearFailed() } },
        leading = { IconTile(Icons.Outlined.Download, ZeneloColors.Celadon, ZeneloColors.CeladonTint) },
        trailing = {
            if (current != null) CircularProgressIndicator(color = ZeneloColors.Celadon, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(18.dp))
        },
    )

    SectionHeader("Queue auto-download")
    fun save(enabled: Boolean = settings.autoDownload, ahead: Int = settings.autoDownloadAhead, wifi: Boolean = settings.autoDownloadWifiOnly, limit: Int = settings.cacheLimitMb) {
        scope.launch { repo.setAutoDownload(enabled, ahead, wifi, limit) }
    }
    SwitchRow("Download the next tracks", "Server tracks in the queue, ahead of playing them", settings.autoDownload) { save(enabled = it) }
    if (settings.autoDownload) {
        var ahead by remember(settings.autoDownloadAhead) { mutableFloatStateOf(settings.autoDownloadAhead.toFloat()) }
        Text("Up to ${ahead.roundToInt()} tracks ahead", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        ZeneloSlider(ahead, { ahead = it }, 1f..20f, Modifier.padding(horizontal = 20.dp), onValueChangeFinished = { save(ahead = ahead.roundToInt()) })
        SwitchRow("Wi-Fi only", "No auto-downloads on mobile data", settings.autoDownloadWifiOnly) { save(wifi = it) }
        var limit by remember(settings.cacheLimitMb) { mutableFloatStateOf(settings.cacheLimitMb.toFloat()) }
        Text("Cache up to ${formatMb(limit.roundToInt())}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        ZeneloSlider(limit, { limit = (it / 256).roundToInt() * 256f }, 256f..8192f, Modifier.padding(horizontal = 20.dp), onValueChangeFinished = { save(limit = limit.roundToInt()) })
    }
    var cacheBytes by remember { mutableLongStateOf(container.mstreamFiles.cacheBytes()) }
    ListRow(
        title = "Clear the cache",
        subtitle = "${formatMb((cacheBytes / (1024 * 1024)).toInt())} used · downloads aren't touched",
        onClick = {
            container.mstreamFiles.clearCache()
            cacheBytes = container.mstreamFiles.cacheBytes()
            onMessage("Cache cleared")
        },
        leading = { IconTile(Icons.Outlined.DeleteSweep, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
    )
}

/** Auto DJ (on / off and how it picks) and the way to Sonic Path. */
@Composable
private fun AutoDjSettings(settings: ZeneloSettings) {
    val container = appContainer()
    val repo = container.settings
    val scope = rememberCoroutineScope()
    val dj = settings.autoDj
    fun save(value: app.zenelo.data.settings.AutoDjSettings) = scope.launch { repo.setAutoDj(value) }

    SectionHeader("Auto DJ")
    SwitchRow("Auto DJ", "When the queue reaches its last track, the server picks more", dj.enabled) { on ->
        container.autoDj.setEnabled(on) { container.player.playFiles(it) }
    }
    ChoiceRow("Minimum rating", dj.minRating, listOf(0, 2, 4, 6, 8, 10), { if (it == 0) "Any track" else "Rated ${it / 2}+ of 5" }) { save(dj.copy(minRating = it)) }
    ChoiceRow("Tracks per pick", dj.batch, listOf(1, 3, 5, 10), { "$it" }) { save(dj.copy(batch = it)) }
    SwitchRow("Similar artists", "Prefer artists like the playing one (Last.fm on the server)", dj.similarArtists) { save(dj.copy(similarArtists = it)) }
    SwitchRow("Keep the tempo", "BPM close to the session's, or half / double", dj.bpmContinuity) { save(dj.copy(bpmContinuity = it)) }
    if (dj.bpmContinuity) {
        var tolerance by remember(dj.bpmTolerance) { mutableFloatStateOf(dj.bpmTolerance.toFloat()) }
        Text("Within ±${tolerance.roundToInt()} BPM", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        ZeneloSlider(tolerance, { tolerance = it }, 1f..20f, Modifier.padding(horizontal = 20.dp), onValueChangeFinished = { save(dj.copy(bpmTolerance = tolerance.roundToInt())) })
    }
    SwitchRow("Harmonic mixing", "Keys next to the session's on the Camelot wheel", dj.harmonicMixing) { save(dj.copy(harmonicMixing = it)) }
    SwitchRow("Sounds like", "Tracks whose sound is close to the last picks (server's audio analysis)", dj.sonic) { save(dj.copy(sonic = it)) }
    if (dj.sonic) {
        var similarity by remember(dj.sonicMinSimilarity) { mutableFloatStateOf(dj.sonicMinSimilarity) }
        Text("Similarity at least ${(similarity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        ZeneloSlider(similarity, { similarity = (it * 20).roundToInt() / 20f }, 0.3f..0.9f, Modifier.padding(horizontal = 20.dp), onValueChangeFinished = { save(dj.copy(sonicMinSimilarity = similarity)) })
    }
    ListRow(
        title = "Sonic Path",
        subtitle = "A path of tracks morphing from one sound to another",
        onClick = { container.sonicPath.show() },
        leading = { IconTile(Icons.Outlined.Route, ZeneloColors.Celadon, ZeneloColors.CeladonTint) },
    )
}

/** Only MP3 can be seeked in a stream the server is still transcoding (no length yet). */
private fun codecLabel(codec: String): String = when (codec) {
    "mp3" -> "MP3 · seekable"
    "opus" -> "Opus · smaller, no seeking"
    else -> "AAC · no seeking"
}

private fun formatMb(mb: Int): String = if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"
