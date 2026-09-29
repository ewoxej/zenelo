package app.zenelo.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.FavoriteKind
import app.zenelo.library.AudioFile
import app.zenelo.ui.components.BackButton
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.PlayFab
import app.zenelo.ui.components.ScreenTitle
import app.zenelo.ui.components.SearchField
import app.zenelo.ui.components.TrackThumb
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FavoritesScreen(
    currentMediaId: String?,
    isPlaying: Boolean,
    onOpenFolder: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val container = appContainer()
    val dao = container.db.favorites()
    val flow = remember { dao.observeAll() }
    val favorites by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val visible = remember(favorites, query) {
        if (query.isBlank()) favorites else favorites.filter { it.title.contains(query, true) || it.subtitle?.contains(query, true) == true }
    }
    val tracks = remember(visible) { visible.filter { it.kind == FavoriteKind.TRACK }.map { AudioFile.of(File(it.path)) } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = if (onBack != null) 4.dp else 20.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) BackButton(onBack)
                if (searching) {
                    SearchField(query, { query = it }, "Search favorites", Modifier.weight(1f))
                    IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Rounded.Close, "Close search") }
                } else {
                    ScreenTitle("Favorites", Modifier.weight(1f), count = favorites.size)
                    IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search", Modifier.size(22.dp)) }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(visible, key = { it.path }) { favorite ->
                    val isCurrent = favorite.path == currentMediaId
                    val extension = File(favorite.path).extension.uppercase().takeIf { favorite.kind == FavoriteKind.TRACK && it.isNotEmpty() }
                    ListRow(
                        title = favorite.title,
                        subtitle = listOfNotNull(favorite.subtitle, extension).joinToString(" · ")
                            .ifEmpty { favorite.kind.name.lowercase().replaceFirstChar { it.uppercase() } },
                        titleColor = if (isCurrent) ZeneloColors.Celadon else ZeneloColors.TextPrimary,
                        onClick = {
                            when (favorite.kind) {
                                // Play the favorite tracks as a list, starting from this one.
                                FavoriteKind.TRACK -> container.player.playFiles(tracks, startIndex = tracks.indexOfFirst { it.path == favorite.path })
                                FavoriteKind.FOLDER -> onOpenFolder(favorite.path)
                                // Library keys behind "album:" / "artist:" (older album entries were folders).
                                FavoriteKind.ALBUM ->
                                    if (favorite.path.startsWith("album:")) onOpenAlbum(favorite.path.removePrefix("album:")) else onOpenFolder(favorite.path)
                                FavoriteKind.ARTIST -> onOpenArtist(favorite.path.removePrefix("artist:"))
                            }
                        },
                        leading = {
                            when (favorite.kind) {
                                // TODO: cover thumbnails once tags / cover lookup land.
                                FavoriteKind.TRACK -> TrackThumb(favorite.path, isCurrent)
                                FavoriteKind.ALBUM -> IconTile(Icons.Outlined.Album, ZeneloColors.Mustard, ZeneloColors.MustardTint)
                                FavoriteKind.FOLDER -> IconTile(Icons.Outlined.Folder, ZeneloColors.Mustard, ZeneloColors.MustardTint)
                                FavoriteKind.ARTIST -> IconTile(Icons.Outlined.Person, ZeneloColors.Mustard, ZeneloColors.MustardTint)
                            }
                        },
                        trailing = {
                            IconButton(onClick = { scope.launch { dao.delete(favorite.path) } }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Favorite, "Remove from favorites", tint = ZeneloColors.Celadon, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                }
                if (favorites.isEmpty()) {
                    item {
                        Text(
                            "Swipe a track, tap the heart while it plays, or long-press a folder to add it here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ZeneloColors.TextMuted,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
        if (tracks.isNotEmpty() && !isPlaying) {
            PlayFab(
                onClick = { container.player.playFiles(tracks) },
                onLongClick = { container.player.playFiles(tracks, shuffle = true) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

