package app.zenelo.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.settings.LibrarySource
import app.zenelo.data.settings.LibraryView
import app.zenelo.data.settings.SortField
import app.zenelo.data.settings.SortOrder
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch

// Pieces of the library pages (Albums, Artists, All tracks, Recently played) and Home, sized
// after the "Player Home JM21" design.

/** "BY NAME · A → Z", "LAST 7 DAYS": mono caps under titles. */
val CaptionStyle = TextStyle(fontFamily = PlexMono, fontSize = 9.5.sp, letterSpacing = 0.95.sp, color = ZeneloColors.TextMuted)

/** Counts and durations next to titles and on rows. */
val CountStyle = TextStyle(fontFamily = PlexMono, fontSize = 11.5.sp, color = ZeneloColors.TextMuted)

val TileTitleStyle = TextStyle(fontFamily = PlexSans, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)

val TileSubtitleStyle = TextStyle(fontFamily = PlexSans, fontSize = 11.sp, color = ZeneloColors.TextSecondary)

/**
 * A track's cover from the thumbnail cache ([large] for grid tiles), or a placeholder: a note, or
 * a waveform on the playing one. [path] is any track of the album / artist.
 */
@Composable
fun CoverImage(
    path: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    large: Boolean = false,
    isCurrent: Boolean = false,
    placeholder: ImageVector = Icons.Outlined.MusicNote,
) {
    val thumbnails = appContainer().thumbnails
    val revision by thumbnails.revision.collectAsStateWithLifecycle()
    val bitmap by produceState(path?.let { thumbnails.peek(it, large) }, path, revision, large) {
        value = path?.let { thumbnails.peek(it, large) ?: thumbnails.load(it, large) }
    }
    Box(modifier.clip(shape).background(ZeneloColors.Placeholder), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (!isCurrent) {
            Icon(placeholder, null, tint = ZeneloColors.TextMuted, modifier = Modifier.size(18.dp))
        }
        if (isCurrent) {
            if (image != null) Box(Modifier.fillMaxSize().background(ZeneloColors.Background.copy(alpha = 0.55f)))
            Icon(Icons.Rounded.GraphicEq, null, tint = ZeneloColors.Celadon, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Header of a library page: back (sub-pages), title + count, the sort / period caption, search
 * and the sort button (null [onSort]: no sort, as on Recently played). While [searching], the
 * title gives way to a search field.
 */
@Composable
fun LibraryHeader(
    title: String,
    count: Int?,
    caption: String?,
    onBack: (() -> Unit)?,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    sortOpen: Boolean = false,
    onSort: (() -> Unit)? = null,
    /** The title opens the library source menu (All / Local / mStream) while a server is connected. */
    sourceMenu: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", modifier = Modifier.size(20.dp))
            }
        }
        if (searching) {
            SearchField(query, onQueryChange, "Search ${title.lowercase()}", Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = { onSearchToggle(false) }) { Icon(Icons.Rounded.Close, "Close search") }
            return@Row
        }
        SourceMenu(enabled = sourceMenu, modifier = Modifier.weight(1f).padding(start = 2.dp)) { source ->
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        title,
                        style = TextStyle(fontFamily = PlexSans, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.17).sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (count != null) {
                        Text(" $count", style = CountStyle.copy(fontSize = 12.sp), modifier = Modifier.padding(bottom = 2.dp))
                    }
                    if (source != null) SourceLabel(source, Modifier.padding(start = 6.dp, bottom = 2.dp))
                }
                if (caption != null) Text(caption, style = CaptionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        actions()
        IconButton(onClick = { onSearchToggle(true) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Search, "Search", modifier = Modifier.size(20.dp))
        }
        if (onSort != null) SortToggle(sortOpen, onSort)
    }
}

/** The ↓↑ button; celadon on a tint while its menu is open. */
@Composable
fun SortToggle(open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (open) ZeneloColors.CeladonTint else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.SwapVert, "Sort", tint = if (open) ZeneloColors.Celadon else ZeneloColors.TextPrimary, modifier = Modifier.size(20.dp))
    }
}

/**
 * The sort menu, dropped from the header over the content below it: ascending options on the
 * left, descending on the right; one tap applies and closes.
 */
