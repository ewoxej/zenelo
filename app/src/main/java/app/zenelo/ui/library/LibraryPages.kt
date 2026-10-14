package app.zenelo.ui.library

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxWidth
import app.zenelo.ui.theme.PlexSans
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridItemSpan
import app.zenelo.library.Lettered
import app.zenelo.ui.components.rememberTopGridState
import app.zenelo.ui.components.rememberTopListState
import app.zenelo.ui.components.applicableSwipes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.AlbumRow
import app.zenelo.data.db.ArtistRow
import app.zenelo.data.db.GenreRow
import androidx.compose.material.icons.outlined.Category
import app.zenelo.data.db.RecentPlay
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.LibraryView
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.data.settings.SortPage
import app.zenelo.library.Library
import app.zenelo.library.LibrarySort
import app.zenelo.mstream.MStreamPaths
import app.zenelo.library.toAudioFile
import app.zenelo.ui.components.FabClearance
import app.zenelo.ui.components.GroupHeader
import app.zenelo.ui.components.SwipeableRow
import app.zenelo.ui.components.TrackMenu
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.formatDuration
import app.zenelo.ui.components.listSwipeOptions
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Where the library pages lead: album, artist and genre pages. */
class LibraryNav(val onOpenAlbum: (String) -> Unit, val onOpenArtist: (String) -> Unit, val onOpenGenre: (String) -> Unit = {})

/**
 * The playing track's path, for highlighting, and whether this phone plays (the Stop button): a
 * device the player follows plays elsewhere, and Stop here wouldn't stop it.
 */
@Composable
fun rememberNowPlaying(): Pair<String?, Boolean> {
    val state by appContainer().player.state.collectAsStateWithLifecycle()
    return state.mediaId to (state.isPlaying && state.remoteDevice == null)
}

@Composable
private fun currentAlbumKey(mediaId: String?): String? {
    val dao = appContainer().db.tracks()
    val flow = remember(mediaId) { mediaId?.let(dao::observe) ?: flowOf(null) }
    val track by flow.collectAsStateWithLifecycle(initialValue = null)
    return track?.albumKey
}

