package app.zenelo.mstream

import android.util.Log
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.AutoDjSettings
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.library.AudioFile
import app.zenelo.library.LibraryMerge
import app.zenelo.player.PlayQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Auto DJ, as in mStream's web app: when the queue's last track starts, the server picks more
 * (`db/random-songs`) and they're appended. The request carries the session's anchors, kept here:
 * - the server's `ignoreList` cursor (no repeats) and the last [ARTIST_COOLDOWN] artists;
 * - tempo: the average BPM of the last [BPM_HISTORY] picks (else the playing track's);
 * - key: the Camelot code of the session's first pick with a key (its wheel neighbours);
 * - sound: the last [SONIC_HISTORY] picks (else the playing track) as the `similarTo` seed;
 * - similar artists of the playing track (Last.fm, through the server).
 * A track the user started (not a pick) begins a new session: tempo, key and sound anchors reset.
 * Picks that have a local copy (see [LibraryMerge.twins]) are queued as that copy.
 */
class MStreamAutoDj(
    private val db: ZeneloDatabase,
    private val settings: SettingsRepository,
    private val client: MStreamClient,
    private val queue: PlayQueue,
    private val scope: CoroutineScope,
) {
    private val _busy = MutableStateFlow(false)

    /** A request is running. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Failures worth telling the user ("nothing matches"), for a snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val mutex = Mutex()
    private var ignoreList: List<Int> = emptyList()
    private val artists = ArrayDeque<String>()
    private val bpms = ArrayDeque<Double>()
    private var camelot: String? = null
    private val sonic = ArrayDeque<String>()

    /** Queued picks by the path they were queued as; [counted] = already taken into the anchors. */
    private val picks = HashMap<String, ServerSong>()
    private val counted = HashSet<String>()
    private var lastCurrent: String? = null

    /** The track a pick failed on: not retried until the track or the options change. */
    private var failedOn: Pair<String, AutoDjSettings>? = null

    /** The last pick failed on the network (not on the server's answer). */
    @Volatile
    private var unreachable = false

    fun start() {
        scope.launch {
            // Current track and how many are left (tag refreshes of the queue don't count).
            val position = queue.state.map { q -> if (q.total == 0) null else q.pathAt(0) to q.size }.distinctUntilChanged()
            combine(position, settings.settings.map { it.autoDj to it.mstream }.distinctUntilChanged()) { q, s -> q to s }
                .collectLatest { (q, s) ->
                    val (dj, account) = s
                    if (!dj.enabled || account == null || q == null) return@collectLatest
                    val (current, left) = q
                    mutex.withLock { onCurrent(current) }
                    // The last track is playing: time for more (the web app asks at the same point).
                    if (left > 1 || failedOn == current to dj) return@collectLatest
                    // Server out of reach: retried quietly while this track plays, so the music
                    // goes on once it's back. Other failures wait for another track or options.
                    var quiet = false
                    while (true) {
                        val files = pick(account, dj, current, quiet)
                        if (files != null) {
                            queue.add(files)
                            break
                        }
                        if (!unreachable) {
                            failedOn = current to dj
                            break
                        }
                        quiet = true
                        delay(RETRY_MS)
                    }
                }
        }
    }

    /** Auto DJ on / off; turned on with nothing queued, it starts playing its picks. */
    fun setEnabled(on: Boolean, play: (List<AudioFile>) -> Unit) {
        scope.launch {
            val s = settings.settings.first()
            settings.setAutoDj(s.autoDj.copy(enabled = on))
            failedOn = null
            if (!on) return@launch
            val account = s.mstream ?: return@launch
            if (queue.state.value.total == 0) {
                mutex.withLock { reset() }
                val files = pick(account, s.autoDj.copy(enabled = true), current = null) ?: return@launch
                play(files)
            }
        }
    }

    /** Asks the server for the next picks; null (and a message) when it has none. */
    private suspend fun pick(account: MStreamAccount, dj: AutoDjSettings, current: String?, quiet: Boolean = false): List<AudioFile>? {
        _busy.value = true
        unreachable = false
        fun fail(message: String): List<AudioFile>? {
            Log.w(TAG, message)
            if (!quiet) _messages.tryEmit(message)
            return null
        }
        try {
            val all = db.tracks().observeAll().first()
            val twins = LibraryMerge.twins(all)
            val playing = current?.let { serverPathOf(it, twins) }
            val playingTags = if (playing != null && (dj.bpmContinuity || dj.harmonicMixing)) client.song(account, playing) else null
            val artist = current?.let { path -> all.firstOrNull { it.path == path } }?.let { it.artist ?: it.albumArtist }
            val similar = if (dj.similarArtists && artist != null) client.similarArtists(account, artist) else emptyList()

            var cursor = mutex.withLock { ignoreList }
            var last: List<ServerSong> = emptyList()
            var chosen: List<ServerSong>? = null
            for (attempt in 1..MAX_TRIES) {
                val (body, refBpm, keys) = mutex.withLock { request(dj, cursor, playing, playingTags, similar) }
                    ?: return fail("Sonic mode needs a server track playing first")
                Log.i(TAG, "Auto DJ asks $body")
                val answer = try {
                    client.randomSongs(account, body)
                } catch (e: MStreamException) {
                    return fail(
                        when {
                            e.code == 403 && dj.sonic -> "Sonic mode: the server doesn't analyze tracks"
                            e.code == 400 && e.message.orEmpty().contains("similarity", true) -> "Nothing sounds close enough — lower the similarity"
                            e.code == 400 && e.message.orEmpty().contains("analyzed", true) -> "The server hasn't analyzed this track yet"
                            e.code == 400 -> "Auto DJ: no tracks match"
                            else -> "Auto DJ: ${e.message}"
                        },
                    )
                }
                if (answer.ignoreList.isNotEmpty()) cursor = answer.ignoreList
                if (answer.songs.isEmpty()) break
                last = answer.songs
                val fit = answer.songs.filterNot { AutoDjRules.blocked(it, refBpm, dj.bpmTolerance, keys) }
                if (fit.isNotEmpty()) {
                    chosen = fit
                    break
                }
            }
            // Every try broke the tempo / key: take the last answer rather than stop the music.
            val songs = chosen ?: last.takeIf { it.isNotEmpty() } ?: return fail("Auto DJ: no tracks match")
            val queued = songs.map { song -> song to (twins[MStreamPaths.of(song.filepath)] ?: MStreamPaths.of(song.filepath)) }
            mutex.withLock {
                ignoreList = cursor
                for ((song, path) in queued) {
                    picks[path] = song
                    song.artist?.let(::coolDown)
                }
            }
            Log.i(TAG, "Auto DJ queued " + songs.joinToString { it.title ?: it.filepath })
            return queued.map { (_, path) -> AudioFile.forPath(path) }
        } catch (e: IOException) {
            if (e is MStreamException) return fail("Auto DJ: ${e.message}")
            unreachable = true
            return fail("Auto DJ: can't reach the server — trying again")
        } finally {
            _busy.value = false
        }
    }

    private data class Request(val body: JSONObject, val refBpm: Double?, val keys: Set<String>?)

    /** The `db/random-songs` body, as the web app's `_buildAutoDjBody`; null when sonic mode has no seed. */
    private fun request(dj: AutoDjSettings, cursor: List<Int>, playing: String?, playingTags: ServerSong?, similar: List<String>): Request? {
        val body = JSONObject()
            .put("ignoreList", JSONArray(cursor))
            .put("minRating", dj.minRating)
        if (dj.batch > 1) body.put("limit", dj.batch.coerceAtMost(25))
        var refBpm: Double? = null
        if (dj.bpmContinuity) {
            refBpm = AutoDjRules.average(bpms) ?: playingTags?.bpm
            val ranges = refBpm?.let { AutoDjRules.bpmRanges(it, dj.bpmTolerance) }.orEmpty()
            if (ranges.isNotEmpty()) {
                body.put("bpmRanges", ranges.toJson())
                body.put("bpmRangesWide", AutoDjRules.bpmRanges(refBpm!!, dj.bpmTolerance + 2).toJson())
            } else {
                refBpm = null
                body.put("requireBpm", true)
            }
        }
        var keys: Set<String>? = null
        if (dj.harmonicMixing) {
            if (camelot == null) camelot = AutoDjRules.toCamelot(playingTags?.musicalKey)
            keys = camelot?.let(AutoDjRules::neighbours)
            keys?.let { body.put("musicalKeys", JSONArray(it.toList())) }
            body.put("requireMusicalKey", true)
        }
        if (similar.isNotEmpty()) body.put("artists", JSONArray(similar))
        if (artists.isNotEmpty()) body.put("ignoreArtists", JSONArray(artists.toList()))
        if (dj.sonic) {
            val seeds = sonic.toList().ifEmpty { listOfNotNull(playing) }
            if (seeds.isEmpty()) return null
            body.put("similarTo", JSONArray(seeds)).put("minSimilarity", (dj.sonicMinSimilarity * 100).roundToInt() / 100.0)
        }
        return Request(body, refBpm, keys)
    }

    /** The playing track changed: a pick joins the anchors, anything else starts a new session. */
    private fun onCurrent(path: String) {
        if (path == lastCurrent) return
        lastCurrent = path
        val pick = picks[path]
        if (pick == null) {
            reset()
            return
        }
        if (!counted.add(path)) return
        pick.bpm?.let { bpms.addLast(it); if (bpms.size > BPM_HISTORY) bpms.removeFirst() }
        if (camelot == null) camelot = AutoDjRules.toCamelot(pick.musicalKey)
        sonic.addLast(pick.filepath)
        if (sonic.size > SONIC_HISTORY) sonic.removeFirst()
    }

    private fun reset() {
        bpms.clear()
        camelot = null
        sonic.clear()
        counted.clear()
    }

    private fun coolDown(artist: String) {
        artists.removeAll { it.equals(artist, ignoreCase = true) }
        artists.addLast(artist)
        if (artists.size > ARTIST_COOLDOWN) artists.removeFirst()
    }

    /** The server path of a queued track: its own, or its server twin's for a local copy. */
    private fun serverPathOf(path: String, twins: Map<String, String>): String? =
        if (MStreamPaths.isRemote(path)) MStreamPaths.serverPath(path)
        else twins.entries.firstOrNull { it.value == path }?.key?.let(MStreamPaths::serverPath)

    private fun List<IntRange>.toJson() = JSONArray(map { JSONObject().put("min", it.first).put("max", it.last) })

    private companion object {
        const val TAG = "Zenelo"
        const val MAX_TRIES = 5
        const val RETRY_MS = 30_000L
        const val ARTIST_COOLDOWN = 15
        const val BPM_HISTORY = 8
        const val SONIC_HISTORY = 5
    }
}
