package app.zenelo.ui.browser

import app.zenelo.data.settings.SortField
import app.zenelo.library.LibrarySort
import app.zenelo.ui.library.LetterRail
import app.zenelo.ui.components.ScrollToTopOn
import app.zenelo.ui.components.applicableSwipes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.ui.graphics.Color
import app.zenelo.data.settings.SortOrder
import app.zenelo.data.settings.SortPage
import app.zenelo.ui.components.SortMenu
import app.zenelo.ui.components.SortToggle
import app.zenelo.ui.components.SelectionBar
import app.zenelo.ui.components.SelectableLeading
import app.zenelo.ui.components.IconMenuItem
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.TrackMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.SwipeAction
import app.zenelo.library.AudioFile
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.RemoteFolders
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.PlayFab
import app.zenelo.ui.components.SearchField
import app.zenelo.ui.components.SelectionMark
import app.zenelo.ui.components.SelectAllIcon
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.ui.components.SectionHeader
import app.zenelo.ui.components.SwipeableRow
import app.zenelo.ui.components.TrackThumb
import app.zenelo.ui.components.listSwipeOptions
import app.zenelo.ui.components.ZeneloSnackbar
import app.zenelo.ui.components.accent
import app.zenelo.ui.components.TagEditorDialog
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import app.zenelo.ui.components.formatTotal
import app.zenelo.ui.components.trackSubtitle
import app.zenelo.ui.components.icon
import app.zenelo.ui.theme.ZeneloColors
import java.io.File

