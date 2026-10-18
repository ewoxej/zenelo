package app.zenelo.data.settings

/**
 * Which swipe actions a row can take: one that doesn't apply counts as "Nothing" there — not
 * offered under the finger, and the swipe isn't taken (as for a slot set to Nothing).
 */
object SwipeRules {
    /** What a row is: a track (local file or server track) or a folder. */
    data class Target(
        val remote: Boolean = false,
        val favorite: Boolean = false,
        /** A server track with a copy on the device (downloaded or the user's own). */
        val hasLocalCopy: Boolean = false,
        /** The server knows it: a server track, or a local file linked to one. */
        val onServer: Boolean = false,
        val folder: Boolean = false,
        /** A folder's tracks on the server only (what "Download" would fetch). */
        val cloudInside: Int = 0,
        /** A federated peer's track or folder: played through our server, nothing kept of it here. */
        val peer: Boolean = false,
        /** The server lets this account write tags into its files (ping's `tagEdit`). */
        val serverTags: Boolean = false,
    )

    fun applies(action: SwipeAction, t: Target, serverAvailable: Boolean): Boolean = when {
        t.peer -> action == SwipeAction.ADD_TO_QUEUE || action == SwipeAction.PLAY_NEXT
        else -> appliesHere(action, t, serverAvailable)
    }

    private fun appliesHere(action: SwipeAction, t: Target, serverAvailable: Boolean): Boolean = when (action) {
        SwipeAction.NONE -> false
        SwipeAction.FAVORITE -> !t.favorite
        SwipeAction.DOWNLOAD -> serverAvailable && if (t.folder) t.cloudInside > 0 else t.remote && !t.hasLocalCopy
        SwipeAction.DELETE_FILE -> !t.folder && !t.remote
        SwipeAction.SONIC_PATH -> !t.folder && serverAvailable && t.onServer
        // A server track's tags are written into the server's file, when it allows that.
        SwipeAction.EDIT_TAGS -> !t.folder && (!t.remote || (t.serverTags && serverAvailable))
        SwipeAction.ADD_TO_QUEUE, SwipeAction.PLAY_NEXT, SwipeAction.ADD_TO_PLAYLIST, SwipeAction.HIDE -> true
    }

    fun applies(action: QueueSwipeAction, t: Target, serverAvailable: Boolean): Boolean = when (action) {
        QueueSwipeAction.NONE -> false
        QueueSwipeAction.FAVORITE -> !t.favorite && !t.peer
        QueueSwipeAction.SONIC_PATH -> serverAvailable && t.onServer
        QueueSwipeAction.REMOVE, QueueSwipeAction.PLAY_NEXT, QueueSwipeAction.MOVE_TO_END -> true
    }
}
