package app.zenelo.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
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
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch

/**
 * "Add to playlist": pick one (or create one) for [paths]; they're appended at its end.
 * [onDone] gets the confirmation to show.
 */
@Composable
fun PlaylistPickerDialog(paths: List<String>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val dao = appContainer().db.playlists()
    val flow = remember { dao.observeWithCounts() }
    val playlists by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val what = if (paths.size == 1) "1 track" else "${paths.size} tracks"

    fun add(id: Long, playlistName: String) {
        scope.launch {
            dao.appendAll(id, paths)
            onDone("$what added to $playlistName")
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZeneloColors.Card,
        title = { Text("Add $what to…") },
        text = {
            Column {
                if (creating) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Playlist name") })
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(Icons.Rounded.Add, ZeneloColors.Mustard, ZeneloColors.MustardTint)
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist", style = MaterialTheme.typography.bodyLarge, color = ZeneloColors.Mustard)
                    }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        // Server playlists are read-only copies: not offered.
                        items(playlists.filterNot { it.remote }, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { add(p.id, p.name) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconTile(Icons.AutoMirrored.Rounded.QueueMusic, ZeneloColors.Mustard, ZeneloColors.MustardTint)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Text("${p.trackCount} track${if (p.trackCount == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = ZeneloColors.TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val trimmed = name.trim()
                        scope.launch {
                            val id = dao.insert(PlaylistEntity(name = trimmed))
                            dao.appendAll(id, paths)
                            onDone("$what added to $trimmed")
                        }
                        onDismiss()
                    },
                ) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = { if (creating) creating = false else onDismiss() }) { Text(if (creating) "Back" else "Cancel") } },
    )
}
