package app.zenelo.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.library.LibrarySort
import app.zenelo.library.toAudioFile
import app.zenelo.ui.components.GroupHeader
import app.zenelo.ui.components.SearchField
import app.zenelo.ui.components.listSwipeOptions
import app.zenelo.ui.library.Chevron
import app.zenelo.ui.library.LibraryNav
import app.zenelo.ui.library.LibraryRow
import app.zenelo.ui.library.LibraryViewModel
import app.zenelo.ui.library.TrackRow
import app.zenelo.ui.library.artistDetail
import app.zenelo.ui.library.rememberNowPlaying
import app.zenelo.ui.library.rememberSelection
import app.zenelo.ui.theme.ZeneloColors

/** Search the whole library from Home: artists, albums and tracks matching the query. */
@Composable
fun SearchScreen(vm: LibraryViewModel, nav: LibraryNav, onBack: () -> Unit) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, _) = rememberNowPlaying()
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val selection = rememberSelection<String>()
    val swipeOptions = remember(settings.swipes) { listSwipeOptions(settings.swipes) }
    fun String?.hit() = this?.contains(q, ignoreCase = true) == true

    val foundArtists = remember(artists, q) { if (q.isEmpty()) emptyList() else artists.orEmpty().filter { it.name.hit() } }
    val foundAlbums = remember(albums, q) { if (q.isEmpty()) emptyList() else albums.orEmpty().filter { it.album.hit() || it.artist.hit() } }
    val foundTracks = remember(tracks, q) {
        if (q.isEmpty()) emptyList() else tracks.orEmpty().filter { LibrarySort.trackName(it).hit() || it.artist.hit() || it.album.hit() }
    }
    val files = remember(foundTracks) { foundTracks.map(TrackEntity::toAudioFile) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(20.dp)) }
            SearchField(query, { query = it }, "Artists, albums, tracks", Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (foundArtists.isNotEmpty()) {
                item(key = "h-artists") { GroupHeader("Artists · ${foundArtists.size}") }
                items(foundArtists.take(5), key = { "a:${it.key}" }) { artist ->
                    LibraryRow(
                        title = artist.name,
                        subtitle = artistDetail(artist),
                        subtitleMono = true,
                        coverPath = artist.coverPath,
                        coverShape = CircleShape,
                        placeholder = Icons.Outlined.Person,
                        onClick = { nav.onOpenArtist(artist.key) },
                        onLongClick = {},
                        trailing = { Chevron() },
                    )
                }
            }
            if (foundAlbums.isNotEmpty()) {
                item(key = "h-albums") { GroupHeader("Albums · ${foundAlbums.size}") }
                items(foundAlbums.take(8), key = { "b:${it.key}" }) { album ->
                    LibraryRow(
                        title = album.album,
                        subtitle = album.artist,
                        coverPath = album.coverPath,
                        // The album's marks, not its cover track's.
                        cloud = album.remote,
                        downloaded = false,
                        onClick = { nav.onOpenAlbum(album.key) },
                        onLongClick = {},
                        trailing = { Chevron() },
                    )
                }
            }
            if (foundTracks.isNotEmpty()) {
                item(key = "h-tracks") { GroupHeader("Tracks · ${foundTracks.size}") }
                items(foundTracks.take(100), key = { "t:${it.path}" }) { track ->
                    TrackRow(
                        vm = vm,
                        track = track,
                        subtitle = listOfNotNull(track.artist, track.album).joinToString(" · "),
                        isCurrent = track.path == mediaId,
                        selection = selection,
                        markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT,
                        swipeOptions = swipeOptions,
                        onPlay = { vm.play(files, start = foundTracks.indexOf(track)) },
                    )
                }
            }
            if (q.isNotEmpty() && foundArtists.isEmpty() && foundAlbums.isEmpty() && foundTracks.isEmpty()) {
                item { Text("Nothing found", style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted, modifier = Modifier.padding(24.dp)) }
            }
        }
    }
}
