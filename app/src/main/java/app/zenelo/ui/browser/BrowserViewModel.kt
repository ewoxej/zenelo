package app.zenelo.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.zenelo.AppContainer
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.SwipeAction
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.library.AudioFile
import app.zenelo.library.StorageRoot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class BrowserState(
    val roots: List<StorageRoot> = emptyList(),
    val root: StorageRoot? = null,
    val dir: File? = null,
    val folders: List<File> = emptyList(),
    val files: List<AudioFile> = emptyList(),
    /** Indexed tags of this folder's files, by path (fills in as files get indexed). */
    val tracks: Map<String, TrackEntity> = emptyMap(),
    /** "6 folders · 72 tracks" per subfolder path, filled in as subfolders get prefetched. */
    val folderInfo: Map<String, String> = emptyMap(),
    /** True until the first listing of [dir] arrives; starts true so launch doesn't flash "empty". */
    val loading: Boolean = true,
    val sortDescending: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
) {
    val visibleFolders: List<File>
        get() = folders.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .let { if (sortDescending) it.asReversed() else it }

    val visibleFiles: List<AudioFile>
        get() = files.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .let { if (sortDescending) it.asReversed() else it }

    val title: String get() = dir?.takeIf { it != root?.dir }?.name ?: root?.shortName.orEmpty()

    val canGoUp: Boolean get() = dir != null && root != null && dir != root.dir

    /** "Music / Ambient / Harbor", relative to the storage root. */
    val breadcrumb: List<String>
        get() {
            val d = dir ?: return emptyList()
            val r = root ?: return listOf(d.name)
            return d.relativeTo(r.dir).path.split(File.separatorChar).filter { it.isNotEmpty() }
        }
}

sealed interface BrowserEvent {
    data class Message(val text: String, val undo: (() -> Unit)? = null, val action: SwipeAction? = null) : BrowserEvent
    /** Shown for file deletion always, and for list removal when `confirmRemove` is on. */
    data class Confirm(val file: AudioFile, val deleteFile: Boolean) : BrowserEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowserViewModel(private val container: AppContainer) : ViewModel() {

    private val fs = container.fileBrowser
    private val player = container.player
    private val favorites = container.db.favorites()

    private val _state = MutableStateFlow(BrowserState(roots = fs.roots()))
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    val settings: StateFlow<ZeneloSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ZeneloSettings())

    private val _events = Channel<BrowserEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private val currentDir = MutableStateFlow<File?>(null)
    private val scrollPositions = HashMap<String, Pair<Int, Int>>()

    // Declared after every property: goHome() can run synchronously and reach open().
    init {
        viewModelScope.launch {
            // Indexing a big folder re-emits it after every batch: sample, and build the map off the main thread.
            currentDir.filterNotNull()
                .flatMapLatest { container.db.tracks().observeInDir(it.absolutePath) }
                .sample(700)
                .map { rows -> rows.associateBy(TrackEntity::path) }
                .flowOn(Dispatchers.Default)
                .collect { tracks -> _state.update { it.copy(tracks = tracks) } }
        }
        viewModelScope.launch { goHome() }
    }

