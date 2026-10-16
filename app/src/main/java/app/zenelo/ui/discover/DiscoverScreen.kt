package app.zenelo.ui.discover

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zenelo.data.db.TrackEntity
import app.zenelo.library.AudioFile
import app.zenelo.library.artistKey
import app.zenelo.mstream.DiscoverJob
import app.zenelo.mstream.Lead
import app.zenelo.mstream.Leads
import app.zenelo.mstream.MStreamException
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.MStreamPeers
import app.zenelo.ui.components.BackButton
import app.zenelo.ui.components.ScreenTitle
import app.zenelo.ui.components.SectionHeader
import app.zenelo.ui.components.TrackThumb
import app.zenelo.ui.components.appContainer
import app.zenelo.ui.components.formatDuration
import app.zenelo.ui.components.rememberTopListState
import app.zenelo.ui.library.LibraryRow
import app.zenelo.ui.theme.ZeneloColors
import java.io.IOException
import kotlin.math.roundToInt

private enum class Source(val label: String) { LIBRARY("Library"), NETWORK("Network"), PEERS("Peers") }

/** What Discover found for the playing track. */
private data class Found(
    val loading: Boolean = true,
    /** Why there's nothing (not analyzed yet, discovery off, the network). */
    val problem: String? = null,
    /** Our paths (local copies where there are) and how close they sound. */
    val tracks: List<Pair<String, Double>> = emptyList(),
    /** Library artists that sound alike, with their closest tracks (our paths). */
    val artists: List<Pair<String, List<String>>> = emptyList(),
    val network: Leads? = null,
    val peers: Leads? = null,
)

/**
 * Discover (mStream): more like the playing track, matched by sound (the server's embeddings).
 * Library — similar tracks and artists of our server; Network — songs other servers published to the
 * discovery network (leads: a plug-in may get them); Peers — the paired servers' songs, played
 * through ours and copied into the library. A tab / Home section.
 */