/** [isActive]: this tab is the one on screen (tabs stay composed while swiping between them). */
@Composable
fun BrowserScreen(viewModel: BrowserViewModel, currentMediaId: String?, isPlaying: Boolean, isActive: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var snackbarIcon by remember { mutableStateOf<ImageVector?>(null) }
    var pendingConfirm by remember { mutableStateOf<BrowserEvent.Confirm?>(null) }
    var tagEditorFor by remember { mutableStateOf<String?>(null) }
    var coverPickerFor by remember { mutableStateOf<String?>(null) }

    // Multi-select of folders and files (long-press a row), like in the queue: paths of either.
    // Cleared when the folder changes.
    var selecting by remember(state.dir) { mutableStateOf(false) }
    var selected by remember(state.dir) { mutableStateOf(emptySet<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    var sortOpen by remember { mutableStateOf(false) }
    // The home folder is where "up" stops: no back button there, and system back leaves the app.
    val atHome = state.dir?.absolutePath == settings.homeFolder
    val canGoUp = state.canGoUp && !atHome
    BackHandler(enabled = isActive && (sortOpen || selecting || state.searching || canGoUp)) {
        when {
            sortOpen -> sortOpen = false
            selecting -> exitSelection()
            state.searching -> viewModel.setSearching(false)
            else -> viewModel.goUp()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BrowserEvent.Confirm -> pendingConfirm = event
                is BrowserEvent.EditTags -> tagEditorFor = event.path
                is BrowserEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbarIcon = event.action?.icon
                    val result = snackbar.showSnackbar(
                        message = event.text,
                        actionLabel = if (event.undo != null) "Undo" else null,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) event.undo?.invoke()
                }
            }
        }
    }

    // One list state per folder, restored when navigating back up.
    val listState = remember(state.dir) {
        val (index, offset) = viewModel.scrollPosition(state.dir)
        LazyListState(index, offset)
    }
    ScrollToTopOn(listState)
    DisposableEffect(state.dir) {
        val dir = state.dir
        onDispose { viewModel.saveScrollPosition(dir, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    val swipeOptions = remember(settings.swipes) { listSwipeOptions(settings.swipes) }
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
        // Deselecting the last one closes selection mode.
        if (selected.isEmpty()) selecting = false
    }
    fun startSelection(path: String) {
        // A peer's rows: play, queue and "Add to my library" from their menus, no selection.
        if (viewModel.onPeer) return
        selecting = true
        selected = selected + path
    }
    val sort = settings.sorts[SortPage.BROWSER] ?: SortPage.BROWSER.default
    val folders = remember(state.folders, state.query, sort, state.folderModified, state.folderTracks) { state.visibleFolders(sort) }
    val files = remember(state.files, state.query, sort, state.tracks) { state.visibleFiles(sort) }
    // A long list sorted by text gets the A–Z rail: over the files (else the folders, by name).
    val rail = remember(folders, files, sort, state.tracks, state.query) {
        if (state.query.isNotBlank()) return@remember null
        val positions = LinkedHashMap<String, Int>()
        val foldersEnd = if (folders.isEmpty()) 0 else 1 + folders.size
        when {
            // Files keep name order under the folder sort (number of tracks).
            files.size > LETTER_RAIL_MIN && sort.sortBy in setOf(SortField.NAME, SortField.ARTIST, SortField.ALBUM, SortField.TRACKS) -> files.forEachIndexed { i, f ->
                val t = state.tracks[f.path]
                val letter = when (sort.sortBy) {
                    SortField.ARTIST -> LibrarySort.letter(t?.artist ?: t?.albumArtist)
                    SortField.ALBUM -> LibrarySort.letter(t?.album)
                    else -> LibrarySort.letter(f.name)
                }
                positions.putIfAbsent(letter, foldersEnd + 1 + i)
            }
            folders.size > LETTER_RAIL_MIN && sort.sortBy == SortField.NAME -> folders.forEachIndexed { i, f -> positions.putIfAbsent(LibrarySort.letter(f.name), 1 + i) }
            else -> return@remember null
        }
        // One letter for all: nothing to jump between.
        positions.keys.toList().takeIf { it.size > 1 }?.let { it to positions }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                val chosenFolders = folders.filter { it.path in selected }
                val chosenFiles = files.filter { it.path in selected }
                val all = folders.size + files.size
                SelectionBar(
                    count = selected.size,
                    allSelected = all > 0 && selected.size == all,
                    // The server's files aren't deleted from here.
                    canDelete = chosenFolders.isEmpty() && !RemoteFolders.isRemote(state.dir),
                    onClose = ::exitSelection,
                    onSelectAll = {
                        if (selected.size == all) exitSelection() else selected = (folders.map { it.path } + files.map { it.path }).toSet()
                    },
                    onPlay = { shuffle ->
                        viewModel.playSelection(chosenFolders, chosenFiles, shuffle)
                        exitSelection()
                    },
                    onAction = { action ->
                        if (action == SwipeAction.DELETE_FILE) {
                            confirmDeleteSelected = true
                        } else {
                            viewModel.onSelection(chosenFolders, chosenFiles, action)
                            exitSelection()
                        }
                    },
                )
                // Same height as the normal top bar, so the list doesn't jump under the finger.
                Breadcrumb(state, viewModel)
            } else {
                TopBar(state, canGoUp, sortOpen, { sortOpen = !sortOpen }, viewModel)
            }
            Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(end = if (rail != null) 18.dp else 0.dp, bottom = 80.dp)) {
                if (folders.isNotEmpty()) {
                    item(key = "h-folders") { SectionHeader("Folders · ${folders.size}") }
                    items(folders, key = { it.absolutePath }) { folder ->
                        // Same swipe actions as tracks, applied to everything in the folder.
                        SwipeableRow(
                            options = applicableSwipes(swipeOptions, settings.swipes, folder.path, folder = true, cloudInside = state.folderStorage[folder.path]?.cloud ?: 0),
                            onSwipe = { slot -> settings.swipes[slot]?.let { viewModel.onFolderSwipe(folder, it) } },
                            enabled = !selecting,
                        ) {
                            FolderRow(
                                folder = folder,
                                label = state.folderLabel(folder),
                                mark = state.folderStorage[folder.path]?.mark ?: app.zenelo.library.StorageMark.NONE,
                                viewModel = viewModel,
                                selecting = selecting,
                                selected = folder.path in selected,
                                markLeft = markLeft,
                                onClick = { if (selecting) toggle(folder.path) else viewModel.open(folder) },
                                onLongClick = { startSelection(folder.path) },
                            )
                        }
                    }
                }
                if (files.isNotEmpty()) {
                    item(key = "h-files") {
                        val total = files.sumOf { state.tracks[it.path]?.durationMs ?: 0L }
                        SectionHeader(listOfNotNull("Files", "${files.size}", total.takeIf { it > 0 }?.let(::formatTotal)).joinToString(" · "))
                    }
                    items(files, key = { it.path }) { file ->
                        SwipeableRow(
                            options = applicableSwipes(swipeOptions, settings.swipes, file.path),
                            onSwipe = { slot -> settings.swipes[slot]?.let { viewModel.onSwipe(file, it) } },
                            enabled = !selecting,
                        ) {
                            TrackRow(
                                file = file,
                                info = state.tracks[file.path],
                                isCurrent = file.path == currentMediaId,
                                selecting = selecting,
                                selected = file.path in selected,
                                markLeft = markLeft,
                                onClick = { if (selecting) toggle(file.path) else viewModel.playFrom(file) },
                                onLongClick = { startSelection(file.path) },
                                onAction = { viewModel.onSwipe(file, it) },
                                onEditTags = { tagEditorFor = file.path },
                                onFindCover = { coverPickerFor = file.path },
                                onCopy = { scope -> viewModel.copyTrack(file, scope) },
                            )
                        }
                    }
                }
                if (!state.loading && folders.isEmpty() && files.isEmpty()) {
                    item(key = "empty") { SectionHeader(if (state.query.isNotBlank()) "Nothing found" else "Empty folder") }
                }
            }
            rail?.let { (letters, positions) -> LetterRail(letters, listState, positions, Modifier.align(Alignment.CenterEnd), bottomInset = 88.dp) }
            if (sortOpen) {
                SortMenu(SortPage.BROWSER.fields, sort, onSelect = { viewModel.setSort(it); sortOpen = false }, onDismiss = { sortOpen = false })
            }
            }
        }

        // Playing: stop (and clear the queue). Otherwise tap: play the folder (or resume it if
        // paused on one of its tracks); long-press: shuffle it.
        if (!selecting && snackbar.currentSnackbarData == null) {
            if (isPlaying) {
                PlayFab(
                    onClick = viewModel::stop,
                    icon = Icons.Rounded.Stop,
                    description = "Stop",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            } else if (files.isNotEmpty()) {
                PlayFab(
                    onClick = { viewModel.playFolder(shuffle = false) },
                    onLongClick = { viewModel.playFolder(shuffle = true) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { ZeneloSnackbar(it, snackbarIcon) }
    }

    tagEditorFor?.let { path ->
        TagEditorDialog(path, onDismiss = { tagEditorFor = null }, onDone = viewModel::showMessage)
    }
    coverPickerFor?.let { path ->
        app.zenelo.ui.components.CoverPickerDialog(path, onDismiss = { coverPickerFor = null }, onDone = viewModel::showMessage)
    }


    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            containerColor = ZeneloColors.Card,
            title = { Text("Delete ${selected.size} file${if (selected.size == 1) "" else "s"}?") },
            text = { Text("They'll be removed from the device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onSelection(emptyList(), files.filter { it.path in selected }, SwipeAction.DELETE_FILE)
                        confirmDeleteSelected = false
                        exitSelection()
                    },
                ) { Text("Delete", color = ZeneloColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") } },
        )
    }

    pendingConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            containerColor = ZeneloColors.Card,
            title = { Text(if (confirm.deleteFile) "Delete file?" else "Hide?") },
            text = { Text(confirm.file.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (confirm.deleteFile) viewModel.deleteFile(confirm.file) else viewModel.hide(emptyList(), listOf(confirm.file))
                        pendingConfirm = null
                    },
                ) {
                    Text(if (confirm.deleteFile) "Delete" else "Hide", color = ZeneloColors.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(state: BrowserState, canGoUp: Boolean, sortOpen: Boolean, onSort: () -> Unit, viewModel: BrowserViewModel) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canGoUp) {
                IconButton(onClick = viewModel::goUp, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up", modifier = Modifier.size(20.dp))
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            // Tap: go to the home folder. Long-press: make the current folder home.
            Box(
                Modifier
                    .size(36.dp)
                    .combinedClickable(onClick = viewModel::onHomeClick, onLongClick = viewModel::setCurrentAsHome),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Home, "Home folder", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            if (state.searching) {
                SearchField(state.query, viewModel::setQuery, "Filter this folder", Modifier.weight(1f))
                IconButton(onClick = { viewModel.setSearching(false) }) { Icon(Icons.Rounded.Close, "Close search") }
            } else {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // A peer's folder (below its libraries): copied whole into our library.
                val dir = state.dir
                if (dir != null && RemoteFolders.isPeer(dir) && app.zenelo.mstream.MStreamPaths.peerPath(RemoteFolders.indexDir(dir)).contains('/') && rememberCanCopy()) {
                    IconButton(onClick = { viewModel.copyFolder(dir) }) { Icon(Icons.Outlined.LibraryAdd, "Add this folder to my library", Modifier.size(22.dp), tint = ZeneloColors.Celadon) }
                }
                IconButton(onClick = { viewModel.setSearching(true) }) { Icon(Icons.Rounded.Search, "Search", Modifier.size(22.dp)) }
                SortToggle(sortOpen, onSort)
            }
        }
        Breadcrumb(state, viewModel)
    }
}


/** "SD / Music / Ambient": first segment switches storage, the others jump to that folder. */
@Composable
private fun Breadcrumb(state: BrowserState, viewModel: BrowserViewModel) {
    val root = state.root ?: return
    var storageMenu by remember { mutableStateOf(false) }
    val segments = state.breadcrumb
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Crumb(root.shortName, isLast = segments.isEmpty()) { storageMenu = true }
            DropdownMenu(expanded = storageMenu, onDismissRequest = { storageMenu = false }) {
                state.roots.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r.name, color = if (r == root) ZeneloColors.Mustard else ZeneloColors.TextPrimary) },
                        onClick = {
                            storageMenu = false
                            viewModel.openRoot(r)
                        },
                    )
                }
            }
        }
        // Collapse the middle of deep paths so the current folder stays visible on the small screen.
        val shown: List<String?> = if (segments.size > 3) listOf(null) + segments.takeLast(2) else segments
        val skipped = segments.size - shown.size
        shown.forEachIndexed { i, name ->
            Separator()
            if (name == null) {
                Crumb("…", isLast = false) {}
            } else {
                val depth = skipped + i + 1
                Crumb(name, isLast = depth == segments.size) {
                    viewModel.open(segments.take(depth).fold(root.dir) { dir, seg -> dir.resolve(seg) })
                }
            }
        }
    }
}

