package app.zenelo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.zenelo.library.ArtistSplitter
import app.zenelo.ui.theme.ZeneloColors

/**
 * Multi-artist tags: the separators they're split on, the names never split, and a field to try
 * a tag against both.
 */
@Composable
fun ArtistSplitDialog(
    separators: List<String>,
    exceptions: List<String>,
    onDismiss: () -> Unit,
    onSave: (separators: List<String>, exceptions: List<String>) -> Unit,
) {
    var seps by remember { mutableStateOf(separators) }
    var keep by remember { mutableStateOf(exceptions) }
    var sample by remember { mutableStateOf("Artist A; Artist B / AC/DC, Artist C") }
    val split = remember(seps, keep, sample) { ArtistSplitter(seps, keep).split(sample) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ZeneloColors.Bar)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Multiple artists", style = MaterialTheme.typography.titleSmall)
            Text(
                "A tag like \"A; B\" lists the track under A and under B. Separators with letters (feat., x) only match whole words.",
                style = MaterialTheme.typography.bodySmall,
                color = ZeneloColors.TextSecondary,
            )

            Text("SEPARATORS", style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted)
            Bubbles(seps, onRemove = { seps = seps - it })
            AddField("feat.", "Add separator") { seps = (seps + it).distinct() }
            if (seps.isEmpty()) {
                Text("No separators: artist tags aren't split.", style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted)
            }

            Text("NEVER SPLIT", style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
            Bubbles(keep, onRemove = { keep = keep - it })
            AddField("Crosby, Stills & Nash", "Add name") { keep = (keep + it).distinct() }

            Text("TRY IT", style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted, modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(
                value = sample,
                onValueChange = { sample = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZeneloColors.Mustard, cursorColor = ZeneloColors.Mustard),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                split.joinToString("  ·  ").ifEmpty { "—" },
                style = MaterialTheme.typography.labelMedium,
                color = ZeneloColors.Celadon,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        seps = ArtistSplitter.DEFAULT_SEPARATORS
                        keep = ArtistSplitter.DEFAULT_EXCEPTIONS
                    },
                ) { Text("Defaults") }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onSave(seps, keep) }) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubbles(items: List<String>, onRemove: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(ZeneloColors.Card).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item, style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { onRemove(item) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, "Remove $item", tint = ZeneloColors.TextMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun AddField(placeholder: String, addLabel: String, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    fun add() {
        val text = input.trim()
        if (text.isEmpty()) return
        onAdd(text)
        input = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.labelMedium) },
            textStyle = MaterialTheme.typography.labelMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZeneloColors.Mustard, cursorColor = ZeneloColors.Mustard),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = ::add, enabled = input.isNotBlank()) {
            Icon(Icons.Rounded.Add, addLabel, tint = ZeneloColors.Mustard)
        }
    }
}
