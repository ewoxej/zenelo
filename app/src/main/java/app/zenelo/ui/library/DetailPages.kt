package app.zenelo.ui.library

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.AlbumRow
import app.zenelo.data.db.ArtistRow
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.albumFavoriteId
import app.zenelo.data.db.artistFavoriteId
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.library.ArtistSplitter
import app.zenelo.library.LibrarySort
import app.zenelo.library.artistKey
import app.zenelo.library.artistTag
import app.zenelo.library.toAudioFile
import app.zenelo.ui.components.CoverImage
import app.zenelo.ui.components.FabClearance
import app.zenelo.ui.components.GroupHeader
import app.zenelo.ui.components.formatTotal
import app.zenelo.ui.components.listSwipeOptions
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors

/** One album: cover, title, artist (opens the artist), and its tracks by number. */
@Composable
fun AlbumScreen(vm: LibraryViewModel, key: String, nav: LibraryNav, onBack: () -> Unit) {
    val tracksFlow = remember(key) { vm.albumTracks(key) }
    val tracks by tracksFlow.collectAsStateWithLifecycle(initialValue = null)
    val albums by vm.albums.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, isPlaying) = rememberNowPlaying()
    val favoriteFlow = remember(key) { vm.observeFavorite(albumFavoriteId(key)) }
    val favorite by favoriteFlow.collectAsStateWithLifecycle(initialValue = false)
    val search = remember { PageSearch() }
    val selection = rememberSelection<String>()
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val swipeOptions = remember(settings.swipes) { listSwipeOptions(settings.swipes) }
    val splitter = remember(settings.artistSeparators, settings.artistExceptions) { settings.artistSplitter }
    val all = tracks.orEmpty()
    val first = all.firstOrNull()
    // The row from the albums list if loaded; else built from the tracks.
    val album = albums?.firstOrNull { it.key == key } ?: first?.let {
        AlbumRow(key, it.album ?: "", it.albumArtist ?: it.artist, all.size, all.sumOf(TrackEntity::durationMs), it.modified, it.path)
    }
    val visible = remember(all, search.query) { all.filter { search.matches(LibrarySort.trackName(it), it.artist) } }
    val files = remember(visible) { visible.map(TrackEntity::toAudioFile) }
    val chosen = { visible.filter { it.path in selection.keys } }

    LibraryScaffold(
        title = album?.album ?: "Album",
        count = tracks?.size,
        caption = album?.artist?.uppercase(),
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(visible.map { it.path }) },
        allSelected = visible.isNotEmpty() && selection.keys.size >= visible.size,
        onSelectionAction = { action -> chosen().let { vm.onTracks(it.map(TrackEntity::toAudioFile), it.associate { t -> t.path to (LibrarySort.trackName(t) to t.artist) }, action) } },
        onSelectionPlay = { vm.play(chosen().map(TrackEntity::toAudioFile), shuffle = it) },
        isPlaying = isPlaying,
        canPlay = files.isNotEmpty(),
        onPlay = { vm.play(files, shuffle = it) },
        onStop = vm::stop,
        headerActions = { if (album != null) FavoriteButton(favorite) { vm.toggleAlbumFavorite(album) } },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance)) {
            if (album != null && !search.open) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(album.coverPath, Modifier.size(120.dp), shape = RoundedCornerShape(10.dp), large = true, placeholder = Icons.Outlined.Album)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(album.album, style = TextStyle(fontFamily = PlexSans, fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            album.artist?.let { artist ->
                                ArtistLinks(artist, splitter, nav.onOpenArtist, Modifier.padding(top = 4.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${album.tracks} track${if (album.tracks == 1) "" else "s"} · ${formatTotal(album.durationMs)}",
                                style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, color = ZeneloColors.TextMuted),
                            )
                        }
                    }
                }
            }
            items(visible, key = { it.path }) { track ->
                TrackRow(
                    vm = vm,
                    track = track,
                    // Compilations: the track's own artist when it differs from the album's.
                    subtitle = track.artist?.takeIf { !it.equals(album?.artist, ignoreCase = true) },
                    showCover = false,
                    number = track.trackNumber?.let { "%02d".format(it) } ?: "·",
                    isCurrent = track.path == mediaId,
                    selection = selection,
                    markLeft = markLeft,
                    swipeOptions = swipeOptions,
                    onPlay = { vm.play(files, start = visible.indexOf(track)) },
                )
            }
        }
    }
}

