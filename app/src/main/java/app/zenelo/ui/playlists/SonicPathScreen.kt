package app.zenelo.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.PlaylistEntity
import app.zenelo.data.db.TrackEntity
import app.zenelo.library.AudioFile
import app.zenelo.mstream.MStreamSonicPath
import app.zenelo.ui.components.BackButton
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.ScreenTitle
import app.zenelo.ui.components.SectionHeader
import app.zenelo.ui.components.ZeneloSlider
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.formatDuration
import app.zenelo.ui.library.LibraryRow
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Sonic Path (mStream): the start and end tracks (set from a track's menu or Now Playing), the
 * length, and the path the server builds between them — play it, queue it or save it as a playlist.
 */
@Composable
fun SonicPathScreen(onMessage: (String) -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val sonicPath = container.sonicPath
    val state by sonicPath.state.collectAsStateWithLifecycle()
    val settingsFlow = remember { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val player by container.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var length by remember(settings?.sonicPathLength) { mutableFloatStateOf((settings?.sonicPathLength ?: 14).toFloat()) }
    var saving by remember { mutableStateOf(false) }
    val result = state.result
    val tracks by produceState<Map<String, TrackEntity>>(emptyMap(), result) {
        value = if (result == null) emptyMap() else container.db.tracks().getMany(result).associateBy { it.path }
    }
    val files = remember(result) { result.orEmpty().map(AudioFile::forPath) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            ScreenTitle("Sonic Path", Modifier.weight(1f))
            if (state.start != null || state.end != null) TextButton(onClick = sonicPath::reset) { Text("Start over") }
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text(
                    "Pick two tracks — the server fills the way between them with tracks whose sound morphs from one to the other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeneloColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                EndRow("Start", state.start, player.mediaId, onUsePlaying = { sonicPath.set(it, start = true) }, onClear = { sonicPath.clear(start = true) })
                EndRow("End", state.end, player.mediaId, onUsePlaying = { sonicPath.set(it, start = false) }, onClear = { sonicPath.clear(start = false) })
                Text("${length.roundToInt()} tracks", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                ZeneloSlider(length, { length = it }, 4f..32f, Modifier.padding(horizontal = 20.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { sonicPath.build(length.roundToInt()) },
                        enabled = state.start != null && state.end != null && !state.loading,
                        colors = ButtonDefaults.buttonColors(containerColor = ZeneloColors.Mustard, contentColor = ZeneloColors.Background),
                    ) { Text(if (result == null) "Build the path" else "Rebuild") }
                    if (state.loading) CircularProgressIndicator(color = ZeneloColors.Mustard, strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                state.problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.Danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                if (state.start == null || state.end == null) {
                    Text(
                        "Set them with \"Sonic path from here\" / \"to here\" in a track's ⋮ menu, or use the playing track.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeneloColors.TextMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            if (result != null) {
                item {
                    SectionHeader("The path · ${result.size} tracks")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { container.player.playFiles(files, 0, shuffle = false) },
                            colors = ButtonDefaults.buttonColors(containerColor = ZeneloColors.Mustard, contentColor = ZeneloColors.Background),
                        ) { Text("Play") }
                        OutlinedButton(onClick = {
                            val at = container.player.addToQueue(files)
                            onMessage("Queued ${files.size} tracks from #$at")
                        }) { Text("Queue") }
                        OutlinedButton(onClick = { saving = true }) { Text("Save") }
                    }
                }
                itemsIndexed(result) { index, path ->
                    val t = tracks[path]
                    LibraryRow(
                        title = t?.title ?: path.substringAfterLast('/').substringBeforeLast('.'),
                        subtitle = t?.artist ?: t?.albumArtist,
                        onClick = { container.player.playFiles(files, index, shuffle = false) },
                        onLongClick = {},
                        coverPath = path,
                        number = "${index + 1}",
                        trailingText = t?.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
                        isCurrent = player.mediaId == path,
                    )
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf(listOfNotNull(state.start?.title, state.end?.title).joinToString(" → ")) }
        AlertDialog(
            onDismissRequest = { saving = false },
            containerColor = ZeneloColors.Card,
            title = { Text("Save as playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    saving = false
                    scope.launch {
                        val dao = container.db.playlists()
                        val id = dao.insert(PlaylistEntity(name = name.trim()))
                        dao.appendAll(id, result.orEmpty())
                        onMessage("Saved \"${name.trim()}\"")
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EndRow(label: String, end: MStreamSonicPath.End?, playing: String?, onUsePlaying: (String) -> Unit, onClear: () -> Unit) {
    ListRow(
        title = end?.title ?: "$label: not set",
        subtitle = if (end == null) null else listOfNotNull(label, end.artist).joinToString(" · "),
        leading = {
            IconTile(if (end == null) Icons.Outlined.Route else Icons.Rounded.MusicNote, ZeneloColors.Celadon, ZeneloColors.CeladonTint)
        },
        trailing = {
            if (end != null) {
                IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Clear", tint = ZeneloColors.TextMuted, modifier = Modifier.size(18.dp)) }
            } else if (playing != null && app.zenelo.mstream.MStreamPaths.isRemote(playing)) {
                TextButton(onClick = { onUsePlaying(playing) }) { Text("Playing track") }
            }
        },
    )
}
