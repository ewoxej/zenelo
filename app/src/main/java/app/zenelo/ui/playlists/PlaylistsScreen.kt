package app.zenelo.ui.playlists

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
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.ScreenTitle
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen() {
    val dao = appContainer().db.playlists()
    val flow = remember { dao.observeWithCounts() }
    val playlists by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle("Playlists", Modifier.weight(1f), count = playlists.size)
            IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New playlist", tint = ZeneloColors.Mustard) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(playlists, key = { it.id }) { playlist ->
                // TODO: playlist detail screen (play, reorder, remove, M3U import/export).
                ListRow(
                    title = playlist.name,
                    subtitle = "${playlist.trackCount} tracks",
                    leading = { IconTile(Icons.AutoMirrored.Rounded.QueueMusic, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
                )
            }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        "Create a playlist with +, then add tracks by swiping or from the ⋮ menu.",
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
