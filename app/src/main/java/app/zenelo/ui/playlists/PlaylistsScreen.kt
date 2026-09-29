package app.zenelo.ui.playlists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.PlaylistEntity
import app.zenelo.ui.components.BackButton
import app.zenelo.ui.components.CoverImage
import app.zenelo.ui.components.IconTile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.foundation.layout.size
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.ScreenTitle
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen(onOpenPlaylist: (Long) -> Unit, onMessage: (String) -> Unit, onBack: (() -> Unit)? = null) {
    val container = appContainer()
    val dao = container.db.playlists()
    val flow = remember { dao.observeWithCounts() }
    val playlists by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    // Any file: pickers often don't know the M3U types, and a wrong file just finds no tracks.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching { container.playlistFiles.import(uri) }.getOrNull()
            onMessage(
                when {
                    result == null -> "Couldn't read the file"
                    result.id == null -> "No tracks of \"${result.name}\" found in the library"
                    result.missing > 0 -> "Imported \"${result.name}\" · ${result.found} tracks · ${result.missing} not found"
                    else -> "Imported \"${result.name}\" · ${result.found} track${if (result.found == 1) "" else "s"}"
                },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = if (onBack != null) 4.dp else 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) BackButton(onBack)
            ScreenTitle("Playlists", Modifier.weight(1f), count = playlists.size)
            IconButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                Icon(Icons.Outlined.FileOpen, "Import M3U playlist", tint = ZeneloColors.TextSecondary)
            }
            IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New playlist", tint = ZeneloColors.Mustard) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(playlists, key = { it.id }) { playlist ->
                ListRow(
                    title = playlist.name,
                    subtitle = "${playlist.trackCount} track${if (playlist.trackCount == 1) "" else "s"}" + if (playlist.remote) " · mStream" else "",
                    onClick = { onOpenPlaylist(playlist.id) },
                    leading = {
                        if (playlist.coverPath != null) {
                            CoverImage(playlist.coverPath, Modifier.size(36.dp), placeholder = Icons.AutoMirrored.Rounded.QueueMusic)
                        } else {
                            IconTile(Icons.AutoMirrored.Rounded.QueueMusic, ZeneloColors.Mustard, ZeneloColors.MustardTint)
                        }
                    },
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = ZeneloColors.TextMuted, modifier = Modifier.padding(horizontal = 12.dp).size(20.dp)) },
                )
            }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        "Create a playlist with +, then add tracks by swiping or from the ⋮ menu. Or import an M3U / M3U8 file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ZeneloColors.TextMuted,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = ZeneloColors.Card,
            title = { Text("New playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch { dao.insert(PlaylistEntity(name = name.trim())) }
                        creating = false
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}