@Composable
fun AlbumsScreen(vm: LibraryViewModel, nav: LibraryNav, onBack: (() -> Unit)?) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, _) = rememberNowPlaying()
    val currentKey = currentAlbumKey(mediaId)
    val search = remember { PageSearch() }
    val selection = rememberSelection<String>()
    val sort = settings.sorts[SortPage.ALBUMS] ?: SortPage.ALBUMS.default
    val view = settings.albumsView
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val all = albums.orEmpty()
    val visible = remember(all, search.query) { all.filter { search.matches(it.album, it.artist) } }
    val chosen = { visible.filter { it.key in selection.keys } }
    // Sorted by text: a header per letter, and the A–Z rail once the list is long.
    val letters = LibrarySort.hasLetterHeaders(sort) && search.query.isBlank()
    val items = remember(visible, sort, letters) { LibrarySort.lettered(visible) { if (letters) LibrarySort.albumHeader(it, sort) else null } }
    val (railLetters, railPositions) = remember(items) { railOf(items) }
    val rail = letters && visible.size > RAIL_MIN && railLetters.size > 1

    LibraryScaffold(
        sourceMenu = true,
        title = "Albums",
        count = albums?.size,
        caption = sort.label,
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(visible.map { it.key }) },
        allSelected = visible.isNotEmpty() && selection.keys.size >= visible.size,
        onSelectionAction = { vm.onAlbums(chosen(), it) },
        onSelectionPlay = { vm.playAlbums(chosen(), it) },
        sortFields = SortPage.ALBUMS.fields,
        sort = sort,
        onSort = { vm.setSort(SortPage.ALBUMS, it) },
        view = view,
        onNextView = { vm.setView(SortPage.ALBUMS, view.next()) },
    ) {
        val onClick = { album: AlbumRow -> if (selection.active) selection.toggle(album.key) else nav.onOpenAlbum(album.key) }
        if (view == LibraryView.LIST) {
            val listState = rememberTopListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(end = if (rail) 18.dp else 0.dp, bottom = FabClearance)) {
                items(items, key = { when (it) { is Lettered.Header -> "h:${it.first}"; is Lettered.Row -> it.item.key } }) { entry ->
                    when (entry) {
                        is Lettered.Header -> GroupHeader(entry.letter)
                        is Lettered.Row -> {
                            val album = entry.item
                            LibraryRow(
                                title = album.album,
                                subtitle = listOfNotNull(album.artist, albumDetail(album, sort)).joinToString(" · "),
                                coverPath = album.coverPath,
                                // The album's marks, not its cover track's.
                                storage = album.storage.mark,
                                coverSize = 48.dp,
                                isCurrent = album.key == currentKey,
                                selecting = selection.active,
                                selected = album.key in selection.keys,
                                markLeft = markLeft,
                                onClick = { onClick(album) },
                                onLongClick = { selection.start(album.key) },
                                trailing = { Chevron() },
                            )
                        }
                    }
                }
            }
            if (rail) LetterRail(railLetters, listState, railPositions, Modifier.align(Alignment.CenterEnd))
        } else {
            val columns = if (view == LibraryView.GRID3) 3 else 2
            val gridState = rememberTopGridState()
            LazyVerticalGrid(
                GridCells.Fixed(columns),
                Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = if (rail) 26.dp else 16.dp, top = 4.dp, bottom = FabClearance),
                horizontalArrangement = Arrangement.spacedBy(if (columns == 3) 10.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { when (it) { is Lettered.Header -> "h:${it.first}"; is Lettered.Row -> it.item.key } }, span = { if (it is Lettered.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { entry ->
                    when (entry) {
                        is Lettered.Header -> GroupHeader(entry.letter, inset = 0.dp)
                        is Lettered.Row -> {
                            val album = entry.item
                            AlbumTile(
                                album = album,
                                detail = albumDetail(album, sort),
                                compact = columns == 3,
                                isCurrent = album.key == currentKey,
                                selecting = selection.active,
                                selected = album.key in selection.keys,
                                markLeft = markLeft,
                                onClick = { onClick(album) },
                                onLongClick = { selection.start(album.key) },
                            )
                        }
                    }
                }
            }
            if (rail) LetterRail(railLetters, gridState, railPositions, Modifier.align(Alignment.CenterEnd))
        }
        EmptyNote(albums, visible.isEmpty(), search, "No albums yet. Albums come from the tags of your files once the library is scanned.")
    }
}