@Composable
fun BoxScope.SortMenu(fields: List<SortField>, current: SortOrder, onSelect: (SortOrder) -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .matchParentSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    )
    Column(
        Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ZeneloColors.Bar)
            // Taps between the cells stay on the panel.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ASCENDING ↑", style = CaptionStyle.copy(letterSpacing = 1.14.sp), modifier = Modifier.weight(1f).padding(start = 4.dp))
            Text("DESCENDING ↓", style = CaptionStyle.copy(letterSpacing = 1.14.sp), modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        for (field in fields) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (descending in listOf(false, true)) {
                    val order = SortOrder(field, descending)
                    val selected = order == current
                    Column(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) ZeneloColors.CeladonTint else ZeneloColors.Inset)
                            .clickable { onSelect(order) }
                            .padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            field.label,
                            style = TextStyle(
                                fontFamily = PlexSans,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
                            ),
                            maxLines = 1,
                        )
                        Text(
                            if (descending) field.descending else field.ascending,
                            style = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, color = ZeneloColors.TextMuted),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Round button bottom left: cycles 2×2 → 3×3 → list; shows the current view. */
@Composable
fun ViewButton(view: LibraryView, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(ZeneloColors.Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when (view) {
                LibraryView.GRID2 -> Icons.Rounded.GridView
                LibraryView.GRID3 -> Icons.Rounded.Apps
                LibraryView.LIST -> Icons.AutoMirrored.Rounded.ViewList
            },
            "Change view",
            tint = ZeneloColors.TextPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** "TODAY", "A": group header inside a library list. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, inset: androidx.compose.ui.unit.Dp = 16.dp) {
    Text(
        text.uppercase(),
        style = TextStyle(fontFamily = PlexMono, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.26.sp, color = ZeneloColors.TextMuted),
        modifier = modifier.padding(start = inset, top = 12.dp, bottom = 4.dp),
    )
}

/** Two-line text block of a grid tile: title (celadon when playing) and up to two captions. */
@Composable
fun TileText(
    title: String,
    subtitle: String?,
    extra: String? = null,
    highlighted: Boolean = false,
    compact: Boolean = false,
    cloud: Boolean = false,
    mark: app.zenelo.library.StorageMark = if (cloud) app.zenelo.library.StorageMark.CLOUD else app.zenelo.library.StorageMark.NONE,
) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StorageMarkIcon(mark, Modifier.padding(end = 4.dp))
        Text(
            title,
            style = TileTitleStyle.copy(fontSize = if (compact) 12.5.sp else 13.5.sp),
            color = if (highlighted) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (subtitle != null) Text(subtitle, style = TileSubtitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (extra != null && !compact) {
        Text(extra, style = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, color = ZeneloColors.TextMuted), maxLines = 1)
    }
}

/** Keeps a gap as wide as the round buttons at the end of a scrolling page. */
val FabClearance = 88.dp

/**
 * Makes [content] (a page title) open the library source menu — All / Local / mStream — while an
 * mStream server is connected. [content] gets the chosen source when it isn't "All" (to label it).
 */
@Composable
fun SourceMenu(enabled: Boolean, modifier: Modifier = Modifier, content: @Composable (LibrarySource?) -> Unit) {
    val container = appContainer()
    val flow = remember { container.settings.settings }
    val settings by flow.collectAsStateWithLifecycle(initialValue = null)
    val active = enabled && settings?.mstream != null
    val source = settings?.librarySource ?: LibrarySource.ALL
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(if (active) Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true } else Modifier) {
            content(source.takeIf { active && it != LibrarySource.ALL })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySource.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label, color = if (choice == source) ZeneloColors.Mustard else ZeneloColors.TextPrimary) },
                    leadingIcon = {
                        Icon(
                            when (choice) {
                                LibrarySource.ALL -> Icons.Outlined.LibraryMusic
                                LibrarySource.LOCAL -> Icons.Outlined.PhoneAndroid
                                LibrarySource.MSTREAM -> Icons.Outlined.Cloud
                            },
                            null,
                            tint = if (choice == source) ZeneloColors.Mustard else ZeneloColors.TextMuted,
                        )
                    },
                    onClick = {
                        open = false
                        scope.launch { container.settings.setLibrarySource(choice) }
                    },
                )
            }
        }
    }
}

/** "LOCAL" / "MSTREAM" next to a title while the library shows one source only. */
@Composable
fun SourceLabel(source: LibrarySource, modifier: Modifier = Modifier) {
    Text(
        source.label.uppercase(),
        style = CaptionStyle.copy(color = ZeneloColors.Celadon),
        modifier = modifier,
    )
}

/**
 * On the device and on the mStream server: a server track with a local copy (downloaded or the
 * user's own), or a local file with a server copy (see `ServerLinks`). Follows downloads as they finish.
 */
