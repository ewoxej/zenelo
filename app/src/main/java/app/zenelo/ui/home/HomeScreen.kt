package app.zenelo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.FavoriteEntity
import app.zenelo.data.db.FavoriteKind
import app.zenelo.data.db.RecentPlay
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.HomeMode
import app.zenelo.data.settings.Section
import app.zenelo.data.settings.SortPage
import app.zenelo.library.AudioFile
import app.zenelo.library.Library
import app.zenelo.library.LibrarySort
import app.zenelo.library.toAudioFile
import app.zenelo.ui.components.CaptionStyle
import app.zenelo.ui.components.CountStyle
import app.zenelo.ui.components.CoverImage
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.icon
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.formatDuration
import app.zenelo.ui.library.LibraryNav
import app.zenelo.ui.library.LibraryViewModel
import app.zenelo.ui.library.artistDetail
import app.zenelo.ui.library.recentTitle
import app.zenelo.ui.library.rememberNowPlaying
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors
import app.zenelo.ui.components.SourceLabel
import app.zenelo.ui.components.SourceMenu
import java.io.File

/** Where Home's shortcuts, card headers and tiles lead. */
class HomeNav(
    val library: LibraryNav,
    val onOpenPlaylist: (Long) -> Unit,
    val onOpenSection: (Section) -> Unit,
    val onOpenFolder: (File) -> Unit,
    val onCustomize: () -> Unit,
    val onSearch: () -> Unit,
)

/** How many rows a list card shows before "+N". */
private const val LIST_ROWS = 4

/**
 * Home: shortcut icons on top, then the cards in the chosen order (a grid card scrolls sideways,
 * a list card shows four rows and a "+N" count). Built from the Home settings.
 */
