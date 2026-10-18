package app.zenelo.mstream

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.ZeneloSettings
import app.zenelo.library.AudioFile
import app.zenelo.player.PlayQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What another device plays, as the server last heard it. */
data class RemoteTrack(val serverPath: String, val title: String?, val artist: String?, val album: String?, val art: String?, val durationMs: Long)

/** One of the user's playback devices (this phone, a browser, …) — the server's summary. */
data class PlaybackDevice(
    val id: String,
    val name: String,
    val type: String,
    val online: Boolean,
    val playing: Boolean,
    val positionMs: Long,
    val volume: Int?,
    val track: RemoteTrack?,
    val queueLength: Int,
    val queueRev: Int = 0,
    /** The current track's index in its queue (-1: none). */
    val index: Int = -1,
    /** Shuffle and repeat ("off" / "all" / "one" / "autodj"); null when the device doesn't report them. */
    val shuffle: Boolean? = null,
    val repeat: String? = null,
    /** ISO time it last reported playing (the server's clock). */
    val lastPlayedAt: String?,
    val updatedAt: String?,
    /** When we heard it ([SystemClock.elapsedRealtime]), for [positionNow]. */
    val receivedAt: Long,
) {
    fun positionNow(): Long {
        val p = positionMs + if (playing) SystemClock.elapsedRealtime() - receivedAt else 0L
        val d = track?.durationMs ?: 0L
        return if (d > 0) p.coerceAtMost(d) else p
    }
}

/**
 * Playback devices, Spotify Connect–like (server: the fork's `src/api/devices.js`). This phone is
 * one device while the playback service runs ([attach] … [detach]): it reports what it plays
 * (the queue as server paths — local tracks without a server copy can't move and are left out),
 * runs the commands other devices send, and sees them. The player UI follows the device the music
 * is on ([controlledDevice], [followed]): paused there it stays followed, so play / pause / next /
 * a pick in the queue go to it. The playback moves only when something new is started here (the
 * device playing so far pauses) or through the devices button ([playOn]).
 *
 * Pick-up: a device that played last and has gone offline leaves its queue here, paused where it
 * stopped, so play carries on.
 *
 * The stream is Server-Sent Events over a long GET (OkHttp, read line by line).
 */