@Composable
fun rememberDownloaded(path: String?): Boolean {
    if (path == null) return false
    val links by appContainer().serverLinks.links.collectAsStateWithLifecycle()
    return links.isLinked(path)
}

/** Whether a track can play now: always online; offline, local files and server tracks with a copy here. */
@Composable
fun rememberPlayableNow(): (String) -> Boolean {
    val container = appContainer()
    val online by container.network.online.collectAsStateWithLifecycle()
    val links by container.serverLinks.links.collectAsStateWithLifecycle()
    return remember(online, links) { { path: String -> online || container.mstreamFiles.playsOffline(path) } }
}

/** Online (the network is up): server features show only then. */
@Composable
fun rememberOnline(): Boolean {
    val online by appContainer().network.online.collectAsStateWithLifecycle()
    return online
}

/** Offline: playlists none of whose tracks can play (shown faded). Empty online. */
@Composable
fun rememberUnplayablePlaylists(): Set<Long> {
    val playable = rememberPlayableNow()
    if (rememberOnline()) return emptySet()
    val dao = appContainer().db.playlists()
    val flow = remember { dao.observeAllEntries() }
    val entries by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(entries, playable) {
        entries.groupBy { it.playlistId }.filterValues { list -> list.none { playable(it.path) } }.keys
    }
}

/** Each playlist's mark (cloud / partly / downloaded), from its entries and the links. */
@Composable
fun rememberPlaylistMarks(): Map<Long, app.zenelo.library.StorageMark> {
    val container = appContainer()
    val flow = remember { container.db.playlists().observeAllEntries() }
    val entries by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val links by container.serverLinks.links.collectAsStateWithLifecycle()
    return remember(entries, links) {
        entries.groupBy { it.playlistId }.mapValues { (_, list) -> storageOf(list.map { it.path }, links).mark }
    }
}

/** A set of tracks' storage counts, by the links ([ServerLinks.Links]). */
fun storageOf(paths: List<String>, links: app.zenelo.mstream.ServerLinks.Links): app.zenelo.library.Storage =
    paths.fold(app.zenelo.library.Storage()) { acc, path ->
        acc + when {
            links.isLinked(path) -> app.zenelo.library.Storage(1, 0, 1)
            app.zenelo.mstream.MStreamPaths.isRemote(path) -> app.zenelo.library.Storage(1, 1, 0)
            else -> app.zenelo.library.Storage(1, 0, 0)
        }
    }

/**
 * A page's download button (album, artist, genre, playlist, favorites): "Download" while some of
 * its tracks are on the server only (and the server is reachable); once none is, "Remove
 * downloads" for the files "Download" saved (confirmed; they become server tracks again). [kept]:
 * a playlist / favorites kept downloaded — removing stops that first ([onStopKeeping]), and the
 * dialog can also just stop it.
 */
