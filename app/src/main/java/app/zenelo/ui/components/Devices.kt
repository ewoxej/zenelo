package app.zenelo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.PlaybackDevice
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.delay

private fun typeIcon(type: String): ImageVector = when (type) {
    "android" -> Icons.Outlined.Smartphone
    "web", "desktop" -> Icons.Outlined.Computer
    else -> Icons.Outlined.DevicesOther
}

/** The other device the player follows: one playing while this phone doesn't (null otherwise). */
@Composable
fun rememberActiveRemote(localPlaying: Boolean): PlaybackDevice? {
    val devices = appContainer().devices
    val list by devices.devices.collectAsStateWithLifecycle()
    return devices.activeRemote(list, localPlaying)
}

/**
 * The mini player while another device plays: its track, the orange play ("Play here": the sound
 * moves to this phone), and pause / next for that device. Opens Now Playing like the local one
 * ([modifier] carries the tap and the drag), which then shows that device too.
 */
@Composable
fun RemoteMiniPlayer(remote: PlaybackDevice, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val devices = appContainer().devices
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(remote) { while (true) { now = remote.positionNow(); delay(500) } }
    val duration = remote.track?.durationMs ?: 0L
    Column(modifier.fillMaxWidth().background(ZeneloColors.Bar)) {
        LinearProgressIndicator(
            progress = { if (duration > 0) now.toFloat() / duration else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = ZeneloColors.Mustard,
            trackColor = ZeneloColors.Bar,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(remote.track?.let { MStreamPaths.of(it.serverPath) }, Modifier.size(38.dp), shape = RoundedCornerShape(4.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(remote.track?.title ?: "", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(typeIcon(remote.type), null, tint = ZeneloColors.Mustard, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Playing on ${remote.name}",
                        style = TextStyle(fontFamily = PlexSans, fontSize = 11.5.sp, color = ZeneloColors.Mustard),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Play here: the sound moves to this phone.
            IconButton(onClick = { devices.playOnLater(devices.selfId, onMessage) }) {
                Icon(Icons.Rounded.PlayArrow, "Play here", tint = ZeneloColors.Mustard)
            }
            IconButton(onClick = { devices.command(remote.id, "playPause") }) {
                Icon(if (remote.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause")
            }
            IconButton(onClick = { devices.command(remote.id, "next") }) { Icon(Icons.Rounded.SkipNext, "Next") }
        }
        HorizontalDivider(color = ZeneloColors.Background, thickness = 1.dp)
    }
}

/**
 * The devices of the mStream account: this phone and the others with what they play. A tap moves
 * the playback there (the device playing so far pauses).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(localPlaying: Boolean, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val devices = appContainer().devices
    val list by devices.devices.collectAsStateWithLifecycle()
    val connected by devices.connected.collectAsStateWithLifecycle()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val remote = devices.controlledDevice.collectAsStateWithLifecycle().value
    val current = if (localPlaying) devices.selfId else remote?.id
    val others = list.filter { it.id != devices.selfId && it.online }.sortedWith(compareByDescending<PlaybackDevice> { it.playing }.thenBy { it.name })

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = ZeneloColors.Bar) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Devices", style = TextStyle(fontFamily = PlexSans, fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                if (!connected) Text("OFFLINE", style = CaptionStyle)
            }
            Spacer(Modifier.height(8.dp))
            DeviceRow(Icons.Outlined.Smartphone, "${devices.name} · this phone", if (localPlaying) "Playing" else "Ready", on = current == devices.selfId) {
                onDismiss()
                devices.playOnLater(devices.selfId, onMessage)
            }
            if (others.isEmpty()) {
                Text(
                    if (connected) "Open mStream on another device with this account — a browser or another phone — and it shows up here."
                    else "Connect to an mStream server (Settings → mStream) to see your other devices.",
                    style = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, lineHeight = 17.sp, color = ZeneloColors.TextMuted),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                Text("OTHER DEVICES", style = CaptionStyle, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                others.forEach { d ->
                    val status = when {
                        d.playing -> "Playing · ${d.track?.title.orEmpty()}"
                        d.track != null -> "Paused · ${d.track.title.orEmpty()}"
                        else -> "Idle"
                    }
                    DeviceRow(typeIcon(d.type), d.name, status, on = current == d.id) {
                        onDismiss()
                        devices.playOnLater(d.id, onMessage)
                    }
                }
            }
            Text(
                "Tap a device to move the playback there. A queue paused on one device is picked up on the next.",
                style = TextStyle(fontFamily = PlexSans, fontSize = 11.5.sp, lineHeight = 16.sp, color = ZeneloColors.TextMuted),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, status: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(
            if (on) Icons.Rounded.GraphicEq else icon,
            if (on) ZeneloColors.Mustard else ZeneloColors.TextSecondary,
            if (on) ZeneloColors.MustardTint else ZeneloColors.Card,
            size = 40.dp,
            iconSize = 20.dp,
            cornerRadius = 10.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = if (on) ZeneloColors.Mustard else ZeneloColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(status, style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, color = ZeneloColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
