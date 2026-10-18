package app.zenelo.ui.components

import androidx.compose.material.icons.outlined.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Radar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.zenelo.data.settings.SwipeAction
import app.zenelo.ui.theme.ZeneloColors

// Row pieces shared by the folder browser and the library pages.

/** The ⋮ button of a track row and its menu. */
@Composable
fun TrackMenu(
    onAction: (SwipeAction) -> Unit,
    /** The full-screen tag editor (tags, cover, fill from file name). */
    onEditTags: () -> Unit,
    /** Server tracks whose tags can't be edited: the cover search. */
    onFindCover: () -> Unit = {},
    /** False for mStream tracks: not deleted; their tags edited only when the server allows it. */
    local: Boolean = true,
    /** The track, for "Play similar" / "Sonic path" (while logged in to mStream). */
    path: String? = null,
    /** A page's own removal, e.g. "Remove from playlist" (after the actions). */
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, "More", tint = ZeneloColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // Server tracks can be downloaded (once), not deleted; local files the other way round.
            // Offline, nothing downloads: not offered.
            val downloaded = rememberDownloaded(path) || !rememberOnline()
            // The heart toggles: a favorite (under either of its paths) offers to remove it.
            val container = appContainer()
            val links by container.serverLinks.links.collectAsStateWithLifecycle()
            val favoriteFlow = remember(path, links) { path?.let { container.db.favorites().observeIsFavoriteAny(listOf(it) + links.copiesOf(it)) } }
            val favorite by (favoriteFlow ?: kotlinx.coroutines.flow.flowOf(false)).collectAsStateWithLifecycle(initialValue = false)
            SwipeAction.entries.filter {
                // Sonic path comes with Play similar below (server tracks only).
                // Edit tags has its own item below.
                it != SwipeAction.NONE && it != SwipeAction.SONIC_PATH && it != SwipeAction.EDIT_TAGS && it != (if (local) SwipeAction.DOWNLOAD else SwipeAction.DELETE_FILE) &&
                    !(it == SwipeAction.DOWNLOAD && downloaded)
            }.forEach { action ->
                DropdownMenuItem(
                    text = { Text(if (action == SwipeAction.FAVORITE && favorite) "Remove from favorites" else action.label) },
                    leadingIcon = { Icon(action.icon, null, tint = action.accent) },
                    onClick = {
                        menu = false
                        onAction(action)
                    },
                )
            }
            if (onRemove != null) {
                DropdownMenuItem(
                    text = { Text(removeLabel) },
                    leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null, tint = ZeneloColors.Danger) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
            // A server track's tags go into the server's file when it allows that (else its cover search).
            if (local || rememberServerTagEdit(path)) {
                DropdownMenuItem(
                    text = { Text("Edit tags…") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = ZeneloColors.Celadon) },
                    onClick = {
                        menu = false
                        onEditTags()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Find cover…") },
                    leadingIcon = { Icon(Icons.Outlined.Image, null, tint = ZeneloColors.Celadon) },
                    onClick = {
                        menu = false
                        onFindCover()
                    },
                )
            }
            if (path != null) SonicPathItems(path) { menu = false }
        }
    }
}

/**
 * "Play similar" and "Sonic path" (the track becomes the path's start, or its end once there's a
 * start, and the screen opens). Logged in to mStream and online only.
 */
@Composable
fun SonicPathItems(path: String, icons: Boolean = true, onDone: () -> Unit) {
    val sonicPath = appContainer().sonicPath
    val available by sonicPath.available.collectAsStateWithLifecycle()
    // Tracks the server has (local ones through their server copy): the path runs through its library.
    if (!available || !rememberOnServer(path)) return
    val player = appContainer().player
    DropdownMenuItem(
        text = { Text("Play similar") },
        leadingIcon = if (icons) ({ Icon(Icons.Outlined.Radar, null, tint = ZeneloColors.Celadon) }) else null,
        onClick = {
            onDone()
            sonicPath.playSimilar(path) { player.playFiles(it, startIndex = 0, shuffle = false) }
        },
    )
    DropdownMenuItem(
        text = { Text(SwipeAction.SONIC_PATH.label) },
        leadingIcon = if (icons) ({ Icon(SwipeAction.SONIC_PATH.icon, null, tint = SwipeAction.SONIC_PATH.accent) }) else null,
        onClick = {
            onDone()
            sonicPath.add(path)
        },
    )
}

/**
 * Replaces the top bar while rows are selected: count, select all, and actions for all of them
 * (play next, queue, favorite; ⋮: play, shuffle, and delete when [canDelete]).
 */
@Composable
fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    canDelete: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onPlay: (shuffle: Boolean) -> Unit,
    onAction: (SwipeAction) -> Unit,
    /** An extra ⋮ item for the page, e.g. "Remove from playlist". */
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
    /** The heart button; off where everything is a favorite already. */
    favorite: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Close, "Cancel selection", Modifier.size(20.dp)) }
        Text("$count", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onClick = onSelectAll, modifier = Modifier.size(40.dp)) {
            SelectAllIcon(allSelected)
        }
        for (action in listOfNotNull(SwipeAction.PLAY_NEXT, SwipeAction.ADD_TO_QUEUE, SwipeAction.FAVORITE.takeIf { favorite })) {
            IconButton(onClick = { onAction(action) }, enabled = count > 0, modifier = Modifier.size(40.dp)) {
                Icon(action.icon, action.label, tint = action.accent, modifier = Modifier.size(22.dp))
            }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = count > 0, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.MoreVert, "More", tint = ZeneloColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                IconMenuItem("Play", Icons.Rounded.PlayArrow, ZeneloColors.Mustard) { menu = false; onPlay(false) }
                IconMenuItem("Shuffle", Icons.Rounded.Shuffle, ZeneloColors.Celadon) { menu = false; onPlay(true) }
                val playlist = SwipeAction.ADD_TO_PLAYLIST
                IconMenuItem("Add to playlist…", playlist.icon, playlist.accent) { menu = false; onAction(playlist) }
                // Server tracks among them (local ones are skipped): only while the server is reachable.
                val server by appContainer().sonicPath.available.collectAsStateWithLifecycle()
                if (server) {
                    val download = SwipeAction.DOWNLOAD
                    IconMenuItem(download.label, download.icon, download.accent) { menu = false; onAction(download) }
                }
                val hide = SwipeAction.HIDE
                IconMenuItem(hide.label, hide.icon, hide.accent) { menu = false; onAction(hide) }
                if (onRemove != null) {
                    IconMenuItem(removeLabel, Icons.Rounded.RemoveCircleOutline, ZeneloColors.Danger) { menu = false; onRemove() }
                }
                // Folders can't be deleted from here.
                if (canDelete) {
                    val delete = SwipeAction.DELETE_FILE
                    IconMenuItem(delete.label, delete.icon, delete.accent) { menu = false; onAction(delete) }
                }
            }
        }
    }
}

/** A row's icon / thumbnail, with the selection mark in front of it when the mark sits on the left. */
@Composable
fun SelectableLeading(showMark: Boolean, selected: Boolean, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showMark) SelectionMark(selected, Modifier.padding(end = 12.dp))
        content()
    }
}

@Composable
fun IconMenuItem(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null, tint = tint) }, onClick = onClick)
}