@Composable
private fun Separator() {
    Text(" / ", style = MaterialTheme.typography.labelMedium, color = ZeneloColors.TextMuted)
}

@Composable
private fun Crumb(text: String, isLast: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isLast) ZeneloColors.TextPrimary else ZeneloColors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(onClick = onClick),
    )
}


@Composable
private fun TrackRow(
    file: AudioFile,
    info: TrackEntity?,
    isCurrent: Boolean,
    selecting: Boolean,
    selected: Boolean,
    markLeft: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (SwipeAction) -> Unit,
    onEditTags: () -> Unit,
    onFindCover: () -> Unit,
    onCopy: (scope: String?) -> Unit,
) {
    ListRow(
        title = file.name,
        subtitle = trackSubtitle(file, info),
        mark = app.zenelo.ui.components.rememberStorageMark(file.path),
        titleColor = if (isCurrent) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
        modifier = if (selected) Modifier.background(ZeneloColors.MustardTint) else Modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = { SelectableLeading(selecting && markLeft, selected) { TrackThumb(file.path, isCurrent) } },
        trailing = {
            if (selecting) {
                if (!markLeft) SelectionMark(selected, Modifier.padding(horizontal = 10.dp))
            } else if (MStreamPaths.isPeer(file.path)) {
                PeerTrackMenu(info, onAction, onCopy)
            } else {
                TrackMenu(onAction, onEditTags, onFindCover, local = !MStreamPaths.isRemote(file.path), path = file.path)
            }
        },
    )
}

