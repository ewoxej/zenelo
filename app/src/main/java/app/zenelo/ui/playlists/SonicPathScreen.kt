package app.zenelo.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.runtime.saveable.rememberSaveable
import app.zenelo.data.db.FavoriteKind
import app.zenelo.library.LibrarySort
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.SonicPathSuggestions
import app.zenelo.ui.components.GroupHeader
import app.zenelo.ui.components.SearchField
import app.zenelo.ui.components.rememberOnServer
import app.zenelo.ui.library.LibraryViewModel
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
 * Sonic Path (mStream): the start and end tracks ("Sonic path" on a track, or picked here — tap an
 * end), swapped with ⇅, the length, and the path the server builds between them — play it, queue
 * it or save it as a playlist. A tab / Home section, or opened by "Sonic path".
 */
@Composable
fun SonicPathScreen(onMessage: (String) -> Unit, onPick: (start: Boolean) -> Unit, onBack: (() -> Unit)?) {
    val container = appContainer()
    val sonicPath = container.sonicPath
    val state by sonicPath.state.collectAsStateWithLifecycle()
    val available by sonicPath.available.collectAsStateWithLifecycle()
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
    // The playing track, when the path can start / end there.
    val playing = player.mediaId?.takeIf { rememberOnServer(it) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackButton(onBack)
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
                if (!available) {
                    Text(
                        if (settings?.mstream == null) "Sonic Path is an mStream feature: connect a server in Settings → mStream." else "Sonic Path needs the network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeneloColors.Danger,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        EndRow(true, state.start, playing.takeIf { it != state.end?.path }, enabled = available, onPick = { onPick(true) }, onUse = { sonicPath.set(it, start = true) }, onClear = { sonicPath.clear(start = true) })
                        EndRow(false, state.end, playing.takeIf { it != state.start?.path }, enabled = available, onPick = { onPick(false) }, onUse = { sonicPath.set(it, start = false) }, onClear = { sonicPath.clear(start = false) })
                    }
                    val canSwap = state.start != null || state.end != null
                    IconButton(onClick = sonicPath::swap, enabled = canSwap, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Rounded.SwapVert, "Swap start and end", tint = if (canSwap) ZeneloColors.Celadon else ZeneloColors.TextMuted.copy(alpha = 0.4f))
                    }
                }
                Text("${length.roundToInt()} tracks", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
                ZeneloSlider(length, { length = it }, 4f..32f, Modifier.padding(horizontal = 20.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { sonicPath.build(length.roundToInt()) },
                        enabled = available && state.start != null && state.end != null && !state.loading,
                        colors = ButtonDefaults.buttonColors(containerColor = ZeneloColors.Mustard, contentColor = ZeneloColors.Background),
                    ) { Text(if (result == null) "Build the path" else "Rebuild") }
                    if (state.loading) CircularProgressIndicator(color = ZeneloColors.Mustard, strokeWidth = 2.dp, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                state.problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.Danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                if (available && (state.start == null || state.end == null)) {
                    Text(
                        "Tap an end to pick its track, or use \"Sonic path\" in a track's ⋮ menu or swipe: the first sets the start, the next the end.",
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

/** One end: its track (tap: pick another; × clears it), or a prompt to pick one (or use the playing track). */
@Composable
private fun EndRow(
    start: Boolean,
    end: MStreamSonicPath.End?,
    playing: String?,
    enabled: Boolean,
    onPick: () -> Unit,
    onUse: (String) -> Unit,
    onClear: () -> Unit,
) {
    val label = if (start) "Start" else "End"
    if (end != null) {
        LibraryRow(
            title = end.title,
            subtitle = listOfNotNull(label.uppercase(), end.artist).joinToString(" · "),
            onClick = { if (enabled) onPick() },
            onLongClick = {},
            coverPath = end.path,
            trailing = {
                IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Clear", tint = ZeneloColors.TextMuted, modifier = Modifier.size(18.dp)) }
            },
        )
    } else {
        // Same row as a set end: the icon tile in the cover's place, so both line up.
        LibraryRow(
            title = "Pick the ${label.lowercase()} track",
            subtitle = label.uppercase(),
            onClick = { if (enabled) onPick() },
            onLongClick = {},
            dimmed = !enabled,
            leading = { IconTile(Icons.Outlined.Route, ZeneloColors.Celadon, ZeneloColors.CeladonTint, size = 40.dp, iconSize = 20.dp, cornerRadius = 6.dp) },
            trailing = {
                if (enabled && playing != null) TextButton(onClick = { onUse(playing) }) { Text("Playing") }
            },
        )
    }
}

/**
 * Picks an end of the Sonic Path: search the tracks the server has (local ones through their
 * link); before anything is typed, the playing one, up next, recent plays and favorites.
 */
@Composable
fun SonicPathPicker(vm: LibraryViewModel, start: Boolean, onDone: () -> Unit) {
    val container = appContainer()
    val sonicPath = container.sonicPath
    val state by sonicPath.state.collectAsStateWithLifecycle()
    val all by vm.tracks.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val queue by container.player.queue.state.collectAsStateWithLifecycle()
    val links by container.serverLinks.links.collectAsStateWithLifecycle()
    val favoritesFlow = remember { container.db.favorites().observeAll() }
    val favorites by favoritesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()

    val byPath = remember(all) { all.orEmpty().associateBy { it.path } }
    val usable: (String) -> Boolean = remember(byPath, links) { { p -> p in byPath && (MStreamPaths.isRemote(p) || links.isLinked(p)) } }
    val other = (if (start) state.end else state.start)?.path
    val groups = remember(usable, recent, favorites, queue, player.mediaId, other) {
        SonicPathSuggestions.of(
            playing = player.mediaId?.let(links::canonical),
            upNext = (1 until minOf(queue.size, 30)).map { links.canonical(queue.pathAt(it)) },
            recent = recent.orEmpty().map { links.canonical(it.path) },
            favorites = favorites.filter { it.kind == FavoriteKind.TRACK }.sortedByDescending { it.addedAt }.map { links.canonical(it.path) },
            usable = usable,
            exclude = other,
        )
    }
    val found = remember(all, q, usable) {
        if (q.isEmpty()) emptyList()
        else all.orEmpty().filter { (LibrarySort.trackName(it).contains(q, true) || it.artist?.contains(q, true) == true || it.album?.contains(q, true) == true) && usable(it.path) }
    }

    fun pick(path: String) {
        sonicPath.set(path, start)
        onDone()
    }

    @Composable
    fun Track(path: String) {
        val t = byPath[path]
        LibraryRow(
            title = t?.let(LibrarySort::trackName) ?: path.substringAfterLast('/').substringBeforeLast('.'),
            subtitle = listOfNotNull(t?.artist ?: t?.albumArtist, t?.album).joinToString(" · "),
            onClick = { pick(path) },
            onLongClick = {},
            coverPath = path,
            trailingText = t?.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
            isCurrent = player.mediaId == path,
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onDone)
            SearchField(query, { query = it }, if (start) "Start track: title, artist, album" else "End track: title, artist, album", Modifier.weight(1f), autoFocus = false)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (q.isEmpty()) {
                groups.forEach { group ->
                    item(key = "h:${group.title}") { GroupHeader(group.title) }
                    items(group.paths, key = { "${group.title}:$it" }) { Track(it) }
                }
                if (groups.isEmpty()) {
                    item { Hint("Search for a track on the mStream server") }
                }
            } else {
                items(found.take(100), key = { it.path }) { Track(it.path) }
                if (found.isEmpty()) item { Hint("Nothing on the server matches") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted, modifier = Modifier.padding(24.dp))
}