@Composable
fun ArtistsScreen(vm: LibraryViewModel, nav: LibraryNav, onBack: (() -> Unit)?) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val search = remember { PageSearch() }
    val selection = rememberSelection<String>()
    val sort = settings.sorts[SortPage.ARTISTS] ?: SortPage.ARTISTS.default
    val view = settings.artistsView
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val all = artists.orEmpty()
    val visible = remember(all, search.query) { all.filter { search.matches(it.name) } }
    val chosen = { visible.filter { it.key in selection.keys } }
    // Sorted by text: a header per letter, and the A–Z rail once the list is long.
    val letters = LibrarySort.hasLetterHeaders(sort) && search.query.isBlank()
    val items = remember(visible, sort, letters) { LibrarySort.lettered(visible) { if (letters) LibrarySort.artistHeader(it, sort) else null } }
    val (railLetters, railPositions) = remember(items) { railOf(items) }
    val rail = letters && visible.size > RAIL_MIN && railLetters.size > 1

    LibraryScaffold(
        sourceMenu = true,
        title = "Artists",
        count = artists?.size,
        caption = sort.label,
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(visible.map { it.key }) },
        allSelected = visible.isNotEmpty() && selection.keys.size >= visible.size,
        onSelectionAction = { vm.onArtists(chosen(), it) },
        onSelectionPlay = { vm.playArtists(chosen(), it) },
        sortFields = SortPage.ARTISTS.fields,
        sort = sort,
        onSort = { vm.setSort(SortPage.ARTISTS, it) },
        view = view,
        onNextView = { vm.setView(SortPage.ARTISTS, view.next()) },
    ) {
        val onClick = { artist: ArtistRow -> if (selection.active) selection.toggle(artist.key) else nav.onOpenArtist(artist.key) }
        if (view == LibraryView.LIST) {
            val listState = rememberTopListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(end = if (rail) 18.dp else 0.dp, bottom = FabClearance)) {
                items(items, key = { when (it) { is Lettered.Header -> "h:${it.first}"; is Lettered.Row -> it.item.key } }) { entry ->
                    when (entry) {
                        is Lettered.Header -> GroupHeader(entry.letter)
                        is Lettered.Row -> {
                            val artist = entry.item
                            LibraryRow(
                                title = artist.name,
                                subtitle = artistDetail(artist),
                                subtitleMono = true,
                                coverPath = artist.coverPath,
                                coverShape = CircleShape,
                                coverSize = 44.dp,
                                placeholder = Icons.Outlined.Person,
                                storage = artist.storage.mark,
                                selecting = selection.active,
                                selected = artist.key in selection.keys,
                                markLeft = markLeft,
                                onClick = { onClick(artist) },
                                onLongClick = { selection.start(artist.key) },
                                trailing = { Chevron() },
                            )
                        }
                    }
                }
            }
            if (rail) LetterRail(railLetters, listState, railPositions, Modifier.align(Alignment.CenterEnd))
        } else {
            val columns = if (view == LibraryView.GRID3) 3 else 2
            val gridState = rememberTopGridState()
            LazyVerticalGrid(
                GridCells.Fixed(columns),
                Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = if (rail) 26.dp else 16.dp, top = 4.dp, bottom = FabClearance),
                horizontalArrangement = Arrangement.spacedBy(if (columns == 3) 10.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { when (it) { is Lettered.Header -> "h:${it.first}"; is Lettered.Row -> it.item.key } }, span = { if (it is Lettered.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { entry ->
                    when (entry) {
                        is Lettered.Header -> GroupHeader(entry.letter, inset = 0.dp)
                        is Lettered.Row -> {
                            val artist = entry.item
                            ArtistTile(
                                artist = artist,
                                compact = columns == 3,
                                selecting = selection.active,
                                selected = artist.key in selection.keys,
                                markLeft = markLeft,
                                onClick = { onClick(artist) },
                                onLongClick = { selection.start(artist.key) },
                            )
                        }
                    }
                }
            }
            if (rail) LetterRail(railLetters, gridState, railPositions, Modifier.align(Alignment.CenterEnd))
        }
        EmptyNote(artists, visible.isEmpty(), search, "No artists yet. Artists come from the tags of your files once the library is scanned.")
    }
}

/** Genres from the tags (local files and the server's), each opening its tracks. List only. */
@Composable
fun GenresScreen(vm: LibraryViewModel, nav: LibraryNav, onBack: (() -> Unit)?) {
    val genres by vm.genres.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val search = remember { PageSearch() }
    val selection = rememberSelection<String>()
    val sort = settings.sorts[SortPage.GENRES] ?: SortPage.GENRES.default
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val all = genres.orEmpty()
    val visible = remember(all, search.query) { all.filter { search.matches(it.name) } }
    val chosen = { visible.filter { it.key in selection.keys } }
    // Sorted by text: a header per letter, and the A–Z rail once the list is long.
    val letters = LibrarySort.hasLetterHeaders(sort) && search.query.isBlank()
    val items = remember(visible, sort, letters) { LibrarySort.lettered(visible) { if (letters) LibrarySort.genreHeader(it, sort) else null } }
    val (railLetters, railPositions) = remember(items) { railOf(items) }
    val rail = letters && visible.size > RAIL_MIN && railLetters.size > 1

    LibraryScaffold(
        sourceMenu = true,
        title = "Genres",
        count = genres?.size,
        caption = sort.label,
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(visible.map { it.key }) },
        allSelected = visible.isNotEmpty() && selection.keys.size >= visible.size,
        onSelectionAction = { vm.onGenres(chosen(), it) },
        onSelectionPlay = { vm.playGenres(chosen(), it) },
        sortFields = SortPage.GENRES.fields,
        sort = sort,
        onSort = { vm.setSort(SortPage.GENRES, it) },
    ) {
        val listState = rememberTopListState()
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(end = if (rail) 18.dp else 0.dp, bottom = FabClearance)) {
            items(items, key = { when (it) { is Lettered.Header -> "h:${it.first}"; is Lettered.Row -> it.item.key } }) { entry ->
                when (entry) {
                    is Lettered.Header -> GroupHeader(entry.letter)
                    is Lettered.Row -> {
                        val genre = entry.item
                        LibraryRow(
                            title = genre.name,
                            subtitle = genreDetail(genre),
                            subtitleMono = true,
                            coverPath = genre.coverPath,
                            placeholder = Icons.Outlined.Category,
                            storage = genre.storage.mark,
                            selecting = selection.active,
                            selected = genre.key in selection.keys,
                            markLeft = markLeft,
                            onClick = { if (selection.active) selection.toggle(genre.key) else nav.onOpenGenre(genre.key) },
                            onLongClick = { selection.start(genre.key) },
                            trailing = { Chevron() },
                        )
                    }
                }
            }
        }
        if (rail) LetterRail(railLetters, listState, railPositions, Modifier.align(Alignment.CenterEnd))
        EmptyNote(genres, visible.isEmpty(), search, "No genres yet. They come from the genre tags of your files (and of the mStream server's).")
    }
}

