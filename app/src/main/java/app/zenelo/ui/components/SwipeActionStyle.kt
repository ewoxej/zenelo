package app.zenelo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.zenelo.data.settings.QueueSwipeAction
import app.zenelo.data.settings.SwipeAction
import app.zenelo.ui.theme.ZeneloColors

/** Icon and accent per action, shared by swipe settings, swipe backgrounds, row menus and snackbars. */
val SwipeAction.icon: ImageVector
    get() = when (this) {
        SwipeAction.NONE -> Icons.Rounded.Block
        SwipeAction.ADD_TO_QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
        SwipeAction.PLAY_NEXT -> Icons.AutoMirrored.Rounded.PlaylistPlay
        SwipeAction.FAVORITE -> Icons.Outlined.FavoriteBorder
        SwipeAction.ADD_TO_PLAYLIST -> Icons.AutoMirrored.Rounded.PlaylistAdd
        SwipeAction.REMOVE_FROM_LIST -> Icons.Outlined.RemoveCircleOutline
        SwipeAction.HIDE -> Icons.Outlined.VisibilityOff
        SwipeAction.DELETE_FILE -> Icons.Outlined.DeleteOutline
    }

val SwipeAction.accent: Color
    get() = when (this) {
        SwipeAction.NONE, SwipeAction.HIDE -> ZeneloColors.TextMuted
        SwipeAction.ADD_TO_QUEUE, SwipeAction.PLAY_NEXT, SwipeAction.ADD_TO_PLAYLIST -> ZeneloColors.Mustard
        SwipeAction.FAVORITE -> ZeneloColors.Info
        SwipeAction.REMOVE_FROM_LIST, SwipeAction.DELETE_FILE -> ZeneloColors.Danger
    }

val QueueSwipeAction.icon: ImageVector
    get() = when (this) {
        QueueSwipeAction.NONE -> Icons.Rounded.Block
        QueueSwipeAction.REMOVE -> Icons.Outlined.RemoveCircleOutline
        QueueSwipeAction.PLAY_NEXT -> Icons.AutoMirrored.Rounded.PlaylistPlay
        QueueSwipeAction.MOVE_TO_END -> Icons.Rounded.VerticalAlignBottom
        QueueSwipeAction.FAVORITE -> Icons.Outlined.FavoriteBorder
    }

val QueueSwipeAction.accent: Color
    get() = when (this) {
        QueueSwipeAction.NONE -> ZeneloColors.TextMuted
        QueueSwipeAction.REMOVE -> ZeneloColors.Danger
        QueueSwipeAction.PLAY_NEXT, QueueSwipeAction.MOVE_TO_END -> ZeneloColors.Mustard
        QueueSwipeAction.FAVORITE -> ZeneloColors.Info
    }
