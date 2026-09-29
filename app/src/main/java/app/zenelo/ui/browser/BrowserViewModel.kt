package app.zenelo.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.zenelo.AppContainer
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.RemoteFolders
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.SortField
import app.zenelo.data.settings.SortOrder
import app.zenelo.data.settings.SortPage
import app.zenelo.library.LibrarySort
import app.zenelo.data.settings.SwipeAction
import app.zenelo.ui.components.formatTotal
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BrowserState(
    val roots: List<StorageRoot> = emptyList(),
    val root: StorageRoot? = null,
    val dir: File? = null,
    val folders: List<File> = emptyList(),
    val files: List<AudioFile> = emptyList(),
    /** Indexed tags of this folder's files, by path (fills in as files get indexed). */
    val tracks: Map<String, TrackEntity> = emptyMap(),
    val folderModified: Map<String, Long> = emptyMap(),
    /** Direct subfolder count per subfolder path, known once that subfolder was listed (prefetch). */
    val subfolderCounts: Map<String, Int> = emptyMap(),
    /** Tracks and total duration under each subfolder (recursive), from the library index. */
    val folderStats: Map<String, Pair<Int, Long>> = emptyMap(),
    /** Tracks directly in each subfolder (its own subfolders not counted), for sorting. */
    val folderTracks: Map<String, Int> = emptyMap(),
    /** True until the first listing of [dir] arrives; starts true so launch doesn't flash "empty". */
    val loading: Boolean = true,
    val query: String = "",
    val searching: Boolean = false,
) {
    /**
     * Folders sort by name, date or the tracks directly inside them; artist / album / duration apply
     * to files only (folders keep name order).
     */
    fun visibleFolders(sort: SortOrder): List<File> {
        val matching = folders.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        return when (sort.sortBy) {
            SortField.DATE_ADDED ->
                if (sort.descending) matching.sortedByDescending { folderModified[it.path] ?: 0L } else matching.sortedBy { folderModified[it.path] ?: 0L }
            // Stable sort: equal counts stay in name order.
            SortField.TRACKS ->
                if (sort.descending) matching.sortedByDescending { folderTracks[it.path] ?: 0 } else matching.sortedBy { folderTracks[it.path] ?: 0 }
            SortField.NAME -> if (sort.descending) matching.asReversed() else matching
            else -> matching
        }
    }

    /** Files by name (as listed), date, or their tags (untagged files go last). */
    fun visibleFiles(sort: SortOrder): List<AudioFile> {
        val matching = files.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        val known = matching.mapNotNull { tracks[it.path] }
        fun byTracks(): List<AudioFile> {
            val order = LibrarySort.tracks(known, sort).withIndex().associate { (i, t) -> t.path to i }
            return matching.sortedBy { order[it.path] ?: Int.MAX_VALUE }
        }
        return when (sort.sortBy) {
            SortField.NAME -> if (sort.descending) matching.asReversed() else matching
            SortField.DATE_ADDED -> if (sort.descending) matching.sortedByDescending { it.modified } else matching.sortedBy { it.modified }
            // A folder sort: files keep name order.
            SortField.TRACKS -> matching
            else -> byTracks()
        }
    }

    /** "3 folders · 41 tracks" or "9 tracks · 48 min"; null until anything is known. */
    fun folderLabel(folder: File): String? {
        val subfolders = subfolderCounts[folder.path]
        val (tracks, duration) = folderStats[folder.path] ?: (0 to 0L)
        return when {
            subfolders != null && subfolders > 0 -> "$subfolders folder${if (subfolders == 1) "" else "s"} · $tracks track${if (tracks == 1) "" else "s"}"
            tracks > 0 -> "$tracks track${if (tracks == 1) "" else "s"}" + (if (duration > 0) " · ${formatTotal(duration)}" else "")
            subfolders == 0 -> "empty"
            else -> null
        }
    }

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
        // Recursive track counts for the subfolders on screen, straight from the index: folders
        // never opened still show what's inside once the background pass has read them.
        viewModelScope.launch {
            // Server folders (`/mstream/…`) are the index's `mstream://…` dirs: queried as those,
            // keyed back by folder path.
            currentDir.filterNotNull()
                .flatMapLatest { dir ->
                    val prefix = RemoteFolders.indexPath(dir) + "/"
                    container.db.tracks().observeDirStats(prefix, prefix + "\uFFFF").map { rows -> dir to rows }
                }
                .sample(700)
                .map { (dir, rows) ->
                    val prefix = RemoteFolders.indexPath(dir) + "/"
                    val byChild = HashMap<String, Pair<Int, Long>>()
                    val direct = HashMap<String, Int>()
                    for (row in rows) {
                        if (!row.dir.startsWith(prefix)) continue
                        val child = prefix + row.dir.substring(prefix.length).substringBefore('/')
                        val key = RemoteFolders.folderPath(child)
                        val (t, d) = byChild[key] ?: (0 to 0L)
                        byChild[key] = (t + row.tracks) to (d + row.durationMs)
                        if (row.dir == child) direct[key] = row.tracks
                    }
                    Triple(dir, byChild, direct)
                }
                .flowOn(Dispatchers.Default)
                .collect { (dir, stats, direct) ->
                    _state.update { if (it.dir != dir) it else it.copy(folderStats = stats, folderTracks = direct) }
                }
        }
        viewModelScope.launch {
            // Indexing a big folder re-emits it after every batch: sample, and build the map off the main thread.
            currentDir.filterNotNull()
                .flatMapLatest { container.db.tracks().observeInDir(RemoteFolders.indexPath(it)) }
                .sample(700)
                .map { rows -> rows.associateBy(TrackEntity::path) }
                .flowOn(Dispatchers.Default)
                .collect { tracks -> _state.update { it.copy(tracks = tracks) } }
        }
        viewModelScope.launch { goHome() }
        // The server's folders appear as a root while logged in.
        viewModelScope.launch {
            container.settings.settings.map { it.mstream != null }.distinctUntilChanged().collect { server ->
                _state.update { it.copy(roots = fs.roots(server)) }
            }
        }
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
                folderModified = cached?.folderModified.orEmpty(),
                subfolderCounts = cached?.let { c -> subfolderCounts(c.folders) }.orEmpty(),
                folderStats = if (it.dir == dir) it.folderStats else emptyMap(),
                folderTracks = if (it.dir == dir) it.folderTracks else emptyMap(),
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
                    if (it.dir != dir) it else it.copy(folders = listing.folders, files = listing.files, folderModified = listing.folderModified, loading = false)
                }
            } else {
                _state.update { if (it.dir != dir) it else it.copy(loading = false) }
            }
            // Tags for the folder on screen right away; the background pass covers the rest.
            if (!RemoteFolders.isRemote(dir)) container.indexer.indexFiles(listing.files.map { File(it.path) })
            fs.prefetch(listing.folders)
            _state.update { if (it.dir != dir) it else it.copy(subfolderCounts = subfolderCounts(listing.folders)) }
        }
    }

    private fun subfolderCounts(folders: List<File>): Map<String, Int> =
        folders.mapNotNull { f -> fs.cached(f)?.let { f.path to it.folders.size } }.toMap()

    fun setSort(sort: SortOrder) {
        viewModelScope.launch { container.settings.setSort(SortPage.BROWSER, sort) }
    }

    val sort: SortOrder get() = settings.value.sorts[SortPage.BROWSER] ?: SortPage.BROWSER.default

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
        val home = container.settings.settings.first().homeFolder?.let(::File)
            ?.takeIf { it.isDirectory || (RemoteFolders.isRemote(it) && container.settings.settings.first().mstream != null) }
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

    private fun visibleFiles() = _state.value.visibleFiles(sort)

    /**
     * The play button. Paused on a track of this folder: carries on from there. Otherwise plays the
     * folder from the top ([shuffle]: shuffled).
     */
    fun playFolder(shuffle: Boolean) {
        val current = player.state.value.mediaId
        val dir = _state.value.dir
        val currentDir = current?.let { if (MStreamPaths.isRemote(it)) RemoteFolders.folder(MStreamPaths.dir(it)) else File(it).parentFile }
        if (!shuffle && current != null && dir != null && currentDir == dir) {
            player.play()
        } else {
            player.playFiles(visibleFiles(), shuffle = if (shuffle) true else null)
        }
    }

    fun stop() = player.stop()

    fun playFrom(file: AudioFile) {
        val files = visibleFiles()
        player.playFiles(files, startIndex = files.indexOf(file).coerceAtLeast(0))
    }

    /** Plays everything under [folder] (subfolders included). */
    fun playSubtree(folder: File, shuffle: Boolean) {
        viewModelScope.launch {
            val files = fs.listRecursive(folder)
            if (files.isEmpty()) _events.send(BrowserEvent.Message("No music in ${folder.name}"))
            else player.playFiles(files, shuffle = if (shuffle) true else null)
        }
    }

    /** Adds everything under [folder] to the queue: at the end, or right after the current track. */
    fun queueSubtree(folder: File, next: Boolean) {
        viewModelScope.launch {
            val files = fs.listRecursive(folder)
            if (files.isEmpty()) return@launch _events.send(BrowserEvent.Message("No music in ${folder.name}"))
            if (next) player.playNext(files) else player.addToQueue(files)
            val what = "${files.size} track${if (files.size == 1) "" else "s"}"
            _events.send(
                BrowserEvent.Message(
                    if (next) "$what play next" else "$what added to queue",
                    action = if (next) SwipeAction.PLAY_NEXT else SwipeAction.ADD_TO_QUEUE,
                ),
            )
        }
    }

    /** List swipe actions on a folder row apply to the whole folder. */
    fun onFolderSwipe(folder: File, action: SwipeAction) {
        when (action) {
            SwipeAction.NONE -> Unit
            SwipeAction.ADD_TO_QUEUE -> queueSubtree(folder, next = false)
            SwipeAction.PLAY_NEXT -> queueSubtree(folder, next = true)
            SwipeAction.FAVORITE -> toggleFolderFavorite(folder)
            SwipeAction.ADD_TO_PLAYLIST -> addFolderToPlaylist(folder)
            SwipeAction.REMOVE_FROM_LIST, SwipeAction.HIDE -> {
                val before = _state.value.folders
                _state.update { it.copy(folders = it.folders - folder) }
                viewModelScope.launch {
                    _events.send(BrowserEvent.Message("Removed from list", undo = { _state.update { it.copy(folders = before) } }, action = action))
                }
            }
            SwipeAction.DELETE_FILE -> showMessage("Folders can't be deleted from here")
            SwipeAction.DOWNLOAD -> viewModelScope.launch {
                _events.send(BrowserEvent.Message(container.mstreamDownloads.request(fs.listRecursive(folder).map { it.path }), action = action))
            }
        }
    }

    /** Everything under [folder] (subfolders included) into a playlist, via the picker. */
    fun addFolderToPlaylist(folder: File) {
        viewModelScope.launch {
            val files = fs.listRecursive(folder)
            if (files.isEmpty()) _events.send(BrowserEvent.Message("No music in ${folder.name}")) else container.playlistPicker.pick(files.map { it.path })
        }
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
                SwipeAction.ADD_TO_PLAYLIST -> container.playlistPicker.pick(listOf(file.path))
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
                SwipeAction.DOWNLOAD -> _events.send(BrowserEvent.Message(container.mstreamDownloads.request(listOf(file.path)), action = action))
            }
        }
    }

    /** Selected folders expanded to everything under them, then the selected files, in list order. */
    private suspend fun expand(folders: List<File>, files: List<AudioFile>): List<AudioFile> =
        folders.flatMap { fs.listRecursive(it) } + files

    fun playSelection(folders: List<File>, files: List<AudioFile>, shuffle: Boolean) {
        viewModelScope.launch {
            val all = expand(folders, files)
            if (all.isEmpty()) _events.send(BrowserEvent.Message("No music selected"))
            else player.playFiles(all, shuffle = if (shuffle) true else null)
        }
    }

    /**
     * Actions on several selected rows; folders act like their folder menu (everything under them).
     * Deletion (files only) is confirmed by the screen first.
     */
    fun onSelection(folders: List<File>, files: List<AudioFile>, action: SwipeAction) {
        if (folders.isEmpty() && files.isEmpty()) return
        viewModelScope.launch {
            when (action) {
                SwipeAction.ADD_TO_QUEUE, SwipeAction.PLAY_NEXT -> {
                    val all = expand(folders, files)
                    if (all.isEmpty()) return@launch _events.send(BrowserEvent.Message("No music selected"))
                    if (action == SwipeAction.PLAY_NEXT) player.playNext(all) else player.addToQueue(all)
                    val what = tracks(all.size)
                    _events.send(BrowserEvent.Message(if (action == SwipeAction.PLAY_NEXT) "$what play next" else "$what added to queue", action = action))
                }
                SwipeAction.FAVORITE -> {
                    // Adds (never toggles off): selecting a mix shouldn't un-favorite some of them.
                    folders.forEach { favorites.insert(FavoriteEntity(it.absolutePath, FavoriteKind.FOLDER, it.name)) }
                    files.forEach { favorites.insert(FavoriteEntity(it.path, FavoriteKind.TRACK, it.title)) }
                    val what = listOfNotNull(
                        folders.size.takeIf { it > 0 }?.let { "$it folder${if (it == 1) "" else "s"}" },
                        files.size.takeIf { it > 0 }?.let(::tracks),
                    ).joinToString(" · ")
                    _events.send(BrowserEvent.Message("$what added to favorites", action = action))
                }
                SwipeAction.REMOVE_FROM_LIST, SwipeAction.HIDE -> {
                    val before = _state.value
                    _state.update { it.copy(files = it.files - files.toSet(), folders = it.folders - folders.toSet()) }
                    _events.send(
                        BrowserEvent.Message(
                            "${folders.size + files.size} removed from list",
                            undo = { _state.update { it.copy(files = before.files, folders = before.folders) } },
                            action = action,
                        ),
                    )
                }
                SwipeAction.DELETE_FILE -> {
                    val deleted = withContext(Dispatchers.IO) { files.filter { !MStreamPaths.isRemote(it.path) && File(it.path).delete() } }
                    _state.update { it.copy(files = it.files - deleted.toSet()) }
                    val failed = files.size - deleted.size
                    _events.send(BrowserEvent.Message("${deleted.size} deleted" + if (failed > 0) " · $failed couldn't be deleted" else ""))
                }
                SwipeAction.ADD_TO_PLAYLIST -> {
                    val all = expand(folders, files)
                    if (all.isEmpty()) _events.send(BrowserEvent.Message("No music selected")) else container.playlistPicker.pick(all.map { it.path })
                }
                SwipeAction.DOWNLOAD -> _events.send(BrowserEvent.Message(container.mstreamDownloads.request(expand(folders, files).map { it.path }), action = action))
                SwipeAction.NONE -> Unit
            }
        }
    }

    private fun tracks(n: Int) = "$n track${if (n == 1) "" else "s"}"

    fun removeFromList(file: AudioFile) {
        _state.update { it.copy(files = it.files - file) }
    }

    fun deleteFile(file: AudioFile) {
        viewModelScope.launch {
            val deleted = !MStreamPaths.isRemote(file.path) && File(file.path).delete()
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
