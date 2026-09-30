package app.zenelo.library

import app.zenelo.data.db.AlbumRow
import app.zenelo.data.db.ArtistRow
import app.zenelo.data.db.TrackEntity
import app.zenelo.data.settings.SortField
import app.zenelo.data.settings.SortOrder
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sorting and section headers of the library pages. Text compares like a human would (case and
 * accents ignored); unknown values (no artist, no album) always go last, in either direction.
 */
object LibrarySort {
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }

    fun trackName(track: TrackEntity): String = track.title ?: track.path.substringAfterLast('/').substringBeforeLast('.')

    fun albums(albums: List<AlbumRow>, order: SortOrder): List<AlbumRow> = when (order.sortBy) {
        SortField.ARTIST -> albums.sortedWith(text<AlbumRow>(order) { it.artist }.then(text<AlbumRow>(SortOrder(SortField.NAME)) { it.album }))
        SortField.DATE_ADDED -> albums.sortedWith(number<AlbumRow>(order) { it.added })
        SortField.TRACKS -> albums.sortedWith(number<AlbumRow>(order) { it.tracks.toLong() }.then(text<AlbumRow>(SortOrder(SortField.NAME)) { it.album }))
        else -> albums.sortedWith(text<AlbumRow>(order) { it.album })
    }

    fun artists(artists: List<ArtistRow>, order: SortOrder): List<ArtistRow> = when (order.sortBy) {
        SortField.DATE_ADDED -> artists.sortedWith(number<ArtistRow>(order) { it.added })
        SortField.TRACKS -> artists.sortedWith(number<ArtistRow>(order) { it.tracks.toLong() }.then(text<ArtistRow>(SortOrder(SortField.NAME)) { it.name }))
        SortField.ALBUMS -> artists.sortedWith(number<ArtistRow>(order) { it.albums.toLong() }.then(text<ArtistRow>(SortOrder(SortField.NAME)) { it.name }))
        else -> artists.sortedWith(text<ArtistRow>(order) { it.name })
    }

    fun tracks(tracks: List<TrackEntity>, order: SortOrder): List<TrackEntity> {
        val byName = text<TrackEntity>(SortOrder(SortField.NAME), ::trackName)
        return when (order.sortBy) {
            SortField.ARTIST -> tracks.sortedWith(text<TrackEntity>(order) { it.artist ?: it.albumArtist }.then(byName))
            SortField.ALBUM -> tracks.sortedWith(text<TrackEntity>(order) { it.album }.then(compareBy<TrackEntity> { it.trackNumber ?: Int.MAX_VALUE }).then(byName))
            SortField.DATE_ADDED -> tracks.sortedWith(number<TrackEntity>(order) { it.modified })
            SortField.DURATION -> tracks.sortedWith(number<TrackEntity>(order) { it.durationMs })
            else -> tracks.sortedWith(text<TrackEntity>(order, ::trackName))
        }
    }

    /**
     * Header of a track in the "All tracks" list: the first letter of the sorted-by text, or the
     * month for date sorts; null where headers make no sense (duration).
     */
    fun trackHeader(track: TrackEntity, order: SortOrder): String? = when (order.sortBy) {
        SortField.ARTIST -> letter(track.artist ?: track.albumArtist)
        SortField.ALBUM -> letter(track.album)
        SortField.DATE_ADDED -> month(track.modified)
        SortField.DURATION -> null
        else -> letter(trackName(track))
    }

    /** Headers that are single letters get the A–Z rail. */
    fun hasLetterHeaders(order: SortOrder) = order.sortBy in setOf(SortField.NAME, SortField.ARTIST, SortField.ALBUM)

    /**
     * The first letter without accents, as the collator compares ("É" and "Ё" sort with "E" / "Е":
     * headers of their own would repeat a letter further down), else "#".
     */
    fun letter(text: String?): String {
        val c = text?.trimStart()?.firstOrNull() ?: return "#"
        if (!c.isLetter()) return "#"
        val base = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).first()
        return base.uppercase()
    }

    private val monthFormat = SimpleDateFormat("MMM yyyy", Locale.ENGLISH)

    fun month(millis: Long): String = synchronized(monthFormat) { monthFormat.format(Date(millis)).uppercase() }

    private fun <T> text(order: SortOrder, key: (T) -> String?): Comparator<T> = Comparator { a, b ->
        val x = key(a)?.takeIf { it.isNotBlank() }
        val y = key(b)?.takeIf { it.isNotBlank() }
        when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            else -> collator.compare(x, y).let { if (order.descending) -it else it }
        }
    }

    private fun <T> number(order: SortOrder, key: (T) -> Long): Comparator<T> =
        if (order.descending) compareByDescending(key) else compareBy(key)
}
