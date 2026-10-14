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
 * runs the commands other devices send, and sees them. The UI follows a device that plays while
 * this one doesn't ("Playing on …") and moves the playback with [playOn].
 *
 * Pick-up: another device that played more recently than this one and is now paused (or gone)
 * hands its queue over — loaded here paused where it stopped, so play carries on.
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
     * The other device this phone follows: the player UI shows it and its transport controls it
     * (`PlayerController`). Taken up when a device starts playing while this phone doesn't, kept
     * while it pauses, dropped when this phone plays, the device goes offline, or the devices
     * button moves the playback.
     */
    private val _controlled = MutableStateFlow<String?>(null)
    val controlledDevice: StateFlow<PlaybackDevice?> = combine(_devices, _controlled) { list, id ->
        id?.let { i -> list.firstOrNull { it.id == i && it.online } }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val attached = MutableStateFlow<Player?>(null)
    private var streamJob: Job? = null
    private var reportJob: Job? = null
    private var tickJob: Job? = null

    /** Queue ids of the entries last reported, index-aligned (for `goTo`). */
    private var reportedIds: List<Int> = emptyList()
    private var sentQueue: List<String>? = null
    private var lastSig = ""
    private var lastSentAt = 0L
    private var pickedKey = ""
    private var transferringUntil = 0L

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
        _controlled.value = null
    }

    // ── The stream ──────────────────────────────────────────────────────

    private suspend fun stream(account: MStreamAccount) {
        var backoff = 2000L
        while (true) {
            val url = "${account.url}/api/v1/devices/events".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("id", selfId)?.addQueryParameter("name", name)?.addQueryParameter("type", "android")
                ?.build() ?: return
            val call = client.newCall(Request.Builder().url(url).header("x-access-token", account.token).header("Accept", "text/event-stream").build())
            val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            try {
                withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        if (response.code == 404 || response.code == 403) {
                            Log.i(TAG, "devices: not on this server (${response.code})")
                            return@withContext
                        }
                        if (!response.isSuccessful) throw MStreamException("devices ${response.code}", response.code)
                        val source = response.body?.source() ?: return@withContext
                        backoff = 2000L
                        var event = "message"
                        val data = StringBuilder()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) dispatch(account, event, data.toString())
                                    event = "message"
                                    data.setLength(0)
                                }
                                line.startsWith(":") -> Unit
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.i(TAG, "devices stream: ${e.message}")
            } finally {
                handle.dispose()
                _connected.value = false
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun dispatch(account: MStreamAccount, event: String, data: String) {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (event) {
            "devices" -> {
                val list = json.optJSONArray("devices") ?: JSONArray()
                _devices.value = List(list.length()) { parse(list.getJSONObject(it)) }
                _connected.value = true
                sentQueue = null
                lastSig = ""
                withContext(Dispatchers.Main) { updateFollow(); report(force = true); maybePickUp(account) }
            }
            "device" -> {
                val d = parse(json)
                _devices.update { l -> if (l.any { it.id == d.id }) l.map { if (it.id == d.id) d else it } else l + d }
                withContext(Dispatchers.Main) { updateFollow(); maybePickUp(account) }
            }
            "removed" -> _devices.update { l -> l.filterNot { it.id == json.optString("id") } }
            "command" -> withContext(Dispatchers.Main) { run(json.optString("command"), json.optJSONObject("args")) }
        }
    }

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
        // Playing here: this phone is where the sound is, nothing to follow.
        if (playing) _controlled.value = null
        val sig = listOf(index, playing, serverTrack, (p.volume * 100).toInt(), p.currentPosition / 2000).joinToString("|")
        val queueChanged = serverPaths != sentQueue
        val now = SystemClock.elapsedRealtime()
        if (!force && !queueChanged && sig == lastSig && !(playing && now - lastSentAt > POSITION_EVERY_MS)) return
        lastSig = sig
        lastSentAt = now
        reportedIds = ids
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

    init {
        scope.launch { settings.map { it.mstream }.distinctUntilChanged().collect { currentAccount = it } }
    }

    // ── Commands from other devices (main thread) ───────────────────────

    private fun run(command: String, args: JSONObject?) {
        val p = attached.value ?: return
        when (command) {
            "play" -> p.play()
            "pause" -> p.pause()
            "playPause" -> if (p.playWhenReady) p.pause() else p.play()
            "next" -> p.seekToNextMediaItem()
            "previous" -> if (p.currentPosition > 4000) p.seekTo(0) else p.seekToPreviousMediaItem()
            "seek" -> args?.optLong("positionMs", -1)?.takeIf { it >= 0 }?.let { p.seekTo(it) }
            "goTo" -> args?.optInt("index", -1)?.let { reportedIds.getOrNull(it) }?.let(queue::skipTo)
            "setVolume" -> args?.optInt("volume", -1)?.takeIf { it >= 0 }?.let { p.volume = (it / 100f).coerceIn(0f, 1f) }
            "takeover" -> if (args != null) {
                transferringUntil = SystemClock.elapsedRealtime() + 5000
                load(args, args.optBoolean("play", true))
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

    private fun updateFollow() {
        val list = _devices.value
        val current = _controlled.value?.let { id -> list.firstOrNull { it.id == id } }
        if (_controlled.value != null && current?.online != true) _controlled.value = null
        if (attached.value?.playingNow() == true) return
        // A device playing while this phone doesn't: follow it (another one that starts playing
        // takes over from a followed device that is paused).
        val playing = list.filter { it.id != selfId && it.online && it.playing }.maxByOrNull { it.updatedAt.orEmpty() } ?: return
        if (current == null || !current.online || !current.playing) _controlled.value = playing.id
    }

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
        val me = list.firstOrNull { it.id == selfId }
        val best = list.filter { it.id != selfId && it.lastPlayedAt != null && it.queueLength > 0 }
            .maxByOrNull { it.lastPlayedAt!! } ?: return
        if (me?.lastPlayedAt != null && best.lastPlayedAt!! <= me.lastPlayedAt) return
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

    /** The device the bar follows: one playing while this phone doesn't. */
    fun activeRemote(list: List<PlaybackDevice>, localPlaying: Boolean): PlaybackDevice? =
        if (localPlaying) null else list.filter { it.id != selfId && it.online && it.playing }.maxByOrNull { it.updatedAt.orEmpty() }

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
        scope.launch(Dispatchers.IO) {
            val body = JSONObject().put("from", selfId).put("to", to).put("command", command)
            if (args != null) body.put("args", args)
            runCatching { post(account, "api/v1/devices/command", body) }
        }
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
            activeRemote(list, false) != null -> activeRemote(list, false)!!.id
            queue.state.value.total > 0 -> selfId
            else -> list.filter { it.lastPlayedAt != null && it.queueLength > 0 }.maxByOrNull { it.lastPlayedAt!! }?.id ?: selfId
        }
        if (from == to) {
            if (to == selfId) withContext(Dispatchers.Main) { _controlled.value = null; p.play() } else {
                _controlled.value = to
                command(to, "play")
            }
            return null
        }
        if (from == selfId) withContext(Dispatchers.Main) { report(force = true) }
        delay(if (from == selfId) 400 else 0)
        transferringUntil = SystemClock.elapsedRealtime() + 15_000
        return try {
            val r = withContext(Dispatchers.IO) { post(account, "api/v1/devices/transfer", transferBody(from, to, play = true)) }
            if (!r.optBoolean("ok")) return if (r.optString("reason") == "offline") "That device is offline" else "Nothing to move"
            withContext(Dispatchers.Main) {
                if (to == selfId) r.optJSONObject("state")?.let { load(it, play = true) }
                if (from == selfId && p.playingNow()) p.pause()
                _controlled.value = if (to == selfId) null else to
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

    private companion object {
        const val TAG = "Zenelo"
        const val POSITION_EVERY_MS = 10_000L
        val JSON = "application/json".toMediaType()
    }
}
