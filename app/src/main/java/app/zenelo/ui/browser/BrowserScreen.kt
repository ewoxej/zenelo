package app.zenelo.ui.browser

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
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.graphics.Color
import app.zenelo.data.settings.BrowserSort
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
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.PlayFab
import app.zenelo.ui.components.SearchField
import app.zenelo.ui.components.SelectionMark
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.ui.components.SectionHeader
import app.zenelo.ui.components.SwipeableRow
import app.zenelo.ui.components.TrackThumb
import app.zenelo.ui.components.listSwipeOptions
import app.zenelo.ui.components.ZeneloSnackbar
import app.zenelo.ui.components.accent
import app.zenelo.ui.components.CoverPickerDialog
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
    var coverPickerFor by remember { mutableStateOf<String?>(null) }
    var tagEditorFor by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    // Multi-select of folders and files (long-press a row), like in the queue: paths of either.
    // Cleared when the folder changes.
    var selecting by remember(state.dir) { mutableStateOf(false) }
    var selected by remember(state.dir) { mutableStateOf(emptySet<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    BackHandler(enabled = isActive && (selecting || state.searching || state.canGoUp)) {
        when {
            selecting -> exitSelection()
            state.searching -> viewModel.setSearching(false)
            else -> viewModel.goUp()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BrowserEvent.Confirm -> pendingConfirm = event
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
        selecting = true
        selected = selected + path
    }
    val sort = settings.browserSort
    val folders = remember(state.folders, state.query, sort, state.folderModified) { state.visibleFolders(sort) }
    val files = remember(state.files, state.query, sort) { state.visibleFiles(sort) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                val chosenFolders = folders.filter { it.path in selected }
                val chosenFiles = files.filter { it.path in selected }
                val all = folders.size + files.size
                SelectionBar(
                    count = selected.size,
                    allSelected = all > 0 && selected.size == all,
                    canDelete = chosenFolders.isEmpty(),
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
                TopBar(state, sort, viewModel)
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
                if (folders.isNotEmpty()) {
                    item(key = "h-folders") { SectionHeader("Folders · ${folders.size}") }
                    items(folders, key = { it.absolutePath }) { folder ->
                        // Same swipe actions as tracks, applied to everything in the folder.
                        SwipeableRow(
                            options = swipeOptions,
                            onSwipe = { slot -> settings.swipes[slot]?.let { viewModel.onFolderSwipe(folder, it) } },
                            enabled = !selecting,
                        ) {
                            FolderRow(
                                folder = folder,
                                label = state.folderLabel(folder),
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
                            options = swipeOptions,
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
                                onDownloadCover = { coverPickerFor = file.path },
                                onEditTags = { fromName -> tagEditorFor = file.path to fromName },
                            )
                        }
                    }
                }
                if (!state.loading && folders.isEmpty() && files.isEmpty()) {
                    item(key = "empty") { SectionHeader(if (state.query.isNotBlank()) "Nothing found" else "Empty folder") }
                }
            }
        }

        // Hidden while something plays. Tap: play the folder. Long-press: shuffle it.
        if (files.isNotEmpty() && !isPlaying && !selecting && snackbar.currentSnackbarData == null) {
            PlayFab(
                onClick = { viewModel.playFolder(shuffle = false) },
                onLongClick = { viewModel.playFolder(shuffle = true) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { ZeneloSnackbar(it, snackbarIcon) }
    }

    tagEditorFor?.let { (path, fromName) ->
        TagEditorDialog(path, fromName, onDismiss = { tagEditorFor = null }, onDone = viewModel::showMessage)
    }

    coverPickerFor?.let { path ->
        CoverPickerDialog(path, onDismiss = { coverPickerFor = null }, onDone = viewModel::showMessage)
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
            title = { Text(if (confirm.deleteFile) "Delete file?" else "Remove from list?") },
            text = { Text(confirm.file.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (confirm.deleteFile) viewModel.deleteFile(confirm.file) else viewModel.removeFromList(confirm.file)
                        pendingConfirm = null
                    },
                ) {
                    Text(if (confirm.deleteFile) "Delete" else "Remove", color = ZeneloColors.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(state: BrowserState, sort: BrowserSort, viewModel: BrowserViewModel) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.canGoUp) {
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
                IconButton(onClick = { viewModel.setSearching(true) }) { Icon(Icons.Rounded.Search, "Search", Modifier.size(22.dp)) }
                SortButton(sort, viewModel::setSort)
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
    onDownloadCover: () -> Unit,
    onEditTags: (fromFileName: Boolean) -> Unit,
) {
    ListRow(
        title = file.name,
        subtitle = trackSubtitle(file, info),
        titleColor = if (isCurrent) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
        modifier = if (selected) Modifier.background(ZeneloColors.MustardTint) else Modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = { SelectableLeading(selecting && markLeft, selected) { TrackThumb(file.path, isCurrent) } },
        trailing = {
            if (selecting) {
                if (!markLeft) SelectionMark(selected, Modifier.padding(horizontal = 10.dp))
            } else {
                TrackMenu(onAction, onDownloadCover, onEditTags)
            }
        },
    )
}

/** The ⋮ button of a track row and its menu. */
@Composable
private fun TrackMenu(
    onAction: (SwipeAction) -> Unit,
    onDownloadCover: () -> Unit,
    onEditTags: (fromFileName: Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, "More", tint = ZeneloColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            SwipeAction.entries.filter { it != SwipeAction.NONE }.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    leadingIcon = { Icon(action.icon, null, tint = action.accent) },
                    onClick = {
                        menu = false
                        onAction(action)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Edit tags…") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = ZeneloColors.Celadon) },
                onClick = {
                    menu = false
                    onEditTags(false)
                },
            )
            DropdownMenuItem(
                text = { Text("Tags from file name…") },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null, tint = ZeneloColors.Celadon) },
                onClick = {
                    menu = false
                    onEditTags(true)
                },
            )
            DropdownMenuItem(
                text = { Text("Download cover…") },
                leadingIcon = { Icon(Icons.Outlined.Image, null, tint = ZeneloColors.Celadon) },
                onClick = {
                    menu = false
                    onDownloadCover()
                },
            )
        }
    }
}

/** Replaces the top bar while files are selected: count, select all, and actions for all of them. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    canDelete: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPlay: (shuffle: Boolean) -> Unit,
    onAction: (SwipeAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Close, "Cancel selection", Modifier.size(20.dp)) }
        Text("$count", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onClick = onSelectAll, modifier = Modifier.size(40.dp)) {
            Icon(
                if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                if (allSelected) "Deselect all" else "Select all",
                tint = ZeneloColors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        for (action in listOf(SwipeAction.PLAY_NEXT, SwipeAction.ADD_TO_QUEUE, SwipeAction.FAVORITE)) {
            IconButton(onClick = { onAction(action) }, enabled = count > 0, modifier = Modifier.size(40.dp)) {
                Icon(action.icon, action.label, tint = action.accent, modifier = Modifier.size(22.dp))
            }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = count > 0, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.MoreVert, "More", tint = ZeneloColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                FolderMenuItem("Play", Icons.Rounded.PlayArrow, ZeneloColors.Mustard) { menu = false; onPlay(false) }
                FolderMenuItem("Shuffle", Icons.Rounded.Shuffle, ZeneloColors.Celadon) { menu = false; onPlay(true) }
                // Folders can't be deleted from here.
                if (canDelete) {
                    val delete = SwipeAction.DELETE_FILE
                    FolderMenuItem(delete.label, delete.icon, delete.accent) { menu = false; onAction(delete) }
                }
            }
        }
    }
}

@Composable
private fun SortButton(sort: BrowserSort, onSelect: (BrowserSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                if (sort == BrowserSort.DATE_NEWEST || sort == BrowserSort.DATE_OLDEST) Icons.Rounded.Schedule else Icons.Rounded.SortByAlpha,
                "Sort",
                tint = if (sort == BrowserSort.NAME_ASC) ZeneloColors.TextPrimary else ZeneloColors.Mustard,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BrowserSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, color = if (option == sort) ZeneloColors.Mustard else ZeneloColors.TextPrimary) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Folder row: tap opens it, ⋮ shows its actions (play, shuffle, queue, favorite), long-press selects. */
@Composable
private fun FolderRow(
    folder: File,
    label: String?,
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
            FolderMenuItem("Play", Icons.Rounded.PlayArrow, ZeneloColors.Mustard) { menu = false; viewModel.playSubtree(folder, shuffle = false) }
            FolderMenuItem("Shuffle", Icons.Rounded.Shuffle, ZeneloColors.Celadon) { menu = false; viewModel.playSubtree(folder, shuffle = true) }
            FolderMenuItem("Play next", SwipeAction.PLAY_NEXT.icon, SwipeAction.PLAY_NEXT.accent) { menu = false; viewModel.queueSubtree(folder, next = true) }
            FolderMenuItem("Add to queue", SwipeAction.ADD_TO_QUEUE.icon, SwipeAction.ADD_TO_QUEUE.accent) { menu = false; viewModel.queueSubtree(folder, next = false) }
            FolderMenuItem("Favorite", SwipeAction.FAVORITE.icon, SwipeAction.FAVORITE.accent) { menu = false; viewModel.toggleFolderFavorite(folder) }
        }
    }
}

/** A row's icon / thumbnail, with the selection mark in front of it when the mark sits on the left. */
@Composable
private fun SelectableLeading(showMark: Boolean, selected: Boolean, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showMark) SelectionMark(selected, Modifier.padding(end = 12.dp))
        content()
    }
}

@Composable
private fun FolderMenuItem(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null, tint = tint) }, onClick = onClick)
}
