package app.zenelo.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.settings.HomeItem
import app.zenelo.data.settings.HomeMode
import app.zenelo.data.settings.Navigation
import app.zenelo.data.settings.Section
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.ui.components.CaptionStyle
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.icon
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn

private const val UNREACHABLE = "Settings has to stay reachable: keep it in the bottom bar, or on Home with Home in the bar."

@Composable
private fun rememberNavSettings(): ZeneloSettings? {
    val repo = appContainer().settings
    val flow = remember { repo.settings }
    val settings by flow.collectAsStateWithLifecycle(initialValue = null)
    return settings
}

/** Header of the customize screens: back, title, Reset. */
@Composable
private fun CustomizeHeader(title: String, onBack: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(20.dp)) }
        Text(
            title,
            style = TextStyle(fontFamily = PlexSans, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.17).sp),
            modifier = Modifier.weight(1f),
        )
        Text(
            "Reset",
            style = TextStyle(fontFamily = PlexSans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ZeneloColors.Mustard),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onReset).padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}

/**
 * The screen's hint; a refused change takes its place for a few seconds (in place, so the rows
 * below don't move under the finger).
 */
@Composable
private fun Hint(text: String, warning: String?, onWarningDone: () -> Unit) {
    if (warning != null) {
        LaunchedEffect(warning) {
            delay(3500)
            onWarningDone()
        }
    }
    Text(
        warning ?: text,
        style = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, lineHeight = 17.sp, color = if (warning != null) ZeneloColors.Mustard else ZeneloColors.TextMuted),
        minLines = 3,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * Customize Home: each section Off, an Icon (shortcut) or a Grid / List card; drag to reorder.
 * Settings has no content, so it can only be a shortcut.
 */
@Composable
fun CustomizeHomeScreen(onBack: () -> Unit) {
    val repo = appContainer().settings
    val settings = rememberNavSettings() ?: return
    val scope = rememberCoroutineScope()
    var warning by remember { mutableStateOf<String?>(null) }
    // Local copy for dragging; follows the stored order otherwise.
    var items by remember { mutableStateOf(settings.home) }
    LaunchedEffect(settings.home) { items = settings.home }
    val tabs by rememberUpdatedState(settings.tabs)
    BackHandler(onBack = onBack)

    fun save(next: List<HomeItem>) {
        items = next
        scope.launch { if (!repo.setNavigation(tabs, next)) { items = settings.home; warning = UNREACHABLE } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CustomizeHeader("Customize home", onBack) { save(Navigation.DEFAULT_HOME) }
        Hint("Icon adds a shortcut to the top row. Grid and List add a card. Drag to reorder.", warning) { warning = null }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text("SECTION", style = CaptionStyle.copy(letterSpacing = 1.14.sp), modifier = Modifier.weight(1f))
            Text("SHOW AS", style = CaptionStyle.copy(letterSpacing = 1.14.sp))
        }
        ReorderableColumn(
            list = items,
            onSettle = { from, to -> save(items.toMutableList().apply { add(to, removeAt(from)) }) },
            modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(ZeneloColors.Bar),
        ) { _, item, dragging ->
            androidx.compose.runtime.key(item.section) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (dragging) ZeneloColors.Card else ZeneloColors.Bar)
                        .height(48.dp)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragIndicator,
                        "Reorder",
                        tint = ZeneloColors.TextMuted,
                        modifier = Modifier.draggableHandle().padding(horizontal = 10.dp).size(18.dp),
                    )
                    Text(item.section.label, style = TextStyle(fontFamily = PlexSans, fontSize = 13.5.sp, color = ZeneloColors.TextPrimary), modifier = Modifier.weight(1f))
                    ModeControl(item) { mode -> save(items.map { if (it.section == item.section) it.copy(mode = mode) else it }) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Off / Icon / Grid / List. */
@Composable
private fun ModeControl(item: HomeItem, onSelect: (HomeMode) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(ZeneloColors.Inset).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeMode.entries.forEach { mode ->
            val enabled = item.section.hasCard || mode == HomeMode.OFF || mode == HomeMode.ICON
            val selected = item.mode == mode
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) ZeneloColors.CeladonTint else ZeneloColors.Inset)
                    .clickable(enabled = enabled && !selected) { onSelect(mode) }
                    .alpha(if (enabled) 1f else 0.35f)
                    .padding(horizontal = 7.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mode.label,
                    style = TextStyle(
                        fontFamily = PlexSans,
                        fontSize = 11.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) ZeneloColors.Celadon else ZeneloColors.TextSecondary,
                    ),
                )
            }
        }
    }
}

/** Bottom bar: up to five tabs, in order; none hides the bar and leaves Home as the only page. */
@Composable
fun CustomizeTabsScreen(onBack: () -> Unit) {
    val repo = appContainer().settings
    val settings = rememberNavSettings() ?: return
    val scope = rememberCoroutineScope()
    var warning by remember { mutableStateOf<String?>(null) }
    fun layout(tabs: List<Section>) = tabs + Section.entries.filter { it !in tabs }
    var rows by remember { mutableStateOf(layout(settings.tabs)) }
    var enabled by remember { mutableStateOf(settings.tabs.toSet()) }
    LaunchedEffect(settings.tabs) {
        rows = layout(settings.tabs)
        enabled = settings.tabs.toSet()
    }
    val home by rememberUpdatedState(settings.home)
    BackHandler(onBack = onBack)

    fun save(order: List<Section>, on: Set<Section>) {
        val tabs = order.filter { it in on }
        rows = order
        enabled = on
        scope.launch {
            if (!repo.setNavigation(tabs, home)) {
                rows = layout(settings.tabs)
                enabled = settings.tabs.toSet()
                warning = UNREACHABLE
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CustomizeHeader("Bottom bar", onBack) {
            save(layout(Navigation.DEFAULT_TABS), Navigation.DEFAULT_TABS.toSet())
        }
        Hint("Up to ${Navigation.MAX_TABS} tabs; drag to reorder. With none, the bar hides and Home is the only page. The app opens on the tab you used last.", warning) { warning = null }
        Spacer(Modifier.height(12.dp))
        ReorderableColumn(
            list = rows,
            onSettle = { from, to -> save(rows.toMutableList().apply { add(to, removeAt(from)) }, enabled) },
            modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(ZeneloColors.Bar),
        ) { _, section, dragging ->
            androidx.compose.runtime.key(section) {
                val on = section in enabled
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (dragging) ZeneloColors.Card else ZeneloColors.Bar)
                        .height(52.dp)
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragIndicator,
                        "Reorder",
                        tint = ZeneloColors.TextMuted,
                        modifier = Modifier.draggableHandle().padding(horizontal = 10.dp).size(18.dp),
                    )
                    Icon(section.icon, null, tint = if (on) ZeneloColors.Celadon else ZeneloColors.TextMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(section.label, style = TextStyle(fontFamily = PlexSans, fontSize = 13.5.sp, color = ZeneloColors.TextPrimary), modifier = Modifier.weight(1f))
                    Switch(
                        checked = on,
                        onCheckedChange = { checked ->
                            when {
                                checked && enabled.size >= Navigation.MAX_TABS -> warning = "At most ${Navigation.MAX_TABS} tabs: turn one off first."
                                else -> save(rows, if (checked) enabled + section else enabled - section)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ZeneloColors.OnMustard,
                            checkedTrackColor = ZeneloColors.Mustard,
                            uncheckedThumbColor = ZeneloColors.TextMuted,
                            uncheckedTrackColor = ZeneloColors.Card,
                            uncheckedBorderColor = ZeneloColors.Card,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