@Composable
fun DiscoverScreen(onMessage: (String) -> Unit, onOpenArtist: (String) -> Unit, onBack: (() -> Unit)?) {
    val container = appContainer()
    val peers = container.peers
    val settings by remember { container.settings.settings }.collectAsStateWithLifecycle(initialValue = null)
    val online by container.network.online.collectAsStateWithLifecycle()
    val features by peers.features.collectAsStateWithLifecycle()
    val jobs by peers.jobs.collectAsStateWithLifecycle()
    val plugins by peers.plugins.collectAsStateWithLifecycle()
    val paired by peers.peers.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val links by container.serverLinks.links.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableStateOf(Source.LIBRARY) }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Lead?>(null) }

    // The server may have changed what it offers (peers paired, plug-ins switched on).
    LaunchedEffect(settings?.mstream, online) { if (settings?.mstream != null && online) peers.refresh() }

    // The track the finds are like: the playing one when the server has it. While a peer's song
    // plays (one started from here), the last of ours stays: the list doesn't go away under it.
    var anchor by rememberSaveable { mutableStateOf<String?>(null) }
    val playing = player.mediaId?.takeIf { links.serverOf(it) != null }
    LaunchedEffect(playing) { if (playing != null) anchor = playing }
    val mediaId = playing ?: anchor?.takeIf { MStreamPaths.isPeer(player.mediaId) }
    val seed = mediaId?.let(links::serverOf)
    val seedInfo by produceState<TrackEntity?>(null, mediaId) { value = mediaId?.let { container.db.tracks().get(it) } }

    // A finished copy makes its songs ours: they leave the leads.
    val copied = jobs.count { it.state == "done" }
    val found by produceState(Found(), seed, newOnly, features, settings?.mstream, copied, paired.isNotEmpty()) {
        // Asked again (another track, a finished copy): what's shown stays until the answer.
        value = value.copy(loading = true)
        val account = settings?.mstream
        if (seed == null || account == null || !features.discovery) { value = Found(loading = false); return@produceState }
        val serverPath = MStreamPaths.serverPath(seed)
        val twins = links.localCopies
        fun ours(fp: String) = MStreamPaths.of(fp).let { twins[it] ?: it }
        val result = try {
            val similar = container.mstream.similarScored(account, serverPath, 30)
            val artist = container.db.tracks().get(seed)?.artist
            Found(
                loading = false,
                tracks = similar.map { (song, sim) -> ours(song.filepath) to sim }.filter { it.first != mediaId },
                artists = artist?.let { a -> runCatching { container.mstream.soundAlike(account, a, 8) }.getOrDefault(emptyList()) }
                    .orEmpty().map { (name, entries) -> name to entries.map { ours(it.filepath) } },
            )
        } catch (e: MStreamException) {
            Found(loading = false, problem = when (e.code) {
                409 -> "This track hasn't been analyzed yet — similar tracks show once the server's scan reaches it."
                403 -> "Discover is off on this server: an admin can switch on sound analysis."
                404 -> "The server doesn't know this track's sound yet."
                else -> e.message
            })
        } catch (e: IOException) {
            Found(loading = false, problem = "Couldn't reach the server")
        }
        value = result.copy(loading = features.network || features.peerDiscovery)
        val network = if (features.network) runCatching { container.mstream.leads(account, serverPath, peers = false, limit = 25, newArtistsOnly = newOnly) }.getOrNull() else null
        value = value.copy(network = network)
        val peerLeads = if (features.peerDiscovery && paired.isNotEmpty()) runCatching { container.mstream.leads(account, serverPath, peers = true, limit = 25, newArtistsOnly = newOnly) }.getOrNull() else null
        peerLeads?.rows?.forEach { peers.remember(it) }
        value = value.copy(peers = peerLeads, loading = false)
    }
    val sources = buildList {
        add(Source.LIBRARY)
        if (features.network) add(Source.NETWORK)
        // Only with peers to ask: none paired (or none answering at all) is no tab.
        if (features.peerDiscovery && paired.isNotEmpty() && found.peers?.let { it.searched + it.unreachable == 0 } != true) add(Source.PEERS)
    }
    val shown = if (source in sources) source else Source.LIBRARY
    val trackInfo by produceState<Map<String, TrackEntity>>(emptyMap(), found.tracks) {
        value = container.db.tracks().getMany(found.tracks.map { it.first }).associateBy { it.path }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) BackButton(onBack)
            ScreenTitle("Discover", Modifier.weight(1f))
            if (found.loading && seed != null) CircularProgressIndicator(color = ZeneloColors.Mustard, strokeWidth = 2.dp, modifier = Modifier.padding(end = 12.dp).size(18.dp))
        }
        LazyColumn(Modifier.weight(1f), state = rememberTopListState()) {
            item {
                val problem = when {
                    settings?.mstream == null -> "Discover is an mStream feature: connect a server in Settings → mStream."
                    !online -> "Discover needs the network."
                    !features.discovery -> "Discover is off on this server: an admin can switch on sound analysis."
                    player.mediaId == null -> "Play a track: Discover finds more that sound like it."
                    mediaId == null && MStreamPaths.isPeer(player.mediaId) -> "A peer's track is playing — Discover works from tracks of your server."
                    seed == null -> "This track isn't on your mStream server: Discover works from the server's tracks."
                    else -> null
                }
                if (problem != null) {
                    Hint(problem)
                } else {
                    SeedRow(mediaId!!, seedInfo?.title ?: player.title.takeIf { mediaId == player.mediaId }, seedInfo?.artist ?: seedInfo?.albumArtist)
                }
                JobsStrip(jobs, onCancel = peers::cancel, onClear = peers::clearFinished)
                if (problem == null && sources.size > 1) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sources.forEach { s ->
                            val count = when (s) {
                                Source.LIBRARY -> found.tracks.size
                                Source.NETWORK -> found.network?.rows?.size
                                Source.PEERS -> found.peers?.rows?.size
                            }
                            Chip(s.label + (count?.let { " · $it" } ?: ""), s == shown, Modifier.weight(1f)) { source = s }
                        }
                    }
                }
            }
            if (seed == null || settings?.mstream == null || !online || !features.discovery) return@LazyColumn
            when (shown) {
                Source.LIBRARY -> {
                    found.problem?.let { item { Hint(it) } }
                    if (found.artists.isNotEmpty()) {
                        item {
                            SectionHeader("Similar artists")
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                found.artists.forEach { (name, entries) ->
                                    ArtistChip(name, onOpen = { onOpenArtist(artistKey(name)) }, onQueue = entries.takeIf { it.isNotEmpty() }?.let { e ->
                                        {
                                            val at = container.player.addToQueue(e.map(AudioFile::forPath))
                                            onMessage("Queued ${e.size} tracks of $name from #$at")
                                        }
                                    })
                                }
                            }
                        }
                    }
                    if (found.tracks.isNotEmpty()) {
                        item {
                            SectionHeader("Similar tracks · ${found.tracks.size}")
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { container.player.playFiles((listOf(mediaId!!) + found.tracks.map { it.first }).map(AudioFile::forPath), 0, shuffle = false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ZeneloColors.Mustard, contentColor = ZeneloColors.Background),
                                ) { Text("Play similar") }
                                OutlinedButton(onClick = {
                                    val at = container.player.addToQueue(found.tracks.map { AudioFile.forPath(it.first) })
                                    onMessage("Queued ${found.tracks.size} tracks from #$at")
                                }) { Text("Queue all") }
                            }
                        }
                        itemsIndexed(found.tracks, key = { _, t -> "t:" + t.first }) { index, (path, sim) ->
                            val t = trackInfo[path]
                            LibraryRow(
                                title = t?.title ?: path.substringAfterLast('/').substringBeforeLast('.'),
                                subtitle = listOfNotNull(t?.artist ?: t?.albumArtist, t?.album).joinToString(" · ").ifBlank { null },
                                onClick = { container.player.playFiles(found.tracks.map { AudioFile.forPath(it.first) }, index, shuffle = false) },
                                onLongClick = {},
                                coverPath = path,
                                trailingText = pct(sim),
                                isCurrent = player.mediaId == path,
                            )
                        }
                    } else if (!found.loading && found.problem == null) {
                        item { Hint("Nothing similar found yet. Tracks the server's scan hasn't reached don't show.") }
                    }
                }
                Source.NETWORK, Source.PEERS -> {
                    val net = shown == Source.NETWORK
                    val part = if (net) found.network else found.peers
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    part == null -> if (found.loading) "LOOKING…" else "COULDN'T ASK"
                                    net -> "SEARCHED ${part.searched} SERVER${if (part.searched == 1) "" else "S"}"
                                    else -> listOfNotNull("${part.searched} PEER${if (part.searched == 1) "" else "S"} ANSWERED", part.unreachable.takeIf { it > 0 }?.let { "$it UNREACHABLE" }).joinToString(" · ")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = ZeneloColors.TextMuted,
                                modifier = Modifier.weight(1f),
                            )
                            Text("New artists only", style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextSecondary)
                            Toggle(newOnly, Modifier.padding(start = 10.dp)) { newOnly = it }
                        }
                    }
                    val rows = part?.rows.orEmpty()
                    if (part != null && rows.isEmpty()) {
                        item { Hint(if (net) "Nothing new on the network for this track." else "No peer has something like this.") }
                    }
                    itemsIndexed(rows, key = { i, r -> "l:$i:" + (r.filepath ?: r.title) }) { index, lead ->
                        val path = if (net) null else peers.remember(lead)
                        LibraryRow(
                            title = lead.title ?: "(untitled)",
                            subtitle = listOfNotNull(lead.artist, lead.album, lead.peerName ?: if (net) "a server" else null).joinToString(" · "),
                            onClick = {
                                if (path != null) {
                                    // A peer's songs play through our server: from this one on.
                                    val files = rows.mapNotNull(peers::remember).map(AudioFile::forPath)
                                    container.player.playFiles(files, files.indexOfFirst { it.path == path }.coerceAtLeast(0), shuffle = false)
                                } else {
                                    sheet = lead
                                }
                            },
                            onLongClick = { sheet = lead },
                            coverPath = path,
                            showCover = path != null,
                            leading = if (path == null) ({ MatchBar(lead.similarity) }) else null,
                            trailingText = lead.durationSec?.let { formatDuration((it * 1000).toLong()) } ?: pct(lead.similarity),
                            isCurrent = path != null && player.mediaId == path,
                            unplayable = false,
                            trailing = {
                                IconButton(onClick = { sheet = lead }, modifier = Modifier.size(36.dp)) {
                                    Icon(if (path != null) Icons.Outlined.LibraryAdd else Icons.Outlined.Download, "Get it", tint = ZeneloColors.Celadon, modifier = Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                    item {
                        Hint(
                            if (net) "Leads from the discovery network: other servers' songs, not on yours. Tap one to get it."
                            else "Peers' songs play through your server. Tap to play, ＋ to add one (or its album) to your library.",
                        )
                    }
                }
            }
        }
    }

    sheet?.let { lead -> LeadSheet(lead, plugins, onMessage, onDismiss = { sheet = null }) }
}

