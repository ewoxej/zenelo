package app.zenelo.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.zenelo.data.db.TrackEntity
import app.zenelo.library.AudioFile
import app.zenelo.mstream.MStreamPaths
import app.zenelo.mstream.RemoteMedia
import java.io.File

/**
 * mediaId is the absolute file path; the session rebuilds the URI from requestMetadata.
 * Only displayTitle is set: ExoPlayer prefers MediaItem fields over the file's own tags, so setting
 * title here would hide the tag title.
 */
/** [cover]: folder image or downloaded cover for files without embedded art (shown in the notification too). */
fun AudioFile.toMediaItem(info: TrackEntity? = null, cover: File? = null): MediaItem {
    // mStream tracks: resolved to the server's URL (with the login) by RemoteMedia when opened.
    val uri = if (MStreamPaths.isRemote(path)) RemoteMedia.uri(path) else Uri.fromFile(File(path))
    return MediaItem.Builder()
        .setMediaId(path)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setDisplayTitle(title)
                // Values from our own tag index are the file's tags, so these don't hide anything;
                // they let the queue show titles and artists before a track has been loaded.
                .setTitle(info?.title)
                .setArtist(info?.artist)
                .setAlbumTitle(info?.album)
                .setAlbumArtist(info?.albumArtist)
                .setArtworkUri(cover?.let(Uri::fromFile))
                // Our length (exact for MP3s the player can only estimate): used while the player has none.
                .setDurationMs(info?.durationMs?.takeIf { it > 0 })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()
}