class MStreamDevices(
    context: Context,
    base: OkHttpClient,
    private val db: ZeneloDatabase,
    private val settings: Flow<ZeneloSettings>,
    private val queue: PlayQueue,
    private val links: ServerLinks,
    private val online: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val client = base.newBuilder().readTimeout(75, TimeUnit.SECONDS).build()

    val selfId: String = File(context.filesDir, "device-id").let { f ->
        runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.length >= 8 }
            ?: ("android-" + UUID.randomUUID().toString().replace("-", "").take(20)).also { runCatching { f.writeText(it) } }
    }
    /** "FiiO JM21": the model, with the maker unless the model already names it. */
    val name: String = run {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar(Char::uppercaseChar)
        val model = Build.MODEL.orEmpty()
        when {
            model.isBlank() -> maker.ifBlank { "Android" }
            model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model".trim()
        }
    }

    private val _devices = MutableStateFlow<List<PlaybackDevice>>(emptyList())
    val devices: StateFlow<List<PlaybackDevice>> = _devices.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * False once the server answered `devices/poll` with 404 / 403: upstream mStream (not the fork)
     * has no devices, so the UI hides them. True again for another account.
     */
    private val _supported = MutableStateFlow(true)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()
    private var streamedAccount: Pair<String, String>? = null

    /** Whether this phone plays (as last reported). */
    private val localPlaying = MutableStateFlow(false)
    /** The device the playback was just moved to, until it reports playing ([playOn]). */
    private val hint = MutableStateFlow<String?>(null)
    private var hintJob: Job? = null

    /**
     * The other device this phone follows ([followed]): the player UI shows it and its transport
     * controls it (`PlayerController`).
     */
    val controlledDevice: StateFlow<PlaybackDevice?> = combine(_devices, localPlaying, hint) { list, playing, h ->
        followed(list, selfId, playing, h)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val attached = MutableStateFlow<Player?>(null)
    private var streamJob: Job? = null
    private var reportJob: Job? = null
    private var tickJob: Job? = null

    /** Queue ids of the entries last reported, index-aligned (for `goTo`, `remove`), and their server paths. */
    private var reportedIds: List<Int> = emptyList()
    private var reportedPaths: List<String> = emptyList()
    private var sentQueue: List<String>? = null
    private var lastSig = ""
    private var lastSentAt = 0L
    private var pickedKey = ""
    private var transferringUntil = 0L
    /** When a command (another device's control, a transfer) last came in: the playback it starts isn't this phone taking over. */
    private var commandedAt = 0L

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = scheduleReport()
    }

    // ── Lifecycle (PlaybackService) ─────────────────────────────────────

    fun attach(player: Player) {
        attached.value = player
        player.addListener(playerListener)
        if (streamJob == null) {
            streamJob = scope.launch {
                combine(settings.map { it.mstream }.distinctUntilChanged(), online, attached) { a, on, p -> Triple(a, on, p != null) }
                    .distinctUntilChanged()
                    .collectLatest { (account, on, isAttached) ->
                        _connected.value = false
                        // Another server or user: the last one's devices aren't this one's.
                        val key = account?.let { it.url to it.username }
                        if (key != streamedAccount) {
                            streamedAccount = key
                            _devices.value = emptyList()
                            _supported.value = true
                        }
                        if (account != null && account.token.isNotEmpty() && on && isAttached) stream(account)
                    }
            }
        }
        reportJob = scope.launch(Dispatchers.Main) { queue.state.collect { scheduleReport() } }
        tickJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(POSITION_EVERY_MS)
                if (attached.value?.playWhenReady == true) report()
            }
        }
    }

    fun detach() {
        attached.value?.removeListener(playerListener)
        attached.value = null
        reportJob?.cancel()
        tickJob?.cancel()
        _connected.value = false
        _devices.value = emptyList()
        localPlaying.value = false
    }

    // ── The stream ──────────────────────────────────────────────────────

    /**
     * Long polling (`devices/poll`), not the SSE stream: behind some proxies (Cloudflare in front of
     * a home server) not a byte of the stream came through, so no device saw another. Each answer
     * carries the events since the last one; `ack` tells the server what arrived.
     */
    private suspend fun stream(account: MStreamAccount) {
        var backoff = 2000L
        val session = UUID.randomUUID().toString().replace("-", "").take(16)
        var ack = 0L
        while (true) {
            val url = "${account.url}/api/v1/devices/poll".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("id", selfId)?.addQueryParameter("name", name)?.addQueryParameter("type", "android")
                ?.addQueryParameter("session", session)?.addQueryParameter("ack", ack.toString())
                ?.build() ?: return
            val call = client.newCall(Request.Builder().url(url).header("x-access-token", account.token).build())
            val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            val answer = try {
                withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        if (response.code == 404 || response.code == 403) {
                            Log.i(TAG, "devices: not on this server (${response.code})")
                            _devices.value = emptyList()
                            _supported.value = false
                            null
                        } else {
                            if (!response.isSuccessful) throw MStreamException("devices ${response.code}", response.code)
                            JSONObject(response.body?.string().orEmpty())
                        }
                    }
                } ?: run { _connected.value = false; return }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.i(TAG, "devices poll: ${e.message}")
                _connected.value = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            } finally {
                handle.dispose()
            }
            backoff = 2000L
            ack = answer.optLong("cursor", ack)
            val events = answer.optJSONArray("events") ?: JSONArray()
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                dispatch(account, e.optString("event"), e.optJSONObject("data") ?: continue)
            }
        }
    }

    private suspend fun dispatch(account: MStreamAccount, event: String, json: JSONObject) {
        when (event) {
            "devices" -> {
                val list = json.optJSONArray("devices") ?: JSONArray()
                val old = _devices.value
                _devices.value = List(list.length()) { i -> parse(list.getJSONObject(i)).let { d -> smoothed(old.firstOrNull { it.id == d.id }, d) } }
                _connected.value = true
                sentQueue = null
                lastSig = ""
                withContext(Dispatchers.Main) { report(force = true); maybePickUp(account) }
            }
            "device" -> {
                val fresh = parse(json)
                lateinit var d: PlaybackDevice
                _devices.update { l ->
                    d = smoothed(l.firstOrNull { it.id == fresh.id }, fresh)
                    if (l.any { it.id == d.id }) l.map { if (it.id == d.id) d else it } else l + d
                }
                if (d.playing && d.id == hint.value) hint.value = null
                withContext(Dispatchers.Main) { maybePickUp(account) }
            }
            "removed" -> _devices.update { l -> l.filterNot { it.id == json.optString("id") } }
            "command" -> withContext(Dispatchers.Main) { run(json.optString("command"), json.optJSONObject("args")) }
        }
    }

    /**
     * A playing device's playhead runs on this phone's clock: a report keeps the running anchor
     * unless it shows a jump (seek, another track, play / pause) — re-anchoring on each report made
     * the seconds stutter.
     */
    private fun smoothed(old: PlaybackDevice?, d: PlaybackDevice): PlaybackDevice =
        if (old != null && old.playing && d.playing && old.track?.serverPath == d.track?.serverPath && old.index == d.index &&
            kotlin.math.abs(d.positionMs - old.positionNow()) < DRIFT_MS
        ) d.copy(positionMs = old.positionMs, receivedAt = old.receivedAt) else d

    private fun parse(o: JSONObject): PlaybackDevice {
        fun str(j: JSONObject, k: String) = if (j.isNull(k)) null else j.optString(k).takeIf { it.isNotEmpty() }
        val t = o.optJSONObject("track")
        return PlaybackDevice(
            id = o.optString("id"),
            name = o.optString("name", "Device"),
            type = o.optString("type", "other"),
            online = o.optBoolean("online"),
            playing = o.optBoolean("playing"),
            positionMs = o.optLong("positionMs"),
            volume = if (o.isNull("volume")) null else o.optInt("volume"),
            track = t?.let { RemoteTrack(it.optString("filepath"), str(it, "title"), str(it, "artist"), str(it, "album"), str(it, "art"), it.optLong("durationMs")) },
            queueLength = o.optInt("queueLength"),
            queueRev = o.optInt("queueRev"),
            index = o.optInt("index", -1),
            shuffle = if (o.isNull("shuffle") || !o.has("shuffle")) null else o.optBoolean("shuffle"),
            repeat = str(o, "repeat"),
            lastPlayedAt = str(o, "lastPlayedAt"),
            updatedAt = str(o, "updatedAt"),
            receivedAt = SystemClock.elapsedRealtime(),
        )
    }

    // ── What this phone plays ───────────────────────────────────────────

    private fun Player.playingNow() = playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE

    private fun scheduleReport() {
        scope.launch(Dispatchers.Main) {
            delay(300)
            report()
        }
    }

    /** Main thread (reads the player). */
    private fun report(force: Boolean = false) {
        val p = attached.value ?: return
        if (!_connected.value) return
        val account = currentAccount ?: return
        val snapshot = queue.state.value
        // The queue as server paths; tracks with no server copy are left out.
        val serverPaths = ArrayList<String>()
        val ids = ArrayList<Int>()
        var index = -1
        val l = links.current
        for (i in 0 until snapshot.total) {
            val offset = i - snapshot.history
            val server = l.serverOf(snapshot.pathAt(offset)) ?: continue
            if (offset == 0) index = serverPaths.size
            serverPaths += MStreamPaths.serverPath(server)
            ids += snapshot.idAt(offset)
        }
        val mediaId = p.currentMediaItem?.mediaId
        val serverTrack = l.serverOf(mediaId)
        val playing = p.playingNow()
        // Started here (not by a command): the music moves here, the devices playing so far pause.
        if (playing && !localPlaying.value && SystemClock.elapsedRealtime() - commandedAt > 3000) {
            _devices.value.filter { it.id != selfId && it.online && it.playing }.forEach { command(it.id, "pause") }
        }
        localPlaying.value = playing
        val repeat = when {
            autoDjOn -> "autodj"
            p.repeatMode == Player.REPEAT_MODE_ONE -> "one"
            p.repeatMode == Player.REPEAT_MODE_ALL -> "all"
            else -> "off"
        }
        val shuffle = p.shuffleModeEnabled
        val sig = listOf(index, playing, serverTrack, (p.volume * 100).toInt(), shuffle, repeat, p.currentPosition / 2000).joinToString("|")
        val queueChanged = serverPaths != sentQueue
        val now = SystemClock.elapsedRealtime()
        if (!force && !queueChanged && sig == lastSig && !(playing && now - lastSentAt > POSITION_EVERY_MS)) return
        lastSig = sig
        lastSentAt = now
        reportedIds = ids
        reportedPaths = serverPaths
        val info = mediaId?.let(queue::cachedInfo)
        val meta = p.mediaMetadata
        val duration = p.duration.takeIf { it > 0 } ?: info?.durationMs ?: 0L
        val position = p.currentPosition
        val volume = (p.volume * 100).toInt()
        scope.launch(Dispatchers.IO) {
            val art = serverTrack?.let { runCatching { db.remoteTracks().get(it)?.artFile }.getOrNull() }
            val status = JSONObject()
                .put("index", if (serverTrack == null) -1 else index)
                .put("positionMs", position.coerceAtLeast(0))
                .put("playing", playing)
                .put("volume", volume)
                .put("shuffle", shuffle)
                .put("repeat", repeat)
                .put("track", if (serverTrack == null) JSONObject.NULL else JSONObject()
                    .put("filepath", MStreamPaths.serverPath(serverTrack))
                    .put("title", info?.title ?: meta.title?.toString() ?: MStreamPaths.fileName(serverTrack))
                    .put("artist", info?.artist ?: meta.artist?.toString() ?: "")
                    .put("album", info?.album ?: meta.albumTitle?.toString() ?: "")
                    .put("art", art ?: "")
                    .put("durationMs", duration))
            val body = JSONObject().put("id", selfId).put("status", status)
            if (queueChanged) body.put("queue", JSONArray(serverPaths))
            if (runCatching { post(account, "api/v1/devices/state", body) }.isSuccess && queueChanged) sentQueue = serverPaths
        }
    }

    @Volatile private var currentAccount: MStreamAccount? = null
    @Volatile private var autoDjOn = false

    /** Turns Auto DJ on / off here (set by the container: Auto DJ lives elsewhere). */
    var setAutoDj: ((Boolean) -> Unit)? = null

    init {
        scope.launch { settings.map { it.mstream }.distinctUntilChanged().collect { currentAccount = it } }
        scope.launch {
            settings.map { it.autoDj.enabled }.distinctUntilChanged().collect {
                autoDjOn = it
                scheduleReport()
            }
        }
    }

    // ── Commands from other devices (main thread) ───────────────────────

    private fun run(command: String, args: JSONObject?) {
        val p = attached.value ?: return
        commandedAt = SystemClock.elapsedRealtime()
        when (command) {
            "play" -> p.play()
            "pause" -> p.pause()
            "playPause" -> if (p.playWhenReady) p.pause() else p.play()
            "next" -> p.seekToNextMediaItem()
            "previous" -> if (p.currentPosition > 4000) p.seekTo(0) else p.seekToPreviousMediaItem()
            "seek" -> args?.optLong("positionMs", -1)?.takeIf { it >= 0 }?.let { p.seekTo(it) }
            "goTo" -> args?.optInt("index", -1)?.let { reportedIds.getOrNull(it) }?.let(queue::skipTo)
            "setVolume" -> args?.optInt("volume", -1)?.takeIf { it >= 0 }?.let { p.volume = (it / 100f).coerceIn(0f, 1f) }
            "setShuffle" -> if (args?.has("shuffle") == true) p.shuffleModeEnabled = args.optBoolean("shuffle")
            "setRepeat" -> when (val mode = args?.optString("mode")) {
                "autodj" -> {
                    p.repeatMode = Player.REPEAT_MODE_OFF
                    if (!autoDjOn) setAutoDj?.invoke(true)
                }
                "off", "all", "one" -> {
                    if (autoDjOn) setAutoDj?.invoke(false)
                    p.repeatMode = when (mode) { "all" -> Player.REPEAT_MODE_ALL; "one" -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                }
            }
            "takeover" -> if (args != null) {
                transferringUntil = SystemClock.elapsedRealtime() + 5000
                load(args, args.optBoolean("play", true))
            }
            // From guests of Play on (the fork's api/guest.js): queue server tracks, take one out.
            "enqueue" -> args?.optJSONArray("paths")?.let { list ->
                val files = List(list.length()) { list.optString(it) }.filter { it.isNotBlank() }
                    .map { AudioFile.forPath(MStreamPaths.of(it)) }
                if (files.isNotEmpty()) {
                    Log.i(TAG, "devices: guest queues ${files.size} track(s), next=${args.optBoolean("next")}")
                    if (args.optBoolean("next")) queue.playNext(files) else queue.add(files)
                }
            }
            "remove" -> {
                val i = args?.optInt("index", -1) ?: -1
                val id = reportedIds.getOrNull(i)
                if (id != null && reportedPaths.getOrNull(i) == args?.optString("path")) queue.remove(setOf(id))
            }
        }
        scope.launch(Dispatchers.Main) { delay(400); report(force = true) }
    }

    /** Loads a transferred state ({ queue, index, positionMs }) into the queue. */
    private fun load(state: JSONObject, play: Boolean) {
        val list = state.optJSONArray("queue") ?: return
        if (list.length() == 0) return
        val files = List(list.length()) { AudioFile.forPath(MStreamPaths.of(list.getString(it))) }
        Log.i(TAG, "devices: load ${files.size} tracks at ${state.optInt("index", 0)} / ${state.optLong("positionMs", 0)} ms, play=$play")
        queue.load(files, state.optInt("index", 0), state.optLong("positionMs", 0), play)
        sentQueue = null
    }

    // ── Following another device (main thread) ──────────────────────────

    /** The queue of another device: its library paths (as ours, `mstream://…`) and the current index. */
    suspend fun remoteQueue(id: String): Pair<List<String>, Int>? = withContext(Dispatchers.IO) {
        val account = currentAccount ?: return@withContext null
        runCatching {
            val request = Request.Builder().url("${account.url}/api/v1/devices/$id/queue").header("x-access-token", account.token).build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val json = JSONObject(r.body?.string().orEmpty())
                val q = json.optJSONArray("queue") ?: JSONArray()
                List(q.length()) { MStreamPaths.of(q.getString(it)) } to json.optInt("index", 0)
            }
        }.getOrNull()
    }

    // ── Pick-up (main thread) ───────────────────────────────────────────

    private fun maybePickUp(account: MStreamAccount) {
        val p = attached.value ?: return
        if (p.playingNow() || SystemClock.elapsedRealtime() < transferringUntil) return
        val list = _devices.value
        if (list.any { it.id != selfId && it.online && it.playing }) return   // follow it instead
        // Who played last: a device still online is followed, not emptied; this phone keeps its own queue.
        val best = list.filter { it.lastPlayedAt != null && it.queueLength > 0 }.maxByOrNull { it.lastPlayedAt!! } ?: return
        if (best.id == selfId || best.online) return
        val key = "${best.id}|${best.lastPlayedAt}"
        if (key == pickedKey) return
        pickedKey = key
        scope.launch(Dispatchers.IO) {
            val r = runCatching { post(account, "api/v1/devices/transfer", transferBody(best.id, selfId, play = false)) }.getOrNull() ?: return@launch
            val state = r.optJSONObject("state") ?: return@launch
            withContext(Dispatchers.Main) {
                if (attached.value?.playingNow() == false) {
                    Log.i(TAG, "picked up the queue of ${best.name}")
                    load(state, play = false)
                }
            }
        }
    }

    // ── Actions for the UI ──────────────────────────────────────────────

    fun command(to: String, command: String, args: JSONObject? = null) {
        val account = currentAccount ?: return
        if (command == "playPause" || command == "play" || command == "pause") {
            _devices.update { l ->
                l.map {
                    if (it.id != to) it else it.copy(
                        positionMs = it.positionNow(), receivedAt = SystemClock.elapsedRealtime(),
                        playing = when (command) { "play" -> true; "pause" -> false; else -> !it.playing },
                    )
                }
            }
        }
        if (command == "setShuffle" || command == "setRepeat" || command == "setVolume") {
            _devices.update { l ->
                l.map {
                    if (it.id != to || args == null) it else when (command) {
                        "setShuffle" -> it.copy(shuffle = args.optBoolean("shuffle"))
                        "setRepeat" -> it.copy(repeat = args.optString("mode"))
                        else -> it.copy(volume = args.optInt("volume"))
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            val body = JSONObject().put("from", selfId).put("to", to).put("command", command)
            if (args != null) body.put("args", args)
            runCatching { post(account, "api/v1/devices/command", body) }
        }
    }

    /** The followed device's volume after a volume key, for the overlay (null: hidden). */
    private val _volumeHud = MutableStateFlow<Pair<String, Int>?>(null)
    val volumeHud: StateFlow<Pair<String, Int>?> = _volumeHud.asStateFlow()
    private var hudJob: Job? = null

    /**
     * A volume key while another device is followed: its volume moves by [delta] (0–100) instead
     * of this phone's. False when nothing is followed (the key does what it always does).
     */
    fun adjustFollowedVolume(delta: Int): Boolean {
        val d = controlledDevice.value ?: return false
        if (d.volume == null) return false
        val volume = (d.volume + delta).coerceIn(0, 100)
        command(d.id, "setVolume", JSONObject().put("volume", volume))
        _volumeHud.value = d.name to volume
        hudJob?.cancel()
        hudJob = scope.launch { delay(1500); _volumeHud.value = null }
        return true
    }

    /** Moves the playback to [to] (this phone or another device). Returns a message for the UI, or null. */
    suspend fun playOn(to: String): String? {
        val account = currentAccount ?: return "Not connected to the server"
        val p = attached.value ?: return null
        val list = _devices.value
        val localPlaying = withContext(Dispatchers.Main) { p.playingNow() }
        val from = when {
            localPlaying -> selfId
            controlledDevice.value != null -> controlledDevice.value!!.id
            queue.state.value.total > 0 -> selfId
            else -> list.filter { it.lastPlayedAt != null && it.queueLength > 0 }.maxByOrNull { it.lastPlayedAt!! }?.id ?: selfId
        }
        if (from == to) {
            if (to == selfId) withContext(Dispatchers.Main) { commandedAt = SystemClock.elapsedRealtime(); p.play() } else command(to, "play")
            return null
        }
        if (from == selfId) withContext(Dispatchers.Main) { report(force = true) }
        delay(if (from == selfId) 400 else 0)
        transferringUntil = SystemClock.elapsedRealtime() + 15_000
        hintJob?.cancel()
        hint.value = to.takeIf { it != selfId }
        if (hint.value != null) hintJob = scope.launch { delay(8000); hint.value = null }
        return try {
            val r = withContext(Dispatchers.IO) { post(account, "api/v1/devices/transfer", transferBody(from, to, play = true)) }
            if (!r.optBoolean("ok")) return if (r.optString("reason") == "offline") "That device is offline" else "Nothing to move"
            withContext(Dispatchers.Main) {
                if (to == selfId) {
                    commandedAt = SystemClock.elapsedRealtime()
                    r.optJSONObject("state")?.let { load(it, play = true) }
                }
                if (from == selfId && p.playingNow()) p.pause()
            }
            if (to != selfId) "Playing on ${list.firstOrNull { it.id == to }?.name ?: "the other device"}" else null
        } catch (e: Exception) {
            "Couldn't move the playback"
        } finally {
            transferringUntil = SystemClock.elapsedRealtime() + 2000
        }
    }

    /** [playOn] in this object's scope: a sheet that closes on the tap must not cancel it. */
    fun playOnLater(to: String, onMessage: (String) -> Unit) {
        scope.launch {
            val message = playOn(to)
            if (message != null) withContext(Dispatchers.Main) { onMessage(message) }
        }
    }

    private fun transferBody(from: String, to: String, play: Boolean) =
        JSONObject().put("from", from).put("to", to).put("caller", selfId).put("play", play)

    private fun post(account: MStreamAccount, path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("${account.url}/$path")
            .header("x-access-token", account.token)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw MStreamException("$path ${r.code}", r.code)
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        }
    }

    companion object {
        /**
         * The other device the player follows (null: this phone). Nothing while this phone plays;
         * else [hint] (the playback was just moved there); else one playing (the latest to start);
         * else the one that played last, when that isn't this phone and it's still online — so a
         * device paused stays followed, and this phone's own pause keeps its own player.
         */
        fun followed(list: List<PlaybackDevice>, selfId: String, localPlaying: Boolean, hint: String? = null): PlaybackDevice? {
            if (localPlaying) return null
            hint?.let { h -> list.firstOrNull { it.id == h && it.online } }?.let { return it }
            list.filter { it.id != selfId && it.online && it.playing }.maxByOrNull { it.lastPlayedAt.orEmpty() }?.let { return it }
            val last = list.filter { it.lastPlayedAt != null && it.queueLength > 0 }.maxByOrNull { it.lastPlayedAt!! }
            return last?.takeIf { it.id != selfId && it.online }
        }

        private const val TAG = "Zenelo"
        private const val POSITION_EVERY_MS = 10_000L
        private const val DRIFT_MS = 2500L
        private val JSON = "application/json".toMediaType()
    }
}
