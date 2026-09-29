package app.zenelo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.zenelo.AppContainer
import app.zenelo.data.db.AlbumRow
import app.zenelo.data.db.ArtistRow
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.RecentPlay
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.albumFavoriteId
import app.zenelo.data.db.artistFavoriteId
import app.zenelo.data.settings.LibraryView
import app.zenelo.data.settings.SortOrder
import app.zenelo.data.settings.SortPage
import app.zenelo.data.settings.SwipeAction
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.library.AudioFile
import app.zenelo.library.LibrarySort
import app.zenelo.library.toAudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LibraryEvent {
    data class Message(val text: String, val undo: (() -> Unit)? = null, val action: SwipeAction? = null) : LibraryEvent
    data class ConfirmDelete(val files: List<AudioFile>) : LibraryEvent
}

/** The ⋮ menu's dialogs, shown at the root so any page can open them. */
sealed interface TrackDialog {
    data class EditTags(val path: String, val fromFileName: Boolean) : TrackDialog
    data class Cover(val path: String) : TrackDialog
}

/**
 * State and actions of Home and the library pages (activity-scoped, like the browser's). Lists
 * are null until first loaded, so pages don't flash "empty".
 */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val library = container.library
    private val player = container.player
    private val favorites = container.db.favorites()

    val settings: StateFlow<ZeneloSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ZeneloSettings())

    /** Tracks removed from the lists this session (swipe "Remove from list" / "Hide"). */
    private val hidden = MutableStateFlow(emptySet<String>())

    private fun sortOf(page: SortPage): Flow<SortOrder> =
        settings.map { it.sorts[page] ?: page.default }.distinctUntilChanged()

    val albums: StateFlow<List<AlbumRow>?> = combine(library.albums, sortOf(SortPage.ALBUMS), LibrarySort::albums)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val artists: StateFlow<List<ArtistRow>?> = combine(library.artists, sortOf(SortPage.ARTISTS), LibrarySort::artists)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tracks: StateFlow<List<TrackEntity>?> = combine(library.allTracks, sortOf(SortPage.TRACKS), hidden) { all, order, gone ->
        LibrarySort.tracks(if (gone.isEmpty()) all else all.filter { it.path !in gone }, order)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recent: StateFlow<List<RecentPlay>?> = combine(library.recent(), hidden) { plays, gone ->
        if (gone.isEmpty()) plays else plays.filter { it.path !in gone }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _dialog = MutableStateFlow<TrackDialog?>(null)
    val dialog: StateFlow<TrackDialog?> = _dialog.asStateFlow()

    fun openDialog(dialog: TrackDialog?) {
        _dialog.value = dialog
    }

    fun albumTracks(key: String) = library.albumTracks(key)

    fun artistTracks(key: String) = library.artistTracks(key)

    fun setSort(page: SortPage, order: SortOrder) {
        viewModelScope.launch { container.settings.setSort(page, order) }
    }

    fun setView(page: SortPage, view: LibraryView) {
        viewModelScope.launch { container.settings.setView(page, view) }
    }

    fun showMessage(text: String) {
        viewModelScope.launch { _events.send(LibraryEvent.Message(text)) }
    }

    // --- Playing

    /** [shuffle]: shuffled from a random track; otherwise from [start] in the user's shuffle mode. */
    fun play(files: List<AudioFile>, start: Int = 0, shuffle: Boolean = false) {
        if (files.isEmpty()) return
        player.playFiles(files, startIndex = start.coerceIn(files.indices), shuffle = if (shuffle) true else null)
    }

    fun stop() = player.stop()

    fun playAlbums(albums: List<AlbumRow>, shuffle: Boolean) {
        viewModelScope.launch { play(filesOfAlbums(albums), shuffle = shuffle) }
    }

    fun playArtists(artists: List<ArtistRow>, shuffle: Boolean) {
        viewModelScope.launch { play(filesOfArtists(artists), shuffle = shuffle) }
    }

    private suspend fun filesOfAlbums(albums: List<AlbumRow>) =
        albums.flatMap { library.albumTracks(it.key).first() }.map(TrackEntity::toAudioFile)

    private suspend fun filesOfArtists(artists: List<ArtistRow>) =
        artists.flatMap { library.artistTracks(it.key).first() }.map(TrackEntity::toAudioFile)

    // --- Favorites

    fun toggleAlbumFavorite(album: AlbumRow) {
        viewModelScope.launch {
            val added = favorites.toggle(FavoriteEntity(albumFavoriteId(album.key), FavoriteKind.ALBUM, album.album, album.artist))
            _events.send(LibraryEvent.Message(if (added) "Album added to favorites" else "Album removed from favorites"))
        }
    }

    fun toggleArtistFavorite(artist: ArtistRow) {
        viewModelScope.launch {
            val added = favorites.toggle(FavoriteEntity(artistFavoriteId(artist.key), FavoriteKind.ARTIST, artist.name))
            _events.send(LibraryEvent.Message(if (added) "Artist added to favorites" else "Artist removed from favorites"))
        }
    }

    fun observeFavorite(id: String) = favorites.observeIsFavorite(id)

    // --- Row actions (swipes, ⋮) and selections

    /** A swipe or ⋮ action on one track row. */
    fun onTrack(file: AudioFile, title: String, artist: String?, action: SwipeAction) {
        viewModelScope.launch {
            when (action) {
                SwipeAction.NONE -> Unit
                SwipeAction.ADD_TO_QUEUE -> {
                    val position = player.addToQueue(file)
                    _events.send(LibraryEvent.Message("Added to queue · #$position", action = action))
                }
                SwipeAction.PLAY_NEXT -> {
                    player.playNext(file)
                    _events.send(LibraryEvent.Message("Plays next", action = action))
                }
                SwipeAction.FAVORITE -> {
                    val added = favorites.toggle(FavoriteEntity(file.path, FavoriteKind.TRACK, title, artist))
                    _events.send(LibraryEvent.Message(if (added) "Added to favorites" else "Removed from favorites", action = action))
                }
                SwipeAction.ADD_TO_PLAYLIST -> container.playlistPicker.pick(listOf(file.path))
                SwipeAction.REMOVE_FROM_LIST, SwipeAction.HIDE -> {
                    hidden.update { it + file.path }
                    _events.send(LibraryEvent.Message("Removed from list", undo = { hidden.update { it - file.path } }, action = action))
                }
                SwipeAction.DELETE_FILE -> _events.send(LibraryEvent.ConfirmDelete(listOf(file)))
            }
        }
    }

    /** Selected tracks; [titles] name them for favorites (path → title, artist). */
    fun onTracks(files: List<AudioFile>, titles: Map<String, Pair<String, String?>>, action: SwipeAction) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            if (action == SwipeAction.FAVORITE) {
                // Adds (never toggles off): selecting a mix shouldn't un-favorite some of them.
                files.forEach { f ->
                    val (title, artist) = titles[f.path] ?: (f.title to null)
                    favorites.insert(FavoriteEntity(f.path, FavoriteKind.TRACK, title, artist))
                }
                _events.send(LibraryEvent.Message("${count(files.size, "track")} added to favorites", action = action))
            } else {
                queue(files, action)
            }
        }
    }

    fun onAlbums(albums: List<AlbumRow>, action: SwipeAction) {
        if (albums.isEmpty()) return
        viewModelScope.launch {
            if (action == SwipeAction.FAVORITE) {
                albums.forEach { favorites.insert(FavoriteEntity(albumFavoriteId(it.key), FavoriteKind.ALBUM, it.album, it.artist)) }
                _events.send(LibraryEvent.Message("${count(albums.size, "album")} added to favorites", action = action))
            } else {
                queue(filesOfAlbums(albums), action)
            }
        }
    }

    fun onArtists(artists: List<ArtistRow>, action: SwipeAction) {
        if (artists.isEmpty()) return
        viewModelScope.launch {
            if (action == SwipeAction.FAVORITE) {
                artists.forEach { favorites.insert(FavoriteEntity(artistFavoriteId(it.key), FavoriteKind.ARTIST, it.name)) }
                _events.send(LibraryEvent.Message("${count(artists.size, "artist")} added to favorites", action = action))
            } else {
                queue(filesOfArtists(artists), action)
            }
        }
    }

    private suspend fun queue(files: List<AudioFile>, action: SwipeAction) {
        if (files.isEmpty()) return
        val what = count(files.size, "track")
        when (action) {
            SwipeAction.PLAY_NEXT -> {
                player.playNext(files)
                _events.send(LibraryEvent.Message("$what play next", action = action))
            }
            SwipeAction.ADD_TO_QUEUE -> {
                player.addToQueue(files)
                _events.send(LibraryEvent.Message("$what added to queue", action = action))
            }
            SwipeAction.ADD_TO_PLAYLIST -> container.playlistPicker.pick(files.map(AudioFile::path))
            else -> Unit
        }
    }

    fun delete(files: List<AudioFile>) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { files.filter { File(it.path).delete() } }
            // The index follows on the next scan; until then keep them out of the lists.
            hidden.update { it + deleted.map(AudioFile::path) }
            withContext(Dispatchers.IO) { container.db.tracks().delete(deleted.map(AudioFile::path)) }
            val failed = files.size - deleted.size
            _events.send(
                LibraryEvent.Message(
                    if (files.size == 1) (if (failed == 0) "File deleted" else "Could not delete file")
                    else "${deleted.size} deleted" + if (failed > 0) " · $failed couldn't be deleted" else "",
                ),
            )
        }
    }

    private fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(container) }
        }
    }
}
