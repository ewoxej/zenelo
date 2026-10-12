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
    )

    fun applies(action: SwipeAction, t: Target, serverAvailable: Boolean): Boolean = when (action) {
        SwipeAction.NONE -> false
        SwipeAction.FAVORITE -> !t.favorite
        SwipeAction.DOWNLOAD -> serverAvailable && if (t.folder) t.cloudInside > 0 else t.remote && !t.hasLocalCopy
        SwipeAction.DELETE_FILE -> !t.folder && !t.remote
        SwipeAction.SONIC_PATH -> !t.folder && serverAvailable && t.onServer
        SwipeAction.ADD_TO_QUEUE, SwipeAction.PLAY_NEXT, SwipeAction.ADD_TO_PLAYLIST, SwipeAction.HIDE -> true
    }

    fun applies(action: QueueSwipeAction, t: Target, serverAvailable: Boolean): Boolean = when (action) {
        QueueSwipeAction.NONE -> false
        QueueSwipeAction.FAVORITE -> !t.favorite
        QueueSwipeAction.SONIC_PATH -> serverAvailable && t.onServer
        QueueSwipeAction.REMOVE, QueueSwipeAction.PLAY_NEXT, QueueSwipeAction.MOVE_TO_END -> true
    }
}