fun genreDetail(genre: GenreRow): String =
    "${genre.tracks} track${if (genre.tracks == 1) "" else "s"} · ${genre.artists} artist${if (genre.artists == 1) "" else "s"}"

private sealed interface TrackItem {
    /** [first]: index of its first track, so the key stays unique if a header comes up twice. */
    data class Header(val text: String, val first: Int) : TrackItem
    data class Track(val track: TrackEntity, val index: Int) : TrackItem
}

/** All tracks: letter (or month) headers, and an A–Z rail for letter sorts. List only. */
@Composable
fun TracksScreen(vm: LibraryViewModel, onBack: (() -> Unit)?) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, isPlaying) = rememberNowPlaying()
    val search = remember { PageSearch() }
    val selection = rememberSelection<String>()
    val sort = settings.sorts[SortPage.TRACKS] ?: SortPage.TRACKS.default
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val swipeOptions = remember(settings.swipes) { listSwipeOptions(settings.swipes) }
    val all = tracks.orEmpty()
    val visible = remember(all, search.query) { all.filter { search.matches(LibrarySort.trackName(it), it.artist, it.album) } }
    val items = remember(visible, sort) {
        buildList {
            var last: String? = null
            visible.forEachIndexed { i, t ->
                val header = LibrarySort.trackHeader(t, sort)
                if (header != null && header != last) add(TrackItem.Header(header, i))
                last = header
                add(TrackItem.Track(t, i))
            }
        }
    }
    val chosen = { visible.filter { it.path in selection.keys } }
    val listState = rememberTopListState()

    LibraryScaffold(
        sourceMenu = true,
        title = "All tracks",
        count = tracks?.size,
        caption = sort.label,
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(visible.map { it.path }) },
        allSelected = visible.isNotEmpty() && selection.keys.size >= visible.size,
        onSelectionAction = { action -> chosen().let { vm.onTracks(it.map(TrackEntity::toAudioFile), titles(it), action) } },
        onSelectionPlay = { vm.play(chosen().map(TrackEntity::toAudioFile), shuffle = it) },
        sortFields = SortPage.TRACKS.fields,
        sort = sort,
        onSort = { vm.setSort(SortPage.TRACKS, it) },
        isPlaying = isPlaying,
        canPlay = visible.isNotEmpty(),
        onPlay = { vm.play(visible.map(TrackEntity::toAudioFile), shuffle = it) },
        onStop = vm::stop,
    ) {
        val rail = LibrarySort.hasLetterHeaders(sort) && search.query.isBlank() && items.size > 30
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(end = if (rail) 18.dp else 0.dp, bottom = FabClearance),
        ) {
            items(items, key = { if (it is TrackItem.Header) "h:${it.first}" else (it as TrackItem.Track).track.path }) { item ->
                when (item) {
                    is TrackItem.Header -> GroupHeader(item.text)
                    is TrackItem.Track -> TrackRow(
                        vm = vm,
                        track = item.track,
                        subtitle = listOfNotNull(item.track.artist ?: item.track.albumArtist, item.track.album).joinToString(" · "),
                        showCover = false,
                        isCurrent = item.track.path == mediaId,
                        selection = selection,
                        markLeft = markLeft,
                        swipeOptions = swipeOptions,
                        onPlay = { vm.play(visible.map(TrackEntity::toAudioFile), start = item.index) },
                    )
                }
            }
        }
        if (rail) {
            // Each letter once ("#" can head both the digits first and the unknown last): its first header.
            val positions = remember(items) {
                LinkedHashMap<String, Int>().apply { items.forEachIndexed { i, it -> if (it is TrackItem.Header) putIfAbsent(it.text, i) } }
            }
            val letters = remember(positions) { positions.keys.toList() }
            // Clear of the play button.
            LetterRail(letters, listState, positions, Modifier.align(Alignment.CenterEnd), bottomInset = FabClearance)
        }
        EmptyNote(tracks, visible.isEmpty(), search, "No tracks yet. The library fills in as your folders are scanned.")
    }
}

