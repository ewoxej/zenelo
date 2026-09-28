package app.zenelo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
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

/**
 * Row with two thresholds per direction (short / long swipe), each mapped to a [SwipeSlot].
 * Crossing a threshold gives a haptic tick; releasing fires the action and snaps back.
 */
@Composable
fun SwipeableRow(
    actions: Map<SwipeSlot, SwipeAction>,
    onAction: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val currentActions by rememberUpdatedState(actions)
    val currentOnAction by rememberUpdatedState(onAction)

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
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val action = slot?.let { currentActions[it] }
                        if (action != null && action != SwipeAction.NONE) currentOnAction(action)
                        scope.launch { offset.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + dragAmount) }
                }
            },
    ) {
        SwipeBackground(
            action = slot?.let { actions[it] },
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

@Composable
private fun SwipeBackground(
    action: SwipeAction?,
    isLong: Boolean,
    toRight: Boolean,
    visible: Boolean,
    modifier: Modifier,
) {
    if (!visible) return
    val color = when (action) {
        null, SwipeAction.NONE -> ZeneloColors.Card
        else -> action.accent.copy(alpha = if (isLong) 0.32f else 0.16f).compositeOver(ZeneloColors.Background)
    }
    Row(
        modifier.background(color).padding(horizontal = 20.dp),
        horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (action != null && action != SwipeAction.NONE) {
            Icon(action.icon, null, tint = action.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(action.label, style = MaterialTheme.typography.labelMedium, color = ZeneloColors.TextPrimary)
        }
    }
}
