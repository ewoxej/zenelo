package app.zenelo.library

import app.zenelo.mstream.MStreamPaths

/** Where a track or a set of tracks is, for the marks in lists. */
enum class StorageMark {
    /** On the device only (or nothing to say): no mark. */
    NONE,

    /** On the mStream server only. */
    CLOUD,

    /** Partly on the server only: some of it still in the cloud. */
    PARTIAL,

    /** On the device and on the server (downloaded, or a file of the user's that matched one). */
    DOWNLOADED,
}

/** How many of a set's tracks are on the server only ([cloud]) and on both ([downloaded]). */
data class Storage(val tracks: Int = 0, val cloud: Int = 0, val downloaded: Int = 0) {
    val mark: StorageMark
        get() = when {
            tracks == 0 -> StorageMark.NONE
            cloud == tracks -> StorageMark.CLOUD
            cloud > 0 -> StorageMark.PARTIAL
            downloaded > 0 -> StorageMark.DOWNLOADED
            else -> StorageMark.NONE
        }

    operator fun plus(other: Storage) = Storage(tracks + other.tracks, cloud + other.cloud, downloaded + other.downloaded)

    companion object {
        /**
         * One track: [linked] holds the paths with a copy on the other side (`ServerLinks`, both
         * the local and the server paths). A server track without one is cloud only.
         */
        fun of(path: String, linked: Set<String>): Storage = when {
            path in linked -> Storage(1, 0, 1)
            MStreamPaths.isRemote(path) -> Storage(1, 1, 0)
            else -> Storage(1, 0, 0)
        }

        fun of(paths: Iterable<String>, linked: Set<String>): Storage = paths.fold(Storage()) { acc, p -> acc + of(p, linked) }

        fun markOf(path: String, linked: Set<String>): StorageMark = of(path, linked).mark
    }
}
