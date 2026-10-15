package app.zenelo.ui.components

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.mstream.GuestLink
import app.zenelo.mstream.MStreamAccount
import app.zenelo.mstream.QrMatrix
import app.zenelo.ui.theme.PlexMono
import app.zenelo.ui.theme.PlexSans
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val DURATIONS = listOf("1h" to "1 hour", "12h" to "12 hours", "7d" to "7 days", "never" to "Until revoked")

/**
 * Guest links of Play on (the fork's api/guest.js): a link — shared, or shown as a QR code — lets
 * someone without an account control one of the account's devices from their phone's browser (what
 * plays, the queue, search, queueing, skip, pause, volume). Made here for a device and a while;
 * revoked here too. The QR code comes from the server (`MStreamClient.qr`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestsSheet(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val container = appContainer()
    val devices = container.devices
    val accountFlow = remember { container.settings.settings.map { it.mstream } }
    val account by accountFlow.collectAsStateWithLifecycle(initialValue = null)
    val list by devices.devices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var reload by remember { mutableIntStateOf(0) }
    val links by produceState<List<GuestLink>?>(null, account, reload) {
        value = account?.let { a -> runCatching { container.mstream.guestLinks(a) }.getOrElse { emptyList() } }
    }
    // This phone first, then the others: online before offline.
    val choices = remember(list) {
        listOf(devices.selfId to "${devices.name} · this phone") +
            list.filter { it.id != devices.selfId }
                .sortedWith(compareByDescending<app.zenelo.mstream.PlaybackDevice> { it.online }.thenBy { it.name })
                .map { it.id to (if (it.online) it.name else "${it.name} · offline") }
    }
    var device by remember { mutableStateOf(devices.selfId) }
    var duration by remember { mutableStateOf("12h") }
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<GuestLink?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = ZeneloColors.Bar) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Text(
                "Guests",
                style = TextStyle(fontFamily = PlexSans, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                "A link for one of your devices: whoever opens it controls what plays there from their phone — no account, no password. Revoke it any time.",
                style = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, lineHeight = 17.sp, color = ZeneloColors.TextMuted),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            val a = account
            if (a == null) {
                Text("Connect to an mStream server (Settings → mStream) first.", style = HintStyle, modifier = Modifier.padding(20.dp))
                return@Column
            }

            Text("DEVICE", style = CaptionStyle, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
            choices.forEach { (id, name) ->
                Choice(name, selected = device == id) { device = id }
            }
            Text("FOR", style = CaptionStyle, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DURATIONS.forEach { (value, name) -> Chip(name, duration == value, Modifier.weight(1f)) { duration = value } }
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(80) },
                singleLine = true,
                label = { Text("Name (optional)") },
                placeholder = { Text("Party, Friday…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ZeneloColors.Mustard, cursorColor = ZeneloColors.Mustard),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (busy) ZeneloColors.Card else ZeneloColors.Mustard)
                    .clickable(enabled = !busy) {
                        busy = true
                        scope.launch {
                            runCatching { container.mstream.createGuestLink(a, device, duration, label.trim()) }
                                .onSuccess { id -> label = ""; shown = id; reload++ }
                                .onFailure { onMessage(it.message ?: "Couldn't make the link") }
                            busy = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.QrCode2, null, tint = if (busy) ZeneloColors.TextMuted else ZeneloColors.OnMustard, modifier = Modifier.size(18.dp))
                    Text(if (busy) "Making the link…" else "Make a link", style = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = if (busy) ZeneloColors.TextMuted else ZeneloColors.OnMustard)
                }
            }

            Text("LINKS", style = CaptionStyle, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
            when {
                links == null -> Text("Loading…", style = HintStyle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                links!!.isEmpty() -> Text("No guest links.", style = HintStyle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                else -> links!!.forEach { link ->
                    LinkRow(a, link, open = shown == link.id, onToggle = { shown = if (shown == link.id) null else link.id }, onRevoke = { revoking = link }, onMessage = onMessage)
                }
            }
        }
    }

    revoking?.let { link ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke this link?") },
            text = { Text("Whoever has it loses access at once.") },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    account?.let { a -> scope.launch { runCatching { container.mstream.revokeGuestLink(a, link.id) }.onFailure { onMessage(it.message ?: "Couldn't revoke") }; reload++ } }
                }) { Text("Revoke", color = ZeneloColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
}

private val HintStyle = TextStyle(fontFamily = PlexSans, fontSize = 13.sp, color = ZeneloColors.TextMuted)

@Composable
private fun Choice(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(if (selected) ZeneloColors.Mustard else ZeneloColors.Card),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(ZeneloColors.OnMustard)) }
        Text(name, style = TextStyle(fontFamily = PlexSans, fontSize = 14.sp), color = ZeneloColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Chip(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(9.dp)).background(if (selected) ZeneloColors.CeladonTint else ZeneloColors.Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = TextStyle(fontFamily = PlexSans, fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) ZeneloColors.Celadon else ZeneloColors.TextSecondary,
            maxLines = 1,
        )
    }
}

private fun expiry(at: Long?): String {
    if (at == null) return "Until revoked"
    val ms = at - System.currentTimeMillis()
    if (ms <= 0) return "Ended"
    val h = ms / 3_600_000.0
    return when {
        h >= 48 -> "${Math.round(h / 24)} days left"
        h >= 1 -> "${Math.round(h)} h left"
        else -> "${maxOf(1, ms / 60_000)} min left"
    }
}

@Composable
private fun LinkRow(account: MStreamAccount, link: GuestLink, open: Boolean, onToggle: () -> Unit, onRevoke: () -> Unit, onMessage: (String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val url = remember(link.token) { container.mstream.guestUrl(account, link.token) }
    Column(Modifier.fillMaxWidth().background(if (open) ZeneloColors.Inset else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(Icons.Outlined.QrCode2, ZeneloColors.Mustard, ZeneloColors.MustardTint, size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(link.label.ifBlank { link.deviceName }, style = TextStyle(fontFamily = PlexSans, fontSize = 14.sp, fontWeight = FontWeight.Medium), color = ZeneloColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (link.label.isNotBlank()) "${link.deviceName} · " else "") + expiry(link.expiresAt),
                    style = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, color = ZeneloColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share the guest link"))
            }) { Icon(Icons.Outlined.Share, "Share", tint = ZeneloColors.TextSecondary) }
            IconButton(onClick = { clipboard.setText(AnnotatedString(url)); onMessage("Link copied") }) {
                Icon(Icons.Outlined.ContentCopy, "Copy", tint = ZeneloColors.TextSecondary)
            }
            IconButton(onClick = onRevoke) { Icon(Icons.Outlined.DeleteOutline, "Revoke", tint = ZeneloColors.TextSecondary) }
        }
        if (open) {
            val qr by produceState<QrMatrix?>(null, url) { value = runCatching { container.mstream.qr(account, url) }.getOrNull() }
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val q = qr
                if (q == null) {
                    Text("Loading the code…", style = HintStyle, modifier = Modifier.padding(16.dp))
                } else {
                    QrCode(q)
                    Text(
                        "Scan with the guest's phone camera, or share the link.",
                        style = TextStyle(fontFamily = PlexSans, fontSize = 12.sp, color = ZeneloColors.TextMuted),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/** The modules on white, with the quiet zone scanners need. */
@Composable
private fun QrCode(q: QrMatrix) {
    Canvas(Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
        val quiet = 4
        val cell = size.width / (q.size + quiet * 2)
        for (y in 0 until q.size) {
            for (x in 0 until q.size) {
                if (q.dark(x, y)) {
                    drawRect(Color.Black, topLeft = Offset((x + quiet) * cell, (y + quiet) * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
