package app.zenelo.mstream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import app.zenelo.data.settings.TranscodeMode
import app.zenelo.online.Http
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * The players' way to the mStream server. A server track's MediaItem URI is
 * `mstream://server?p=<vpath/rel>` (see [uri]); when ExoPlayer opens it, it becomes its downloaded /
 * cached copy if any, else `<server>/media/<vpath/rel>` (or `/transcode/…` per the setting) with the
 * login token as a header. Local files pass through.
 */
@OptIn(UnstableApi::class)
class RemoteMedia(
    private val context: Context,
    private val client: MStreamClient,
    private val files: MStreamFiles,
    private val loadAccount: suspend () -> MStreamAccount?,
) {
    /** The current login, pushed from settings; read on the loader thread. */
    @Volatile
    var account: MStreamAccount? = null

    /** Transcoding (from settings): mode, codec, bitrate. */
    @Volatile
    var transcode: Triple<TranscodeMode, String, String> = Triple(TranscodeMode.OFF, "mp3", "128k")

    fun dataSources(context: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        return ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val serverPath = spec.uri.getQueryParameter("p") ?: throw IOException("No mStream path in ${spec.uri}")
            // Downloaded or in the queue cache: the file, no network.
            files.localCopy(MStreamPaths.of(serverPath))?.let {
                Log.i(TAG, "open ${serverPath.substringAfterLast('/')} from ${it.path}")
                return@Factory spec.buildUpon().setUri(Uri.fromFile(it)).build()
            }
            // The service may open a restored queue before settings arrived: read them once.
            val login = account ?: runBlocking { loadAccount() }?.also { account = it }
                ?: throw IOException("Not connected to an mStream server")
            val (mode, codec, bitrate) = transcode
            val transcoded = mode == TranscodeMode.ALWAYS || (mode == TranscodeMode.MOBILE && metered())
            val url = if (transcoded) client.transcodeUrl(login, serverPath, codec, bitrate) else client.mediaUrl(login, serverPath)
            Log.i(TAG, "open ${serverPath.substringAfterLast('/')} streamed" + if (transcoded) " as $codec $bitrate" else "")
            spec.buildUpon()
                .setUri(Uri.parse(url.toString()))
                .setHttpRequestHeaders(spec.httpRequestHeaders + ("x-access-token" to login.token))
                .build()
        }
    }

    private fun metered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        private const val SCHEME = "mstream"
        private const val TAG = "Zenelo"

        /** The MediaItem URI for one of our `mstream://` paths (query-encoded: names carry '#', '?'). */
        fun uri(path: String): Uri =
            Uri.Builder().scheme(SCHEME).authority("server").appendQueryParameter("p", MStreamPaths.serverPath(path)).build()
    }
}