/** What can be done with a lead: play it (a peer's), copy it into the library, a plug-in's download, look it up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeadSheet(lead: Lead, plugins: List<app.zenelo.mstream.DiscoveryPlugin>, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val container = appContainer()
    val peers = container.peers
    val context = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val path = peers.remember(lead)
    val copy = plugins.firstOrNull { it.name == MStreamPeers.COPY }
    val getIt = plugins.filter { it.name != MStreamPeers.COPY }
    fun done(text: String? = null) {
        text?.let(onMessage)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = ZeneloColors.Bar) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(lead.title ?: "(untitled)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                listOfNotNull(lead.artist, lead.album, if (path != null) "on ${lead.peerName ?: "a peer"}" else "from the discovery network", lead.durationSec?.let { formatDuration((it * 1000).toLong()) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = ZeneloColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            if (path != null) {
                SheetAction(Icons.Rounded.PlayArrow, "Play next", ZeneloColors.Mustard) {
                    container.player.playNext(AudioFile.forPath(path))
                    done("Plays next")
                }
                SheetAction(Icons.AutoMirrored.Outlined.QueueMusic, "Add to queue", ZeneloColors.Celadon) {
                    val at = container.player.addToQueue(AudioFile.forPath(path))
                    done("Added to queue · #$at")
                }
                if (copy != null) {
                    SectionHeader("Add to my library")
                    SheetAction(Icons.Outlined.LibraryAdd, "The song", ZeneloColors.Celadon) { peers.get(copy.name, lead); done() }
                    if (lead.album != null && "album" in copy.scopes) {
                        SheetAction(Icons.Outlined.LibraryAdd, "The album · ${lead.album}", ZeneloColors.Celadon) { peers.get(copy.name, lead, "album"); done() }
                    }
                    if (lead.artist != null && "artist-missing" in copy.scopes) {
                        SheetAction(Icons.Outlined.LibraryAdd, "${lead.artist}'s albums I lack", ZeneloColors.Celadon) { peers.get(copy.name, lead, "artist-missing"); done() }
                    }
                }
            }
            if (path == null && getIt.isNotEmpty()) {
                SectionHeader("Get it")
                getIt.forEach { p -> SheetAction(Icons.Outlined.Download, p.title, ZeneloColors.Celadon) { peers.get(p.name, lead); done() } }
            }
            if (path == null) {
                val text = listOfNotNull(lead.artist, lead.title).joinToString(" - ")
                SheetAction(Icons.Outlined.ContentCopy, "Copy “$text”", ZeneloColors.TextSecondary) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Song", text))
                    done("Copied")
                }
            }
            lead.recordingMbid?.let { mbid ->
                SheetAction(Icons.Outlined.OpenInNew, "Open on MusicBrainz", ZeneloColors.TextSecondary) {
                    open(context, "https://musicbrainz.org/recording/$mbid")
                    done()
                }
            }
            if (path == null && getIt.isEmpty() && copy == null) {
                Hint("No plug-in on this server gets network leads. An admin can turn plug-ins on in the admin panel.")
            }
        }
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The jobs still worth a look: running, waiting, failed. */
@Composable
private fun JobsStrip(jobs: List<DiscoverJob>, onCancel: (DiscoverJob) -> Unit, onClear: () -> Unit) {
    val shown = jobs.filter { it.live || it.state == "failed" }.take(6)
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(ZeneloColors.Card).padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GETTING", style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted, modifier = Modifier.weight(1f))
            if (shown.any { it.state == "failed" }) TextButton(onClick = onClear) { Text("Clear failed") }
        }
        shown.forEach { job ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (job.state) {
                            "queued" -> "Waiting"
                            "failed" -> job.error ?: "Failed"
                            else -> job.statusText ?: "Working…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.state == "failed") ZeneloColors.Danger else ZeneloColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (job.state == "running") {
                        val p = job.progress
                        if (p != null && p > 0) {
                            LinearProgressIndicator(progress = { p.toFloat().coerceIn(0f, 1f) }, color = ZeneloColors.Celadon, trackColor = ZeneloColors.Bar, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        } else {
                            LinearProgressIndicator(color = ZeneloColors.Celadon, trackColor = ZeneloColors.Bar, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
                if (job.live) {
                    IconButton(onClick = { onCancel(job) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Stop", tint = ZeneloColors.TextMuted, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SeedRow(path: String, title: String?, artist: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackThumb(path)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("MORE LIKE", style = MaterialTheme.typography.labelSmall, color = ZeneloColors.TextMuted)
            Text(title ?: path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ArtistChip(name: String, onOpen: () -> Unit, onQueue: (() -> Unit)?) {
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(9.dp)).background(ZeneloColors.Card), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable(onClick = onOpen).padding(start = 10.dp, end = if (onQueue == null) 12.dp else 4.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Person, null, tint = ZeneloColors.Mustard, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        if (onQueue != null) {
            IconButton(onClick = onQueue, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Outlined.QueueMusic, "Queue tracks that fit", tint = ZeneloColors.Celadon, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun Chip(name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(34.dp).clip(RoundedCornerShape(9.dp)).background(if (selected) ZeneloColors.CeladonTint else ZeneloColors.Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) ZeneloColors.Celadon else ZeneloColors.TextSecondary,
            maxLines = 1,
        )
    }
}

/** A small on / off switch (the Material one is too wide for this row on the small screen). */
@Composable
private fun Toggle(on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Box(
        modifier.size(width = 36.dp, height = 20.dp).clip(RoundedCornerShape(10.dp))
            .background(if (on) ZeneloColors.Mustard else ZeneloColors.Card)
            .clickable { onChange(!on) }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(if (on) ZeneloColors.Background else ZeneloColors.TextMuted))
    }
}

/** How close a lead sounds, as a small bar in the cover's place. */
@Composable
private fun MatchBar(value: Double) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(ZeneloColors.Card), contentAlignment = Alignment.Center) {
        Text(pct(value), style = MaterialTheme.typography.labelSmall, color = ZeneloColors.Celadon)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = ZeneloColors.TextMuted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
