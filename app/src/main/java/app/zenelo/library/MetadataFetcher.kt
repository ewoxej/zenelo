package app.zenelo.library

import android.content.Context
import app.zenelo.data.db.CoverEntity
import app.zenelo.data.db.LyricsEntity
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.db.ZeneloDatabase
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.online.CoverCandidate
import app.zenelo.online.CoverSources
import app.zenelo.online.FoundLyrics
import app.zenelo.online.Http
import app.zenelo.online.LrcLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers and lyrics for tracks: local sources first, then the internet (Wi-Fi only, if enabled).
 *
 * Covers: embedded → folder image → cached download → Deezer / iTunes / MusicBrainz. Downloaded
 * covers are written into files lacking art, through [PendingWrites] (the playing file waits for
 * its track to end).
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
    private val pendingWrites: PendingWrites,
) {
    private val covers = db.covers()
    private val lyrics = db.lyrics()
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

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
        val found = findCoverOnline(track)
        if (found == null) {
            // Only remember misses we're sure about: a dropped connection shouldn't block retries.
            if (http.onWifi()) covers.upsert(CoverEntity(key, null, null, now()))
            return@withContext null
        }
        saveCover(key, found.second, found.first.source)
    }

    /**
     * Tags first; then artist / album / title guessed from the file name by the user's patterns
     * (for missing or wrong tags). Without an album the cover is found through the song.
     */
    private suspend fun findCoverOnline(track: TrackEntity): Pair<CoverCandidate, ByteArray>? {
        val tagArtist = track.albumArtist ?: track.artist
        if (tagArtist != null && track.album != null) coverSources.auto(tagArtist, track.album)?.let { return it }
        val guess = guess(track)
        if (guess?.artist != null) {
            if (guess.album != null && !(Text.matches(guess.artist, tagArtist) && Text.matches(guess.album, track.album))) {
                coverSources.auto(guess.artist, guess.album)?.let { return it }
            }
            if (guess.title != null) coverSources.autoBySong(guess.artist, guess.title)?.let { return it }
        }
        if (tagArtist != null && track.album == null && track.title != null) {
            coverSources.autoBySong(tagArtist, track.title)?.let { return it }
        }
        return null
    }

    /** Artist / album / title from the file path, by the patterns in settings. */
    suspend fun guess(track: TrackEntity): PathGuess? =
        FilenamePattern.guess(settings.settings.first().filenamePatterns, track.path)

    /**
     * Writes [cover] into the tracks (only those without art unless [replace]). The playing file
     * gets it when its track ends, but shows it at once (see [PendingWrites]).
     * Returns how many files were (or will be) updated.
     */
    suspend fun embed(targets: List<TrackEntity>, cover: File, replace: Boolean): Int = withContext(Dispatchers.IO) {
        if (!settings.settings.first().embedCovers) return@withContext 0
        targets.count { track ->
            (replace || !track.hasArtwork) && pendingWrites.embedCover(track.path, cover) != PendingWrites.Result.FAILED
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
        Applied(file, embed(targets, file, replace = true))
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
        val seconds = (track.durationMs / 1000).toInt()
        // Tags first, then the file name patterns (missing or wrong tags).
        val attempts = buildList {
            val artist = track.artist ?: track.albumArtist
            if (track.title != null && artist != null) add(Triple(track.title, artist, track.album))
            guess(track)?.let { g -> if (g.title != null && g.artist != null) add(Triple(g.title, g.artist, g.album)) }
        }.distinct()
        if (attempts.isEmpty()) return@withContext cached
        var result: FoundLyrics? = null
        for ((title, artist, album) in attempts) {
            val found = lrcLib.find(title, artist, album, seconds) ?: return@withContext cached // offline: retry later
            result = found
            if (found.synced != null || found.plain != null || found.instrumental) break
        }
        val found = result ?: return@withContext cached
        LyricsEntity(track.path, found.synced, found.plain, found.instrumental, SOURCE_LRCLIB, now()).also { lyrics.upsert(it) }
    }

    fun coverKey(track: TrackEntity): String = track.albumKey ?: "track:${track.path}"

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
