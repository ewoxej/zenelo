package app.zenelo.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zenelo.data.db.AlbumRow
import app.zenelo.data.db.ArtistRow
import app.zenelo.data.settings.LibraryView
import app.zenelo.data.settings.SortField
import app.zenelo.data.settings.SortOrder
import app.zenelo.data.settings.SwipeAction
import app.zenelo.ui.components.CountStyle
import app.zenelo.ui.components.CoverImage
import app.zenelo.ui.components.LibraryHeader
import app.zenelo.ui.components.PlayFab
import app.zenelo.ui.components.SelectionBar
import app.zenelo.ui.components.SelectionMark
import app.zenelo.ui.components.SortMenu
import app.zenelo.ui.components.TileText
import app.zenelo.ui.components.ViewButton
import app.zenelo.ui.components.formatTotal
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Multi-select on a library page: long-press starts it, deselecting the last row ends it. */
@Stable
class Selection<K> {
    var active by mutableStateOf(false)
        private set
    var keys by mutableStateOf(emptySet<K>())
        private set

    fun start(key: K) {
        active = true
        keys = keys + key
    }

    fun toggle(key: K) {
        keys = if (key in keys) keys - key else keys + key
        if (keys.isEmpty()) active = false
    }

    fun toggleAll(all: Collection<K>) {
        if (keys.size >= all.size) clear() else keys = all.toSet()
    }

    fun clear() {
        active = false
        keys = emptySet()
    }
}

@Composable
fun <K> rememberSelection() = remember { Selection<K>() }

/** Search state of a page: open or not, and the query. */
@Stable
class PageSearch {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")

    fun toggle(on: Boolean) {
        open = on
        if (!on) query = ""
    }

    fun matches(vararg texts: String?): Boolean =
        query.isBlank() || texts.any { it?.contains(query.trim(), ignoreCase = true) == true }
}

/**
 * The shared frame of the library pages: header (or the selection bar while selecting), the sort
 * menu over the content, the view button bottom left and play / stop bottom right.
 */
