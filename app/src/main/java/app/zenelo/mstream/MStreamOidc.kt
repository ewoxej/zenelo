package app.zenelo.mstream

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.zenelo.data.settings.SettingsRepository
import app.zenelo.work.LibraryWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import android.util.Base64

/**
 * Sign-in to mStream through its OIDC provider (Authelia, …; the fork's `api/oidc.js`): the
 * browser opens the server's start page, the provider signs the user in, and the server hands
 * the app back [OIDC_REDIRECT]`?code=…&state=…` (MainActivity's intent filter) — a one-time
 * code traded here for the account's token. The sign-in in flight (server, state) is kept in
 * preferences: Android may drop the app while the browser is in front.
 */
class MStreamOidc(
    private val context: Context,
    private val client: MStreamClient,
    private val settings: SettingsRepository,
    private val messages: MutableSharedFlow<String>,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("mstream_oidc", Context.MODE_PRIVATE)
    private val _busy = MutableStateFlow(false)
    /** A code is being traded for the token. */
    val busy: StateFlow<Boolean> = _busy

    /** Opens the browser on the server's sign-in; false when there's no browser or no address. */
    fun start(activity: Context, url: String): Boolean {
        val state = random()
        val start = client.oidcStartUrl(url, state) ?: return false
        prefs.edit().putString(KEY_URL, url.trim()).putString(KEY_STATE, state).putLong(KEY_AT, System.currentTimeMillis()).apply()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(start)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (activity !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { activity.startActivity(intent) }.isSuccess
    }

    /** The server came back ([uri] is [OIDC_REDIRECT]…); false when [uri] isn't ours. */
    fun handle(uri: Uri?): Boolean {
        if (uri == null || "${uri.scheme}://${uri.host}" != OIDC_REDIRECT) return false
        val url = prefs.getString(KEY_URL, null)
        val state = prefs.getString(KEY_STATE, null)
        val fresh = System.currentTimeMillis() - prefs.getLong(KEY_AT, 0) < FLOW_TTL_MS
        prefs.edit().clear().apply()
        if (url == null || state == null || uri.getQueryParameter("state") != state || !fresh) {
            messages.tryEmit("That sign-in expired — try again")
            return true
        }
        uri.getQueryParameter("error")?.let {
            Log.w("Zenelo", "mStream OIDC sign-in refused: $it")
            messages.tryEmit(REASONS[it] ?: "Sign-in failed ($it)")
            return true
        }
        val code = uri.getQueryParameter("code") ?: return true.also { messages.tryEmit("Sign-in failed: no code") }
        _busy.value = true
        scope.launch {
            runCatching { client.oidcExchange(url, code) }
                .onSuccess { account ->
                    settings.setMStream(account)
                    LibraryWork.syncServer(context, force = true)
                    messages.emit("Connected to ${account.url} as ${account.username}")
                }
                .onFailure {
                    Log.w("Zenelo", "mStream OIDC code exchange failed", it)
                    messages.emit(it.message ?: "Couldn't finish the sign-in")
                }
            _busy.value = false
        }
        return true
    }

    private fun random(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_STATE = "state"
        const val KEY_AT = "at"
        const val FLOW_TTL_MS = 10 * 60 * 1000L
        // The server's refusal codes (api/oidc.js REASONS).
        val REASONS = mapOf(
            "no-account" to "There is no mStream account with your username",
            "not-in-group" to "Your account isn't allowed to use this server",
            "no-username" to "The sign-in provider didn't say who you are",
            "state" to "That sign-in expired — try again",
            "provider" to "The sign-in provider refused or didn't answer",
            "off" to "Single sign-on is turned off on the server",
        )
    }
}
