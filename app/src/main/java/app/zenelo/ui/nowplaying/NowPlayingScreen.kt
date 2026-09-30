package app.zenelo.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.rememberUpdatedState
import app.zenelo.data.settings.PullDownArea
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.ui.components.SelectionMark
import app.zenelo.ui.components.SelectAllIcon
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.scale
import app.zenelo.ui.components.SonicPathItems
import app.zenelo.ui.components.UNPLAYABLE_ALPHA
import app.zenelo.ui.components.rememberDownloaded
import app.zenelo.ui.components.rememberUnplayable
import app.zenelo.ui.components.rememberOnline
import app.zenelo.ui.settings.zeneloSwitchColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.player.PlayerController
import app.zenelo.player.PlayerUiState
import app.zenelo.data.db.ArtistRow
import app.zenelo.data.db.TrackEntity
import app.zenelo.library.artistKey
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.RemoteFolders
import app.zenelo.library.artistTag
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.ui.graphics.compositeOver
import app.zenelo.data.settings.QueueSwipeAction
import app.zenelo.data.settings.SwipeSlot
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.ui.components.SwipeOption
import app.zenelo.ui.components.SwipeableRow
import app.zenelo.ui.components.accent
import app.zenelo.ui.components.icon
import app.zenelo.ui.components.marquee
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.zenelo.library.LyricLine
import app.zenelo.library.Lrc
import app.zenelo.ui.components.Artwork
import app.zenelo.ui.components.CoverPickerDialog
import app.zenelo.ui.components.TagEditorDialog
import app.zenelo.ui.components.formatChip
import app.zenelo.ui.theme.PlexSans
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.zenelo.ui.components.ZeneloSlider
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.formatDuration
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

private const val PAGE_LYRICS = 0
private const val PAGE_COVER = 1
private const val PAGE_QUEUE = 2

/**
 * Swipe left/right between lyrics ← cover → queue. The track title, seek bar and transport stay
 * fixed below the pager on every page, as in the design.
 */
