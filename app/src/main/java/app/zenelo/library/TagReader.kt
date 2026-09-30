package app.zenelo.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.zenelo.data.db.TrackEntity
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads tags and stream info with jaudiotagger, and embeds cover art.
 *
 * jaudiotagger runs in Android mode: its desktop image code (javax.imageio) doesn't exist here, so
 * artwork dimensions are measured with [BitmapFactory] and FLAC pictures are built explicitly.
 */
object TagReader {

    init {
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /** Formats we write covers into. Ogg/Opus would need image decoding inside jaudiotagger. */
    private val embeddable = setOf("flac", "mp3", "m4a", "mp4", "aac", "alac", "wav", "aif", "aiff", "dsf")

    fun canEmbed(path: String) = path.substringAfterLast('.', "").lowercase() in embeddable

    /** Tags plus embedded lyrics (if any), read in one pass. */
    data class TagData(val track: TrackEntity, val lyrics: String?)

    /** Always returns an entity; files jaudiotagger can't parse get file-level info only. */
    fun read(file: File): TagData {
        val parsed = runCatching { AudioFileIO.read(file) }.getOrNull()
        val header = parsed?.audioHeader
        val tag = parsed?.tag

        val title = tag.field(FieldKey.TITLE)
        val artist = tag.field(FieldKey.ARTIST)
        val album = tag.field(FieldKey.ALBUM)
        val albumArtist = tag.field(FieldKey.ALBUM_ARTIST)
        val track = TrackEntity(
            path = file.absolutePath,
            dir = file.parent.orEmpty(),
            modified = file.lastModified(),
            size = file.length(),
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = tag.field(FieldKey.TRACK)?.substringBefore('/')?.trim()?.toIntOrNull(),
            // VBR MP3 without a Xing / VBRI frame: jaudiotagger's length is a guess, count the frames.
            durationMs = (if (file.extension.equals("mp3", ignoreCase = true)) Mp3Scan.durationMs(file) else null)
                ?: header?.let { (it.preciseTrackLength * 1000).toLong() } ?: 0L,
            sampleRate = header?.sampleRateAsNumber ?: 0,
            bitsPerSample = header?.bitsPerSample ?: 0,
            bitrateKbps = header?.bitRateAsNumber?.toInt() ?: 0,
            lossless = header?.isLossless ?: false,
            hasArtwork = runCatching { tag?.artworkList?.isNotEmpty() == true }.getOrDefault(false),
            trackGainDb = tag?.replayGain("REPLAYGAIN_TRACK_GAIN"),
            albumGainDb = tag?.replayGain("REPLAYGAIN_ALBUM_GAIN"),
            albumKey = albumKey(albumArtist ?: artist, album),
            trackPeak = tag?.replayGain("REPLAYGAIN_TRACK_PEAK"),
            albumPeak = tag?.replayGain("REPLAYGAIN_ALBUM_PEAK"),
            genre = runCatching { tag?.getAll(FieldKey.GENRE) }.getOrNull().orEmpty()
                .flatMap(Genres::split).distinct().joinToString("; ").ifEmpty { null },
        )
        return TagData(track, tag.field(FieldKey.LYRICS))
    }

    /**
     * Replaces the file's cover art. Returns false when the format isn't supported or the write
     * didn't stick; the caller keeps the cover in its cache either way.
     */
    fun embedArtwork(file: File, image: ByteArray): Boolean {
        if (!canEmbed(file.path)) return false
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            if (bounds.outWidth <= 0) return false
            val mime = if (image.isPng()) "image/png" else "image/jpeg"

            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.deleteArtworkField()
            if (tag is FlacTag) {
                tag.setField(
                    tag.createArtworkField(image, PictureTypes.DEFAULT_ID, mime, "", bounds.outWidth, bounds.outHeight, 24, 0),
                )
            } else {
                if (tag is VorbisCommentTag) return false
                val artwork = ArtworkFactory.getNew().apply {
                    setBinaryData(image)
                    setMimeType(mime)
                    setPictureType(PictureTypes.DEFAULT_ID)
                    setWidth(bounds.outWidth)
                    setHeight(bounds.outHeight)
                }
                tag.setField(artwork)
            }
            audio.commit()
            AudioFileIO.read(file).tag?.artworkList?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    /** User-editable text tags; empty string = no value. */
    data class EditableTags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        val trackNumber: String = "",
        val year: String = "",
        val genre: String = "",
    )

    private val editableKeys = listOf(
        FieldKey.TITLE to EditableTags::title,
        FieldKey.ARTIST to EditableTags::artist,
        FieldKey.ALBUM to EditableTags::album,
        FieldKey.ALBUM_ARTIST to EditableTags::albumArtist,
        FieldKey.TRACK to EditableTags::trackNumber,
        FieldKey.YEAR to EditableTags::year,
        FieldKey.GENRE to EditableTags::genre,
    )

    fun readEditable(file: File): EditableTags? = runCatching {
        val tag = AudioFileIO.read(file).tag
        EditableTags(
            title = tag.field(FieldKey.TITLE).orEmpty(),
            artist = tag.field(FieldKey.ARTIST).orEmpty(),
            album = tag.field(FieldKey.ALBUM).orEmpty(),
            albumArtist = tag.field(FieldKey.ALBUM_ARTIST).orEmpty(),
            trackNumber = tag.field(FieldKey.TRACK).orEmpty(),
            year = tag.field(FieldKey.YEAR).orEmpty(),
            genre = tag.field(FieldKey.GENRE).orEmpty(),
        )
    }.getOrNull()

    /** Writes the text tags (empty fields are removed). Returns false if the format can't be written. */
    fun writeEditable(file: File, tags: EditableTags): Boolean = runCatching {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        for ((key, getter) in editableKeys) {
            val value = getter.get(tags).trim()
            if (value.isEmpty()) runCatching { tag.deleteField(key) } else tag.setField(key, value)
        }
        audio.commit()
        AudioFileIO.read(file).tag.field(FieldKey.TITLE).orEmpty() == tags.title.trim()
    }.getOrDefault(false)

    /**
     * Downscales very large covers before storing / embedding: 1200px is plenty for a 4.7" screen
     * and keeps files from growing by megabytes.
     */
    fun normalizeCover(image: ByteArray, maxSide: Int = 1200): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (side <= 0 || (side <= maxSide && image.size <= 800_000)) return image
        var sample = 1
        while (side / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return image
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    /** "nordic lights|harbor" — shared by all tracks of an album; null when the album is unknown. */
    fun albumKey(artist: String?, album: String?): String? {
        val a = artist?.let(Text::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        val b = album?.let(Text::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        return "$a|$b"
    }

    private fun Tag?.field(key: FieldKey): String? =
        this?.let { runCatching { it.getFirst(key) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

    /** Vorbis / MP4 keep ReplayGain as named fields, ID3 in TXXX frames. Values look like "-6.53 dB". */
    private fun Tag.replayGain(name: String): Float? {
        val raw = if (this is AbstractID3v2Tag) {
            runCatching { getFields("TXXX") }.getOrNull().orEmpty()
                .mapNotNull { (it as? AbstractID3v2Frame)?.body as? FrameBodyTXXX }
                .firstOrNull { it.description.equals(name, ignoreCase = true) }
                ?.firstTextValue
        } else {
            listOf(name, name.lowercase(), "----:com.apple.iTunes:$name", "----:com.apple.iTunes:${name.lowercase()}")
                .firstNotNullOfOrNull { key -> runCatching { getFirst(key) }.getOrNull()?.takeIf { it.isNotBlank() } }
        }
        return raw?.replace("dB", "", ignoreCase = true)?.trim()?.toFloatOrNull()
    }

    private fun ByteArray.isPng() = size > 4 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte()
}

/** Loose text normalization for matching names across tags and online catalogs. */
object Text {
    private val bracketed = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** "The Dark Side of the Moon (2011 Remaster)" → "the dark side of the moon". */
    fun normalize(s: String): String =
        s.lowercase()
            .replace(bracketed, "")
            .replace("&", " and ")
            .replace(nonWord, " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /**
     * Same after normalization (a leading "the" ignored), or one starts the other at a word
     * boundary: editions ("Album Deluxe"), "feat." credits ("Artist feat X"). Not a word inside
     * another name: "music" doesn't match "vedicdhvani music".
     */
    fun matches(a: String?, b: String?): Boolean {
        val x = a?.let(::normalize)?.removePrefix("the ").orEmpty()
        val y = b?.let(::normalize)?.removePrefix("the ").orEmpty()
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.startsWith("$y ") || y.startsWith("$x ")
    }
}
