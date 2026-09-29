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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.zenelo.data.settings.LibraryView
import app.zenelo.data.settings.SortField
import app.zenelo.data.settings.SortOrder
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors

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
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
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
            }
            if (caption != null) Text(caption, style = CaptionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
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
fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = TextStyle(fontFamily = PlexMono, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.26.sp, color = ZeneloColors.TextMuted),
        modifier = modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Two-line text block of a grid tile: title (celadon when playing) and up to two captions. */
@Composable
fun TileText(title: String, subtitle: String?, extra: String? = null, highlighted: Boolean = false, compact: Boolean = false) {
    Spacer(Modifier.height(6.dp))
    Text(
        title,
        style = TileTitleStyle.copy(fontSize = if (compact) 12.5.sp else 13.5.sp),
        color = if (highlighted) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (subtitle != null) Text(subtitle, style = TileSubtitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (extra != null && !compact) {
        Text(extra, style = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, color = ZeneloColors.TextMuted), maxLines = 1)
    }
}

/** Keeps a gap as wide as the round buttons at the end of a scrolling page. */
val FabClearance = 88.dp
