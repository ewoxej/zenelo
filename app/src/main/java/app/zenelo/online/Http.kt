package app.zenelo.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Shared HTTP client. Every online feature checks [onWifi] first: downloads are Wi-Fi only. */
class Http(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Also the base of other clients (mStream), sharing the connection pool. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    fun onWifi(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Body as text, or null on any failure. `404` is a normal "not found" for most APIs here. */
    suspend fun getText(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }

    suspend fun getObject(url: HttpUrl): JSONObject? = getText(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    suspend fun getArray(url: HttpUrl): JSONArray? = getText(url)?.let { runCatching { JSONArray(it) }.getOrNull() }

    suspend fun getBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
    }

    companion object {
        // TODO: MusicBrainz asks for contact info in the UA; add a project URL once there is one.
        const val USER_AGENT = "Zenelo/1.0.0 (Android audio player)"
    }
}

/** Enforces a minimum gap between calls (MusicBrainz allows 1 request/s). */
class RateLimiter(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = last + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            last = System.currentTimeMillis()
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
