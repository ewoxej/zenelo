package app.zenelo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Route
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
    onDownloadCover: () -> Unit,
    onEditTags: (fromFileName: Boolean) -> Unit,
    /** False for mStream tracks: the server's files aren't ours to rewrite or delete. */
    local: Boolean = true,
    /** The track, for "Sonic path from / to here" (while logged in to mStream). */
    path: String? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, "More", tint = ZeneloColors.TextMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // Server tracks can be downloaded, not deleted; local files the other way round.
            SwipeAction.entries.filter { it != SwipeAction.NONE && it != (if (local) SwipeAction.DOWNLOAD else SwipeAction.DELETE_FILE) }.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    leadingIcon = { Icon(action.icon, null, tint = action.accent) },
                    onClick = {
                        menu = false
                        onAction(action)
                    },
                )
            }
            if (local) {
                DropdownMenuItem(
                    text = { Text("Edit tags…") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = ZeneloColors.Celadon) },
                    onClick = {
                        menu = false
                        onEditTags(false)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Tags from file name…") },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null, tint = ZeneloColors.Celadon) },
                    onClick = {
                        menu = false
                        onEditTags(true)
                    },
                )
            }
            if (path != null) SonicPathItems(path) { menu = false }
            DropdownMenuItem(
                text = { Text("Download cover…") },
                leadingIcon = { Icon(Icons.Outlined.Image, null, tint = ZeneloColors.Celadon) },
                onClick = {
                    menu = false
                    onDownloadCover()
                },
            )
        }
    }
}

/** "Sonic path from / to here": sets an end of the Sonic Path and opens it (logged in to mStream only). */
@Composable
fun SonicPathItems(path: String, icons: Boolean = true, onDone: () -> Unit) {
    val sonicPath = appContainer().sonicPath
    val available by sonicPath.available.collectAsStateWithLifecycle()
    if (!available) return
    DropdownMenuItem(
        text = { Text("Sonic path from here") },
        leadingIcon = if (icons) ({ Icon(Icons.Outlined.Route, null, tint = ZeneloColors.Celadon) }) else null,
        onClick = {
            onDone()
            sonicPath.set(path, start = true)
        },
    )
    DropdownMenuItem(
        text = { Text("Sonic path to here") },
        leadingIcon = if (icons) ({ Icon(Icons.Outlined.Route, null, tint = ZeneloColors.Celadon) }) else null,
        onClick = {
            onDone()
            sonicPath.set(path, start = false)
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
        for (action in listOf(SwipeAction.PLAY_NEXT, SwipeAction.ADD_TO_QUEUE, SwipeAction.FAVORITE)) {
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