@Composable
fun NowPlayingScreen(
    sheet: PlayerSheet,
    onOpenFolder: (File) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    /** Library artists (null until loaded): only those get a "Go to artist" item. */
    artists: StateFlow<List<ArtistRow>?>,
) {
    val onBack = sheet::close
    val container = appContainer()
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = PAGE_COVER) { 3 }
    val scope = rememberCoroutineScope()

    val favorites = container.db.favorites()
    // Liked under its local or its server copy: the same song.
    val links by container.serverLinks.links.collectAsState()
    val isFavorite by remember(state.mediaId, links) {
        state.mediaId?.let { favorites.observeIsFavoriteAny(listOf(it) + links.copiesOf(it)) } ?: flowOf(false)
    }.collectAsState(initial = false)

    val trackFlow = remember(state.mediaId) { state.mediaId?.let(container.db.tracks()::observe) ?: flowOf(null) }
    val track by trackFlow.collectAsState(initial = null)
    var coverPicker by remember { mutableStateOf(false) }
    var tagEditor by remember { mutableStateOf(false) }
    var coverMessage by remember { mutableStateOf<String?>(null) }

    // Pull-down to collapse, starting inside the area chosen in settings (top bar ... whole screen);
    // the sheet follows the finger. Handled after the children: lists that scroll vertically and
    // the seek bar keep their drags.
    val areaFlow = remember { container.settings.settings.map { it.pullDownArea } }
    val area by areaFlow.collectAsStateWithLifecycle(initialValue = PullDownArea.TOP_THIRD)
    val density = LocalDensity.current
    // As easy as pulling the mini player up.
    val threshold = with(density) { 40.dp.toPx() }
    val topBarHeight = with(density) { 64.dp.toPx() }
    val pullGesture = Modifier.pointerInput(area) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val zone = maxOf(topBarHeight, size.height * area.fraction)
            if (down.position.y > zone) return@awaitEachGesture
            // This page moves with the sheet: positions on the screen, not in the moving page, else the
            // sheet lags behind the finger and the fling's speed reads as almost nothing.
            fun screen(position: Offset) = Offset(position.x, position.y + sheet.drawnOffsetPx)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, screen(down.position))
            var lastY = 0f
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                if (over > 0) {
                    change.consume()
                    sheet.dragStart()
                    sheet.dragBy(over)
                    lastY = screen(change.position).y
                }
            } ?: return@awaitEachGesture
            verticalDrag(start.id) { change ->
                val y = screen(change.position).y
                change.consume()
                tracker.addPosition(change.uptimeMillis, screen(change.position))
                sheet.dragBy(y - lastY)
                lastY = y
            }
            sheet.dragEnd(tracker.calculateVelocity().y, threshold)
        }
    }

    if (tagEditor && state.mediaId != null) {
        TagEditorDialog(state.mediaId!!, fromFileName = false, onDismiss = { tagEditor = false }, onDone = { coverMessage = it })
    }
    if (coverPicker && state.mediaId != null) {
        CoverPickerDialog(state.mediaId!!, onDismiss = { coverPicker = false }, onDone = { coverMessage = it })
    }
    coverMessage?.let { text ->
        LaunchedEffect(text) {
            kotlinx.coroutines.delay(2500)
            coverMessage = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .then(pullGesture),
    ) {
        TopBar(
            state,
            track,
            artists,
            onBack,
            onOpenFolder,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            onDownloadCover = { coverPicker = true },
            onEditTags = { tagEditor = true },
            onMessage = { coverMessage = it },
        )
        HorizontalPager(
            pager,
            Modifier.weight(1f),
            // Keep neighbours composed so the cover doesn't pop in/out while swiping.
            beyondViewportPageCount = 1,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pager,
                snapAnimationSpec = spring(stiffness = Spring.StiffnessMedium),
            ),
        ) { page ->
            when (page) {
                PAGE_LYRICS -> LyricsPage(state, player)
                PAGE_COVER -> CoverPage(state)
                PAGE_QUEUE -> QueuePage(player)
            }
        }
        coverMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.Mustard, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        PageSwitcher(pager.currentPage) { scope.launch { pager.animateScrollToPage(it, animationSpec = tween(180)) } }
        TrackTitle(state, isFavorite) {
            val id = state.mediaId ?: return@TrackTitle
            scope.launch {
                favorites.toggle(FavoriteEntity(id, FavoriteKind.TRACK, state.title ?: File(id).nameWithoutExtension, state.artist), container.serverLinks.current.copiesOf(id))
            }
        }
        SeekBar(state, player, track)
        Transport(state, player)
    }
}

