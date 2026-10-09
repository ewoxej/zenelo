package app.zenelo.player

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * For the app's long-lived scopes (playback service, queue, app): an exception nobody caught in
 * one of their jobs — a file whose tags or cover can't be read, a server answer we didn't expect —
 * is logged instead of taking the whole app (and the music) down.
 */
fun logUncaught(scope: String) = CoroutineExceptionHandler { _, e ->
    if (e !is CancellationException) Log.e("Zenelo", "uncaught in $scope", e)
}
