package app.zenelo.mstream

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import app.zenelo.online.Http
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * The players' way to the mStream server. A server track's MediaItem URI is
 * `mstream://server?p=<vpath/rel>` (see [uri]); when ExoPlayer opens it, it becomes
 * `<server>/media/<vpath/rel>` with the login token as a header. Local files pass through.
 */
@OptIn(UnstableApi::class)
class RemoteMedia(private val client: MStreamClient, private val loadAccount: suspend () -> MStreamAccount?) {
    /** The current login, pushed from settings; read on the loader thread. */
    @Volatile
    var account: MStreamAccount? = null

    fun dataSources(context: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        return ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http)) { spec ->
            if (spec.uri.scheme != SCHEME) return@Factory spec
            val serverPath = spec.uri.getQueryParameter("p") ?: throw IOException("No mStream path in ${spec.uri}")
            // The service may open a restored queue before settings arrived: read them once.
            val login = account ?: runBlocking { loadAccount() }?.also { account = it }
                ?: throw IOException("Not connected to an mStream server")
            spec.buildUpon()
                .setUri(Uri.parse(client.mediaUrl(login, serverPath).toString()))
                .setHttpRequestHeaders(spec.httpRequestHeaders + ("x-access-token" to login.token))
                .build()
        }
    }

    companion object {
        private const val SCHEME = "mstream"

        /** The MediaItem URI for one of our `mstream://` paths (query-encoded: names carry '#', '?'). */
        fun uri(path: String): Uri =
            Uri.Builder().scheme(SCHEME).authority("server").appendQueryParameter("p", MStreamPaths.serverPath(path)).build()
    }
}
