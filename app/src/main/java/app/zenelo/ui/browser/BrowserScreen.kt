package app.zenelo.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SortByAlpha
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
import app.zenelo.ui.components.SectionHeader
import app.zenelo.ui.components.SwipeableRow
import app.zenelo.ui.components.ZeneloSnackbar
import app.zenelo.ui.components.accent
import app.zenelo.ui.components.CoverPickerDialog
import app.zenelo.ui.components.formatTotal
import app.zenelo.ui.components.trackSubtitle
import app.zenelo.ui.components.icon
import app.zenelo.ui.theme.ZeneloColors

/** [isActive]: this tab is the one on screen (tabs stay composed while swiping between them). */
@Composable
fun BrowserScreen(viewModel: BrowserViewModel, currentMediaId: String?, isPlaying: Boolean, isActive: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var snackbarIcon by remember { mutableStateOf<ImageVector?>(null) }
    var pendingConfirm by remember { mutableStateOf<BrowserEvent.Confirm?>(null) }
    var coverPickerFor by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = isActive && (state.searching || state.canGoUp)) {
        if (state.searching) viewModel.setSearching(false) else viewModel.goUp()
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

    val folders = remember(state.folders, state.query, state.sortDescending) { state.visibleFolders }
    val files = remember(state.files, state.query, state.sortDescending) { state.visibleFiles }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, viewModel)
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
                if (folders.isNotEmpty()) {
                    item(key = "h-folders") { SectionHeader("Folders · ${folders.size}") }
                    items(folders, key = { it.absolutePath }) { folder ->
                        ListRow(
                            title = folder.name,
                            subtitle = state.folderInfo[folder.absolutePath],
                            onClick = { viewModel.open(folder) },
                            onLongClick = { viewModel.toggleFolderFavorite(folder) },
                            leading = { IconTile(Icons.Outlined.Folder, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
                            trailing = {
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    null,
                                    tint = ZeneloColors.TextMuted,
                                    modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
                                )
                            },
                        )
                    }
                }
                if (files.isNotEmpty()) {
                    item(key = "h-files") {
                        val total = files.sumOf { state.tracks[it.path]?.durationMs ?: 0L }
                        SectionHeader(listOfNotNull("Files", "${files.size}", total.takeIf { it > 0 }?.let(::formatTotal)).joinToString(" · "))
                    }
                    items(files, key = { it.path }) { file ->
                        SwipeableRow(actions = settings.swipes, onAction = { viewModel.onSwipe(file, it) }) {
                            TrackRow(
                                file = file,
                                info = state.tracks[file.path],
                                isCurrent = file.path == currentMediaId,
                                onClick = { viewModel.playFrom(file) },
                                onAction = { viewModel.onSwipe(file, it) },
                                onDownloadCover = { coverPickerFor = file.path },
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
        if (files.isNotEmpty() && !isPlaying && snackbar.currentSnackbarData == null) {
            PlayFab(
                onClick = { viewModel.playFolder(shuffle = false) },
                onLongClick = { viewModel.playFolder(shuffle = true) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { ZeneloSnackbar(it, snackbarIcon) }
    }

    coverPickerFor?.let { path ->
        CoverPickerDialog(path, onDismiss = { coverPickerFor = null }, onDone = viewModel::showMessage)
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
private fun TopBar(state: BrowserState, viewModel: BrowserViewModel) {
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
                IconButton(onClick = viewModel::toggleSort) {
                    Icon(
                        Icons.Rounded.SortByAlpha,
                        "Sort",
                        tint = if (state.sortDescending) ZeneloColors.Mustard else ZeneloColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
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
    onClick: () -> Unit,
    onAction: (SwipeAction) -> Unit,
    onDownloadCover: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListRow(
        title = file.name,
        subtitle = trackSubtitle(file, info),
        titleColor = if (isCurrent) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
        onClick = onClick,
        leading = {
            if (isCurrent) {
                IconTile(Icons.Rounded.GraphicEq, ZeneloColors.Celadon, ZeneloColors.CeladonTint)
            } else {
                IconTile(Icons.Outlined.MusicNote, ZeneloColors.TextSecondary)
            }
        },
        trailing = {
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
                        text = { Text("Download cover…") },
                        leadingIcon = { Icon(Icons.Outlined.Image, null, tint = ZeneloColors.Celadon) },
                        onClick = {
                            menu = false
                            onDownloadCover()
                        },
                    )
                }
            }
        },
    )
}
