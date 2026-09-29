package app.zenelo.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import app.zenelo.data.BackupData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import app.zenelo.data.db.LibraryStats
import app.zenelo.work.LibraryWork
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.settings.NormalizationMode
import app.zenelo.data.settings.HomeMode
import app.zenelo.data.settings.DEFAULT_FILENAME_PATTERNS
import app.zenelo.data.settings.QueueSwipeAction
import app.zenelo.data.settings.QueueSwipeSlot
import app.zenelo.data.settings.PullDownArea
import app.zenelo.data.settings.SelectionMarkerSide
import app.zenelo.data.settings.SwipeAction
import app.zenelo.library.FilenamePattern
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.TextFields
import app.zenelo.data.settings.SwipeSlot
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.player.ShuffleMode
import app.zenelo.ui.components.IconTile
import app.zenelo.ui.components.BackButton
import app.zenelo.ui.components.ListRow
import app.zenelo.ui.components.ScreenTitle
import app.zenelo.ui.components.SectionHeader
import app.zenelo.ui.components.ZeneloSlider
import app.zenelo.ui.components.accent
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.icon
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
private fun rememberSettings(): ZeneloSettings {
    val repo = appContainer().settings
    val flow = remember { repo.settings }
    val settings by flow.collectAsStateWithLifecycle(initialValue = ZeneloSettings())
    return settings
}

