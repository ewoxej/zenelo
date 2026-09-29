package app.zenelo.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Add to playlist" from anywhere (swipes, ⋮ menus, selections, Now Playing): the tracks wait
 * here while the root shows the playlist picker.
 */
class PlaylistPicker {
    private val _request = MutableStateFlow<List<String>?>(null)
    val request: StateFlow<List<String>?> = _request.asStateFlow()

    fun pick(paths: List<String>) {
        if (paths.isNotEmpty()) _request.value = paths
    }

    fun dismiss() {
        _request.value = null
    }
}
