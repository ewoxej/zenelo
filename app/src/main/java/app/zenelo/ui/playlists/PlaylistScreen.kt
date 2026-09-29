package app.zenelo.ui.playlists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.PlaylistTrack
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.data.settings.SwipeAction
import app.zenelo.library.AudioFile
import app.zenelo.ui.components.FabClearance
import app.zenelo.ui.components.SwipeableRow
import app.zenelo.ui.components.TrackMenu
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.formatDuration
import app.zenelo.ui.components.formatTotal
import app.zenelo.ui.components.listSwipeOptions
import app.zenelo.ui.library.LibraryRow
import app.zenelo.ui.library.LibraryScaffold
import app.zenelo.ui.library.LibraryViewModel
import app.zenelo.ui.library.PageSearch
import app.zenelo.ui.library.TrackDialog
import app.zenelo.ui.library.rememberNowPlaying
import app.zenelo.ui.library.rememberSelection
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun PlaylistTrack.name() = title ?: path.substringAfterLast('/').substringBeforeLast('.')

private fun PlaylistTrack.toAudioFile(): AudioFile {
    val file = path.substringAfterLast('/')
    return AudioFile(path, file, file.substringAfterLast('.', "").lowercase(), 0L)
}

/**
 * One playlist: play it, drag the handles to reorder, remove tracks ("Remove from list" swipe,
 * or select several), rename or delete it from ⋮.
 */
@Composable
fun PlaylistScreen(vm: LibraryViewModel, id: Long, onBack: () -> Unit) {
    val dao = appContainer().db.playlists()
    val scope = rememberCoroutineScope()
    val playlistFlow = remember(id) { dao.observe(id) }
    val playlist by playlistFlow.collectAsStateWithLifecycle(initialValue = null)
    val tracksFlow = remember(id) { dao.observeTracks(id) }
    val tracks by tracksFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, isPlaying) = rememberNowPlaying()
    val search = remember { PageSearch() }
    val selection = rememberSelection<Int>()
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val swipeOptions = remember(settings.swipes) { listSwipeOptions(settings.swipes) }

    // One state object for the page's lifetime: the reorder library keeps its first onMove lambda.
    val itemsState = remember { mutableStateOf(emptyList<PlaylistTrack>()) }
    var items by itemsState
    var reordering by remember { mutableStateOf(false) }
    LaunchedEffect(tracks) { if (!reordering) tracks?.let { items = it } }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        itemsState.value = itemsState.value.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val visible = if (search.query.isBlank()) items else items.filter { search.matches(it.name(), it.artist, it.album) }
    val files = remember(visible) { visible.map(PlaylistTrack::toAudioFile) }
    val chosen = { visible.filter { it.position in selection.keys } }
    fun save(list: List<PlaylistTrack>) = scope.launch { dao.replace(id, list.map(PlaylistTrack::path)) }
    fun remove(positions: Set<Int>) {
        val next = items.filter { it.position !in positions }
        items = next
        save(next)
        vm.showMessage(if (positions.size == 1) "Removed from playlist" else "${positions.size} removed from playlist")
    }

    val playlistFiles = appContainer().playlistFiles
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val count = runCatching { playlistFiles.export(id, uri) }.getOrNull()
            vm.showMessage(if (count == null) "Couldn't write the file" else "Exported ${count} track${if (count == 1) "" else "s"}")
        }
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val total = items.sumOf { it.durationMs ?: 0L }

    LibraryScaffold(
        title = playlist?.name ?: "Playlist",
        count = tracks?.size,
        caption = when {
            total >= 60_000 -> formatTotal(total).uppercase()
            total > 0 -> formatDuration(total)
            else -> null
        },
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(visible.map { it.position }) },
        allSelected = visible.isNotEmpty() && selection.keys.size >= visible.size,
        onSelectionAction = { action ->
            chosen().let { list -> vm.onTracks(list.map(PlaylistTrack::toAudioFile), list.associate { it.path to (it.name() to it.artist) }, action) }
        },
        onSelectionPlay = { vm.play(chosen().map(PlaylistTrack::toAudioFile), shuffle = it) },
        onSelectionRemove = { remove(selection.keys) },
        removeLabel = "Remove from playlist",
        isPlaying = isPlaying,
        canPlay = files.isNotEmpty(),
        onPlay = { vm.play(files, shuffle = it) },
        onStop = vm::stop,
        headerActions = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.MoreVert, "More", Modifier.size(20.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(
                        text = { Text("Export as M3U8…") },
                        onClick = {
                            menu = false
                            exporter.launch("${playlist?.name?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "Playlist"}.m3u8")
                        },
                    )
                    DropdownMenuItem(text = { Text("Delete playlist", color = ZeneloColors.Danger) }, onClick = { menu = false; deleting = true })
                }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = FabClearance)) {
            items(visible, key = { it.position }) { track ->
                ReorderableItem(reorder, key = track.position) { dragging ->
                    val file = remember(track) { track.toAudioFile() }
                    val title = track.name()
                    val act = { action: SwipeAction ->
                        // Here "remove from list" takes the track out of the playlist.
                        if (action == SwipeAction.REMOVE_FROM_LIST || action == SwipeAction.HIDE) remove(setOf(track.position))
                        else vm.onTrack(file, title, track.artist, action)
                    }
                    SwipeableRow(
                        options = swipeOptions,
                        onSwipe = { slot -> settings.swipes[slot]?.let(act) },
                        enabled = !selection.active && !dragging,
                    ) {
                        LibraryRow(
                            title = title,
                            subtitle = listOfNotNull(track.artist, track.album).joinToString(" · ").ifEmpty { null },
                            coverPath = track.path,
                            placeholder = Icons.Outlined.MusicNote,
                            trailingText = track.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
                            isCurrent = track.path == mediaId,
                            // Not in the index (moved or deleted): shown, but dimmed.
                            dimmed = track.title == null && track.durationMs == null,
                            selecting = selection.active,
                            selected = track.position in selection.keys,
                            markLeft = markLeft,
                            onClick = { if (selection.active) selection.toggle(track.position) else vm.play(files, start = visible.indexOf(track)) },
                            onLongClick = { selection.start(track.position) },
                            trailing = {
                                TrackMenu(
                                    onAction = act,
                                    onDownloadCover = { vm.openDialog(TrackDialog.Cover(track.path)) },
                                    onEditTags = { vm.openDialog(TrackDialog.EditTags(track.path, it)) },
                                )
                                // Reordering only makes sense over the whole list.
                                if (search.query.isBlank()) {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        "Reorder",
                                        tint = ZeneloColors.TextMuted,
                                        modifier = Modifier
                                            // Holding the handle still is a slow drag, not the row's long-press.
                                            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
                                            .draggableHandle(
                                                onDragStarted = { reordering = true },
                                                onDragStopped = {
                                                    save(itemsState.value)
                                                    reordering = false
                                                },
                                            )
                                            .padding(8.dp)
                                            .size(22.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        if (tracks != null && items.isEmpty()) {
            Text(
                "Empty. Add tracks with the \"Add to playlist\" swipe, the ⋮ menu, or a selection.",
                style = MaterialTheme.typography.bodySmall,
                color = ZeneloColors.TextMuted,
                modifier = Modifier.padding(24.dp),
            )
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(playlist?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = ZeneloColors.Card,
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { dao.rename(id, name.trim()) }
                    renaming = false
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = ZeneloColors.Card,
            title = { Text("Delete playlist?") },
            text = { Text("${playlist?.name.orEmpty()} · the files stay on the device.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch { dao.delete(id) }
                    onBack()
                }) { Text("Delete", color = ZeneloColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}