/** Letters down the right edge of a list: tap or drag to jump; the one on screen is celadon. */
@Composable
fun LetterRail(letters: List<String>, listState: LazyListState, positions: Map<String, Int>, modifier: Modifier, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    LetterRail(letters, positions, { listState.firstVisibleItemIndex }, { listState.scrollToItem(it) }, modifier.padding(bottom = bottomInset))
}

/** The rail of a grid (headers span the whole row). */
@Composable
private fun LetterRail(letters: List<String>, gridState: androidx.compose.foundation.lazy.grid.LazyGridState, positions: Map<String, Int>, modifier: Modifier) {
    LetterRail(letters, positions, { gridState.firstVisibleItemIndex }, { gridState.scrollToItem(it) }, modifier)
}

/**
 * The rail itself: the letters in equal slots down its height — or, when they don't all fit, some
 * letters with dots for the hidden runs ([RailSlots]). The finger picks the letter under it (inside
 * a dot, by its place in the dot) and the list jumps there; meanwhile a bubble beside the rail shows
 * that letter.
 */
@Composable
private fun LetterRail(letters: List<String>, positions: Map<String, Int>, firstVisible: () -> Int, scrollTo: suspend (Int) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val current by remember(positions) {
        derivedStateOf {
            val first = firstVisible()
            // At the very top (a section header above the first letter): the first letter.
            positions.entries.lastOrNull { it.value <= first }?.key ?: letters.firstOrNull()
        }
    }
    // The letter under the finger and where the finger is (px), while it's down.
    var touched by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    BoxWithConstraints(modifier.width(22.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val height = constraints.maxHeight
        val slotPx = with(LocalDensity.current) { RAIL_SLOT.toPx() }
        val slots = remember(letters.size, height, slotPx) { RailSlots.layout(letters.size, (height / slotPx).toInt()) }
        fun at(y: Float) {
            if (slots.isEmpty() || height <= 0) return
            val slotHeight = height.toFloat() / slots.size
            val i = (y / slotHeight).toInt().coerceIn(slots.indices)
            val letter = RailSlots.letterAt(slots[i], ((y - i * slotHeight) / slotHeight).coerceIn(0f, 1f))
            if (touched?.first != letter) positions[letters[letter]]?.let { scope.launch { scrollTo(it) } }
            touched = letter to y.coerceIn(0f, height.toFloat())
        }
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(slots, letters) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        at(down.position.y)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            at(change.position.y)
                        }
                        touched = null
                    }
                },
        ) {
            val currentIndex = letters.indexOf(current)
            slots.forEach { slot ->
                val on = currentIndex in slot.first..slot.last
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (slot.isDot) "•" else letters[slot.first],
                        style = TextStyle(
                            fontFamily = PlexMono,
                            fontSize = 9.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) ZeneloColors.Celadon else ZeneloColors.TextMuted,
                        ),
                    )
                }
            }
        }
        // The bubble left of the rail, level with the finger (drawn outside the rail's bounds).
        touched?.let { (letter, y) ->
            Box(
                Modifier
                    // Required: the rail (22dp) would squeeze it. Centered on the rail, so shift by half + gap.
                    .offset { IntOffset(-(RAIL_BUBBLE / 2 + 22.dp / 2 + 8.dp).roundToPx(), (y - RAIL_BUBBLE.toPx() / 2).roundToInt()) }
                    .requiredSize(RAIL_BUBBLE)
                    .clip(CircleShape)
                    .background(ZeneloColors.Card),
                contentAlignment = Alignment.Center,
            ) {
                Text(letters[letter], style = TextStyle(fontFamily = PlexSans, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ZeneloColors.Celadon))
            }
        }
    }
}