@Composable
fun SettingsScreen(
    onOpenSwipeSettings: () -> Unit,
    onCustomizeHome: () -> Unit,
    onCustomizeTabs: () -> Unit,
    onMessage: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val repo = appContainer().settings
    val settings = rememberSettings()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackButton(onBack)
            ScreenTitle("Settings", Modifier.padding(start = if (onBack != null) 0.dp else 20.dp, top = 12.dp, bottom = 4.dp))
        }

        SectionHeader("Navigation")
        ListRow(
            title = "Bottom bar",
            subtitle = settings.tabs.joinToString(" · ") { it.label }.ifEmpty { "Hidden · Home only" },
            onClick = onCustomizeTabs,
            leading = { IconTile(Icons.Outlined.Tab, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
            trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = ZeneloColors.TextMuted, modifier = Modifier.padding(12.dp).size(20.dp)) },
        )
        ListRow(
            title = "Home screen",
            subtitle = settings.home.let { h ->
                val icons = h.count { it.mode == HomeMode.ICON }
                val cards = h.count { it.mode == HomeMode.GRID || it.mode == HomeMode.LIST }
                "$icons shortcut${if (icons == 1) "" else "s"} · $cards card${if (cards == 1) "" else "s"}"
            },
            onClick = onCustomizeHome,
            leading = { IconTile(Icons.Outlined.Home, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
            trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = ZeneloColors.TextMuted, modifier = Modifier.padding(12.dp).size(20.dp)) },
        )

        SectionHeader("Lists")
        ListRow(
            title = "List swipe actions",
            subtitle = "${settings.swipes.getValue(SwipeSlot.RIGHT_SHORT).label} · ${settings.swipes.getValue(SwipeSlot.LEFT_SHORT).label}",
            onClick = onOpenSwipeSettings,
            leading = { IconTile(Icons.Outlined.Swipe, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
            trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = ZeneloColors.TextMuted, modifier = Modifier.padding(12.dp).size(20.dp)) },
        )

        SwitchRow(
            title = "Show played tracks in queue",
            subtitle = "Above the current track, dimmed",
            checked = settings.queueHistory,
        ) { scope.launch { repo.setQueueHistory(it) } }
        ChoiceRow(
            title = "Swipe down to close player",
            current = settings.pullDownArea,
            choices = PullDownArea.entries,
            label = { it.label },
        ) { scope.launch { repo.setPullDownArea(it) } }
        ChoiceRow(
            title = "Selection mark",
            current = settings.selectionMarker,
            choices = SelectionMarkerSide.entries,
            label = { it.label },
        ) { scope.launch { repo.setSelectionMarker(it) } }

        SectionHeader("Shuffle")
        ShuffleMode.entries.forEach { mode ->
            RadioRow(mode.label, mode.description, selected = settings.shuffleMode == mode) {
                scope.launch { repo.setShuffleMode(mode) }
            }
        }

        SectionHeader("Volume normalization")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NormalizationMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.normalization == mode,
                    onClick = { scope.launch { repo.setNormalization(mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ZeneloColors.MustardTint,
                        selectedLabelColor = ZeneloColors.Mustard,
                    ),
                )
            }
        }

        if (settings.normalization != NormalizationMode.OFF) {
            // Local value while dragging (0.5 dB steps); persisted once on release.
            var preamp by remember(settings.preampDb) { mutableFloatStateOf(settings.preampDb) }
            val shown = (preamp * 2).roundToInt() / 2f
            Text(
                "Pre-amp · ${if (shown > 0) "+" else ""}${"%.1f".format(shown)} dB",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
            Text(
                "Louder or quieter than the −18 LUFS target. Never pushed past a track's peak.",
                style = MaterialTheme.typography.bodySmall,
                color = ZeneloColors.TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            ZeneloSlider(
                value = preamp,
                onValueChange = { preamp = it },
                onValueChangeFinished = { scope.launch { repo.setPreampDb(shown) } },
                valueRange = -6f..6f,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        // Local value while dragging; persisted once on release.
        var crossfade by remember(settings.crossfadeMs) { mutableFloatStateOf(settings.crossfadeMs / 1000f) }
        SectionHeader("Crossfade · ${if (crossfade.roundToInt() == 0) "off (gapless)" else "${crossfade.roundToInt()} s"}")
        ZeneloSlider(
            value = crossfade,
            onValueChange = { crossfade = it },
            onValueChangeFinished = { scope.launch { repo.setCrossfadeMs(crossfade.roundToInt() * 1000) } },
            valueRange = 0f..12f,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        SectionHeader("Covers & lyrics")
        SwitchRow(
            title = "Download covers & lyrics",
            subtitle = "Wi-Fi only · Deezer, iTunes, MusicBrainz, LRCLIB",
            checked = settings.onlineFetch,
        ) { scope.launch { repo.setOnlineFetch(it) } }
        SwitchRow(
            title = "Write covers into files",
            subtitle = "Only files without a cover, or when you pick one",
            checked = settings.embedCovers,
        ) { scope.launch { repo.setEmbedCovers(it) } }
        var editingPatterns by remember { mutableStateOf(false) }
        ListRow(
            title = "File name patterns",
            subtitle = "When tags are missing or wrong · ${settings.filenamePatterns.size} patterns",
            onClick = { editingPatterns = true },
            leading = { IconTile(Icons.Outlined.TextFields, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
        )
        if (editingPatterns) {
            PatternsDialog(settings.filenamePatterns, onDismiss = { editingPatterns = false }) { patterns ->
                scope.launch { repo.setFilenamePatterns(patterns) }
                editingPatterns = false
            }
        }
        var editingKey by remember { mutableStateOf(false) }
        ListRow(
            title = "Last.fm API key",
            subtitle = settings.lastFmApiKey?.let { "Set · ••••${it.takeLast(4)}" } ?: "Optional · not set, key-less sources only",
            onClick = { editingKey = true },
            leading = { IconTile(Icons.Outlined.Key, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
        )
        if (editingKey) {
            LastFmKeyDialog(settings.lastFmApiKey, onDismiss = { editingKey = false }) { key ->
                scope.launch { repo.setLastFmApiKey(key) }
                editingKey = false
            }
        }

        SectionHeader("Library")
        LibraryStatus()
        var editingArtists by remember { mutableStateOf(false) }
        ListRow(
            title = "Multiple artists",
            subtitle = settings.artistSeparators.joinToString("  ").ifEmpty { "Off · tags aren't split" }.let {
                if (settings.artistSeparators.isEmpty()) it else "Split on  $it"
            },
            onClick = { editingArtists = true },
            leading = { IconTile(Icons.Outlined.Person, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
        )
        if (editingArtists) {
            ArtistSplitDialog(settings.artistSeparators, settings.artistExceptions, onDismiss = { editingArtists = false }) { seps, keep ->
                scope.launch { repo.setArtistSplitting(seps, keep) }
                editingArtists = false
            }
        }
        ListRow(
            title = "Home folder",
            subtitle = settings.homeFolder ?: "Long-press the home button in Folders",
            leading = { IconTile(Icons.Outlined.Home, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
        )

        SectionHeader("Backup")
        BackupRows(onMessage)
    }
}

/** "Back up" writes one file (settings, favorites, playlists, play history); "Restore" reads one back after a confirmation. */
@Composable
private fun BackupRows(onMessage: (String) -> Unit) {
    val backup = appContainer().backup
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<BackupData?>(null) }
    val writer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = runCatching { backup.write(uri) }.getOrNull()
            onMessage(if (data == null) "Couldn't write the backup" else "Backed up · ${backupSummary(data)}")
        }
    }
    val reader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { backup.read(uri) }
                .onSuccess { pending = it }
                .onFailure { onMessage(it.message?.takeIf { _ -> it is IllegalArgumentException } ?: "Couldn't read the backup") }
        }
    }
    ListRow(
        title = "Back up",
        subtitle = "Settings, favorites, playlists, play history · one file",
        onClick = { writer.launch("zenelo-backup-${LocalDate.now()}.json") },
        leading = { IconTile(Icons.Outlined.Save, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
    )
    ListRow(
        title = "Restore",
        subtitle = "From a backup file · replaces all of the above",
        onClick = { reader.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
        leading = { IconTile(Icons.Outlined.Restore, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
    )
    pending?.let { data ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = ZeneloColors.Card,
            title = { Text("Restore backup?") },
            text = {
                val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .format(Instant.ofEpochMilli(data.createdAt).atZone(ZoneId.systemDefault()))
                Text("From $date\n${backupSummary(data)}\n\nCurrent settings, favorites, playlists and play history will be replaced.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val ok = runCatching { backup.restore(data) }.isSuccess
                        onMessage(if (ok) "Restored · ${backupSummary(data)}" else "Couldn't restore the backup")
                    }
                }) { Text("Restore", color = ZeneloColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

private fun backupSummary(data: BackupData): String {
    fun n(count: Int, what: String) = "$count $what${if (count == 1) "" else "s"}"
    return listOf(n(data.favorites.size, "favorite"), n(data.playlists.size, "playlist"), n(data.plays.size, "play")).joinToString(" · ")
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted)
        }
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = ZeneloColors.Mustard, unselectedColor = ZeneloColors.TextMuted),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = zeneloSwitchColors())
    }
}

/** Title + current value; tapping opens the choices. */
@Composable
private fun <T> ChoiceRow(title: String, current: T, choices: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(label(current), style = MaterialTheme.typography.bodySmall, color = ZeneloColors.Mustard)
            }
            Icon(Icons.Rounded.UnfoldMore, null, tint = ZeneloColors.TextMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(label(choice), color = if (choice == current) ZeneloColors.Mustard else ZeneloColors.TextPrimary) },
                    onClick = {
                        open = false
                        onSelect(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun zeneloSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = ZeneloColors.OnMustard,
    checkedTrackColor = ZeneloColors.Mustard,
    uncheckedThumbColor = ZeneloColors.TextMuted,
    uncheckedTrackColor = ZeneloColors.Card,
    uncheckedBorderColor = ZeneloColors.Card,
)

@Composable
private fun LastFmKeyDialog(current: String?, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var key by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZeneloColors.Card,
        title = { Text("Last.fm API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Adds Last.fm as an extra cover source. Get a key at last.fm/api/account/create.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZeneloColors.TextSecondary,
                )
                OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, label = { Text("API key") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = { onSave(null) }, enabled = current != null) { Text("Remove", color = ZeneloColors.Danger) }
        },
    )
}

/** "1 234 tracks · 1 100 with covers" and a manual rescan. */
@Composable
private fun LibraryStatus() {
    val context = LocalContext.current
    val container = appContainer()
    val statsFlow = remember { container.db.tracks().observeStats() }
    val stats by statsFlow.collectAsStateWithLifecycle(initialValue = LibraryStats(0, 0))
    val workFlow = remember { LibraryWork.observeRunning(context) }
    val work by workFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val stage = LibraryWork.stageOf(work)
    val summary = "${stats.tracks} tracks · ${stats.withArtwork} with covers"

    ListRow(
        title = "Scan library now",
        subtitle = when (stage) {
            null -> summary
            is LibraryWork.Stage.Scanning -> if (stage.total > 0) "Reading tags · ${stage.done} / ${stage.total}" else "Looking for new files…"
            LibraryWork.Stage.Fetching -> "Downloading covers & lyrics…"
            LibraryWork.Stage.Measuring -> "Measuring loudness…"
        },
        onClick = { LibraryWork.scanNow(context) },
        leading = { IconTile(Icons.Outlined.Refresh, ZeneloColors.Mustard, ZeneloColors.MustardTint) },
        trailing = {
            if (stage != null) {
                CircularProgressIndicator(color = ZeneloColors.Mustard, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(18.dp))
            }
        },
    )
}

/** "List swipe actions": four slots, short and long swipe in each direction. */
@Composable
fun SwipeSettingsScreen(onBack: () -> Unit) {
    val repo = appContainer().settings
    val settings = rememberSettings()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(20.dp)) }
            Text("List swipe actions", style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SwipeSlot.entries.forEach { slot ->
                SwipeSlotCard(slot, settings.swipes.getValue(slot)) { action -> scope.launch { repo.setSwipe(slot, action) } }
            }
        }
        SectionHeader("Queue · left swipes only", Modifier.padding(top = 8.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            QueueSwipeSlot.entries.forEach { slot ->
                SlotCard(
                    label = slot.label,
                    directionIcon = slot.slot.directionIcon,
                    current = settings.queueSwipes.getValue(slot).let { SlotChoice(it.label, it.icon, it.accent) },
                    choices = QueueSwipeAction.entries.map { SlotChoice(it.label, it.icon, it.accent) },
                ) { index -> scope.launch { repo.setQueueSwipe(slot, QueueSwipeAction.entries[index]) } }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Confirm before removing", style = MaterialTheme.typography.bodyMedium)
                Text("Instead of an undo toast", style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted)
            }
            Switch(
                checked = settings.confirmRemove,
                onCheckedChange = { scope.launch { repo.setConfirmRemove(it) } },
                colors = zeneloSwitchColors(),
            )
        }
        Text(
            "Actions: add to queue, play next, favorite, add to playlist, remove from list, hide, delete file.",
            style = MaterialTheme.typography.bodySmall,
            color = ZeneloColors.TextMuted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

private val SwipeSlot.directionIcon: ImageVector
    get() = when (this) {
        SwipeSlot.RIGHT_SHORT -> Icons.AutoMirrored.Rounded.ArrowForward
        SwipeSlot.RIGHT_LONG -> Icons.Rounded.KeyboardDoubleArrowRight
        SwipeSlot.LEFT_SHORT -> Icons.AutoMirrored.Rounded.ArrowBack
        SwipeSlot.LEFT_LONG -> Icons.Rounded.KeyboardDoubleArrowLeft
    }

private data class SlotChoice(val label: String, val icon: ImageVector, val accent: Color)

@Composable
private fun SwipeSlotCard(slot: SwipeSlot, current: SwipeAction, onSelect: (SwipeAction) -> Unit) {
    SlotCard(
        label = slot.label,
        directionIcon = slot.directionIcon,
        current = SlotChoice(current.label, current.icon, current.accent),
        choices = SwipeAction.entries.map { SlotChoice(it.label, it.icon, it.accent) },
    ) { index -> onSelect(SwipeAction.entries[index]) }
}

@Composable
private fun SlotCard(
    label: String,
    directionIcon: ImageVector,
    current: SlotChoice,
    choices: List<SlotChoice>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ZeneloColors.Card)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(directionIcon, null, tint = ZeneloColors.TextMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(current.icon, current.accent, current.accent.copy(alpha = 0.14f), size = 28.dp, iconSize = 16.dp)
                Spacer(Modifier.width(12.dp))
                Text(current.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.UnfoldMore, null, tint = ZeneloColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEachIndexed { index, choice ->
                DropdownMenuItem(
                    text = { Text(choice.label, color = if (choice == current) ZeneloColors.Mustard else ZeneloColors.TextPrimary) },
                    leadingIcon = { Icon(choice.icon, null, tint = choice.accent) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}
