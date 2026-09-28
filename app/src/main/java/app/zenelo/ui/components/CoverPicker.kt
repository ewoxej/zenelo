package app.zenelo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.zenelo.data.db.TrackEntity
import app.zenelo.online.CoverCandidate
import app.zenelo.ui.theme.ZeneloColors
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Download cover": search all cover sources with editable terms, pick one, and it replaces the
 * cover of this track (or of the album's tracks in the same folder). [onDone] gets a status message.
 */
@Composable
fun CoverPickerDialog(path: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val container = appContainer()
    val fetcher = container.metadata
    val scope = rememberCoroutineScope()

    var track by remember { mutableStateOf<TrackEntity?>(null) }
    var albumTracks by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<CoverCandidate>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var wholeAlbum by remember { mutableStateOf(true) }

    fun search() {
        if (!container.http.onWifi()) {
            message = "Wi-Fi is off. Covers download over Wi-Fi only."
            busy = false
            return
        }
        busy = true
        message = null
        scope.launch {
            candidates = fetcher.searchCovers(artist.trim(), album.trim())
            if (candidates.isEmpty()) message = "Nothing found. Try other search terms."
            busy = false
        }
    }

    LaunchedEffect(path) {
        val t = container.db.tracks().get(path) ?: container.indexer.indexOne(path)
        track = t
        if (t != null) {
            albumTracks = container.db.tracks().albumTracksInDir(t.dir, t.albumKey).ifEmpty { listOf(t) }
            // Without tags: the file name patterns from settings, then the folder layout .../Artist/Album/.
            val dir = File(t.dir)
            val guess = fetcher.guess(t)
            artist = t.albumArtist ?: t.artist ?: guess?.artist ?: dir.parentFile?.name.orEmpty()
            album = t.album ?: guess?.album ?: dir.name
        }
        search()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ZeneloColors.Bar)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Download cover", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SearchTerm(artist, { artist = it }, "Artist", ::search)
                    SearchTerm(album, { album = it }, "Album", ::search)
                }
                IconButton(onClick = ::search, enabled = !busy) { Icon(Icons.Rounded.Search, "Search", tint = ZeneloColors.Mustard) }
            }

            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 230.dp), contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(color = ZeneloColors.Mustard, modifier = Modifier.size(28.dp))
                    message != null -> Text(message!!, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextSecondary)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(candidates, key = { it.thumbUrl }) { candidate ->
                            Candidate(candidate) {
                                val targets = if (wholeAlbum) albumTracks else listOfNotNull(track)
                                busy = true
                                scope.launch {
                                    val applied = fetcher.applyCover(targets, candidate)
                                    if (applied != null && container.nowPlaying.value == path) {
                                        container.coverOverride.value = path to applied.cover.absolutePath
                                    }
                                    onDone(
                                        when {
                                            applied == null -> "Couldn't download the cover"
                                            applied.files == 0 -> "Cover saved (writing into files is off or unsupported)"
                                            applied.files == 1 -> "Cover saved to 1 file"
                                            else -> "Cover saved to ${applied.files} files"
                                        },
                                    )
                                    onDismiss()
                                }
                            }
                        }
                    }
                }
            }

            if (albumTracks.size > 1) {
                Row(
                    Modifier.fillMaxWidth().clickable { wholeAlbum = !wholeAlbum },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = wholeAlbum,
                        onCheckedChange = { wholeAlbum = it },
                        colors = CheckboxDefaults.colors(checkedColor = ZeneloColors.Mustard, checkmarkColor = ZeneloColors.OnMustard),
                    )
                    Text("All ${albumTracks.size} tracks of this album in the folder", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}

@Composable
private fun SearchTerm(value: String, onChange: (String) -> Unit, label: String, onSearch: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Candidate(candidate: CoverCandidate, onPick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPick)) {
        AsyncImage(
            model = candidate.thumbUrl,
            contentDescription = candidate.album,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(ZeneloColors.Placeholder),
        )
        Text(
            candidate.album,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "${candidate.source} · ${candidate.artist}",
            style = MaterialTheme.typography.labelMedium,
            color = ZeneloColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