/** Height a rail letter needs; fewer of them fit than there are letters → dots. */
private val RAIL_SLOT = 13.dp
private val RAIL_BUBBLE = 48.dp

/** Rail letters (each once) and where their first header is, for a [LibrarySort.lettered] list. */
private fun <T> railOf(items: List<Lettered<T>>): Pair<List<String>, Map<String, Int>> {
    val positions = LinkedHashMap<String, Int>()
    items.forEachIndexed { i, it -> if (it is Lettered.Header) positions.putIfAbsent(it.letter, i) }
    return positions.keys.toList() to positions
}

/** Letters show when the list is sorted by text and not searched; the rail once it's long. */
private const val RAIL_MIN = 30

private fun titles(tracks: List<TrackEntity>) = tracks.associate { it.path to (LibrarySort.trackName(it) to it.artist) }

/**
 * A track row with the list swipe actions, the ⋮ menu and selection. [onPlay] plays the page's
 * list from this track.
 */
@Composable
fun TrackRow(
    vm: LibraryViewModel,
    track: TrackEntity,
    subtitle: String?,
    isCurrent: Boolean,
    selection: Selection<String>,
    markLeft: Boolean,
    swipeOptions: Map<app.zenelo.data.settings.SwipeSlot, app.zenelo.ui.components.SwipeOption>,
    onPlay: () -> Unit,
    showCover: Boolean = true,
    number: String? = null,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val title = LibrarySort.trackName(track)
    val file = remember(track) { track.toAudioFile() }
    SwipeableRow(
        options = applicableSwipes(swipeOptions, settings.swipes, track.path),
        onSwipe = { slot -> settings.swipes[slot]?.let { vm.onTrack(file, title, track.artist, it) } },
        enabled = !selection.active,
    ) {
        LibraryRow(
            title = title,
            subtitle = subtitle,
            coverPath = track.path,
            placeholder = Icons.Outlined.MusicNote,
            showCover = showCover,
            number = number,
            trailingText = track.durationMs.takeIf { it > 0 }?.let(::formatDuration),
            isCurrent = isCurrent,
            selecting = selection.active,
            selected = track.path in selection.keys,
            markLeft = markLeft,
            onClick = { if (selection.active) selection.toggle(track.path) else onPlay() },
            onLongClick = { selection.start(track.path) },
            trailing = {
                TrackMenu(
                    onAction = { vm.onTrack(file, title, track.artist, it) },
                    onEditTags = { vm.openDialog(TrackDialog.EditTags(track.path)) },
                    onFindCover = { vm.openDialog(TrackDialog.Cover(track.path)) },
                    local = !MStreamPaths.isRemote(track.path),
                    path = track.path,
                )
            },
        )
    }
}

