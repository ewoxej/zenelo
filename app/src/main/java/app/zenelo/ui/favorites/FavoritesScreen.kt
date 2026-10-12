package app.zenelo.ui.favorites

import app.zenelo.ui.components.rememberTopListState
import app.zenelo.ui.components.applicableSwipes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.data.settings.SwipeAction
import app.zenelo.library.AudioFile
import app.zenelo.library.LibrarySort
import app.zenelo.mstream.MStreamPaths
import app.zenelo.ui.components.DownloadAction
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.SwipeableRow
import app.zenelo.ui.components.TrackMenu
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.formatDuration
import app.zenelo.ui.components.listSwipeOptions
import app.zenelo.ui.components.rememberPlayableNow
import app.zenelo.ui.components.FabClearance
import app.zenelo.ui.library.LibraryRow
import app.zenelo.ui.library.LibraryScaffold
import app.zenelo.ui.library.LibraryViewModel
import app.zenelo.ui.library.PageSearch
import app.zenelo.ui.library.TrackDialog
import app.zenelo.ui.library.rememberNowPlaying
import app.zenelo.ui.library.rememberSelection
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch

/**
 * Favorites: tracks (the same swipes, ⋮ menu and selection as every track list; the heart takes a
 * track out of Favorites), folders, albums and artists (open them; the heart removes them).
 */