@Composable
private fun TopBar(
    state: PlayerUiState,
    track: TrackEntity?,
    artists: StateFlow<List<ArtistRow>?>,
    onBack: () -> Unit,
    onOpenFolder: (File) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onDownloadCover: () -> Unit,
    onEditTags: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val downloads = appContainer().mstreamDownloads
    val scope = rememberCoroutineScope()
    val picker = appContainer().playlistPicker
    // mStream tracks have no folder here (yet): the label shows the server's path instead.
    val remote = MStreamPaths.isRemote(state.mediaId)
    val folder = state.mediaId?.let { if (remote) RemoteFolders.folder(MStreamPaths.dir(it)) else File(it).parentFile }
    val roots = appContainer().fileBrowser.let { fs -> remember { fs.roots() } }
    val settingsFlow = appContainer().settings.settings
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val known by artists.collectAsStateWithLifecycle()
    // The album's page needs an album tag; artists: the track's and the album's, each name of a
    // multi-artist tag on its own, the ones with a page (the album artist's always has one).
    val albumKey = track?.albumKey?.takeIf { track.album != null }
    val artistNames = remember(track, settings?.artistSeparators, settings?.artistExceptions, known) {
        val splitter = settings?.artistSplitter ?: return@remember emptyList()
        val t = track ?: return@remember emptyList()
        val grouped = splitter.split(t.artistTag).map(::artistKey).toSet()
        val pages = known?.mapTo(HashSet()) { it.key }
        (splitter.split(t.artist) + splitter.split(t.albumArtist))
            .filter { artistKey(it) in grouped || pages?.contains(artistKey(it)) == true }
            .distinctBy(::artistKey)
    }
    // Taller than the icons need: it doubles as the pull-down handle.
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (remote) "PLAYING FROM MSTREAM" else "PLAYING FROM FOLDER", style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted)
            Text(
                // Up to three segments below the storage root: "Music / Ambient / Harbor".
                if (remote) {
                    MStreamPaths.dir(state.mediaId!!).let(MStreamPaths::serverPath).split('/').filter { it.isNotEmpty() }.takeLast(3).joinToString(" / ")
                } else {
                    folder?.let { f ->
                        val root = roots.firstOrNull { f.startsWith(it.dir) }?.dir
                        val relative = if (root != null) f.relativeTo(root).path else f.path
                        relative.split(File.separatorChar).filter { it.isNotEmpty() }.takeLast(3).joinToString(" / ")
                    }.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Open folder") },
                    enabled = folder != null,
                    onClick = {
                        menu = false
                        folder?.let(onOpenFolder)
                    },
                )
                if (albumKey != null) {
                    DropdownMenuItem(
                        text = { Text("Go to album") },
                        onClick = {
                            menu = false
                            onOpenAlbum(albumKey)
                        },
                    )
                }
                artistNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(if (artistNames.size == 1) "Go to artist" else "Go to artist $name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            menu = false
                            onOpenArtist(artistKey(name))
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Add to playlist…") },
                    enabled = state.mediaId != null,
                    onClick = {
                        menu = false
                        state.mediaId?.let { picker.pick(listOf(it)) }
                    },
                )
                // Server features need the network: offline they're not offered.
                val online = rememberOnline()
                if (remote && online && !rememberDownloaded(state.mediaId)) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        onClick = {
                            menu = false
                            state.mediaId?.let { path -> scope.launch { downloads.request(listOf(path))?.let(onMessage) } }
                        },
                    )
                }
                if (settings?.mstream != null && online) {
                    val dj = settings?.autoDj?.enabled == true
                    val container = appContainer()
                    DropdownMenuItem(
                        text = { Text("Auto DJ") },
                        trailingIcon = { Switch(checked = dj, onCheckedChange = null, colors = zeneloSwitchColors(), modifier = Modifier.scale(0.8f)) },
                        onClick = {
                            menu = false
                            container.autoDj.setEnabled(!dj) { container.player.playFiles(it) }
                            onMessage(if (dj) "Auto DJ off" else "Auto DJ on")
                        },
                    )
                    state.mediaId?.let { path -> SonicPathItems(path, icons = false) { menu = false } }
                }
                DropdownMenuItem(
                    text = { Text("Edit tags…") },
                    // The server's files aren't ours to write.
                    enabled = state.mediaId != null && !remote,
                    onClick = {
                        menu = false
                        onEditTags()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Download cover…") },
                    enabled = state.mediaId != null,
                    onClick = {
                        menu = false
                        onDownloadCover()
                    },
                )
            }
        }
    }
}

@Composable
private fun CoverPage(state: PlayerUiState) {
    // A large square as in the design, capped so it never turns into a full-screen image.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 56.dp, maxHeight - 24.dp, 300.dp)
        Artwork(state.artwork, Modifier.size(side), cornerRadius = 12.dp, largePlaceholder = true, file = state.artworkFile)
    }
}

/**
 * Synced lyrics: the current line in mustard, bold and larger, kept a third of the way down;
 * tapping a line seeks there. Plain lyrics are shown as static text.
 */