@Composable
fun DownloadAction(paths: List<String>, onDownload: () -> Unit, kept: Boolean = false, onStopKeeping: (() -> Unit)? = null) {
    val container = appContainer()
    val available by container.sonicPath.available.collectAsStateWithLifecycle()
    val links by container.serverLinks.links.collectAsStateWithLifecycle()
    val storage = remember(paths, links) { storageOf(paths, links) }
    val downloads = remember(paths, links) { paths.mapNotNull(links::downloadOf).distinct().size }
    var confirming by remember { androidx.compose.runtime.mutableStateOf(false) }
    when {
        storage.cloud > 0 && available -> IconButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, "Download ${storage.cloud} track${if (storage.cloud == 1) "" else "s"}", tint = ZeneloColors.Celadon, modifier = Modifier.size(22.dp))
        }
        downloads > 0 -> IconButton(onClick = { confirming = true }) {
            Icon(Icons.Outlined.FileDownloadOff, "Remove downloads", tint = ZeneloColors.Celadon, modifier = Modifier.size(22.dp))
        }
        kept && onStopKeeping != null -> IconButton(onClick = onStopKeeping) {
            Icon(Icons.Outlined.DownloadDone, "Downloaded · stop downloading new tracks", tint = ZeneloColors.Celadon, modifier = Modifier.size(22.dp))
        }
    }
    if (confirming) {
        val bytes by androidx.compose.runtime.produceState<Long?>(null, paths) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.mstreamDownloads.downloadsOf(paths).sumOf { it.length() } }
        }
        val what = "$downloads downloaded track${if (downloads == 1) "" else "s"}" + (bytes?.let { " · ${formatSize(it)}" } ?: "")
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = ZeneloColors.Card,
            title = { Text("Remove downloads?") },
            text = {
                Column {
                    val one = downloads == 1
                    Text(
                        "$what ${if (one) "leaves" else "leave"} the device. ${if (one) "It stays" else "They stay"} on the server and in this list, streamed when played." +
                            // Linked files the user had before (matched to the server) aren't downloads.
                            (if (storage.downloaded > downloads) " Your own files stay." else "") +
                            if (kept) " New tracks won't download anymore." else "",
                    )
                    if (kept && onStopKeeping != null) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                confirming = false
                                onStopKeeping()
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Only stop downloading new tracks") }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirming = false
                    // Stop keeping first, or the removed tracks would be downloaded again.
                    if (kept) onStopKeeping?.invoke()
                    container.appScope.launch {
                        val r = container.mstreamDownloads.removeDownloads(paths)
                        container.playbackMessages.emit(
                            if (r.files == 0) "Nothing removed" else "Removed ${r.files} download${if (r.files == 1) "" else "s"} · ${formatSize(r.bytes)} freed",
                        )
                    }
                }) { Text("Remove", color = ZeneloColors.Danger) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Whether to show the player's loading spinner: only once loading lasts [delayMs] — a local file
 * starts in a few milliseconds and would just flicker it.
 */
@Composable
fun rememberSpinner(loading: Boolean, delayMs: Long = 300): Boolean {
    var shown by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(loading) {
        shown = false
        if (loading) {
            kotlinx.coroutines.delay(delayMs)
            shown = true
        }
    }
    return shown && loading
}

/** How faded a track (or playlist) is that can't play offline. */
const val UNPLAYABLE_ALPHA = 0.4f

/** Offline and a server track with no copy on the device (downloaded, the user's own or cached): it can't play now. */
@Composable
fun rememberUnplayable(path: String?, remote: Boolean = app.zenelo.mstream.MStreamPaths.isRemote(path)): Boolean {
    if (path == null || !remote) return false
    val container = appContainer()
    val online by container.network.online.collectAsStateWithLifecycle()
    if (online) return false
    val links by container.serverLinks.links.collectAsStateWithLifecycle()
    return links.localOf(path) == null && !container.mstreamFiles.isCached(path)
}

/** A local file or server track the server knows (a server track, or a local file with a server copy). */
@Composable
fun rememberOnServer(path: String?): Boolean {
    if (path == null) return false
    if (app.zenelo.mstream.MStreamPaths.isRemote(path)) return true
    return rememberDownloaded(path)
}

/** The small cloud after a title: on the mStream server only (not on the device). */
@Composable
fun CloudMark(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.Cloud, "On the server", tint = ZeneloColors.TextMuted, modifier = modifier.size(13.dp))
}

/** Partly on the mStream server only: some of a set's tracks aren't on the device yet. */
@Composable
fun PartialMark(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.CloudDownload, "Partly on the server", tint = ZeneloColors.TextMuted, modifier = modifier.size(13.dp))
}

/** The mark for [mark] (nothing for [StorageMark.NONE]). */
@Composable
fun StorageMarkIcon(mark: app.zenelo.library.StorageMark, modifier: Modifier = Modifier) {
    when (mark) {
        app.zenelo.library.StorageMark.CLOUD -> CloudMark(modifier)
        app.zenelo.library.StorageMark.PARTIAL -> PartialMark(modifier)
        app.zenelo.library.StorageMark.DOWNLOADED -> DownloadMark(modifier)
        app.zenelo.library.StorageMark.NONE -> Unit
    }
}

/** A track's mark, following downloads and links as they change. */
@Composable
fun rememberStorageMark(path: String?): app.zenelo.library.StorageMark {
    if (path == null) return app.zenelo.library.StorageMark.NONE
    val links by appContainer().serverLinks.links.collectAsStateWithLifecycle()
    return when {
        links.isLinked(path) -> app.zenelo.library.StorageMark.DOWNLOADED
        app.zenelo.mstream.MStreamPaths.isRemote(path) -> app.zenelo.library.StorageMark.CLOUD
        else -> app.zenelo.library.StorageMark.NONE
    }
}

/** The small arrow after a title: downloaded from the mStream server. */
@Composable
fun DownloadMark(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.DownloadDone, "Downloaded", tint = ZeneloColors.Celadon, modifier = modifier.size(13.dp))
}