/** Recently played: newest first, grouped by day, time of play on the right. No sort. */
@Composable
fun RecentScreen(vm: LibraryViewModel, onBack: (() -> Unit)?) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, isPlaying) = rememberNowPlaying()
    val search = remember { PageSearch() }
    val selection = rememberSelection<String>()
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val swipeOptions = remember(settings.swipes) { listSwipeOptions(settings.swipes) }
    val all = recent.orEmpty()
    val visible = remember(all, search.query) { all.filter { search.matches(it.title, it.artist, it.album, it.path.substringAfterLast('/')) } }
    val groups = remember(visible) { visible.groupBy { dayGroup(it.playedAt) } }
    val files = remember(visible) { visible.map(RecentPlay::toAudioFile) }
    val chosen = { visible.filter { it.path in selection.keys } }

    LibraryScaffold(
        sourceMenu = true,
        title = "Recently played",
        count = null,
        caption = "LAST ${Library.RECENT_DAYS} DAYS · ${all.size} TRACK${if (all.size == 1) "" else "S"}",
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(visible.map { it.path }) },
        allSelected = visible.isNotEmpty() && selection.keys.size >= visible.size,
        onSelectionAction = { action ->
            chosen().let { plays -> vm.onTracks(plays.map(RecentPlay::toAudioFile), plays.associate { it.path to (recentTitle(it) to it.artist) }, action) }
        },
        onSelectionPlay = { vm.play(chosen().map(RecentPlay::toAudioFile), shuffle = it) },
        isPlaying = isPlaying,
        canPlay = visible.isNotEmpty(),
        onPlay = { vm.play(files, shuffle = it) },
        onStop = vm::stop,
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberTopListState(), contentPadding = PaddingValues(bottom = FabClearance)) {
            groups.forEach { (group, plays) ->
                item(key = "h:$group") { GroupHeader(group) }
                items(plays, key = { it.path }) { play ->
                    val title = recentTitle(play)
                    val file = remember(play) { play.toAudioFile() }
                    SwipeableRow(
                        options = applicableSwipes(swipeOptions, settings.swipes, play.path),
                        onSwipe = { slot -> settings.swipes[slot]?.let { vm.onTrack(file, title, play.artist, it) } },
                        enabled = !selection.active,
                    ) {
                        LibraryRow(
                            title = title,
                            subtitle = play.artist,
                            coverPath = play.path,
                            placeholder = Icons.Outlined.MusicNote,
                            trailingText = playTime(play.playedAt, group),
                            isCurrent = play.path == mediaId,
                            selecting = selection.active,
                            selected = play.path in selection.keys,
                            markLeft = markLeft,
                            onClick = { if (selection.active) selection.toggle(play.path) else vm.play(files, start = visible.indexOf(play)) },
                            onLongClick = { selection.start(play.path) },
                            trailing = {
                                TrackMenu(
                                    onAction = { vm.onTrack(file, title, play.artist, it) },
                                    onEditTags = { vm.openDialog(TrackDialog.EditTags(play.path)) },
                                    onFindCover = { vm.openDialog(TrackDialog.Cover(play.path)) },
                                    local = !MStreamPaths.isRemote(play.path),
                                    path = play.path,
                                )
                            },
                        )
                    }
                }
            }
        }
        EmptyNote(recent, visible.isEmpty(), search, "Nothing played in the last ${Library.RECENT_DAYS} days. A track shows up here after 30 seconds of listening.")
    }
}

fun recentTitle(play: RecentPlay) = play.title ?: play.path.substringAfterLast('/').substringBeforeLast('.')

private const val TODAY = "Today"
private const val YESTERDAY = "Yesterday"

/** "Today", "Yesterday", or "This week" for older plays of the last seven days. */
fun dayGroup(millis: Long, now: Long = System.currentTimeMillis()): String {
    val start = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        millis >= start -> TODAY
        millis >= start - 86_400_000L -> YESTERDAY
        else -> "This week"
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val weekdayFormat = SimpleDateFormat("EEE", Locale.ENGLISH)

/** "14:05" today and yesterday, "Mon" before. */
fun playTime(millis: Long, group: String): String = synchronized(timeFormat) {
    if (group == TODAY || group == YESTERDAY) timeFormat.format(Date(millis)) else weekdayFormat.format(Date(millis))
}

/** Loading shows nothing; an empty library a hint; an empty search "Nothing found". */
@Composable
fun <T> EmptyNote(data: List<T>?, empty: Boolean, search: PageSearch, hint: String) {
    if (data == null || !empty) return
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
        Text(
            if (search.query.isNotBlank()) "Nothing found" else hint,
            style = MaterialTheme.typography.bodySmall,
            color = ZeneloColors.TextMuted,
        )
    }
}