/** Folder row: tap opens it, ⋮ shows its actions (play, shuffle, queue, favorite), long-press selects. */
@Composable
private fun FolderRow(
    folder: File,
    label: String?,
    mark: app.zenelo.library.StorageMark,
    viewModel: BrowserViewModel,
    selecting: Boolean,
    selected: Boolean,
    markLeft: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListRow(
        title = folder.name,
        subtitle = label,
        mark = mark,
        modifier = if (selected) Modifier.background(ZeneloColors.MustardTint) else Modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = {
            SelectableLeading(selecting && markLeft, selected) {
                IconTile(Icons.Outlined.Folder, ZeneloColors.Mustard, ZeneloColors.MustardTint)
            }
        },
        trailing = {
            if (selecting) {
                if (!markLeft) SelectionMark(selected, Modifier.padding(horizontal = 10.dp))
            } else {
                FolderMenu(folder, viewModel)
            }
        },
    )
}

@Composable
private fun FolderMenu(folder: File, viewModel: BrowserViewModel) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, "More", tint = ZeneloColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val peer = RemoteFolders.isPeer(folder)
            IconMenuItem("Play", Icons.Rounded.PlayArrow, ZeneloColors.Mustard) { menu = false; viewModel.playSubtree(folder, shuffle = false) }
            IconMenuItem("Shuffle", Icons.Rounded.Shuffle, ZeneloColors.Celadon) { menu = false; viewModel.playSubtree(folder, shuffle = true) }
            IconMenuItem("Play next", SwipeAction.PLAY_NEXT.icon, SwipeAction.PLAY_NEXT.accent) { menu = false; viewModel.queueSubtree(folder, next = true) }
            IconMenuItem("Add to queue", SwipeAction.ADD_TO_QUEUE.icon, SwipeAction.ADD_TO_QUEUE.accent) { menu = false; viewModel.queueSubtree(folder, next = false) }
            if (peer) {
                // The peer's libraries themselves are too big a press: their folders one by one.
                if (app.zenelo.mstream.MStreamPaths.peerPath(RemoteFolders.indexDir(folder)).contains('/') && rememberCanCopy()) {
                    IconMenuItem("Add to my library", Icons.Outlined.LibraryAdd, ZeneloColors.Celadon) { menu = false; viewModel.copyFolder(folder) }
                }
            } else {
                IconMenuItem("Favorite", SwipeAction.FAVORITE.icon, SwipeAction.FAVORITE.accent) { menu = false; viewModel.toggleFolderFavorite(folder) }
                IconMenuItem("Add to playlist…", SwipeAction.ADD_TO_PLAYLIST.icon, SwipeAction.ADD_TO_PLAYLIST.accent) { menu = false; viewModel.addFolderToPlaylist(folder) }
            }
        }
    }
}

