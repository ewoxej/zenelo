package app.zenelo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.zenelo.data.settings.SwipeAction
import app.zenelo.data.settings.SwipeSlot
import app.zenelo.ui.theme.ZeneloColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SHORT_THRESHOLD = 0.18f
private const val LONG_THRESHOLD = 0.5f

/** What a swipe slot shows and does. */
data class SwipeOption(val label: String, val icon: ImageVector, val accent: Color)

/**
 * Row with two thresholds per direction (short / long swipe), one [SwipeOption] per [SwipeSlot].
 * Crossing a threshold gives a haptic tick; releasing fires [onSwipe] and snaps back.
 *
 * A direction without options isn't captured at all, so the drag goes to the parent: queue rows
 * (left swipes only) leave right swipes to the lyrics / cover / queue pager.
 */
@Composable
fun SwipeableRow(
    options: Map<SwipeSlot, SwipeOption>,
    onSwipe: (SwipeSlot) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val currentOptions by rememberUpdatedState(options)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val hasRight = options.keys.any { it == SwipeSlot.RIGHT_SHORT || it == SwipeSlot.RIGHT_LONG }
    val hasLeft = options.keys.any { it == SwipeSlot.LEFT_SHORT || it == SwipeSlot.LEFT_LONG }

    val slot by remember {
        derivedStateOf {
            if (width == 0) return@derivedStateOf null
            val fraction = offset.value / width
            when {
                fraction >= LONG_THRESHOLD -> SwipeSlot.RIGHT_LONG
                fraction >= SHORT_THRESHOLD -> SwipeSlot.RIGHT_SHORT
                fraction <= -LONG_THRESHOLD -> SwipeSlot.LEFT_LONG
                fraction <= -SHORT_THRESHOLD -> SwipeSlot.LEFT_SHORT
                else -> null
            }
        }
    }
    LaunchedEffect(slot) {
        if (slot != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .pointerInput(enabled, hasLeft, hasRight) {
                if (!enabled || (!hasLeft && !hasRight)) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Claim the drag only in a direction we have actions for.
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        if ((over > 0 && hasRight) || (over < 0 && hasLeft)) {
                            change.consume()
                            scope.launch { offset.snapTo(clamp(offset.value + over, hasLeft, hasRight)) }
                        }
                    } ?: return@awaitEachGesture
                    val completed = horizontalDrag(start.id) { change ->
                        // Read before consuming: a consumed change reports a zero position change.
                        val delta = change.positionChange().x
                        change.consume()
                        // Read the offset inside the coroutine: snaps run later, in order, and must accumulate.
                        scope.launch { offset.snapTo(clamp(offset.value + delta, hasLeft, hasRight)) }
                    }
                    if (completed) slot?.takeIf { it in currentOptions }?.let(currentOnSwipe)
                    scope.launch { offset.animateTo(0f) }
                }
            },
    ) {
        SwipeBackground(
            option = slot?.let { options[it] },
            isLong = slot == SwipeSlot.RIGHT_LONG || slot == SwipeSlot.LEFT_LONG,
            toRight = offset.value > 0,
            visible = abs(offset.value) > 1f,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
        }
    }
}

private fun clamp(value: Float, hasLeft: Boolean, hasRight: Boolean): Float = when {
    !hasRight -> value.coerceAtMost(0f)
    !hasLeft -> value.coerceAtLeast(0f)
    else -> value
}

/** Options for the four list slots from the user's swipe settings ([SwipeAction.NONE] = no option). */
fun listSwipeOptions(swipes: Map<SwipeSlot, SwipeAction>): Map<SwipeSlot, SwipeOption> =
    swipes.filterValues { it != SwipeAction.NONE }.mapValues { (_, a) -> SwipeOption(a.label, a.icon, a.accent) }

@Composable
private fun SwipeBackground(
    option: SwipeOption?,
    isLong: Boolean,
    toRight: Boolean,
    visible: Boolean,
    modifier: Modifier,
) {
    if (!visible) return
    val color = option?.accent?.copy(alpha = if (isLong) 0.32f else 0.16f)?.compositeOver(ZeneloColors.Background) ?: ZeneloColors.Card
    Row(
        modifier.background(color).padding(horizontal = 20.dp),
        horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (option != null) {
            Icon(option.icon, null, tint = option.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(option.label, style = MaterialTheme.typography.labelMedium, color = ZeneloColors.TextPrimary)
        }
    }
}

/**
 * [options] without the actions that don't apply to the row's track ([path]) or folder ([folder]):
 * they count as Nothing there (see [app.zenelo.data.settings.SwipeRules]) — a liked track isn't
 * offered "Add to favorites", a track on the device "Download", a server track "Delete file".
 */
@Composable
fun applicableSwipes(
    options: Map<SwipeSlot, SwipeOption>,
    swipes: Map<SwipeSlot, SwipeAction>,
    path: String?,
    folder: Boolean = false,
    cloudInside: Int = 0,
): Map<SwipeSlot, SwipeOption> {
    val target = rememberSwipeTarget(path, folder, cloudInside)
    val available by appContainer().sonicPath.available.collectAsStateWithLifecycle()
    return remember(options, swipes, target, available) {
        options.filterKeys { slot -> swipes[slot]?.let { app.zenelo.data.settings.SwipeRules.applies(it, target, available) } == true }
    }
}

/** The queue's left swipes, [applicableSwipes]-style. */
@Composable
fun applicableQueueSwipes(
    options: Map<SwipeSlot, SwipeOption>,
    swipes: Map<app.zenelo.data.settings.QueueSwipeSlot, app.zenelo.data.settings.QueueSwipeAction>,
    path: String?,
): Map<SwipeSlot, SwipeOption> {
    val target = rememberSwipeTarget(path, folder = false, cloudInside = 0)
    val available by appContainer().sonicPath.available.collectAsStateWithLifecycle()
    return remember(options, swipes, target, available) {
        options.filterKeys { slot ->
            swipes.entries.firstOrNull { it.key.slot == slot }?.value?.let { app.zenelo.data.settings.SwipeRules.applies(it, target, available) } == true
        }
    }
}

@Composable
private fun rememberSwipeTarget(path: String?, folder: Boolean, cloudInside: Int): app.zenelo.data.settings.SwipeRules.Target {
    val container = appContainer()
    val favorites by container.favoritePaths.collectAsStateWithLifecycle()
    val links by container.serverLinks.links.collectAsStateWithLifecycle()
    return remember(path, folder, cloudInside, favorites, links) {
        val remote = app.zenelo.mstream.MStreamPaths.isRemote(path)
        app.zenelo.data.settings.SwipeRules.Target(
            remote = remote,
            // A song liked under either of its paths (server track / local copy) is liked.
            favorite = path != null && (path in favorites || links.copiesOf(path).any { it in favorites }),
            hasLocalCopy = remote && links.localOf(path) != null,
            onServer = path != null && links.serverOf(path) != null,
            folder = folder,
            cloudInside = cloudInside,
            peer = path != null && (app.zenelo.mstream.MStreamPaths.isPeer(path) || app.zenelo.mstream.RemoteFolders.isPeer(java.io.File(path))),
        )
    }
}