@Composable
fun HomeScreen(vm: LibraryViewModel, nav: HomeNav) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shortcuts = settings.home.filter { it.mode == HomeMode.ICON }
    val cards = settings.home.filter { it.mode == HomeMode.GRID || it.mode == HomeMode.LIST }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // The title picks the library source (All / Local / mStream) while a server is connected.
                SourceMenu(enabled = true, modifier = Modifier.weight(1f)) { source ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Home", style = TextStyle(fontFamily = PlexSans, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp))
                        if (source != null) SourceLabel(source, Modifier.padding(start = 8.dp, bottom = 3.dp))
                    }
                }
                IconButton(onClick = nav.onSearch) { Icon(Icons.Rounded.Search, "Search", Modifier.size(21.dp)) }
                IconButton(onClick = nav.onCustomize) { Icon(Icons.Rounded.Tune, "Customize home", Modifier.size(21.dp)) }
            }
        }
        if (shortcuts.isNotEmpty()) {
            item(key = "shortcuts") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shortcuts.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { Shortcut(it.section, Modifier.weight(1f)) { nav.onOpenSection(it.section) } }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        cards.forEach { item ->
            item(key = "card:${item.section}") {
                val grid = item.mode == HomeMode.GRID
                when (item.section) {
                    Section.RECENT -> RecentCard(vm, grid, nav)
                    Section.ALBUMS -> AlbumsCard(vm, grid, nav)
                    Section.ARTISTS -> ArtistsCard(vm, grid, nav)
                    Section.TRACKS -> TracksCard(vm, grid, nav)
                    Section.FOLDERS -> FoldersCard(grid, nav)
                    Section.FAVORITES -> FavoritesCard(vm, grid, nav)
                    Section.PLAYLISTS -> PlaylistsCard(grid, nav)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Shortcut(section: Section, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ZeneloColors.Bar)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconTile(section.icon, ZeneloColors.Mustard, ZeneloColors.MustardTint, size = 30.dp, iconSize = 16.dp, cornerRadius = 9.dp)
        Spacer(Modifier.height(6.dp))
        Text(section.label, style = TextStyle(fontFamily = PlexSans, fontSize = 11.sp, color = ZeneloColors.TextSecondary), maxLines = 1)
    }
}

/** A Home card: icon, title, caption, count and a chevron (the header opens the full page). */
@Composable
private fun HomeCard(section: Section, caption: String?, count: Int?, nav: HomeNav, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ZeneloColors.Bar)
            .padding(bottom = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { nav.onOpenSection(section) }.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(section.icon, ZeneloColors.Mustard, ZeneloColors.MustardTint, size = 26.dp, iconSize = 14.dp, cornerRadius = 8.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(section.label, style = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = ZeneloColors.TextPrimary)
                if (caption != null) Text(caption, style = CaptionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (count != null) Text("$count", style = CountStyle)
            Icon(Icons.Rounded.ChevronRight, null, tint = ZeneloColors.TextMuted, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        content()
    }
}

/** A tile in a grid card: cover (or icon), title and caption; the row scrolls sideways. */
@Composable
private fun HomeTile(
    title: String,
    subtitle: String?,
    coverPath: String?,
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(8.dp),
    icon: ImageVector = Icons.Outlined.Album,
    highlighted: Boolean = false,
) {
    Column(Modifier.width(96.dp).clickable(onClick = onClick)) {
        CoverImage(coverPath, Modifier.size(96.dp), shape = shape, large = true, placeholder = icon)
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = TextStyle(fontFamily = PlexSans, fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            color = if (highlighted) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) Text(subtitle, style = TextStyle(fontFamily = PlexSans, fontSize = 11.sp, color = ZeneloColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun <T> TileRow(items: List<T>, key: (T) -> Any, tile: @Composable (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items.take(30), key = key) { tile(it) }
    }
}

/** A row in a list card. */
@Composable
private fun HomeRow(
    title: String,
    subtitle: String?,
    coverPath: String?,
    onClick: () -> Unit,
    trailing: String? = null,
    shape: Shape = RoundedCornerShape(6.dp),
    icon: ImageVector = Icons.Outlined.Album,
    coverSize: Dp = 40.dp,
    highlighted: Boolean = false,
    mono: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).height(50.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(coverPath, Modifier.size(coverSize), shape = shape, placeholder = icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(fontFamily = PlexSans, fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                color = if (highlighted) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (mono) TextStyle(fontFamily = PlexMono, fontSize = 11.sp) else TextStyle(fontFamily = PlexSans, fontSize = 11.5.sp),
                    color = ZeneloColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) Text(trailing, style = CountStyle.copy(fontSize = 11.sp))
    }
}

@Composable
private fun MoreCount(rest: Int, what: String) {
    if (rest <= 0) return
    Text(
        "+$rest $what",
        style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, color = ZeneloColors.TextMuted),
        modifier = Modifier.padding(start = 66.dp, top = 4.dp),
    )
}

@Composable
private fun EmptyCard(text: String) {
    Text(text, style = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, color = ZeneloColors.TextMuted), modifier = Modifier.padding(horizontal = 14.dp))
}

@Composable
private fun RecentCard(vm: LibraryViewModel, grid: Boolean, nav: HomeNav) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    val (mediaId, _) = rememberNowPlaying()
    val plays = recent.orEmpty()
    val files = remember(plays) { plays.map(RecentPlay::toAudioFile) }
    HomeCard(Section.RECENT, "LAST ${Library.RECENT_DAYS} DAYS", recent?.size, nav) {
        if (recent != null && plays.isEmpty()) return@HomeCard EmptyCard("Nothing played yet.")
        if (grid) {
            TileRow(plays, { it.path }) { play ->
                HomeTile(recentTitle(play), play.artist, play.path, { vm.play(files, start = plays.indexOf(play)) }, icon = Icons.Outlined.MusicNote, highlighted = play.path == mediaId)
            }
        } else {
            plays.take(LIST_ROWS).forEachIndexed { i, play ->
                HomeRow(recentTitle(play), play.artist, play.path, { vm.play(files, start = i) }, icon = Icons.Outlined.MusicNote, highlighted = play.path == mediaId)
            }
            MoreCount(plays.size - LIST_ROWS, "tracks")
        }
    }
}

@Composable
private fun AlbumsCard(vm: LibraryViewModel, grid: Boolean, nav: HomeNav) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sort = settings.sorts[SortPage.ALBUMS] ?: SortPage.ALBUMS.default
    val list = albums.orEmpty()
    HomeCard(Section.ALBUMS, "BY ${sort.sortBy.label.uppercase()}", albums?.size, nav) {
        if (albums != null && list.isEmpty()) return@HomeCard EmptyCard("No albums yet.")
        if (grid) {
            TileRow(list, { it.key }) { album -> HomeTile(album.album, album.artist, album.coverPath, { nav.library.onOpenAlbum(album.key) }) }
        } else {
            list.take(LIST_ROWS).forEach { album -> HomeRow(album.album, album.artist, album.coverPath, { nav.library.onOpenAlbum(album.key) }) }
            MoreCount(list.size - LIST_ROWS, "albums")
        }
    }
}

@Composable
private fun ArtistsCard(vm: LibraryViewModel, grid: Boolean, nav: HomeNav) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sort = settings.sorts[SortPage.ARTISTS] ?: SortPage.ARTISTS.default
    val list = artists.orEmpty()
    HomeCard(Section.ARTISTS, "BY ${sort.sortBy.label.uppercase()}", artists?.size, nav) {
        if (artists != null && list.isEmpty()) return@HomeCard EmptyCard("No artists yet.")
        if (grid) {
            TileRow(list, { it.key }) { artist ->
                HomeTile(artist.name, null, artist.coverPath, { nav.library.onOpenArtist(artist.key) }, shape = CircleShape, icon = Icons.Outlined.Person)
            }
        } else {
            list.take(LIST_ROWS).forEach { artist ->
                HomeRow(artist.name, artistDetail(artist), artist.coverPath, { nav.library.onOpenArtist(artist.key) }, shape = CircleShape, icon = Icons.Outlined.Person, mono = true)
            }
            MoreCount(list.size - LIST_ROWS, "artists")
        }
    }
}

@Composable
private fun TracksCard(vm: LibraryViewModel, grid: Boolean, nav: HomeNav) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val (mediaId, _) = rememberNowPlaying()
    val sort = settings.sorts[SortPage.TRACKS] ?: SortPage.TRACKS.default
    val list = tracks.orEmpty()
    val play = { track: TrackEntity -> vm.play(list.map(TrackEntity::toAudioFile), start = list.indexOf(track)) }
    HomeCard(Section.TRACKS, "BY ${sort.sortBy.label.uppercase()}", tracks?.size, nav) {
        if (tracks != null && list.isEmpty()) return@HomeCard EmptyCard("No tracks yet.")
        if (grid) {
            TileRow(list, { it.path }) { t -> HomeTile(LibrarySort.trackName(t), t.artist, t.path, { play(t) }, icon = Icons.Outlined.MusicNote, highlighted = t.path == mediaId) }
        } else {
            list.take(LIST_ROWS).forEach { t ->
                HomeRow(LibrarySort.trackName(t), t.artist, t.path, { play(t) }, trailing = formatDuration(t.durationMs), icon = Icons.Outlined.MusicNote, highlighted = t.path == mediaId)
            }
            MoreCount(list.size - LIST_ROWS, "tracks")
        }
    }
}

/** Folders of the home folder. */
@Composable
private fun FoldersCard(grid: Boolean, nav: HomeNav) {
    val container = appContainer()
    val settingsFlow = remember { container.settings.settings }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val home = settings?.let { s -> s.homeFolder?.let(::File) ?: container.fileBrowser.roots().firstOrNull()?.dir }
    val folders by produceState<List<File>?>(null, home) { value = home?.let { container.fileBrowser.list(it).folders } }
    val list = folders.orEmpty()
    HomeCard(Section.FOLDERS, home?.name?.uppercase(), folders?.size, nav) {
        if (folders != null && list.isEmpty()) return@HomeCard EmptyCard("No folders in ${home?.name ?: "the home folder"}.")
        if (grid) {
            TileRow(list, { it.path }) { folder ->
                Column(Modifier.width(96.dp).clickable { nav.onOpenFolder(folder) }) {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(ZeneloColors.MustardTint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Folder, null, tint = ZeneloColors.Mustard, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(folder.name, style = TextStyle(fontFamily = PlexSans, fontSize = 12.5.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            list.take(LIST_ROWS).forEach { folder ->
                Row(Modifier.fillMaxWidth().clickable { nav.onOpenFolder(folder) }.height(50.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Outlined.Folder, ZeneloColors.Mustard, ZeneloColors.MustardTint, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(folder.name, style = TextStyle(fontFamily = PlexSans, fontSize = 13.5.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MoreCount(list.size - LIST_ROWS, "folders")
        }
    }
}

@Composable
private fun FavoritesCard(vm: LibraryViewModel, grid: Boolean, nav: HomeNav) {
    val container = appContainer()
    val flow = remember { container.db.favorites().observeAll() }
    val favorites by flow.collectAsStateWithLifecycle(initialValue = null)
    val list = favorites.orEmpty()
    val tracks = remember(list) { list.filter { it.kind == FavoriteKind.TRACK }.map { AudioFile.forPath(it.path) } }
    val open = { f: FavoriteEntity ->
        when (f.kind) {
            FavoriteKind.TRACK -> vm.play(tracks, start = tracks.indexOfFirst { it.path == f.path })
            FavoriteKind.FOLDER -> nav.onOpenFolder(File(f.path))
            FavoriteKind.ALBUM -> nav.library.onOpenAlbum(f.path.removePrefix("album:"))
            FavoriteKind.ARTIST -> nav.library.onOpenArtist(f.path.removePrefix("artist:"))
        }
    }
    HomeCard(Section.FAVORITES, "RECENTLY ADDED", favorites?.size, nav) {
        if (favorites != null && list.isEmpty()) return@HomeCard EmptyCard("Nothing here yet.")
        if (grid) {
            TileRow(list, { it.path }) { f ->
                HomeTile(f.title, f.subtitle, f.path.takeIf { f.kind == FavoriteKind.TRACK }, { open(f) }, icon = favoriteIcon(f.kind), shape = if (f.kind == FavoriteKind.ARTIST) CircleShape else RoundedCornerShape(8.dp))
            }
        } else {
            list.take(LIST_ROWS).forEach { f ->
                HomeRow(f.title, f.subtitle, f.path.takeIf { f.kind == FavoriteKind.TRACK }, { open(f) }, icon = favoriteIcon(f.kind), shape = if (f.kind == FavoriteKind.ARTIST) CircleShape else RoundedCornerShape(6.dp))
            }
            MoreCount(list.size - LIST_ROWS, "favorites")
        }
    }
}

private fun favoriteIcon(kind: FavoriteKind) = when (kind) {
    FavoriteKind.TRACK -> Icons.Outlined.MusicNote
    FavoriteKind.FOLDER -> Icons.Outlined.Folder
    FavoriteKind.ARTIST -> Icons.Outlined.Person
    else -> Icons.Outlined.Album
}

@Composable
private fun PlaylistsCard(grid: Boolean, nav: HomeNav) {
    val dao = appContainer().db.playlists()
    val flow = remember { dao.observeWithCounts() }
    val playlists by flow.collectAsStateWithLifecycle(initialValue = null)
    val list = playlists.orEmpty()
    HomeCard(Section.PLAYLISTS, null, playlists?.size, nav) {
        if (playlists != null && list.isEmpty()) return@HomeCard EmptyCard("No playlists yet.")
        if (grid) {
            TileRow(list, { it.id }) { p ->
                HomeTile(p.name, "${p.trackCount} tracks", p.coverPath, { nav.onOpenPlaylist(p.id) }, icon = Icons.AutoMirrored.Rounded.QueueMusic)
            }
        } else {
            list.take(LIST_ROWS).forEach { p ->
                HomeRow(p.name, "${p.trackCount} tracks", p.coverPath, { nav.onOpenPlaylist(p.id) }, icon = Icons.AutoMirrored.Rounded.QueueMusic, mono = true)
            }
            MoreCount(list.size - LIST_ROWS, "playlists")
        }
    }
}
