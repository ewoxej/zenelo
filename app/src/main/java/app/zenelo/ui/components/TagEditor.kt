package app.zenelo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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

/**
 * Tag editor for one file. With [fromFileName] the fields start filled from the first matching
 * file name pattern (settings), so "Tags from file name" is a reviewed edit, not a blind rewrite.
 * Changed fields are labelled in mustard. [onDone] gets a status message.
 */
@Composable
fun TagEditorDialog(path: String, fromFileName: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<EditableTags?>(null) }
    var tags by remember { mutableStateOf(EditableTags()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    fun applyGuess(base: EditableTags, guess: PathGuess) = base.copy(
        title = guess.title ?: base.title,
        artist = guess.artist ?: base.artist,
        album = guess.album ?: base.album,
        trackNumber = guess.number?.toString() ?: base.trackNumber,
    )

    suspend fun guess(): PathGuess? = FilenamePattern.guess(container.settings.settings.first().filenamePatterns, path)

    LaunchedEffect(path) {
        val current = container.tagWriter.read(path)
        if (current == null) {
            onDone("This file's tags can't be edited")
            onDismiss()
            return@LaunchedEffect
        }
        original = current
        tags = current
        if (fromFileName) {
            val g = guess()
            if (g == null) {
                onDone("No file name pattern matches this file")
                onDismiss()
                return@LaunchedEffect
            }
            tags = applyGuess(current, g)
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ZeneloColors.Bar)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (fromFileName) "Tags from file name" else "Edit tags", style = MaterialTheme.typography.titleSmall)
            Text(
                path.substringAfterLast('/'),
                style = MaterialTheme.typography.labelMedium,
                color = ZeneloColors.TextMuted,
                maxLines = 1,
                modifier = Modifier.marquee(),
            )
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ZeneloColors.Mustard, modifier = Modifier.size(28.dp))
                }
                return@Column
            }
            val before = original ?: EditableTags()
            Column(
                Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TagField("Title", tags.title, before.title) { tags = tags.copy(title = it) }
                TagField("Artist", tags.artist, before.artist) { tags = tags.copy(artist = it) }
                TagField("Album", tags.album, before.album) { tags = tags.copy(album = it) }
                TagField("Album artist", tags.albumArtist, before.albumArtist) { tags = tags.copy(albumArtist = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { TagField("Track #", tags.trackNumber, before.trackNumber, number = true) { tags = tags.copy(trackNumber = it) } }
                    Box(Modifier.weight(1f)) { TagField("Year", tags.year, before.year, number = true) { tags = tags.copy(year = it) } }
                }
                TagField("Genre", tags.genre, before.genre) { tags = tags.copy(genre = it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { guess()?.let { tags = applyGuess(tags, it) } ?: onDone("No file name pattern matches this file") } }) {
                    Text("From file name")
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    enabled = !saving && tags != before,
                    onClick = {
                        saving = true
                        scope.launch {
                            onDone(
                                when (container.tagWriter.write(path, tags)) {
                                    TagWriter.Result.SAVED -> "Tags saved"
                                    TagWriter.Result.FAILED -> "Couldn't write tags to this file"
                                },
                            )
                            onDismiss()
                        }
                    },
                ) { Text("Save") }
            }
        }
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
