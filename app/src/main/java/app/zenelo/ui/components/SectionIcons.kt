package app.zenelo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import app.zenelo.data.settings.Section

/** The icon of a section in the bottom bar, Home shortcuts and card headers. */
val Section.icon: ImageVector
    get() = when (this) {
        Section.HOME -> Icons.Outlined.Home
        Section.FOLDERS -> Icons.Outlined.Folder
        Section.FAVORITES -> Icons.Outlined.FavoriteBorder
        Section.PLAYLISTS -> Icons.AutoMirrored.Rounded.QueueMusic
        Section.SETTINGS -> Icons.Outlined.Settings
        Section.ALBUMS -> Icons.Outlined.Album
        Section.ARTISTS -> Icons.Outlined.Person
        Section.TRACKS -> Icons.Outlined.MusicNote
        Section.RECENT -> Icons.Outlined.History
    }
