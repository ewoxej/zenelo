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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
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
import app.zenelo.data.db.TrackEntity
import app.zenelo.library.LyricLine
import app.zenelo.library.Lrc
import app.zenelo.ui.components.Artwork
import app.zenelo.ui.components.CoverPickerDialog
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
fun NowPlayingScreen(onBack: () -> Unit, onOpenFolder: (File) -> Unit) {
    val container = appContainer()
    val player = container.player
    val state by player.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = PAGE_COVER) { 3 }
    val scope = rememberCoroutineScope()

    val favorites = container.db.favorites()
    val isFavorite by remember(state.mediaId) {
        state.mediaId?.let(favorites::observeIsFavorite) ?: flowOf(false)
    }.collectAsState(initial = false)

    val trackFlow = remember(state.mediaId) { state.mediaId?.let(container.db.tracks()::observe) ?: flowOf(null) }
    val track by trackFlow.collectAsState(initial = null)
    var coverPicker by remember { mutableStateOf(false) }
    var coverMessage by remember { mutableStateOf<String?>(null) }

    // Pull-down to collapse: fed by drags on non-scrolling areas and by overscroll of the lists.
    var pull by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 110.dp.toPx() }
    val settle: (Float) -> Unit = { velocity ->
        if (pull > threshold || velocity > 1800f) {
            onBack()
        } else {
            scope.launch { animate(pull, 0f, animationSpec = tween(140)) { value, _ -> pull = value } }
        }
    }
    val pullConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // While pulled down, scrolling back up first undoes the pull.
                if (available.y < 0 && pull > 0) {
                    val used = maxOf(available.y, -pull)
                    pull += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) {
                    pull += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
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
            .graphicsLayer { translationY = pull }
            .nestedScroll(pullConnection)
            .draggable(
                state = rememberDraggableState { delta -> pull = (pull + delta).coerceAtLeast(0f) },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity -> settle(velocity) },
            ),
    ) {
        TopBar(state, onBack, onOpenFolder, onDownloadCover = { coverPicker = true })
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
                favorites.toggle(FavoriteEntity(id, FavoriteKind.TRACK, state.title ?: File(id).nameWithoutExtension, state.artist))
            }
        }
        SeekBar(state, player, track)
        Transport(state, player)
    }
}

@Composable
private fun TopBar(state: PlayerUiState, onBack: () -> Unit, onOpenFolder: (File) -> Unit, onDownloadCover: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val folder = state.mediaId?.let { File(it).parentFile }
    val roots = appContainer().fileBrowser.let { fs -> remember { fs.roots() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PLAYING FROM FOLDER", style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted)
            Text(
                // Up to three segments below the storage root: "Music / Ambient / Harbor".
                folder?.let { f ->
                    val root = roots.firstOrNull { f.startsWith(it.dir) }?.dir
                    val relative = if (root != null) f.relativeTo(root).path else f.path
                    relative.split(File.separatorChar).filter { it.isNotEmpty() }.takeLast(3).joinToString(" / ")
                }.orEmpty(),
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
        // Top padding of a third of the page puts the scrolled-to line there.
        val topPad = maxHeight / 3
        LaunchedEffect(current) {
            if (current >= 0) listState.animateScrollToItem(current)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = topPad, bottom = maxHeight - topPad),
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

@Composable
private fun QueuePage(player: PlayerController) {
    // TODO: drag to reorder (sh.calvin.reorderable).
    // Current track first, then what plays next in real order (shuffle included). Rows are drawn
    // by index from the queue snapshot, so a 7000-track queue costs nothing until scrolled.
    val queue by player.queue.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        items(queue.size) { offset ->
            val path = queue.pathAt(offset)
            val info by produceState(player.queue.cachedInfo(path), path) { value = player.queue.info(path) }
            val isCurrent = offset == 0
            val accent = if (isCurrent) ZeneloColors.Celadon else null
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { player.skipTo(offset) }
                    .heightIn(min = 46.dp)
                    .padding(start = 20.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%02d".format(offset + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent ?: ZeneloColors.TextMuted,
                    modifier = Modifier.width(34.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        info?.title ?: path.substringAfterLast('/').substringBeforeLast('.'),
                        style = MaterialTheme.typography.bodyLarge,
                        color = accent ?: ZeneloColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    info?.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted, maxLines = 1)
                    }
                }
                info?.durationMs?.takeIf { it > 0 }?.let {
                    Text(formatDuration(it), style = MaterialTheme.typography.labelMedium, color = ZeneloColors.TextMuted)
                }
                IconButton(onClick = { player.removeFromQueue(offset) }, enabled = !isCurrent, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        "Remove",
                        tint = if (isCurrent) ZeneloColors.Background else ZeneloColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
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
                overflow = TextOverflow.Ellipsis,
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