/** The server copies peers' songs into the library for this account. */
@Composable
private fun rememberCanCopy(): Boolean {
    val plugins by appContainer().peers.plugins.collectAsStateWithLifecycle()
    return plugins.any { it.name == app.zenelo.mstream.MStreamPeers.COPY }
}

/**
 * A federated peer's track: queue it, or have the server copy it into our library — the song, its
 * album or everything of its artist there.
 */
@Composable
private fun PeerTrackMenu(info: TrackEntity?, onAction: (SwipeAction) -> Unit, onCopy: (scope: String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scopes = appContainer().peers.plugins.collectAsStateWithLifecycle().value
        .firstOrNull { it.name == app.zenelo.mstream.MStreamPeers.COPY }?.scopes
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, "More", tint = ZeneloColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            IconMenuItem("Play next", SwipeAction.PLAY_NEXT.icon, SwipeAction.PLAY_NEXT.accent) { menu = false; onAction(SwipeAction.PLAY_NEXT) }
            IconMenuItem("Add to queue", SwipeAction.ADD_TO_QUEUE.icon, SwipeAction.ADD_TO_QUEUE.accent) { menu = false; onAction(SwipeAction.ADD_TO_QUEUE) }
            if (scopes != null) {
                IconMenuItem("Add to my library", Icons.Outlined.LibraryAdd, ZeneloColors.Celadon) { menu = false; onCopy(null) }
                if (info?.album != null && "album" in scopes) {
                    IconMenuItem("Add the album", Icons.Outlined.LibraryAdd, ZeneloColors.Celadon) { menu = false; onCopy("album") }
                }
                if (info?.artist != null && "artist-missing" in scopes) {
                    IconMenuItem("Add the artist's albums I lack", Icons.Outlined.LibraryAdd, ZeneloColors.Celadon) { menu = false; onCopy("artist-missing") }
                }
            }
        }
    }
}

/** Files (or folders) before the A–Z rail shows. */
private const val LETTER_RAIL_MIN = 30
