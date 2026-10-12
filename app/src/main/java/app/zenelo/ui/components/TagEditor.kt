package app.zenelo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import app.zenelo.mstream.MStreamPaths
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.TrackEntity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.zenelo.library.FilenamePattern
import app.zenelo.library.PathGuess
import app.zenelo.library.TagReader.EditableTags
import app.zenelo.library.TagWriter
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A cover removal chosen in the editor, done when it closes. */
private enum class CoverRemoval { TRACK, ALBUM }

/**
 * The tag editor, full screen: the cover and the text tags of one file. Under the cover: "Fill
 * from file name" (the first matching pattern from settings, for review), "Find online", and
 * remove — from this track or the whole album. A removal shows at once and is done when the
 * editor closes (any way); a cover found online is applied right away and cancels it. Changed
 * fields are labelled in mustard; Save writes them. [onDone] gets the status message.
 */
@Composable
fun TagEditorDialog(path: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<EditableTags?>(null) }
    var tags by remember { mutableStateOf(EditableTags()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    // Messages shown in the editor (the snackbar sits under this full-screen dialog).
    var status by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<CoverRemoval?>(null) }
    val trackFlow = remember(path) { container.db.tracks().observe(path) }
    val track by trackFlow.collectAsStateWithLifecycle(initialValue = null)
    // The album's tracks wherever they are (CD1 / CD2 folders too) — on this side: a local file's
    // album is its local files, a server track's the server's (twins aren't counted twice). No
    // album tag: the untagged files of its folder.
    val albumTracks by produceState(emptyList<TrackEntity>(), track?.dir, track?.albumKey) {
        value = track?.let { t ->
            val remote = MStreamPaths.isRemote(t.path)
            val all = t.albumKey?.let { container.db.tracks().albumTracks(it) } ?: container.db.tracks().albumTracksInDir(t.dir, null)
            all.filter { MStreamPaths.isRemote(it.path) == remote }.ifEmpty { listOf(t) }
        }.orEmpty()
    }
    // Whether there's a cover to remove: what the app shows for it (embedded, folder image, download).
    val thumbsRevision by container.thumbnails.revision.collectAsStateWithLifecycle()
    val hasCover by produceState<Boolean?>(null, path, thumbsRevision) {
        value = container.thumbnails.load(path, large = true) != null
    }

    fun applyGuess(base: EditableTags, guess: PathGuess) = base.copy(
        title = guess.title ?: base.title,
        artist = guess.artist ?: base.artist,
        album = guess.album ?: base.album,
        trackNumber = guess.number?.toString() ?: base.trackNumber,
    )

    suspend fun guess(): PathGuess? = FilenamePattern.guess(container.settings.settings.first().filenamePatterns, path)

    /** Closes the editor; a chosen cover removal is done now (in the app's scope: this one ends here). */
    fun close(message: String? = null) {
        val chosen = removal
        val t = track
        onDismiss()
        if (chosen == null || t == null) {
            message?.let(onDone)
            return
        }
        val targets = if (chosen == CoverRemoval.ALBUM) albumTracks.ifEmpty { listOf(t) } else listOf(t)
        // Seen at once, the playing track's too (its file is written when it ends).
        targets.forEach { container.thumbnails.forget(it.path) }
        container.nowPlaying.value?.takeIf { p -> targets.any { it.path == p } }?.let { container.coverOverride.value = it to "" }
        container.appScope.launch {
            val r = container.metadata.removeCover(targets, wholeAlbum = chosen == CoverRemoval.ALBUM)
            val removed = when {
                r.failed > 0 -> "cover kept in ${r.failed} file${if (r.failed == 1) "" else "s"} (couldn't write)"
                chosen == CoverRemoval.ALBUM -> "cover removed from the album"
                else -> "cover removed"
            }
            onDone(listOfNotNull(message, removed).joinToString(" · ").replaceFirstChar { it.uppercase() })
        }
    }

    LaunchedEffect(path) {
        val current = container.tagWriter.read(path)
        if (current == null) {
            onDone("This file's tags can't be edited")
            onDismiss()
            return@LaunchedEffect
        }
        original = current
        tags = current
        loading = false
    }

    val before = original ?: EditableTags()
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(ZeneloColors.Background)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { close() }) { Icon(Icons.Rounded.Close, "Close", Modifier.size(20.dp)) }
                Text("Edit tags", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // Something to save: changed tags, or a cover removal (only that is fine too).
                val changed = !loading && (tags != before || removal != null)
                TextButton(
                    enabled = changed && !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            close(
                                when {
                                    tags == before -> null
                                    container.tagWriter.write(path, tags) == TagWriter.Result.SAVED -> "Tags saved"
                                    else -> "Couldn't write tags to this file"
                                },
                            )
                        }
                    },
                ) { Text("Save", color = if (changed) ZeneloColors.Mustard else ZeneloColors.TextMuted) }
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ZeneloColors.Mustard, modifier = Modifier.size(28.dp))
                }
                return@Column
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        val coverModifier = Modifier.size(132.dp)
                        if (removal != null) {
                            // Removed (done when the editor closes): shown as no cover already.
                            Box(coverModifier.clip(RoundedCornerShape(10.dp)).background(ZeneloColors.Placeholder), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Album, null, tint = ZeneloColors.TextMuted, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            CoverImage(path, coverModifier, shape = RoundedCornerShape(10.dp), large = true, placeholder = Icons.Outlined.Album)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            path.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelMedium,
                            color = ZeneloColors.TextMuted,
                            maxLines = 1,
                            modifier = Modifier.marquee().padding(bottom = 4.dp),
                        )
                        EditorAction("Find online", Icons.Rounded.Search, ZeneloColors.Celadon) { status = null; picking = true }
                        if (removal == null) {
                            // Nothing to remove without a cover.
                            if (hasCover == true) {
                                EditorAction("Remove cover", Icons.Outlined.HideImage, ZeneloColors.Danger) { status = null; removal = CoverRemoval.TRACK }
                            }
                            if (hasCover == true && albumTracks.size > 1) {
                                EditorAction("Remove from all ${albumTracks.size} album tracks", Icons.Outlined.HideImage, ZeneloColors.Danger) { status = null; removal = CoverRemoval.ALBUM }
                            }
                        } else {
                            Text(
                                if (removal == CoverRemoval.ALBUM) "Removed from all ${albumTracks.size} tracks of the album when you close" else "Removed when you close",
                                style = MaterialTheme.typography.bodySmall,
                                color = ZeneloColors.TextSecondary,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            EditorAction("Keep the cover", Icons.AutoMirrored.Rounded.Undo, ZeneloColors.Celadon) { removal = null }
                        }
                    }
                }
                // Full width under the cover: it fills the tags below, not the cover.
                OutlinedButton(
                    onClick = { scope.launch { guess()?.let { tags = applyGuess(tags, it); status = null } ?: run { status = "No file name pattern matches this file" } } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    border = BorderStroke(1.dp, ZeneloColors.Celadon.copy(alpha = 0.5f)),
                ) {
                    Icon(Icons.Outlined.DriveFileRenameOutline, null, tint = ZeneloColors.Celadon, modifier = Modifier.size(18.dp))
                    Text("Fill tags from file name", color = ZeneloColors.Celadon, modifier = Modifier.padding(start = 8.dp))
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextSecondary) }
                TagField("Title", tags.title, before.title) { tags = tags.copy(title = it) }
                TagField("Artist", tags.artist, before.artist) { tags = tags.copy(artist = it) }
                TagField("Album", tags.album, before.album) { tags = tags.copy(album = it) }
                TagField("Album artist", tags.albumArtist, before.albumArtist) { tags = tags.copy(albumArtist = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { TagField("Track #", tags.trackNumber, before.trackNumber, number = true) { tags = tags.copy(trackNumber = it) } }
                    Box(Modifier.weight(1f)) { TagField("Year", tags.year, before.year, number = true) { tags = tags.copy(year = it) } }
                }
                TagField("Genre", tags.genre, before.genre) { tags = tags.copy(genre = it) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // A cover picked online replaces any removal chosen here.
    if (picking) CoverPickerDialog(path, onDismiss = { picking = false }, onDone = { status = it; removal = null })
}

/** A compact text button of the editor's cover column. */
@Composable
private fun EditorAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun TagField(label: String, value: String, original: String, number: Boolean = false, onChange: (String) -> Unit) {
    val changed = value.trim() != original.trim()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label, color = if (changed) ZeneloColors.Mustard else ZeneloColors.TextMuted) },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ZeneloColors.Mustard,
            unfocusedBorderColor = if (changed) ZeneloColors.Mustard.copy(alpha = 0.5f) else ZeneloColors.Card,
            cursorColor = ZeneloColors.Mustard,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