@Composable
private fun LyricsPage(state: PlayerUiState, player: PlayerController) {
    val dao = appContainer().db.lyrics()
    val flow = remember(state.mediaId) { state.mediaId?.let(dao::observe) ?: flowOf(null) }
    val lyrics by flow.collectAsState(initial = null)
    val synced = remember(lyrics?.synced) { lyrics?.synced?.let(Lrc::parse).orEmpty().filter { it.text.isNotBlank() } }
    val plain = lyrics?.plain

    when {
        state.mediaId == null -> Unit
        synced.isNotEmpty() -> SyncedLyrics(synced, player)
        !plain.isNullOrBlank() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            items(plain.lines()) { line ->
                Text(line, style = LyricStyle, color = ZeneloColors.TextSecondary, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        else -> Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                when {
                    lyrics?.instrumental == true -> "Instrumental"
                    // Looked up (online, by tags and file name) and nothing was found.
                    lyrics != null -> "Lyrics not found"
                    else -> "No lyrics yet\nThey download over Wi-Fi when available."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ZeneloColors.TextMuted,
            )
        }
    }
}

private val LyricStyle = TextStyle(fontFamily = PlexSans, fontSize = 15.5.sp, lineHeight = 22.sp)
private val CurrentLyricStyle = TextStyle(fontFamily = PlexSans, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, player: PlayerController) {
    val position by player.position.collectAsStateWithLifecycle()
    val current = remember(lines, position) { lines.indexOfLast { it.timeMs <= position + 150 } }
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The current line is kept a third of the way down. Only a small gap above the first line:
        // near the start the lines can't scroll that far, so the current one moves down to there.
        val topPad = 16.dp
        val anchor = with(LocalDensity.current) { (maxHeight / 3 - topPad).roundToPx() }
        LaunchedEffect(current) {
            if (current < 0) return@LaunchedEffect
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
            if (item != null) {
                listState.animateScrollBy((item.offset - anchor).toFloat())
            } else {
                listState.scrollToItem(current)
                listState.scrollBy(-anchor.toFloat())
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = topPad, bottom = maxHeight * 2 / 3),
        ) {
            itemsIndexed(lines) { index, line ->
                Text(
                    line.text,
                    style = if (index == current) CurrentLyricStyle else LyricStyle,
                    color = when {
                        index == current -> ZeneloColors.Mustard
                        index < current -> ZeneloColors.TextMuted
                        else -> ZeneloColors.TextSecondary
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { player.seekTo(line.timeMs) }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * The queue: played tracks (optional, dimmed), the current one, then what's next in real play
 * order. Rows are drawn by index from the queue snapshot, so a 7000-track queue costs nothing
 * until scrolled. Drag the handle to reorder, long-press to select several, swipe left for the
 * actions set in settings (right swipes stay with the page pager).
 */
@Composable
private fun QueuePage(player: PlayerController) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val queue by player.queue.state.collectAsStateWithLifecycle()
    val settingsFlow = remember { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = ZeneloSettings())
    val showHistory = settings.queueHistory

    // Display offsets from the current track; with history there's no wrap-around.
    val firstOffset = if (showHistory) -queue.history else 0
    val count = if (showHistory) queue.total else queue.size
    // Local copy so a drag can reorder rows live; the queue itself changes on drop. One state object
    // for the page's lifetime: the reorder library keeps its first onMove lambda, so a state that is
    // re-created on queue changes (every track change) would leave drags writing to a dead copy.
    val idsState = remember { mutableStateOf(List(count) { queue.idAt(firstOffset + it) }) }
    var ids by idsState
    val currentId = queue.currentId
    val liveCurrentId = rememberUpdatedState(currentId)
    // While a row is being dragged, queue updates (track changes) mustn't reset the local order.
    var reordering by remember { mutableStateOf(false) }
    LaunchedEffect(queue, showHistory) {
        if (!reordering) idsState.value = List(count) { queue.idAt(firstOffset + it) }
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (showHistory) (queue.history - 1).coerceAtLeast(0) else 0)
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val cur = liveCurrentId.value
        if (from.key == cur || to.key == cur) return@rememberReorderableLazyListState
        idsState.value = idsState.value.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    // Follow the current track (one played row stays visible above it), unless the user is busy.
    LaunchedEffect(currentId, showHistory) {
        if (!reorderState.isAnyItemDragging) listState.animateScrollToItem((firstOffsetIndex(showHistory, queue.history) - 1).coerceAtLeast(0))
    }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    val swipeOptions = remember(settings.queueSwipes) {
        settings.queueSwipes.filterValues { it != QueueSwipeAction.NONE }
            .map { (slot, action) -> slot.slot to SwipeOption(action.label, action.icon, action.accent) }.toMap()
    }

    fun onSwipe(id: Int, slot: SwipeSlot) {
        val action = settings.queueSwipes.entries.firstOrNull { it.key.slot == slot }?.value ?: return
        when (action) {
            QueueSwipeAction.NONE -> Unit
            QueueSwipeAction.REMOVE -> player.removeFromQueue(setOf(id))
            QueueSwipeAction.PLAY_NEXT -> player.playNextInQueue(id)
            QueueSwipeAction.MOVE_TO_END -> player.moveInQueue(id, beforeId = null)
            QueueSwipeAction.FAVORITE -> scope.launch {
                val path = queue.pathOf(id)
                val info = player.queue.info(path)
                container.db.favorites().toggle(
                    FavoriteEntity(path, FavoriteKind.TRACK, info?.title ?: File(path).nameWithoutExtension, info?.artist),
                    container.serverLinks.current.copiesOf(path),
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        QueueHeader(
            upcoming = (queue.size - 1).coerceAtLeast(0),
            selectedCount = selected.size,
            selecting = selecting,
            onSelectMode = { selecting = true },
            allSelected = selected.isNotEmpty() && selected.size >= ids.count { it != currentId },
            onSelectAll = {
                val all = ids.filter { it != currentId }.toSet()
                if (selected.containsAll(all)) {
                    selected = emptySet()
                    selecting = false
                } else {
                    selected = all
                }
            },
            onClear = { player.clearQueue() },
            onDeleteSelected = {
                player.removeFromQueue(selected)
                selected = emptySet()
                selecting = false
            },
            onCancelSelection = {
                selected = emptySet()
                selecting = false
            },
        )
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                val offset = index + firstOffset
                val isCurrent = id == currentId
                val played = offset < 0
                ReorderableItem(reorderState, key = id) { dragging ->
                    SwipeableRow(
                        options = swipeOptions,
                        onSwipe = { slot -> onSwipe(id, slot) },
                        enabled = !isCurrent && !selecting,
                    ) {
                        QueueRow(
                            path = queue.pathOf(id),
                            revision = queue.revision,
                            number = if (played) null else offset + 1,
                            isCurrent = isCurrent,
                            played = played,
                            dragging = dragging,
                            selected = id in selected,
                            selecting = selecting,
                            markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT,
                            onClick = {
                                if (selecting) {
                                    if (!isCurrent) selected = if (id in selected) selected - id else selected + id
                                    // Deselecting the last one closes selection mode.
                                    if (selected.isEmpty()) selecting = false
                                } else {
                                    player.skipTo(id)
                                }
                            },
                            onLongClick = {
                                if (!isCurrent) {
                                    selecting = true
                                    selected = selected + id
                                }
                            },
                            handle = {
                                if (!isCurrent && !selecting) {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        "Reorder",
                                        tint = ZeneloColors.TextMuted,
                                        modifier = Modifier
                                            // Holding the handle still is a slow drag, not the row's long-press (select).
                                            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
                                            .draggableHandle(
                                                onDragStarted = { reordering = true },
                                                onDragStopped = {
                                                    val order = idsState.value
                                                    val at = order.indexOf(id)
                                                    // Dropped last in a wrapped (repeat-all) list = just before the current track.
                                                    val before = order.getOrNull(at + 1)
                                                        ?: liveCurrentId.value.takeIf { !showHistory && queue.size == queue.total && queue.history > 0 }
                                                    player.moveInQueue(id, before)
                                                    reordering = false
                                                },
                                            )
                                            .padding(8.dp)
                                            .size(24.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun firstOffsetIndex(showHistory: Boolean, history: Int) = if (showHistory) history else 0

@Composable
private fun QueueHeader(
    upcoming: Int,
    selectedCount: Int,
    selecting: Boolean,
    onSelectMode: () -> Unit,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelSelection: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) {
            IconButton(onClick = onCancelSelection) { Icon(Icons.Rounded.Close, "Cancel selection", Modifier.size(20.dp)) }
            Text("$selectedCount selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onSelectAll) {
                SelectAllIcon(allSelected)
            }
            IconButton(onClick = onDeleteSelected, enabled = selectedCount > 0) {
                Icon(Icons.Outlined.DeleteOutline, "Remove selected", tint = ZeneloColors.Danger, modifier = Modifier.size(22.dp))
            }
        } else {
            Text("UP NEXT · $upcoming", style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted, modifier = Modifier.weight(1f))
            IconButton(onClick = onSelectMode, enabled = upcoming > 0) {
                Icon(Icons.Outlined.Checklist, "Select", tint = ZeneloColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onClear, enabled = upcoming > 0) {
                Icon(Icons.Outlined.Delete, "Clear queue", tint = ZeneloColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    path: String,
    revision: Int,
    number: Int?,
    isCurrent: Boolean,
    played: Boolean,
    dragging: Boolean,
    selected: Boolean,
    selecting: Boolean,
    markLeft: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    handle: @Composable () -> Unit,
) {
    val queue = appContainer().queue
    val info by produceState(queue.cachedInfo(path), path, revision) { value = queue.info(path) }
    // Offline and only on the server: grey, it'll be skipped.
    val unplayable = rememberUnplayable(path)
    val accent = when {
        isCurrent -> ZeneloColors.Celadon
        played -> ZeneloColors.TextMuted
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    dragging -> ZeneloColors.Card
                    selected -> ZeneloColors.MustardTint.compositeOver(ZeneloColors.Background)
                    else -> ZeneloColors.Background
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (unplayable) UNPLAYABLE_ALPHA else 1f)
            .heightIn(min = 46.dp)
            .padding(start = 20.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The playing track can't be selected: no mark on it.
        val mark = selecting && !isCurrent
        if (mark && markLeft) {
            SelectionMark(selected, Modifier.padding(end = 14.dp))
        } else {
            Text(
                number?.let { "%02d".format(it) } ?: "·",
                style = MaterialTheme.typography.labelMedium,
                color = accent ?: ZeneloColors.TextMuted,
                modifier = Modifier.width(34.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                info?.title ?: path.substringAfterLast('/').substringBeforeLast('.'),
                style = MaterialTheme.typography.bodyLarge,
                color = accent ?: ZeneloColors.TextPrimary,
                maxLines = 1,
                modifier = Modifier.marquee(),
            )
            info?.artist?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted, maxLines = 1)
            }
        }
        info?.durationMs?.takeIf { it > 0 }?.let {
            Text(formatDuration(it), style = MaterialTheme.typography.labelMedium, color = ZeneloColors.TextMuted, modifier = Modifier.padding(start = 8.dp))
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (mark && !markLeft) SelectionMark(selected) else handle()
        }
    }
}

/** Lyrics / cover / queue icons under the pager; the active page is mustard. */
@Composable
private fun PageSwitcher(current: Int, onSelect: (Int) -> Unit) {
    val icons: List<ImageVector> = listOf(Icons.Outlined.Mic, Icons.Outlined.Album, Icons.AutoMirrored.Rounded.QueueMusic)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
        icons.forEachIndexed { page, icon ->
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable { onSelect(page) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (page == current) ZeneloColors.Mustard else ZeneloColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackTitle(state: PlayerUiState, isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                state.title ?: "Nothing playing",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.marquee(),
            )
            Text(
                listOfNotNull(state.artist, state.album).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = ZeneloColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleFavorite, enabled = state.mediaId != null) {
            Icon(
                if (isFavorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = ZeneloColors.Celadon,
            )
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, player: PlayerController, track: TrackEntity?) {
    val position by player.position.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L).toFloat()
    val value = (dragging ?: position.toFloat()).coerceIn(0f, duration)

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        ZeneloSlider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(value.toLong()), style = MaterialTheme.typography.labelMedium, color = ZeneloColors.TextMuted)
            Spacer(Modifier.weight(1f))
            state.mediaId?.let { path ->
                // TODO: "FLAC · 24/96" once bit depth / sample rate are read from the stream.
                Text(
                    formatChip(File(path).extension, track),
                    style = MaterialTheme.typography.labelMedium,
                    color = ZeneloColors.Celadon,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(ZeneloColors.CeladonTint)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium, color = ZeneloColors.TextMuted)
        }
    }
}

@Composable
private fun Transport(state: PlayerUiState, player: PlayerController) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleTile(Icons.Rounded.Shuffle, "Shuffle", active = state.shuffle) { player.toggleShuffle() }
        IconButton(onClick = { player.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(30.dp)) }
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(ZeneloColors.Mustard)
                .clickable { player.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.isPlaying) "Pause" else "Play",
                tint = ZeneloColors.OnMustard,
                modifier = Modifier.size(30.dp),
            )
        }
        IconButton(onClick = { player.next() }) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(30.dp)) }
        ToggleTile(
            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repeat",
            active = state.repeatMode != Player.REPEAT_MODE_OFF,
        ) { player.cycleRepeat() }
    }
}

/** Rounded square for shuffle / repeat: celadon on a celadon tint when active. */
@Composable
private fun ToggleTile(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) ZeneloColors.CeladonTint else ZeneloColors.Card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) ZeneloColors.Celadon else ZeneloColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}