    /** Shows the cached listing immediately (if any), then revalidates and prefetches subfolders. */
    fun open(dir: File) {
        val root = _state.value.roots.firstOrNull { dir.startsWith(it.dir) }
        val cached = fs.cached(dir)
        _state.update {
            it.copy(
                dir = dir,
                root = root ?: it.root,
                folders = cached?.folders.orEmpty(),
                files = cached?.files.orEmpty(),
                folderInfo = cached?.let { c -> folderInfo(c.folders) }.orEmpty(),
                loading = cached == null,
                query = "",
                searching = false,
            )
        }
        currentDir.value = dir
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val listing = fs.list(dir)
            if (listing !== cached) {
                _state.update {
                    if (it.dir != dir) it else it.copy(folders = listing.folders, files = listing.files, loading = false)
                }
            } else {
                _state.update { if (it.dir != dir) it else it.copy(loading = false) }
            }
            // Tags for the folder on screen right away; the background pass covers the rest.
            container.indexer.indexFiles(listing.files.map { File(it.path) })
            fs.prefetch(listing.folders)
            _state.update { if (it.dir != dir) it else it.copy(folderInfo = folderInfo(listing.folders)) }
        }
    }

    private fun folderInfo(folders: List<File>): Map<String, String> = buildMap {
        for (folder in folders) {
            val listing = fs.cached(folder) ?: continue
            put(
                folder.absolutePath,
                listOfNotNull(
                    listing.folders.size.takeIf { it > 0 }?.let { "$it folder${if (it == 1) "" else "s"}" },
                    listing.files.size.takeIf { it > 0 }?.let { "$it track${if (it == 1) "" else "s"}" },
                ).joinToString(" · ").ifEmpty { "empty" },
            )
        }
    }

    fun toggleSort() = _state.update { it.copy(sortDescending = !it.sortDescending) }

    fun setSearching(searching: Boolean) = _state.update { it.copy(searching = searching, query = if (searching) it.query else "") }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun scrollPosition(dir: File?): Pair<Int, Int> = dir?.let { scrollPositions[it.absolutePath] } ?: (0 to 0)

    fun saveScrollPosition(dir: File?, index: Int, offset: Int) {
        if (dir != null) scrollPositions[dir.absolutePath] = index to offset
    }

    fun openRoot(root: StorageRoot) = open(root.dir)

    fun goUp() {
        val s = _state.value
        if (s.canGoUp) s.dir?.parentFile?.let(::open)
    }

    suspend fun goHome() {
        val home = container.settings.settings.first().homeFolder?.let(::File)?.takeIf { it.isDirectory }
        (home ?: _state.value.roots.firstOrNull()?.dir)?.let(::open)
    }

    fun onHomeClick() {
        viewModelScope.launch { goHome() }
    }

    fun showMessage(text: String) {
        viewModelScope.launch { _events.send(BrowserEvent.Message(text)) }
    }

    fun setCurrentAsHome() {
        val dir = _state.value.dir ?: return
        viewModelScope.launch {
            container.settings.setHomeFolder(dir.absolutePath)
            _events.send(BrowserEvent.Message("Home folder set · ${dir.name}"))
        }
    }

    fun playFolder(shuffle: Boolean) = player.playFiles(_state.value.visibleFiles, shuffle = if (shuffle) true else null)

    fun playFrom(file: AudioFile) {
        val files = _state.value.visibleFiles
        player.playFiles(files, startIndex = files.indexOf(file).coerceAtLeast(0))
    }

    fun toggleFolderFavorite(folder: File) {
        viewModelScope.launch {
            val added = favorites.toggle(FavoriteEntity(folder.absolutePath, FavoriteKind.FOLDER, folder.name))
            _events.send(BrowserEvent.Message(if (added) "Folder added to favorites" else "Folder removed from favorites"))
        }
    }

    fun onSwipe(file: AudioFile, action: SwipeAction) {
        viewModelScope.launch {
            when (action) {
                SwipeAction.NONE -> Unit
                SwipeAction.ADD_TO_QUEUE -> {
                    val position = player.addToQueue(file)
                    _events.send(BrowserEvent.Message(action = action, text = "Added to queue · #$position"))
                }
                SwipeAction.PLAY_NEXT -> {
                    player.playNext(file)
                    _events.send(BrowserEvent.Message(action = action, text = "Plays next"))
                }
                SwipeAction.FAVORITE -> {
                    val added = favorites.toggle(FavoriteEntity(file.path, FavoriteKind.TRACK, file.title))
                    _events.send(BrowserEvent.Message(action = action, text = if (added) "Added to favorites" else "Removed from favorites"))
                }
                // TODO: playlist picker sheet.
                SwipeAction.ADD_TO_PLAYLIST -> _events.send(BrowserEvent.Message(action = action, text = "Playlist picker — coming soon"))
                // TODO: persist hidden files.
                SwipeAction.REMOVE_FROM_LIST, SwipeAction.HIDE ->
                    if (settings.value.confirmRemove) {
                        _events.send(BrowserEvent.Confirm(file, deleteFile = false))
                    } else {
                        val before = _state.value.files
                        removeFromList(file)
                        _events.send(BrowserEvent.Message(action = action, text = "Removed from list", undo = { _state.update { it.copy(files = before) } }))
                    }
                SwipeAction.DELETE_FILE -> _events.send(BrowserEvent.Confirm(file, deleteFile = true))
            }
        }
    }

    fun removeFromList(file: AudioFile) {
        _state.update { it.copy(files = it.files - file) }
    }

    fun deleteFile(file: AudioFile) {
        viewModelScope.launch {
            val deleted = File(file.path).delete()
            if (deleted) _state.update { it.copy(files = it.files - file) }
            _events.send(BrowserEvent.Message(if (deleted) "File deleted" else "Could not delete file"))
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { BrowserViewModel(container) }
        }
    }
}