/** An album's artist tag; each of several artists ("A; B") opens its own page. */
@Composable
private fun ArtistLinks(tag: String, splitter: ArtistSplitter, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val names = remember(tag, splitter) { splitter.split(tag) }
    val text = remember(names) {
        buildAnnotatedString {
            names.forEachIndexed { i, name ->
                if (i > 0) append(", ")
                withLink(LinkAnnotation.Clickable(name) { onOpen(artistKey(name)) }) { append(name) }
            }
        }
    }
    Text(
        text,
        style = TextStyle(fontFamily = PlexSans, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        color = ZeneloColors.Celadon,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** One artist: their albums in a row, then all their tracks. */
@Composable
fun ArtistScreen(vm: LibraryViewModel, key: String, nav: LibraryNav, onBack: () -> Unit) {
    val tracksFlow = remember(key) { vm.artistTracks(key) }
    val tracks by tracksFlow.collectAsStateWithLifecycle(initialValue = null)
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, isPlaying) = rememberNowPlaying()
    val favoriteFlow = remember(key) { vm.observeFavorite(artistFavoriteId(key)) }
    val favorite by favoriteFlow.collectAsStateWithLifecycle(initialValue = false)
    val search = remember { PageSearch() }
    val selection = rememberSelection<String>()
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val swipeOptions = remember(settings.swipes) { listSwipeOptions(settings.swipes) }
    val all = tracks.orEmpty()
    val artist = artists?.firstOrNull { it.key == artistKey(key) } ?: all.firstOrNull()?.let {
        val name = settings.artistSplitter.split(it.artistTag).firstOrNull { n -> artistKey(n) == artistKey(key) } ?: key
        ArtistRow(key, name, all.mapNotNull(TrackEntity::albumKey).distinct().size, all.size, it.modified, it.path)
    }
    val albumKeys = remember(all) { all.mapNotNull(TrackEntity::albumKey).toSet() }
    val artistAlbums = remember(albums, albumKeys) { albums.orEmpty().filter { it.key in albumKeys } }
    val visible = remember(all, search.query) { all.filter { search.matches(LibrarySort.trackName(it), it.album) } }
    val files = remember(visible) { visible.map(TrackEntity::toAudioFile) }
    val chosen = { visible.filter { it.path in selection.keys } }

    LibraryScaffold(
        title = artist?.name ?: "Artist",
        count = tracks?.size,
        caption = artist?.let { artistDetail(it).uppercase() },
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(visible.map { it.path }) },
        allSelected = visible.isNotEmpty() && selection.keys.size >= visible.size,
        onSelectionAction = { action -> chosen().let { vm.onTracks(it.map(TrackEntity::toAudioFile), it.associate { t -> t.path to (LibrarySort.trackName(t) to t.artist) }, action) } },
        onSelectionPlay = { vm.play(chosen().map(TrackEntity::toAudioFile), shuffle = it) },
        isPlaying = isPlaying,
        canPlay = files.isNotEmpty(),
        onPlay = { vm.play(files, shuffle = it) },
        onStop = vm::stop,
        headerActions = { if (artist != null) FavoriteButton(favorite) { vm.toggleArtistFavorite(artist) } },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FabClearance)) {
            if (artistAlbums.isNotEmpty() && !search.open) {
                item(key = "albums-header") { GroupHeader("Albums · ${artistAlbums.size}") }
                item(key = "albums") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(artistAlbums, key = { it.key }) { album ->
                            AlbumTile(
                                album = album,
                                detail = null,
                                compact = true,
                                isCurrent = false,
                                selecting = false,
                                selected = false,
                                markLeft = markLeft,
                                onClick = { nav.onOpenAlbum(album.key) },
                                onLongClick = {},
                                modifier = Modifier.width(110.dp),
                            )
                        }
                    }
                }
                item(key = "tracks-header") { GroupHeader("Tracks · ${all.size}") }
            }
            items(visible, key = { it.path }) { track ->
                TrackRow(
                    vm = vm,
                    track = track,
                    subtitle = track.album,
                    isCurrent = track.path == mediaId,
                    selection = selection,
                    markLeft = markLeft,
                    swipeOptions = swipeOptions,
                    onPlay = { vm.play(files, start = visible.indexOf(track)) },
                )
            }
        }
    }
}

@Composable
private fun FavoriteButton(favorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            if (favorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
            if (favorite) "Remove from favorites" else "Add to favorites",
            tint = ZeneloColors.Celadon,
            modifier = Modifier.size(20.dp),
        )
    }
}
