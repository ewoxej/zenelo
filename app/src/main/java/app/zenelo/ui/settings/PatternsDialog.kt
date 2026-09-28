package app.zenelo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.settings.DEFAULT_FILENAME_PATTERNS
import app.zenelo.library.FilenamePattern
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.theme.ZeneloColors
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** A pattern bubble; [id] keeps list keys unique even for duplicate text. */
private data class PatternChip(val id: Int, val text: String)

/**
 * File name patterns as bubbles: type one and add it, remove with ×, drag to reorder (first match
 * wins). The preview shows what the patterns extract from the playing track's path.
 */
@Composable
fun PatternsDialog(current: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var nextId by remember { mutableIntStateOf(current.size) }
    val chipsState = remember { mutableStateOf(current.mapIndexed { i, t -> PatternChip(i, t) }) }
    var chips by chipsState
    var input by remember { mutableStateOf("") }
    val playing = appContainer().nowPlaying.collectAsStateWithLifecycle().value

    fun add() {
        val text = input.trim()
        if (text.isEmpty()) return
        chips = chips + PatternChip(nextId++, text)
        input = ""
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        chipsState.value = chipsState.value.toMutableList().apply { add(to.index, removeAt(from.index)) }
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
            Text("File name patterns", style = MaterialTheme.typography.titleSmall)
            Text(
                "For covers, lyrics and \"Tags from file name\" when tags don't help. First match wins — drag to reorder.\n" +
                    "%artist%  %album%  %title%  %number%  %any%   [ ] = optional   / = folder",
                style = MaterialTheme.typography.bodySmall,
                color = ZeneloColors.TextSecondary,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("[%number%. ]%artist% - %title%", style = MaterialTheme.typography.labelMedium) },
                    textStyle = MaterialTheme.typography.labelMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZeneloColors.Mustard, cursorColor = ZeneloColors.Mustard),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::add, enabled = input.isNotBlank()) {
                    Icon(Icons.Rounded.Add, "Add pattern", tint = ZeneloColors.Mustard)
                }
            }

            LazyColumn(Modifier.heightIn(max = 220.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(chips, key = { it.id }) { chip ->
                    ReorderableItem(reorderState, key = chip.id) { dragging ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (dragging) ZeneloColors.MustardTint else ZeneloColors.Card)
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.DragIndicator,
                                "Reorder",
                                tint = ZeneloColors.TextMuted,
                                modifier = Modifier.draggableHandle().padding(6.dp).size(18.dp),
                            )
                            Text(chip.text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { chips = chips.filter { it.id != chip.id } }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Close, "Remove pattern", tint = ZeneloColors.TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            if (chips.isEmpty()) {
                Text("No patterns: file names won't be used.", style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted)
            }

            if (playing != null) {
                val guess = FilenamePattern.guess(chips.map { it.text }, playing)
                val parts = guess?.let { g ->
                    listOfNotNull(
                        g.artist?.let { "artist: $it" },
                        g.album?.let { "album: $it" },
                        g.title?.let { "title: $it" },
                        g.number?.let { "#$it" },
                    ).joinToString(" · ")
                }
                Text(
                    "Playing: ${playing.substringAfterLast('/')}\n${parts ?: "no pattern matches"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (guess != null) ZeneloColors.Celadon else ZeneloColors.TextMuted,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        chips = DEFAULT_FILENAME_PATTERNS.mapIndexed { i, t -> PatternChip(nextId + i, t) }
                        nextId += DEFAULT_FILENAME_PATTERNS.size
                    },
                ) { Text("Defaults") }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onSave(chips.map { it.text }) }) { Text("Save") }
            }
        }
    }
}