@Composable
fun LibraryScaffold(
    title: String,
    count: Int?,
    caption: String?,
    onBack: (() -> Unit)?,
    search: PageSearch,
    selection: Selection<*>,
    selectAll: () -> Unit,
    allSelected: Boolean,
    onSelectionAction: (SwipeAction) -> Unit,
    onSelectionPlay: (shuffle: Boolean) -> Unit,
    sortFields: List<SortField>? = null,
    sort: SortOrder? = null,
    onSort: (SortOrder) -> Unit = {},
    view: LibraryView? = null,
    onNextView: () -> Unit = {},
    // No play / stop button unless given (Albums and Artists have none).
    isPlaying: Boolean = false,
    canPlay: Boolean = false,
    onPlay: (shuffle: Boolean) -> Unit = {},
    onStop: () -> Unit = {},
    headerActions: @Composable () -> Unit = {},
    onSelectionRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
    content: @Composable BoxScope.() -> Unit,
) {
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = selection.active || search.open || sortOpen) {
        when {
            sortOpen -> sortOpen = false
            selection.active -> selection.clear()
            else -> search.toggle(false)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(
                count = selection.keys.size,
                allSelected = allSelected,
                canDelete = false,
                onClose = selection::clear,
                onSelectAll = selectAll,
                onPlay = { shuffle ->
                    onSelectionPlay(shuffle)
                    selection.clear()
                },
                onAction = { action ->
                    onSelectionAction(action)
                    selection.clear()
                },
                onRemove = onSelectionRemove?.let { remove -> { remove(); selection.clear() } },
                removeLabel = removeLabel,
            )
        } else {
            LibraryHeader(
                title = title,
                count = count,
                caption = caption,
                onBack = onBack,
                searching = search.open,
                query = search.query,
                onQueryChange = { search.query = it },
                onSearchToggle = search::toggle,
                sortOpen = sortOpen,
                onSort = if (sortFields != null) ({ sortOpen = !sortOpen }) else null,
                actions = headerActions,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
            if (view != null && !selection.active) {
                ViewButton(view, onNextView, Modifier.align(Alignment.BottomStart).padding(16.dp))
            }
            if (!selection.active && (isPlaying || canPlay)) {
                if (isPlaying) {
                    PlayFab(onClick = onStop, icon = Icons.Rounded.Stop, description = "Stop", modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
                } else {
                    PlayFab(onClick = { onPlay(false) }, onLongClick = { onPlay(true) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
                }
            }
            if (sortOpen && sortFields != null && sort != null) {
                SortMenu(sortFields, sort, onSelect = { onSort(it); sortOpen = false }, onDismiss = { sortOpen = false })
            }
        }
    }
}

private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)

fun formatDay(millis: Long): String = synchronized(dayFormat) { dayFormat.format(Date(millis)) }

/** The third line of an album tile / row: what the albums are sorted by. */
fun albumDetail(album: AlbumRow, sort: SortOrder?): String = when (sort?.sortBy) {
    SortField.DATE_ADDED -> "Added ${formatDay(album.added)}"
    else -> "${album.tracks} track${if (album.tracks == 1) "" else "s"} · ${formatTotal(album.durationMs)}"
}

fun artistDetail(artist: ArtistRow): String =
    "${artist.albums} album${if (artist.albums == 1) "" else "s"} · ${artist.tracks} track${if (artist.tracks == 1) "" else "s"}"

/** Selection mark in a tile's corner (the side is a setting). */
@Composable
private fun BoxScope.TileMark(selecting: Boolean, selected: Boolean, markLeft: Boolean) {
    if (!selecting) return
    Box(
        Modifier
            .align(if (markLeft) Alignment.TopStart else Alignment.TopEnd)
            .padding(6.dp)
            .clip(CircleShape)
            .background(ZeneloColors.Background.copy(alpha = 0.6f)),
    ) { SelectionMark(selected) }
}

/** An album in a grid: square cover, title, artist, and the sorted-by detail. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumTile(
    album: AlbumRow,
    detail: String?,
    compact: Boolean,
    isCurrent: Boolean,
    selecting: Boolean,
    selected: Boolean,
    markLeft: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            CoverImage(
                album.coverPath,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .then(if (selected) Modifier.border(2.dp, ZeneloColors.Mustard, RoundedCornerShape(10.dp)) else Modifier),
                shape = RoundedCornerShape(10.dp),
                large = !compact,
                placeholder = Icons.Outlined.Album,
            )
            TileMark(selecting, selected, markLeft)
        }
        TileText(album.album, album.artist, detail, highlighted = isCurrent, compact = compact)
    }
}

/** An artist in a grid: round cover and name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistTile(
    artist: ArtistRow,
    compact: Boolean,
    selecting: Boolean,
    selected: Boolean,
    markLeft: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            CoverImage(
                artist.coverPath,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .then(if (selected) Modifier.border(2.dp, ZeneloColors.Mustard, CircleShape) else Modifier),
                shape = CircleShape,
                large = !compact,
                placeholder = Icons.Outlined.Person,
            )
            TileMark(selecting, selected, markLeft)
        }
        Text(
            artist.name,
            style = TextStyle(fontFamily = PlexSans, fontSize = if (compact) 12.5.sp else 13.5.sp, fontWeight = FontWeight.Medium),
            color = ZeneloColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!compact) Text(artistDetail(artist), style = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, color = ZeneloColors.TextMuted), maxLines = 1)
    }
}

/**
 * A list row of the library: leading cover (or number, or nothing), title, subtitle, a trailing
 * text (duration, time) and a trailing slot (⋮, chevron). The selection mark takes the leading
 * or trailing side per the setting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
    coverShape: Shape = RoundedCornerShape(6.dp),
    coverSize: Dp = 40.dp,
    showCover: Boolean = true,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Album,
    number: String? = null,
    trailingText: String? = null,
    isCurrent: Boolean = false,
    dimmed: Boolean = false,
    selecting: Boolean = false,
    selected: Boolean = false,
    markLeft: Boolean = true,
    subtitleMono: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) ZeneloColors.MustardTint.compositeOver(ZeneloColors.Background) else ZeneloColors.Background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting && markLeft) SelectionMark(selected, Modifier.padding(end = 12.dp))
        if (number != null) {
            Text(number, style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, color = if (isCurrent) ZeneloColors.Celadon else ZeneloColors.TextMuted), modifier = Modifier.width(30.dp))
        } else if (showCover) {
            CoverImage(coverPath, Modifier.size(coverSize), shape = coverShape, isCurrent = isCurrent, placeholder = placeholder)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = when {
                    isCurrent -> ZeneloColors.Celadon
                    dimmed -> ZeneloColors.TextMuted
                    else -> ZeneloColors.TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = if (subtitleMono) TextStyle(fontFamily = PlexMono, fontSize = 11.sp) else TextStyle(fontFamily = PlexSans, fontSize = 11.5.sp),
                    color = ZeneloColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) Text(trailingText, style = CountStyle.copy(fontSize = 11.sp), modifier = Modifier.padding(start = 8.dp, end = 4.dp))
        if (selecting) {
            if (!markLeft) SelectionMark(selected, Modifier.padding(horizontal = 10.dp))
        } else {
            trailing()
        }
    }
}

@Composable
fun Chevron() {
    Icon(Icons.Rounded.ChevronRight, null, tint = ZeneloColors.TextMuted, modifier = Modifier.padding(horizontal = 10.dp).size(20.dp))
}
