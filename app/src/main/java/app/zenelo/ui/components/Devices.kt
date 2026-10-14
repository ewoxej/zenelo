package app.zenelo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.mstream.PlaybackDevice
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors

private fun typeIcon(type: String): ImageVector = when (type) {
    "android" -> Icons.Outlined.Smartphone
    "web", "desktop" -> Icons.Outlined.Computer
    else -> Icons.Outlined.DevicesOther
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
    val others = list.filter { it.id != devices.selfId && it.online }.sortedWith(compareByDescending<PlaybackDevice> { it.playing }.thenBy { it.name })

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = ZeneloColors.Bar) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Devices", style = TextStyle(fontFamily = PlexSans, fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                if (!connected) Text("OFFLINE", style = CaptionStyle)
            }
            Spacer(Modifier.height(8.dp))
            DeviceRow(Icons.Outlined.Smartphone, "${devices.name} · this phone", if (localPlaying) "Playing" else "Ready", on = localPlaying) {
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
                    DeviceRow(typeIcon(d.type), d.name, status, on = d.playing) {
                        onDismiss()
                        devices.playOnLater(d.id, onMessage)
                    }
                }
            }
            Text(
                "Tap a device to move the playback there. The player follows the device the music is on, paused too: its controls and queue act there.",
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

/** The followed device's volume after a volume key (`MStreamDevices.adjustFollowedVolume`). */
@Composable
fun RemoteVolumeHud(modifier: Modifier = Modifier) {
    val (name, volume) = appContainer().devices.volumeHud.collectAsStateWithLifecycle().value ?: return
    Row(
        modifier
            .statusBarsPadding()
            .padding(top = 12.dp)
            .width(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ZeneloColors.Card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            when { volume == 0 -> Icons.AutoMirrored.Rounded.VolumeOff; volume < 50 -> Icons.AutoMirrored.Rounded.VolumeDown; else -> Icons.AutoMirrored.Rounded.VolumeUp },
            null,
            tint = ZeneloColors.Mustard,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(name, style = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, color = ZeneloColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { volume / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = ZeneloColors.Mustard,
                trackColor = ZeneloColors.Background,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
        Text("$volume", style = TextStyle(fontFamily = PlexMono, fontSize = 12.sp, color = ZeneloColors.TextMuted))
    }
}
