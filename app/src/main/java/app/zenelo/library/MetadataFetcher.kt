package app.zenelo.library

import android.content.Context
import app.zenelo.data.db.CoverEntity
import app.zenelo.data.db.LyricsEntity
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.online.CoverCandidate
import app.zenelo.online.CoverSources
import app.zenelo.online.Http
import app.zenelo.online.LrcLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers and lyrics for tracks: local sources first, then the internet (Wi-Fi only, if enabled).
 *
 * Covers: embedded → folder image → cached download → Deezer / iTunes / MusicBrainz. Downloaded
 * covers are written into files lacking art. The playing file is never rewritten under the player:
 * its embed waits in [pendingEmbeds] until the track changes.
 *
 * Lyrics: sibling .lrc → tags (seeded by the indexer) → cached → LRCLIB.
 */
class MetadataFetcher(
    context: Context,
    db: ZeneloDatabase,
    private val http: Http,
    private val coverSources: CoverSources,
    private val lrcLib: LrcLib,
    private val settings: SettingsRepository,
    private val indexer: LibraryIndexer,
    private val nowPlaying: StateFlow<String?>,
) {
    private val tracks = db.tracks()
    private val covers = db.covers()
    private val lyrics = db.lyrics()
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val pendingEmbeds = ConcurrentHashMap<String, File>()

    suspend fun onlineAllowed(): Boolean = settings.settings.first().onlineFetch && http.onWifi()

    private val folderImages = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * cover.jpg / folder.jpg / front.jpg / AlbumArt.jpg (jpg or png) next to the track.
     * Probes the few candidate names instead of listing the folder, which for a 7000-file folder
     * on FUSE takes seconds; shared storage and SD cards are case-insensitive, so lowercase probes
     * also find "Cover.JPG". Cached per folder until its mtime changes.
     */
    fun folderImage(dir: String): File? {
        val folder = File(dir)
        val stamp = folder.lastModified()
        folderImages[dir]?.takeIf { it.first == stamp }?.let { return it.second.takeIf(String::isNotEmpty)?.let(::File) }
        val found = FOLDER_IMAGES.asSequence().map { File(folder, it) }.firstOrNull { it.isFile }
        folderImages[dir] = stamp to (found?.path ?: "")
        return found
    }

    /**
     * Local covers for many tracks at once (folder images + cached downloads), for building a
     * playlist: one probe per folder and one query per 500 albums, no network.
     */
    suspend fun localCovers(infos: Collection<TrackEntity>): Map<String, File> = withContext(Dispatchers.IO) {
        val missing = infos.filter { !it.hasArtwork }
        val byDir = missing.map { it.dir }.distinct().associateWith { folderImage(it) }
        val keys = missing.filter { byDir[it.dir] == null }.map(::coverKey).distinct()
        val cached = keys.chunked(500).flatMap { covers.getMany(it) }
            .mapNotNull { c -> c.file?.let(::File)?.takeIf { it.exists() }?.let { c.albumKey to it } }
            .toMap()
        missing.mapNotNull { t -> (byDir[t.dir] ?: cached[coverKey(t)])?.let { t.path to it } }.toMap()
    }

    /** Cover to show for a track without embedded art, downloading it if allowed. */
    suspend fun coverFor(track: TrackEntity, allowNetwork: Boolean): File? = withContext(Dispatchers.IO) {
        folderImage(track.dir)?.let { return@withContext it }
        val key = coverKey(track)
        covers.get(key)?.let { cached ->
            cached.file?.let(::File)?.takeIf { it.exists() }?.let { return@withContext it }
            if (cached.file == null && !expired(cached.fetchedAt)) return@withContext null
        }
        if (!allowNetwork || !onlineAllowed()) return@withContext null
        val (artist, album) = searchTerms(track) ?: return@withContext null
        val found = coverSources.auto(artist, album)
        if (found == null) {
            // Only remember misses we're sure about: a dropped connection shouldn't block retries.
            if (http.onWifi()) covers.upsert(CoverEntity(key, null, null, now()))
            return@withContext null
        }
        saveCover(key, found.second, found.first.source)
    }

    /** Writes [cover] into the tracks (only those without art unless [replace]). Returns files written. */
    suspend fun embed(targets: List<TrackEntity>, cover: File, replace: Boolean): Int = withContext(Dispatchers.IO) {
        if (!settings.settings.first().embedCovers) return@withContext 0
        val image = cover.readBytes()
        var written = 0
        for (track in targets) {
            if ((!replace && track.hasArtwork) || !TagReader.canEmbed(track.path)) continue
            if (track.path == nowPlaying.value) {
                pendingEmbeds[track.path] = cover
                continue
            }
            if (TagReader.embedArtwork(File(track.path), image)) {
                written++
                indexer.indexOne(track.path)
            }
        }
        written
    }

    /** Called on track change: writes covers that were waiting for their file to stop playing. */
    suspend fun flushPendingEmbeds() {
        val playing = nowPlaying.value
        for ((path, cover) in pendingEmbeds.entries.toList()) {
            if (path == playing) continue
            pendingEmbeds.remove(path)
            val track = tracks.get(path) ?: continue
            embed(listOf(track), cover, replace = true)
        }
    }

    /** Candidates for the manual picker, searched by the given terms. */
    suspend fun searchCovers(artist: String, album: String): List<CoverCandidate> =
        if (http.onWifi()) coverSources.candidates(artist, album) else emptyList()

    /** [files]: files updated now or once they stop playing; 0 when embedding is turned off. */
    data class Applied(val cover: File, val files: Int)

    /**
     * Manual pick: becomes the album's cached cover and replaces the art in [targets].
     * Null if the download failed.
     */
    suspend fun applyCover(targets: List<TrackEntity>, candidate: CoverCandidate): Applied? = withContext(Dispatchers.IO) {
        val first = targets.firstOrNull() ?: return@withContext null
        val image = coverSources.download(candidate) ?: return@withContext null
        val file = saveCover(coverKey(first), image, candidate.source)
        val written = embed(targets, file, replace = true)
        Applied(file, written + targets.count { pendingEmbeds.containsKey(it.path) })
    }

    suspend fun lyricsFor(track: TrackEntity, allowNetwork: Boolean): LyricsEntity? = withContext(Dispatchers.IO) {
        lrcFile(track.path)?.let { file ->
            val text = runCatching { file.readText() }.getOrNull()
            if (!text.isNullOrBlank()) {
                val synced = Lrc.looksSynced(text)
                val entity = LyricsEntity(track.path, text.takeIf { synced }, text.takeIf { !synced }, false, SOURCE_FILE, now())
                lyrics.upsert(entity)
                return@withContext entity
            }
        }
        val cached = lyrics.get(track.path)
        if (cached != null && (!cached.isEmpty || !expired(cached.fetchedAt))) return@withContext cached
        if (!allowNetwork || !onlineAllowed()) return@withContext cached
        val title = track.title ?: return@withContext cached
        val artist = track.artist ?: track.albumArtist ?: return@withContext cached
        val found = lrcLib.find(title, artist, track.album, (track.durationMs / 1000).toInt()) ?: return@withContext cached
        LyricsEntity(track.path, found.synced, found.plain, found.instrumental, SOURCE_LRCLIB, now()).also { lyrics.upsert(it) }
    }

    fun coverKey(track: TrackEntity): String = track.albumKey ?: "track:${track.path}"

    /** Search terms for a track: album artist (or artist) and album. */
    fun searchTerms(track: TrackEntity): Pair<String, String>? {
        val artist = track.albumArtist ?: track.artist ?: return null
        val album = track.album ?: return null
        return artist to album
    }

    private suspend fun saveCover(key: String, image: ByteArray, source: String): File {
        val file = File(coverDir, sha1(key) + ".jpg")
        file.writeBytes(TagReader.normalizeCover(image))
        covers.upsert(CoverEntity(key, file.absolutePath, source, now()))
        return file
    }

    private fun lrcFile(path: String): File? {
        val base = path.substringBeforeLast('.')
        return listOf("$base.lrc", "$base.LRC").map(::File).firstOrNull { it.isFile }
    }

    private fun expired(fetchedAt: Long) = now() - fetchedAt > RETRY_AFTER_MS

    private fun now() = System.currentTimeMillis()

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val SOURCE_FILE = "file"
        const val SOURCE_LRCLIB = "lrclib"

        /** Retry "not found" results after a month: catalogs grow. */
        const val RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        private val FOLDER_IMAGES = listOf("cover", "folder", "front", "albumart")
            .flatMap { name -> listOf("jpg", "png", "jpeg").map { "$name.$it" } }
    }
}