@Composable
fun FavoritesScreen(
    vm: LibraryViewModel,
    onOpenFolder: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onMessage: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val container = appContainer()
    val dao = container.db.favorites()
    val flow = remember { dao.observeAll() }
    val favorites by flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, isPlaying) = rememberNowPlaying()
    val search = remember { PageSearch() }
    val selection = rememberSelection<String>()
    val markLeft = settings.selectionMarker == SelectionMarkerSide.LEFT
    val swipeOptions = remember(settings.swipes) { listSwipeOptions(settings.swipes) }

    // Offline, server tracks with no copy here leave the list (like the library).
    val playable = rememberPlayableNow()
    val links by container.serverLinks.links.collectAsStateWithLifecycle()
    val hidden by container.hidden.current.collectAsStateWithLifecycle()
    val all = remember(favorites, playable, links, hidden) {
        // A song liked under both its local and server copy is one row, the local copy.
        favorites.orEmpty().map { if (it.kind == FavoriteKind.TRACK) it.copy(path = links.canonical(it.path)) else it }
            .distinctBy { it.path }
            .filter { it.kind != FavoriteKind.TRACK || playable(it.path) }
            // Hidden tracks ("Hide") stay favorites but don't show until shown again.
            .filterNot { it.kind == FavoriteKind.TRACK && hidden.hides(it.path, links.copiesOf(it.path)) }
    }
    // The index's rows of the tracks: album and length for the rows (titles from the favorite if not indexed).
    val trackPaths = remember(all) { all.filter { it.kind == FavoriteKind.TRACK }.map { it.path } }
    val indexed by produceState(emptyMap<String, TrackEntity>(), trackPaths) {
        value = container.db.tracks().getMany(trackPaths).associateBy { it.path }
    }
    fun FavoriteEntity.trackTitle() = indexed[path]?.let(LibrarySort::trackName) ?: title
    fun FavoriteEntity.artist() = indexed[path]?.artist ?: subtitle
    val visible = remember(all, indexed, search.query) {
        all.filter { search.matches(it.trackTitle(), it.artist(), indexed[it.path]?.album, it.path.substringAfterLast('/')) }
    }
    val tracks = remember(visible) { visible.filter { it.kind == FavoriteKind.TRACK } }
    val files = remember(tracks) { tracks.map { AudioFile.forPath(it.path) } }
    val chosen = { tracks.filter { it.path in selection.keys } }

    // An M3U / M3U8 file's tracks become favorites (any file type: pickers rarely know M3U).
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching { container.playlistFiles.importToFavorites(uri) }.getOrNull()
            onMessage(
                when {
                    r == null -> "Couldn't read the file"
                    r.added == 0 && r.already == 0 -> "No tracks of \"${r.name}\" found in the library"
                    else -> listOfNotNull(
                        "${r.added} added to favorites",
                        r.already.takeIf { it > 0 }?.let { "$it already there" },
                        r.missing.takeIf { it > 0 }?.let { "$it not found" },
                    ).joinToString(" · ")
                },
            )
        }
    }

    LibraryScaffold(
        title = "Favorites",
        count = favorites?.let { all.size },
        caption = null,
        onBack = onBack,
        search = search,
        selection = selection,
        selectAll = { selection.toggleAll(tracks.map { it.path }) },
        allSelected = tracks.isNotEmpty() && selection.keys.size >= tracks.size,
        onSelectionAction = { action ->
            chosen().let { list ->
                // Already favorites: the heart of a selection here has nothing to add.
                if (action != SwipeAction.FAVORITE) vm.onTracks(list.map { AudioFile.forPath(it.path) }, list.associate { it.path to (it.trackTitle() to it.artist()) }, action)
            }
        },
        onSelectionPlay = { vm.play(chosen().map { AudioFile.forPath(it.path) }, shuffle = it) },
        onSelectionRemove = { vm.removeFavorites(chosen()) },
        removeLabel = "Remove from favorites",
        selectionFavorite = false,
        isPlaying = isPlaying,
        canPlay = files.isNotEmpty(),
        onPlay = { vm.play(files, shuffle = it) },
        onStop = vm::stop,
        headerActions = {
            // Downloading the favorites keeps them downloaded: tracks liked later download too.
            val keepFlow = remember { container.settings.settings }
            val keep by keepFlow.collectAsStateWithLifecycle(initialValue = null)
            val allTrackPaths = remember(favorites) { favorites.orEmpty().filter { it.kind == FavoriteKind.TRACK }.map { it.path } }
            DownloadAction(
                allTrackPaths,
                onDownload = {
                    container.appScope.launch {
                        container.settings.setKeepFavoritesDownloaded(true)
                        container.mstreamDownloads.request(allTrackPaths)
                    }
                },
                kept = keep?.keepFavoritesDownloaded == true,
                onStopKeeping = { container.appScope.launch { container.settings.setKeepFavoritesDownloaded(false) } },
            )
            IconButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.FileOpen, "Import M3U playlist into favorites", tint = ZeneloColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberTopListState(), contentPadding = PaddingValues(bottom = FabClearance)) {
            items(visible, key = { it.path }) { favorite ->
                if (favorite.kind == FavoriteKind.TRACK) {
                    val file = remember(favorite.path) { AudioFile.forPath(favorite.path) }
                    val title = favorite.trackTitle()
                    val artist = favorite.artist()
                    val track = indexed[favorite.path]
                    val act = { action: SwipeAction ->
                        // Here the heart takes the track out of Favorites (with undo).
                        if (action == SwipeAction.FAVORITE) vm.removeFavorites(listOf(favorite))
                        else vm.onTrack(file, title, artist, action)
                    }
                    SwipeableRow(
                        options = applicableSwipes(swipeOptions, settings.swipes, favorite.path),
                        onSwipe = { slot -> settings.swipes[slot]?.let(act) },
                        enabled = !selection.active,
                    ) {
                        LibraryRow(
                            title = title,
                            subtitle = listOfNotNull(artist, track?.album).joinToString(" · ").ifEmpty { null },
                            coverPath = favorite.path,
                            placeholder = Icons.Outlined.MusicNote,
                            trailingText = track?.durationMs?.takeIf { it > 0 }?.let(::formatDuration),
                            isCurrent = favorite.path == mediaId,
                            selecting = selection.active,
                            selected = favorite.path in selection.keys,
                            markLeft = markLeft,
                            onClick = { if (selection.active) selection.toggle(favorite.path) else vm.play(files, start = tracks.indexOf(favorite)) },
                            onLongClick = { selection.start(favorite.path) },
                            trailing = {
                                TrackMenu(
                                    onAction = act,
                                    onDownloadCover = { vm.openDialog(TrackDialog.Cover(favorite.path)) },
                                    onEditTags = { vm.openDialog(TrackDialog.EditTags(favorite.path, it)) },
                                    local = !MStreamPaths.isRemote(favorite.path),
                                    path = favorite.path,
                                )
                            },
                        )
                    }
                } else {
                    LibraryRow(
                        title = favorite.title,
                        subtitle = favorite.subtitle ?: favorite.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                        dimmed = selection.active,
                        onClick = {
                            if (selection.active) return@LibraryRow
                            when (favorite.kind) {
                                FavoriteKind.FOLDER -> onOpenFolder(favorite.path)
                                // Library keys behind "album:" / "artist:" (older album entries were folders).
                                FavoriteKind.ALBUM ->
                                    if (favorite.path.startsWith("album:")) onOpenAlbum(favorite.path.removePrefix("album:")) else onOpenFolder(favorite.path)
                                FavoriteKind.ARTIST -> onOpenArtist(favorite.path.removePrefix("artist:"))
                                FavoriteKind.TRACK -> Unit
                            }
                        },
                        onLongClick = {},
                        leading = {
                            val icon = when (favorite.kind) {
                                FavoriteKind.ALBUM -> Icons.Outlined.Album
                                FavoriteKind.ARTIST -> Icons.Outlined.Person
                                else -> Icons.Outlined.Folder
                            }
                            IconTile(icon, ZeneloColors.Mustard, ZeneloColors.MustardTint, size = 40.dp, iconSize = 20.dp, cornerRadius = 6.dp)
                        },
                        trailing = {
                            IconButton(onClick = { vm.removeFavorites(listOf(favorite)) }, enabled = !selection.active, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Favorite, "Remove from favorites", tint = ZeneloColors.Celadon, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                }
            }
        }
        if (favorites != null && visible.isEmpty()) {
            Text(
                if (all.isEmpty()) "Swipe a track, tap the heart while it plays, or long-press a folder to add it here." else "Nothing found",
                style = MaterialTheme.typography.bodySmall,
                color = ZeneloColors.TextMuted,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}
